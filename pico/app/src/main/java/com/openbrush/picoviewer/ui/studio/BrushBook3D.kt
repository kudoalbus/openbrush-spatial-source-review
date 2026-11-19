package com.openbrush.picoviewer.ui.studio

import com.openbrush.picoviewer.R
import android.content.Context

import android.content.SharedPreferences
import android.util.Log
import com.openbrush.picoviewer.domain.drawing.*
import com.pico.spatial.core.ecs.*
import com.pico.spatial.core.ecs.resource.*
import com.pico.spatial.core.math.*
import com.pico.spatial.tracking.hmd.HMDPose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI

data class BrushBookUiState(val mode:BrushBookMode=BrushBookMode.CLASSIC,val page:Int=1,
    val pages:Int=4,val caption:String="")

/** The approved GLB carrier, original icon atlas and all input targets share one transform. */
class BrushBook3D(
    private val owner:Entity,private val frame:Entity,private val header:Entity,private val caption:Entity,private val surface:Entity,
    private val basicModels:List<Entity>,val catalog:BrushBookCatalog,private val prefs:SharedPreferences, private val context: Context,
    private val onUi:(BrushBookUiState)->Unit
):AutoCloseable {
    val entity=Entity().also { owner.addChild(it);it.addChild(frame) }
    // Attachment layout can reset its own transform when Compose changes its content.
    // Keep the authored mounting transform on ordinary ECS parents instead.
    private val headerMount=Entity().also { entity.addChild(it);it.addChild(header) }
    private val captionMount=Entity().also { entity.addChild(it);it.addChild(caption) }
    private val surfaceMount=Entity().also { entity.addChild(it);it.addChild(surface) }
    private val initialMode=runCatching { BrushBookMode.valueOf(prefs.getString("mode","CLASSIC") ?: "CLASSIC") }.getOrDefault(BrushBookMode.CLASSIC)
    val state=BrushBookState(catalog,initialMode,prefs.getInt("classicPage",1),prefs.getInt("extendedPage",1))
    private val physics=PhysicsMaterialResource()
    private val shapes=mutableMapOf<Pair<Float,Float>,ShapeResource>()
    private val meshes=mutableListOf<MeshResource>()
    private val materials=mutableListOf<UnlitMaterial>()
    private val textures=mutableMapOf<String,TextureResource>()
    private val slots=mutableListOf<ModelEntity>()
    private val slotMaterials=mutableListOf<UnlitMaterial>()
    private val proxies=mutableMapOf<Int,Entity>()
    private val ids=mutableMapOf<Long,Int>()
    private val dots=mutableListOf<ModelEntity>()
    private val dotMaterials=mutableListOf<UnlitMaterial>()
    private var selectedRing:Entity?=null
    private var hoverRing:Entity?=null
    private var selectedRenderer="spatial_tube"
    private var hovered:Int?=null
    private var note:String?=null
    private var placed=false
    private var attachmentsSized=false
    private var lastUi:BrushBookUiState?=null
    private var closed=false
    private var openRequested=false
    val visible get()=openRequested && !closed

    suspend fun initialize() {
        entity.enabled=false
        for(path in (catalog.classicAtlases+catalog.extendedAtlases).distinct()) {
            withContext(Dispatchers.IO) { textures[path]=TextureResource(path,LoadType.FROM_ASSETS).also { it.toGlobal() } }
        }
        repeat(12) { slot ->
            val mesh=iconMesh(slot).also { it.toGlobal();meshes.add(it) }
            val material=unlit(Color4(1f,1f,1f,1f));slotMaterials.add(material)
            val model=ModelEntity(mesh,material);entity.addChild(model)
            model.transform().setPosition(BrushBookLayout.slotCenter(slot).native())
            slots.add(model)
        }
        basicModels.forEachIndexed { slot,model ->
            entity.addChild(model)
            val p=BrushBookLayout.slotCenter(slot)
            model.transform().setPosition(Vector3(p.x,.023f,p.z+.017f)).setScaleVector(Vector3(.22f))
                .setQuaternion(Quat(rotAngle=-PI.toFloat()/2,rotAxis=Vector3(1f,0f,0f)))
        }
        // Allocate every potential page-dot collider once; refresh positions for 1/4/9-page modes.
        for(mode in BrushBookMode.entries) {
            val layoutState=BrushBookState(catalog,mode)
            for(target in BrushBookLayout.targets(layoutState)) if(target.command !in proxies) {
                val proxy=Entity();entity.addChild(proxy);proxy.setName("BrushBookHit_${target.command}")
                val shape=shapes.getOrPut(target.width to target.height) {
                    ShapeResource.createBox(Vector3(target.width,.010f,target.height))
                }
                proxy.components.set(CollisionComponent(listOf(shape),physics));proxy.components.set(InteractableComponent())
                proxies[target.command]=proxy;ids[proxy.id]=target.command
            }
        }
        val ring=ringMesh().also { it.toGlobal();meshes.add(it) }
        selectedRing=ModelEntity(ring,unlit(Color4(.85f,.57f,.21f,1f))).also { entity.addChild(it) }
        hoverRing=ModelEntity(ring,unlit(Color4(.7f,.7f,.6f,1f))).also { entity.addChild(it) }
        val dotMesh=MeshResource.createSphere(.0016f).also { it.toGlobal();meshes.add(it) }
        repeat(9) {
            val material=unlit(Color4(.4f,.3f,.16f,1f));dotMaterials.add(material)
            dots.add(ModelEntity(dotMesh,material).also { entity.addChild(it) })
        }
        refresh()
    }
    fun setOpen(open:Boolean) {
        if(open==visible)return
        if(!open)hide()
        else { openRequested=true;entity.enabled=false;placed=false;hovered=null;note=null;emit() }
    }
    fun hide() { openRequested=false;entity.enabled=false;hovered=null;hoverRing?.enabled=false }
    fun place(head:HMDPose,palette:Palette3D,overHandBack:Boolean=false) {
        if(!visible)return
        // Follow the palette's already-smoothed center every frame and keep the book facing
        // the viewer. All icons, panels and input targets inherit this same moving transform.
        val anchor=palette.entity.convertPositionTo(Vector3(0f,0f,0f),null)
        val offset=if(overHandBack)Vector3(-.04f,.18f,-.06f) else Vector3(-.04f,.25f,-.08f)
        val position=anchor+head.rotation.rotateVector(offset)
        val rotation=head.rotation*Quat(rotAngle=PI.toFloat()/2,rotAxis=Vector3(1f,0f,0f))
        entity.transform().setPosition(owner.convertPositionFrom(position,null)).setQuaternion(owner.convertRotationFrom(rotation,null))
        placed=true;entity.enabled=true
        if(!attachmentsSized) {
            fun panel(mount:Entity,z:Float) {
                // AttachmentPanelComponent has no mesh bounds on current PICO OS.
                // StudioScreen requests its physical size directly through panelSize(meters).
                mount.transform().setPosition(Vector3(0f,.019f,z))
                    .setQuaternion(Quat(rotAngle=-PI.toFloat()/2,rotAxis=Vector3(1f,0f,0f)))
            }
            panel(headerMount,-.104f);panel(captionMount,.085f)
            panel(surfaceMount,BrushBookLayout.SURFACE_CENTER_Z)
            attachmentsSized=true
        }
    }
    fun select(renderer:String) {
        if(selectedRenderer!=renderer) { selectedRenderer=renderer;note=null;updateRings();emit() }
    }
    fun activate(command:Int):String? {
        if(!visible || !state.enabled(command))return null
        state.entry(command)?.let { entry ->
            note=if(entry.implemented)entry.name else context.getString(R.string.brush_pending,entry.name)
            val renderer=state.renderer(command)
            if(renderer!=null)selectedRenderer=renderer
            updateRings();emit();return renderer
        }
        if(state.navigate(command)) {
            prefs.edit().putString("mode",state.mode.name).putInt("classicPage",state.savedPage(BrushBookMode.CLASSIC))
                .putInt("extendedPage",state.savedPage(BrushBookMode.EXTENDED)).apply()
            note=null;hovered=null;refresh()
        }
        return null
    }
    fun touch(world:Vector3)=if(visible && placed)BrushBookLayout.touch(local(world),state) else null
    fun surfaceCommand(u:Float,v:Float)=if(visible && placed)BrushBookLayout.surfaceCommand(u,v,state) else null
    fun hover(world:Vector3)=if(visible && placed)BrushBookLayout.touch(local(world),state,hover=true) else null
    fun guards(world:Vector3)=visible && placed && BrushBookLayout.guards(local(world))
    fun ray(origin:Vector3,direction:Vector3):Pair<BrushBookRayHit?,Boolean> {
        if(!visible || !placed)return null to false
        val p=local(origin);val d=(local(origin+direction)-p).unit()
        return BrushBookLayout.ray(p,d,state) to BrushBookLayout.guardsRay(p,d)
    }
    fun blockingDistance(origin:Vector3,direction:Vector3):Float? {
        if(!visible || !placed)return null
        val p=local(origin);return BrushBookLayout.blockingDistance(p,local(origin+direction)-p)
    }
    fun setHover(command:Int?) {
        if(hovered==command)return
        hovered=command;updateRings();emit()
    }
    fun commandFor(target:Entity?,viewer:Vector3):Int? {
        if(!visible || !placed || local(viewer).y<.008f)return null
        var current=target
        repeat(8) {
            if(current==null)return null
            ids[current!!.id]?.let { return it.takeIf(state::enabled) }
            current=current!!.getParent()
        }
        return null
    }
    private fun refresh() {
        val entries=state.slots;val texture=catalog.atlas(state.mode,state.page)?.let(textures::getValue)
        repeat(12) { slot ->
            val entry=entries.getOrNull(slot)
            frame.findEntity("BookTile$slot")?.enabled=entry!=null
            slots[slot].enabled=entry!=null && texture!=null
            if(texture!=null)slotMaterials[slot].setBaseColorTexture(texture)
            val shade=if(entry?.implemented==true)1f else .30f
            slotMaterials[slot].setBaseColor(Color4(shade,shade,shade,1f))
        }
        basicModels.forEachIndexed { i,model -> model.enabled=state.mode==BrushBookMode.BASIC && i<entries.size }
        proxies.values.forEach { it.enabled=false }
        for(target in BrushBookLayout.targets(state)) proxies.getValue(target.command).apply {
            enabled=state.enabled(target.command);transform().setPosition(target.center.native())
        }
        repeat(9) { i ->
            dots[i].enabled=i<state.pageCount
            dots[i].transform().setPosition(Vector3((i-(state.pageCount-1)*.5f)*.010f,.009f,.096f))
            dotMaterials[i].setBaseColor(if(i==state.page)Color4(.85f,.57f,.21f,1f) else Color4(.35f,.25f,.13f,1f))
        }
        for((prefix,on) in listOf("BookPrev" to (state.page>0),"BookNext" to (state.page<state.pageCount-1))) {
            for(suffix in listOf("Bezel","Enamel","Glyph"))frame.findEntity(prefix+suffix)?.enabled=on
        }
        updateRings();emit()
    }
    private fun updateRings() {
        fun show(ring:Entity?,slot:Int?,offset:Float) {
            ring ?: return
            ring.enabled=slot!=null
            if(slot!=null)ring.transform().setPosition((BrushBookLayout.slotCenter(slot)+V3(0f,offset,0f)).native())
        }
        show(selectedRing,state.slots.indexOfFirst { it.renderer==selectedRenderer }.takeIf { it>=0 },.0030f)
        show(hoverRing,hovered?.minus(BrushBookCommands.SLOT)?.takeIf { it in state.slots.indices },.0035f)
    }
    private fun emit() {
        val entry=hovered?.let(state::entry)
        val message=entry?.let { if(it.implemented)it.name else context.getString(R.string.brush_pending,it.name) } ?: note ?:
            catalog.entries[selectedRenderer]?.name ?: context.getString(R.string.choose_brush)
        val ui=BrushBookUiState(state.mode,state.page,state.pageCount,message)
        if(lastUi!=ui) {
            lastUi=ui;onUi(ui)
            Log.i("OpenBrushBrushBook","mode=${ui.mode} page=${ui.page+1}/${ui.pages} selected=$selectedRenderer caption=${ui.caption}")
        }
    }
    private fun iconMesh(slot:Int):MeshResource {
        val stride=catalog.cell+catalog.gutter*2
        val u=(slot%4*stride+catalog.gutter).toFloat()/catalog.atlasWidth
        val v=(slot/4*stride+catalog.gutter).toFloat()/catalog.atlasHeight
        val du=catalog.cell.toFloat()/catalog.atlasWidth;val dv=catalog.cell.toFloat()/catalog.atlasHeight
        val s=.0188f
        return MeshResource.createWithMeshModel(MeshModel(
            listOf(Vector3(-s,0f,s),Vector3(s,0f,s),Vector3(s,0f,-s),Vector3(-s,0f,-s)),listOf(0,1,2,0,2,3),
            normals=List(4) { Vector3(0f,1f,0f) },
            // SDK mesh textures use a bottom-left UV origin; the packed PNG uses top-left rows.
            uv0=listOf(Vector2(u,1f-v-dv),Vector2(u+du,1f-v-dv),Vector2(u+du,1f-v),Vector2(u,1f-v))),name="BrushBookIcon$slot")
    }
    private fun ringMesh():MeshResource {
        val p=mutableListOf<Vector3>();val triangles=mutableListOf<Int>()
        for((a,b) in listOf(-.0228f to -.0217f,.0217f to .0228f)) {
            for(vertical in listOf(false,true)) {
                val start=p.size
                p.addAll(if(vertical)listOf(Vector3(a,0f,-.0228f),Vector3(b,0f,-.0228f),Vector3(b,0f,.0228f),Vector3(a,0f,.0228f))
                    else listOf(Vector3(-.0228f,0f,a),Vector3(.0228f,0f,a),Vector3(.0228f,0f,b),Vector3(-.0228f,0f,b)))
                triangles.addAll(listOf(start,start+1,start+2,start,start+2,start+3))
            }
        }
        return MeshResource.createWithMeshModel(MeshModel(p,triangles),name="BrushBookSelection")
    }
    private fun unlit(color:Color4)=UnlitMaterial.create().also {
        it.setBaseColor(color);it.setApplyToneMapping(false);it.setCullingMode(MaterialCullingMode.NONE);it.toGlobal();materials.add(it)
    }
    override fun close() {
        if(closed)return
        closed=true;if(entity.valid)entity.destroy()
        shapes.values.forEach { it.close() };physics.close();materials.forEach { it.close() };meshes.forEach { it.close() };textures.values.forEach { it.close() }
    }
    private fun local(world:Vector3)=entity.convertPositionFrom(world,null).let { V3(it.x,it.y,it.z) }
    private fun V3.native()=Vector3(x,y,z)
    private fun Entity.transform()=requireNotNull(components[TransformComponent::class.java])
}
