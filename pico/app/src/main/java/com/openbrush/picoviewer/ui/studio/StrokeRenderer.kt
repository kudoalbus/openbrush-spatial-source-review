package com.openbrush.picoviewer.ui.studio

import com.openbrush.picoviewer.ui.viewer.components.DepthHullMaterial
import com.openbrush.picoviewer.domain.drawing.*
import com.openbrush.picoviewer.data.StudioBrushCatalog
import com.openbrush.picoviewer.data.StudioBrushProfile
import com.pico.spatial.core.ecs.*
import com.pico.spatial.core.ecs.resource.*
import com.pico.spatial.core.math.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/** Latest preview wins; mesh construction and native blocking I/O stay out of input callbacks. */
@OptIn(ExperimentalCoroutinesApi::class)
class StrokeRenderer(private val owner: Entity,private val brushes: StudioBrushCatalog,
    private val audioFrame: ()->BrushAudioFrame,private val onRendered: ((Int)->Unit)? = null,
    private val onError: (String)->Unit) {
    private data class Rendered(val entity: Entity,val mesh: MeshResource,var stroke: PaintStroke,
        var surfaceErased: List<ArcSpan>?, val materialKey: MaterialKey, var scrollSource: MeshModel?,
        var movingGeometry: Boolean, var triangleCount: Int, var highlighted: Boolean = false)
    private data class RenderRequest(val strokes: List<PaintStroke>, val selected: String?)
    private data class MaterialKey(val brush: String?, val color: InkColor)
    private val rendered=mutableMapOf<String,Rendered>()
    private val materials=mutableMapOf<MaterialKey,Material>()
    private val textures=mutableMapOf<String,TextureResource>()
    private var highlightMaterial: UnlitMaterial? = null
    private val requests=Channel<RenderRequest>(Channel.CONFLATED)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val startTime=java.lang.System.nanoTime()
    private var animationCursor=0
    @Volatile private var animationsEnabled=true
    private var closed=false
    private val renderJob=scope.launch {
        while(isActive && !closed) try {
            select<Unit> {
                requests.onReceiveCatching { event -> event.getOrNull()?.let { render(it) } }
                if(animationsEnabled && rendered.values.any {
                    val motion=brushes[it.stroke.style.brush]?.motion
                    it.movingGeometry || it.scrollSource!=null ||
                        (motion!=null && (motion.hueRate!=0f || motion.pulsePeriod!=0f))
                }) onTimeout(67L) { if(animationsEnabled) animate() }
            }
        } catch(e:CancellationException) { throw e }
        catch(e:Exception) { onError("笔触显示失败：${e.message}") }
    }

    private suspend fun render(request: RenderRequest) {
            val visible=request.strokes.flatMap { original -> StrokeAppearance.parts(original).map { it to original } }
            val ids=visible.map { StrokeAppearance.partId(it.first) }.toSet()
            for(id in rendered.keys.toList()) if(id !in ids) remove(id)
            for((stroke,original) in visible) {
                if(closed) break
                val id=StrokeAppearance.partId(stroke)
                val profile=brushes[stroke.style.brush]
                val surfaceErased=original.erased.takeIf { profile?.isArcSurface == true }
                val old=rendered[id]?.takeIf { it.stroke.style.brush==stroke.style.brush }
                if(old?.stroke==stroke && old.surfaceErased==surfaceErased) continue
                if (old==null) remove(id)
                val time=seconds()
                val data=withContext(Dispatchers.Default) {
                    if(profile==null) StrokeTubeMesh.build(stroke) else
                        OriginalBrushMesh.build(stroke,profile,original,BrushAnimationFrame(time,audioFrame()))
                }
                if(data.triangles.isEmpty()) { remove(id);continue }
                val model=model(data)
                val scroll=profile?.motion?.scroll ?: 0f
                val movingGeometry=profile?.animatedGeometry==true
                val current=if(scroll!=0f) shifted(model, requireNotNull(profile?.motion).offset(time)) else model
                val scrollSource=model.takeIf { scroll!=0f && !movingGeometry && it.uv0!=null }
                if(old!=null) {
                    val applied=withContext(Dispatchers.IO) { old.mesh.replaceWithMeshModel(current) }
                    check(applied) { "原生网格替换失败" }
                    old.stroke=stroke
                    old.surfaceErased=surfaceErased
                    old.scrollSource=scrollSource
                    old.movingGeometry=movingGeometry
                    old.triangleCount=data.triangles.size/3
                } else {
                    // NonCancellable encloses resource handoff: disposal cannot leak an IO-created mesh.
                    withContext(NonCancellable) {
                        val mesh=withContext(Dispatchers.IO) { MeshResource.createWithMeshModel(current,name="stroke_${id.replace('-','_').replace(':','_')}").also { it.toGlobal() } }
                        if(closed || !owner.valid) mesh.close() else try {
                            val key=MaterialKey(profile?.let { stroke.style.brush.lowercase() },
                                if(profile?.depthOnly==true) InkColor(0f,0f,0f,0f) else stroke.style.color)
                            val material=materials[key] ?: createMaterial(stroke.style.color,profile).also { materials[key]=it }
                            val entity=ModelEntity(mesh,material);owner.addChild(entity)
                            rendered[id]=Rendered(entity,mesh,stroke,surfaceErased,key,scrollSource,movingGeometry,data.triangles.size/3)
                        } catch(e:Exception) { mesh.close();throw e }
                    }
                }
            }
            for(item in rendered.values) {
                val highlight = item.stroke.id == request.selected
                if(item.highlighted == highlight) continue
                val model = item.entity.components[ModelComponent::class.java] ?: continue
                model.materials[0] = if(highlight) selectedMaterial() else materials.getValue(item.materialKey)
                item.highlighted = highlight
            }
            onRendered?.invoke(rendered.values.sumOf { it.triangleCount })
    }

    private fun selectedMaterial(): UnlitMaterial = highlightMaterial ?: UnlitMaterial.create(BlendingMode.OPAQUE).also {
        it.setBaseColor(Color4(1f,.55f,.04f,1f))
        it.setCullingMode(MaterialCullingMode.NONE)
        it.setApplyToneMapping(false)
        it.toGlobal()
        highlightMaterial = it
    }

    private suspend fun animate() {
        val time=seconds()
        val animation=BrushAnimationFrame(time,audioFrame())
        val updated=mutableSetOf<MaterialKey>()
        val scrolling=mutableListOf<Rendered>()
        for(item in rendered.values) {
            val profile=brushes[item.stroke.style.brush] ?: continue
            val motion=profile.motion
            if(motion!=null && (motion.hueRate!=0f || motion.pulsePeriod!=0f) && updated.add(item.materialKey)) {
                val material=materials.getValue(item.materialKey)
                tint(material,item.materialKey.color,profile,time)
            }
            if(item.movingGeometry || item.scrollSource!=null) scrolling.add(item)
        }
        if(scrolling.isEmpty()) return
        // Prioritize input and spread uploads across the active strokes in large documents.
        var vertices=0
        repeat(minOf(8, scrolling.size)) {
            animationCursor %= scrolling.size
            val item=scrolling[animationCursor++]
            val profile=requireNotNull(brushes[item.stroke.style.brush])
            val source=item.scrollSource ?: withContext(Dispatchers.Default) {
                model(OriginalBrushMesh.build(item.stroke,profile,animation=animation))
            }
            val offset=profile.motion?.offset(time) ?: 0f
            val applied=withContext(Dispatchers.IO) {
                item.mesh.replaceWithMeshModel(if(offset!=0f) shifted(source,offset) else source)
            }
            if(!applied) {
                item.scrollSource=null
                item.movingGeometry=false
                onError("笔刷动画网格更新失败")
            }
            vertices+=source.positions.size
            if(vertices>=12_000) return
        }
    }

    private fun model(data: StrokeMeshData) = MeshModel(
        data.positions.map { Vector3(it.x,it.y,it.z) },data.triangles,
        normals=data.normals.map { Vector3(it.x,it.y,it.z) },
        uv0=data.uv?.map { (u,v) -> Vector2(u,v) }
    )

    private fun shifted(source: MeshModel,offset: Float): MeshModel = MeshModel(
        source.positions,source.triangleIndices,normals=source.normals,
        uv0=source.uv0?.map { Vector2(it.x+offset,it.y) }
    )

    private fun seconds()=(java.lang.System.nanoTime()-startTime)/1_000_000_000.0
    fun offer(strokes: List<PaintStroke>, selected: String? = null) {
        if(!closed) requests.trySend(RenderRequest(strokes, selected))
    }
    fun pauseAnimations() { animationsEnabled=false }
    fun resumeAnimations(strokes: List<PaintStroke>, selected: String?) { animationsEnabled=true;offer(strokes,selected) }
    private fun remove(id: String) { rendered.remove(id)?.let { if(it.entity.valid) it.entity.destroy();it.mesh.close() } }
    fun close() {
        if(closed) return
        closed=true;scope.cancel();requests.close()
        // A blocking replacement already running on IO must finish before its mesh is released.
        CoroutineScope(Dispatchers.Main.immediate).launch {
            renderJob.join()
            rendered.keys.toList().forEach(::remove)
            highlightMaterial?.close();highlightMaterial=null
            materials.values.forEach { it.close() };materials.clear()
            textures.values.forEach { it.close() };textures.clear()
        }
    }
    private suspend fun createMaterial(color: InkColor, profile: StudioBrushProfile?): Material {
        if(profile?.depthOnly==true)return DepthHullMaterial.load()
        val mode = blend(profile,color)
        val asset = profile?.texture?.asset
        val pattern = profile?.motion?.pattern
        val texture = if (pattern != null) {
            val key = "pattern:${pattern.name}:$mode"
            textures[key] ?: withContext(Dispatchers.IO) {
                TextureResource(BrushPatternTexture.bitmap(pattern,mode==BlendingMode.ADD)).also { it.toGlobal() }
            }.also { textures[key]=it }
        } else asset?.let { path ->
            textures[path] ?: withContext(Dispatchers.IO) {
                TextureResource.load(path, LoadType.FROM_ASSETS).also { it.toGlobal() }
            }.also { textures[path] = it }
        }
        val rule = profile?.material
        val culling = if (profile?.geometry?.singleSided == true) MaterialCullingMode.BACK else MaterialCullingMode.NONE
        val opacity = color.a * (profile?.opacity ?: 1f)
        val material = if (rule != null && !rule.selfLit && mode != BlendingMode.ADD)
            PhysicallyBasedMaterial.create(mode) else UnlitMaterial.create(mode)
        try {
            tint(material,color,profile,seconds())
            when (material) {
                is PhysicallyBasedMaterial -> {
                    material.setCullingMode(culling)
                    material.setMetallic(when (profile?.name) { "Mylar Tube" -> .95f; "Shiny Hull" -> .2f; else -> 0f })
                    material.setRoughness(when (profile?.name) {
                        "Mylar Tube" -> .25f
                        "Shiny Hull", "Shiny Membrane", "Diamond Hull", "Diamond Membrane" -> .14f
                        "Smooth Hull" -> .24f
                        "Matte Hull", "Matte Membrane" -> .72f
                        else -> .65f
                    })
                    material.setOpacity(opacity)
                    if (mode == BlendingMode.TRANSPARENT) material.setDepthWrite(false)
                    if (mode == BlendingMode.MASKED) material.setOpacityThreshold(requireNotNull(rule).cutoff.toFloat())
                    if (texture != null) material.setBaseColorTexture(texture)
                }
                is UnlitMaterial -> {
                    material.setCullingMode(culling)
                    if (profile?.motion?.wireframe==true) material.setPolygonFillMode(PolygonFillMode.LINE)
                    material.setApplyToneMapping(false)
                    if (profile != null) material.setOpacity(if (mode == BlendingMode.ADD) 1f else opacity)
                    if (mode in setOf(BlendingMode.TRANSPARENT,BlendingMode.ADD)) material.setDepthWrite(false)
                    if (mode == BlendingMode.MASKED) material.setOpacityThreshold(requireNotNull(rule).cutoff.toFloat())
                    if (texture != null) material.setBaseColorTexture(texture)
                }
            }
            material.toGlobal()
            return material
        } catch (e: Exception) {
            material.close()
            throw e
        }
    }

    private fun blend(profile: StudioBrushProfile?,color: InkColor): BlendingMode = when {
        profile!=null && profile.emission>0f && !profile.isArcSurface &&
            profile.name!="Lofted (Hue Shift)" -> BlendingMode.ADD
        (profile?.opacity ?: 1f) < 1f -> BlendingMode.TRANSPARENT
        profile?.material?.blend == "MASK" -> BlendingMode.MASKED
        profile != null && color.a < 1f -> BlendingMode.TRANSPARENT
        else -> BlendingMode.OPAQUE
    }

    private fun tint(material: Material,color: InkColor,profile: StudioBrushProfile?,seconds: Double) {
        val motion=profile?.motion
        val ink=motion?.color(color,seconds) ?: color
        val emission=(profile?.emission ?: 0f)*(motion?.pulse(seconds) ?: 1f)
        val gain=if(blend(profile,color)==BlendingMode.ADD) color.a*requireNotNull(profile).opacity*(1f+emission) else 1f
        val r=linear(ink.r)
        val g=linear(ink.g)
        val b=linear(ink.b)
        val rgba=Color4(r*gain,g*gain,b*gain,if(profile==null) color.a else 1f)
        when(material) {
            is PhysicallyBasedMaterial -> {
                material.setBaseColor(rgba)
                if(emission>0f) material.setEmissiveColor(Color4(r*emission,g*emission,b*emission,1f))
            }
            is UnlitMaterial -> material.setBaseColor(rgba)
        }
    }
    private fun linear(v:Float)=if(v<=.04045f) v/12.92f else Math.pow(((v+.055f)/1.055f).toDouble(),2.4).toFloat()
}
