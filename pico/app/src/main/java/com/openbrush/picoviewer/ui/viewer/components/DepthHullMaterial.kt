package com.openbrush.picoviewer.ui.viewer.components

import android.util.Log
import com.pico.spatial.core.ecs.resource.AssetBundle
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.ShaderGraphMaterial
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object DepthHullMaterial {
    suspend fun load(): Material {
        try {
            return withContext(Dispatchers.IO) {
                AssetBundle.load("asset://passthrough-hull.bundle").use { bundle ->
                    val material=ShaderGraphMaterial.loadFromAssetBundle(bundle,"DepthHull/Hull/Depth")
                    try {
                        material.toGlobal()
                        material.setDepthTest(true)
                        material.setDepthWrite(true)
                        material.setCullingMode(MaterialCullingMode.NONE)
                        Log.i("OpenBrushHull","Loaded depth hull ${material.getName()}")
                        material
                    } catch(e:Exception) {
                        material.close()
                        throw e
                    }
                }
            }
        } catch(e:CancellationException) {
            throw e
        } catch(e:Exception) {
            Log.w("OpenBrushHull","Could not load depth mask; using visible hull",e)
            return UnlitMaterial.create(BlendingMode.OPAQUE).also {
                it.setBaseColor(Color4(.5f,.5f,.5f,1f))
                it.setCullingMode(MaterialCullingMode.NONE)
                it.toGlobal()
            }
        }
    }
}
