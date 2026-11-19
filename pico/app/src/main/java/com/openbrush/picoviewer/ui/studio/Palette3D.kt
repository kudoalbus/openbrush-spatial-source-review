package com.openbrush.picoviewer.ui.studio

import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import com.openbrush.picoviewer.data.PaletteColorCodec
import com.openbrush.picoviewer.domain.drawing.*
import com.openbrush.picoviewer.ui.viewer.components.ArtworkLightingRuntime
import com.pico.spatial.core.ecs.*
import com.pico.spatial.core.ecs.resource.*
import com.pico.spatial.core.math.*
import com.pico.spatial.tracking.hand.HandJoint.Index.*
import com.pico.spatial.tracking.hand.HandPose
import com.pico.spatial.tracking.hmd.HMDPose
import com.pico.spatial.tracking.controller.ControllerPose
import java.io.File
import kotlin.math.*

/** The visible GLB, native pointer colliders and analytical fingertip targets share one transform. */
class Palette3D(private val owner:Entity,private val model:Entity,val layout:PaletteLayout,cache:File,
    surface:Entity,private val prefs:SharedPreferences,private val onUi:(PaletteColorUiState)->Unit):AutoCloseable {
    val entity=Entity().also { owner.addChild(it);it.addChild(model) }
    private val legacyRoot=requireNotNull(model.findEntity("PaletteLegacy"))
    private val pickerRoot=requireNotNull(model.findEntity("PalettePicker"))
    private val pickerLayout=PalettePickerLayout.layout(layout)
    private val colors=PaletteColorState(runCatching {
        prefs.getString("state",null)?.let(PaletteColorCodec::decode)
    }.onFailure { Log.w("OpenBrushPalette","Could not restore palette colors",it) }.getOrNull()
        ?: PaletteColorUiState(true,HsvInk.from(requireNotNull(layout.targets.first { it.id==1 }.color)),List(5){null}))
    private var lastBrushColor=colors.ui.color
    private var lastUi:PaletteColorUiState?=null
    private var dirty=false
    private var lastPersist=0L
    val currentColor get()=colors.ui.color
    val pickerMode get()=colors.ui.picker
    private val activeLayout get()=if(pickerMode)pickerLayout else layout
    private val physics=PhysicsMaterialResource()
    private val shapes=mutableListOf<ShapeResource>()
    private val proxyMeshes=mutableListOf<MeshResource>()
    private val proxyShapes=mutableMapOf<Pair<Float,Float>,ShapeResource>()
    private val ids=mutableMapOf<Long,Int>()
    private val lighting=ArtworkLightingRuntime("drawing-palette")
    private val ringMesh=ringMesh().also { it.toGlobal() }
    private val ringMaterial=UnlitMaterial.create().also {
        it.setBaseColor(Color4(1f,.8f,.2f,1f));it.setApplyToneMapping(false);it.toGlobal()
    }
    private val colorRing=ModelEntity(ringMesh,ringMaterial).also { entity.addChild(it);it.enabled=false }
    private val brushRing=ModelEntity(ringMesh,ringMaterial).also { entity.addChild(it);it.enabled=false }
    private var placed=false
    init {
        val mount=Entity();entity.addChild(mount);mount.addChild(surface)
        mount.transform().setPosition(Vector3(PalettePickerLayout.PANEL_CENTER_X,.0065f,0f))
            .setQuaternion(Quat(rotAngle=-PI.toFloat()/2,rotAxis=Vector3(1f,0f,0f)))
        fun collider(target:PaletteTarget,parent:Entity,anchor:Entity?) {
            // Brush anchors inherit the diagonal handle rotation. Keep their thin touch
            // volumes in palette coordinates, matching analytical finger/ray tests.
            val hit=anchor?.takeIf { target.kind!=PaletteKind.BRUSH } ?: Entity().also {
                it.setName("PaletteHit_${target.id}");parent.addChild(it)
                it.transform().setPosition(target.position.native())
            }
            val shape=proxyShapes.getOrPut(target.radius to target.depth) {
                val mesh=ellipsoidMesh(target.radius,target.depth).also { it.toGlobal();proxyMeshes.add(it) }
                ShapeResource.createConvexMesh(mesh).also(shapes::add)
            }
            hit.components.set(CollisionComponent(listOf(shape),physics));hit.components.set(InteractableComponent())
            ids[hit.id]=target.id
            if(anchor!=null)ids[anchor.id]=target.id
        }
        layout.targets.forEach { target -> collider(target,if(target.kind==PaletteKind.BRUSH)entity else legacyRoot,
            model.findEntity(target.name)) }
        pickerLayout.targets.filter { it.kind!=PaletteKind.BRUSH }.forEach { collider(it,pickerRoot,null) }
        lighting.initialize(entity,entity,model,null,cache);lighting.select(false)
        emit()
    }
    fun commandFor(target:Entity?,viewer:Vector3):Int? {
        if(!entity.enabled)return null
        var current=target
        repeat(8) {
            if(current==null)return null
            ids[current!!.id]?.let { id ->
                val kind=activeLayout.targets.firstOrNull { it.id==id }?.kind ?: return null
                return id.takeIf { kind==PaletteKind.BRUSH || local(viewer).y>=.001f }
            }
            current=current!!.getParent()
        }
        return null
    }
    fun local(world:Vector3)=entity.convertPositionFrom(world,null).v3()
    fun illuminate(model:Entity)=lighting.attachModel(model)
    fun world(point:V3)=entity.convertPositionTo(point.native(),null)
    private fun localTouch(point:V3,brushesOnly:Boolean=false):PaletteTarget? {
        if(!entity.enabled)return null
        return activeLayout.touch(point,brushesOnly)
            ?: if(pickerMode && !brushesOnly)PalettePickerLayout.widget(point)?.let(PalettePickerLayout::target) else null
    }
    fun touch(world:Vector3,brushesOnly:Boolean=false)=localTouch(local(world),brushesOnly)
    fun surfacePoint(u:Float,v:Float)=PalettePickerLayout.surface(u,v)
    fun surfaceTarget(point:V3)=localTouch(point)?.id
    fun guards(world:Vector3)=entity.enabled && layout.guards(local(world))
    fun ray(origin:Vector3,direction:Vector3):Pair<PaletteRayHit?,Boolean> {
        if(!entity.enabled)return null to false
        val start=local(origin);val ray=(local(origin+direction)-start).unit()
        val target=activeLayout.ray(start,ray)
        val widget=if(pickerMode)PalettePickerLayout.plane(start,ray)?.let { (p,d)->
            PalettePickerLayout.widget(p)?.let { PaletteRayHit(PalettePickerLayout.target(it),d) }
        } else null
        return listOfNotNull(target,widget).minByOrNull { it.distance } to layout.guardsRay(start,ray)
    }
    fun rayPoint(origin:Vector3,direction:Vector3):Pair<V3,Float>? {
        if(!entity.enabled)return null
        val p=local(origin);return PalettePickerLayout.plane(p,(local(origin+direction)-p).unit())
    }
    fun sampleColor(id:Int,point:V3,drag:Boolean):InkColor? {
        if(!entity.enabled || !pickerMode)return null
        val hsv=PalettePickerLayout.sample(id,point,colors.ui.selected,drag) ?: return null
        if(hsv!=colors.ui.selected) { colors.select(hsv);dirty=true;emit() }
        lastBrushColor=colors.ui.color
        return lastBrushColor
    }
    fun colorCommand(id:Int):InkColor? {
        when(id) {
            PaletteColorCommands.MODE->colors.toggle()
            in PaletteColorCommands.FAVORITE..PaletteColorCommands.FAVORITE+4->colors.favorite(id-PaletteColorCommands.FAVORITE)
            else->return null
        }
        dirty=true;lastBrushColor=colors.ui.color;emit();commit()
        return lastBrushColor
    }
    fun syncColor(color:InkColor) {
        if(lastBrushColor==color)return
        lastBrushColor=color
        if(colors.brushColor(color)) { dirty=true;emit() }
    }
    fun commit() {
        if(!dirty)return
        prefs.edit().putString("state",PaletteColorCodec.encode(colors.ui)).apply()
        dirty=false;lastPersist=SystemClock.elapsedRealtime()
    }
    private fun emit() {
        legacyRoot.enabled=!pickerMode;pickerRoot.enabled=pickerMode
        if(pickerMode)colorRing.enabled=false
        if(lastUi!=colors.ui) {
            lastUi=colors.ui;onUi(colors.ui)
            Log.i("OpenBrushPalette","picker=$pickerMode h=${colors.ui.selected.h} s=${colors.ui.selected.s} v=${colors.ui.selected.v} slots=${colors.ui.favorites.count { it!=null }}")
        }
    }
    internal fun place(head:HMDPose,left:HandPose?,frame:PalmMenuFrame?,controller:ControllerPose?,delta:Float) {
        val facing=head.rotation*Quat(rotAngle=PI.toFloat()/2f,rotAxis=Vector3(1f,0f,0f))
        val handRotation=frame?.let { basis(it.x,it.y,it.z) }
        val rotation=handRotation ?: facing
        val anchor=left?.joint(THUMB_PROXIMAL)?.position ?: controller?.position
        val worldCenter=if(anchor!=null) anchor-rotation.rotateVector(layout.grip.native())+
            rotation.rotateVector(Vector3(0f,.012f,0f)) else
            head.position+head.rotation.rotateVector(Vector3(-.29f,-.10f,-.60f))
        val desired=owner.convertPositionFrom(worldCenter,null)
        val q=owner.convertRotationFrom(rotation,null)
        val alpha=if(placed) (1f-exp(-delta.coerceIn(.001f,.1f)/.04f)) else 1f
        val t=entity.transform()
        t.setPosition(t.position*(1f-alpha)+desired*alpha)
        t.setQuaternion(nlerp(t.quaternion,q,alpha));placed=true
    }
    fun select(style:PaintStyle) {
        syncColor(style.color)
        if(dirty && SystemClock.elapsedRealtime()-lastPersist>=400L)commit()
        val color=if(pickerMode)null else layout.targets.firstOrNull { it.color==style.color }
        val brush=layout.targets.firstOrNull { it.brush==style.brush }
        highlight(colorRing,color,.024f);highlight(brushRing,brush,.017f)
    }
    private fun highlight(ring:Entity,target:PaletteTarget?,radius:Float) {
        ring.enabled=target!=null
        if(target!=null)ring.transform().setPosition((target.position+V3(0f,.008f,0f)).native()).setScaleVector(Vector3(radius))
    }
    override fun close() {
        commit()
        if(entity.valid)entity.destroy()
        lighting.close();shapes.forEach { it.close() };proxyMeshes.forEach { it.close() };physics.close();ringMesh.close();ringMaterial.close()
    }
    private fun ellipsoidMesh(radius:Float,depth:Float):MeshResource {
        val p=mutableListOf(Vector3(0f,depth,0f),Vector3(0f,-depth,0f));val faces=mutableListOf<Int>()
        for(row in 1..7) for(i in 0..15) {
            val lat=PI.toFloat()*row/8;val a=2*PI.toFloat()*i/16
            p.add(Vector3(sin(lat)*cos(a)*radius,cos(lat)*depth,sin(lat)*sin(a)*radius))
        }
        repeat(16) { i ->
            faces.addAll(listOf(0,2+i,2+(i+1)%16))
            val bottom=2+6*16;faces.addAll(listOf(1,bottom+(i+1)%16,bottom+i))
        }
        repeat(6) { row -> repeat(16) { i ->
            val a=2+row*16+i;val b=2+row*16+(i+1)%16;val c=a+16;val d=b+16
            faces.addAll(listOf(a,c,b,b,c,d))
        } }
        val outward=faces.chunked(3).flatMap { listOf(it[0],it[2],it[1]) }
        return MeshResource.createWithMeshModel(MeshModel(p,outward),name="PaletteTouchVolume")
    }
    private fun ringMesh():MeshResource {
        val p=mutableListOf<Vector3>();val n=mutableListOf<Vector3>();val indices=mutableListOf<Int>()
        repeat(64) { i ->
            val a=2*PI.toFloat()*i/64
            for(r in listOf(.96f,1.04f)) { p.add(Vector3(cos(a)*r,0f,sin(a)*r));n.add(Vector3(0f,1f,0f)) }
        }
        repeat(64) { i ->
            val a=i*2;val b=((i+1)%64)*2
            indices.addAll(listOf(a,b,a+1,a+1,b,b+1,b,a,a+1,b,b+1,a+1))
        }
        return MeshResource.createWithMeshModel(MeshModel(p,indices,normals=n),name="PaletteSelectionRing")
    }
    private fun nlerp(a:Quat,b:Quat,t:Float):Quat {
        val sign=if(a.x*b.x+a.y*b.y+a.z*b.z+a.w*b.w<0f)-1f else 1f
        val x=a.x*(1-t)+b.x*sign*t;val y=a.y*(1-t)+b.y*sign*t
        val z=a.z*(1-t)+b.z*sign*t;val w=a.w*(1-t)+b.w*sign*t
        val length=sqrt(x*x+y*y+z*z+w*w)
        return if(length>1e-6f)Quat(x/length,y/length,z/length,w/length) else b
    }
    private fun basis(x:V3,y:V3,z:V3):Quat {
        val trace=x.x+y.y+z.z
        return if(trace>0f) {
            val s=sqrt(trace+1f)*2f;Quat((y.z-z.y)/s,(z.x-x.z)/s,(x.y-y.x)/s,s/4f)
        } else if(x.x>y.y && x.x>z.z) {
            val s=sqrt(1f+x.x-y.y-z.z)*2f;Quat(s/4f,(y.x+x.y)/s,(z.x+x.z)/s,(y.z-z.y)/s)
        } else if(y.y>z.z) {
            val s=sqrt(1f+y.y-x.x-z.z)*2f;Quat((y.x+x.y)/s,s/4f,(z.y+y.z)/s,(z.x-x.z)/s)
        } else {
            val s=sqrt(1f+z.z-x.x-y.y)*2f;Quat((z.x+x.z)/s,(z.y+y.z)/s,s/4f,(x.y-y.x)/s)
        }
    }
    private fun Entity.transform()=requireNotNull(components[TransformComponent::class.java])
    private fun Vector3.v3()=V3(x,y,z)
    private fun V3.native()=Vector3(x,y,z)
}
