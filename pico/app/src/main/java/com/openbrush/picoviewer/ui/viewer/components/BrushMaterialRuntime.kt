package com.openbrush.picoviewer.ui.viewer.components

import android.util.Log
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.ecs.resource.Material
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/** glTF has no standard additive blend mode; restore it using PICO's native material API. */
object BrushMaterialRuntime {
    suspend fun apply(root:Entity, ownedMaterials: MutableList<Material>) {
        val queue=ArrayDeque<Entity>();queue.add(root)
        val configured=mutableSetOf<String>()
        var additive=0
        while(queue.isNotEmpty()) {
            val entity=queue.removeFirst()
            entity.components[ModelComponent::class.java]?.materials?.let { materials ->
                for(index in 0 until materials.size) {
                    val material=materials[index] as? UnlitMaterial ?: continue
                    val name=material.getName()
                    val tag=TAG.find(name) ?: continue
                    val mode=tag.groupValues[1]
                    if(mode=="DEPTH") {
                        withContext(NonCancellable) {
                            var depth=ownedMaterials.firstOrNull()
                            if(depth==null) {
                                depth=DepthHullMaterial.load()
                                if(!root.valid || !entity.valid) {
                                    depth.close()
                                    throw CancellationException("Artwork window closed")
                                }
                                ownedMaterials.add(depth)
                            }
                            materials[index]=depth
                        }
                        configured.add(name)
                        continue
                    }
                    material.setBlendingMode(when(mode){"ADD"->BlendingMode.ADD;"ALPHA"->BlendingMode.TRANSPARENT;"MASK"->BlendingMode.MASKED;else->BlendingMode.OPAQUE})
                    material.setDepthWrite(tag.groupValues[2]=="1")
                    material.setDepthTest(true)
                    material.setApplyToneMapping(false)
                    if(configured.add(name) && mode=="ADD")additive++
                }
            }
            entity.getChildren().forEach(queue::add)
        }
        Log.i("OpenBrushMaterials","Native unlit materials=${configured.size}, additive=$additive")
    }
    private val TAG=Regex("^OBV_(ADD|ALPHA|MASK|OPAQUE|DEPTH)_([01])_")
}
