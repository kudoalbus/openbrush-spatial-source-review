package com.openbrush.picoviewer

import com.openbrush.picoviewer.data.ArtworkMetadata
import com.openbrush.picoviewer.data.GlbInspector
import com.openbrush.picoviewer.domain.model.Artwork
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlbTriangleCountTest {
    @Test fun countsTheSceneOncePerMeshInstance() {
        val gltf = JSONObject("""
            {
                "scene": 1,
                "scenes": [{"nodes":[3]}, {"nodes":[0,2]}],
                "nodes": [{"children":[1]}, {"mesh":0}, {"mesh":0}, {"mesh":1}],
                "meshes": [
                    {"primitives":[
                        {"indices":1,"attributes":{"POSITION":0}},
                        {"mode":5,"attributes":{"POSITION":2}},
                        {"mode":6,"indices":3,"attributes":{"POSITION":0}},
                        {"mode":1,"attributes":{"POSITION":0}}
                    ]},
                    {"primitives":[{"attributes":{"POSITION":0}}]}
                ],
                "accessors": [{"count":12}, {"count":6}, {"count":5}, {"count":4}]
            }
        """.trimIndent())
        assertEquals(14L, GlbInspector.triangleCount(gltf))
        gltf.remove("scene")
        assertEquals(4L, GlbInspector.triangleCount(gltf))
    }

    @Test fun invalidSceneReferencesLeaveCountUnknown() {
        val gltf = JSONObject("""
            {"scenes":[{"nodes":[0]}],"nodes":[{"children":[0]}],"meshes":[],"accessors":[]}
        """.trimIndent())
        assertNull(GlbInspector.triangleCount(gltf))
    }

    @Test fun persistsCountInRestorableArtworkMetadata() {
        val older = Artwork("/cache/scene.glb", "Scene", "GLB", "")
        assertNull(ArtworkMetadata.decode(ArtworkMetadata.encode(older)).triangleCount)
        val counted = older.copy(triangleCount=3_000_000_000L)
        assertEquals(counted, ArtworkMetadata.decode(ArtworkMetadata.encode(counted)))
    }
}
