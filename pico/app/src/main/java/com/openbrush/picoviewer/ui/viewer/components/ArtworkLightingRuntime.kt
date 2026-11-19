package com.openbrush.picoviewer.ui.viewer.components

import android.util.Log
import com.openbrush.picoviewer.data.AmbientKtx
import com.openbrush.picoviewer.domain.model.*
import com.pico.spatial.core.ecs.*
import com.pico.spatial.core.ecs.resource.*
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import java.io.File

/** Owned by one artwork window, whose native scene isolates its directional lights. */
class ArtworkLightingRuntime(private val instance:String) : AutoCloseable {
    private data class Rig(val root:Entity,val ibl:Entity)
    private val textures=mutableListOf<TextureResource>()
    private val rigs=mutableListOf<Rig>()
    private val receivers=mutableListOf<ImageBasedLightReceiverComponent>()
    private var fileRig:Rig?=null
    private var appRig:Rig?=null
    private var selected:Boolean?=null
    fun initialize(owner:Entity,manipulationRoot:Entity,model:Entity,lighting:ArtworkLighting?,cache:File) {
        appRig=createRig(owner,SOFTWARE,File(cache,"$instance-app.ktx"))
        fileRig=lighting?.let{createRig(manipulationRoot,it,File(cache,"$instance-file.ktx"))}
        attachModel(model)
    }
    /** Reuse this rig for an additional model without adding more scene-wide directional lights. */
    fun attachModel(model:Entity) {
        val source=if(selected==true && fileRig!=null)requireNotNull(fileRig).ibl else requireNotNull(appRig).ibl
        val queue=ArrayDeque<Entity>();queue.add(model)
        while(queue.isNotEmpty()) {
            val e=queue.removeFirst()
            if(e.components[ModelComponent::class.java]!=null) {
                val receiver=ImageBasedLightReceiverComponent(source)
                e.components.set(receiver);receivers.add(receiver)
            }
            queue.addAll(e.getChildren())
        }
    }
    private fun createRig(parent:Entity,l:ArtworkLighting,path:File):Rig {
        path.writeBytes(AmbientKtx.bytes(l.ambient))
        val texture=TextureResource.load(path.absolutePath,LoadType.FROM_STORAGE,TextureCreateOption().apply {
            textureEncoding=TextureEncoding.LINEAR;colorSpace=TextureColorSpace.SRGB
        });textures.add(texture)
        val root=Entity();parent.addChild(root)
        val ibl=Entity();root.addChild(ibl)
        // Match the 1000-lux/unit directional calibration. Without this, the
        // unit-radiance ambient is overwhelmed by the physically measured lights.
        ibl.components.set(ImageBasedLightComponent(ImageBasedLightSource.Single(texture),9.965784f))
        l.lights.forEach { light ->
            val e=Entity();root.addChild(e)
            val q=AmbientKtx.picoQuaternion(light.orientation)
            requireNotNull(e.components[TransformComponent::class.java]).setQuaternion(Quat(q[0],q[1],q[2],q[3]))
            val peak=light.color.maxOrNull() ?: 0f
            val rgb=if(peak>0f)light.color.map{it/peak} else listOf(0f,0f,0f)
            // Open Brush stores intensity multiplied into RGB; preserve ratios/HDR.
            // Unity's unitless intensity is calibrated to 1000 lux per unit here.
            e.components.set(DirectionalLightComponent(Color4(rgb[0],rgb[1],rgb[2],1f),peak*1000f,light.shadow))
        }
        root.enabled=false
        return Rig(root,ibl).also(rigs::add)
    }
    fun select(useFile:Boolean) {
        val enabled=useFile && fileRig!=null
        if(selected==enabled)return
        val active=if(enabled)requireNotNull(fileRig) else requireNotNull(appRig)
        rigs.forEach{it.root.enabled=it===active}
        receivers.forEach{it.source=active.ibl}
        selected=enabled
        Log.i("OpenBrushLighting","instance=$instance mode=${if(enabled)"file" else "software"} receivers=${receivers.size}")
    }
    /** The enclosing Entity owner must be destroyed first. */
    override fun close(){receivers.clear();rigs.clear();textures.forEach{it.close()};textures.clear()}
    companion object {
        val SOFTWARE=ArtworkLighting(listOf(.62f,.62f,.62f),listOf(
            ArtworkLight(listOf(1f,.96f,.9f),listOf(.35f,-.2f,.08f,.91f).let { q->val n=kotlin.math.sqrt(q.sumOf{(it*it).toDouble()}).toFloat();q.map{it/n}},true),
            ArtworkLight(listOf(.35f,.4f,.5f),listOf(-.35f,.75f,.1f,.55f).let { q->val n=kotlin.math.sqrt(q.sumOf{(it*it).toDouble()}).toFloat();q.map{it/n}},false)
        ),"software")
    }
}
