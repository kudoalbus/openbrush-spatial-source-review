// Source-review edition: exercises cases that run without a headset or the bundled artwork.
package com.openbrush.picoviewer

import com.openbrush.picoviewer.data.*
import com.openbrush.picoviewer.domain.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BrushMaterialTest {
    private fun studio(): StudioBrushCatalog {
        fun asset(path: String) = File("src/main/assets", path).readText()
        return StudioBrushCatalog.load(BrushBookCatalogCodec.decode(asset("brush-library/catalog.json")), ::asset)
    }

    @Test fun tiltPassthroughBuildsVolumeAndSurvivesGlbMaterialConversion() {
        val guid=BrushRenderCatalog.PASSTHROUGH_HULL
        val shape=BrushGeometry(name="PassthroughHull",kind="HULL")
        val points=(0..8).map { i->
            StrokePoint(floatArrayOf(0f,i*.25f,0f),floatArrayOf(0f,0f,0f,1f),1f)
        }
        // This source width exceeds v3's editable width limit and must stay intact in model preview.
        val stroke=BrushStroke(0,floatArrayOf(.4f,.8f,.2f,1f),4f,1f,points)
        val mesh=TiltStudioMesh.build(stroke,studio()[guid]!!)
        assertTrue(TiltStudioMesh.build(stroke.copy(size=0f),studio()[guid]!!).triangles.isEmpty())
        assertTrue(mesh.triangles.size>12)
        assertTrue(mesh.positions.maxOf { it.z }-mesh.positions.minOf { it.z }>.39f)
        val output=File.createTempFile("tilt-depth-hull", ".glb").apply { deleteOnExit() }
        val report=TiltMesher.write(TiltSketch(listOf(stroke),listOf(guid),false),output,
            { BrushStyle("PassthroughHull") },{ shape })
        assertEquals(mesh.triangles.size/3L,report.triangleCount)
        val document=GlbInspector.json(output)
        assertEquals(1,GlbBrushMaterials.apply(document,catalog()).selfLit)
        val material=document.getJSONArray("materials").getJSONObject(0)
        assertTrue(material.getString("name").startsWith("OBV_DEPTH_1_"))
        assertEquals("OPAQUE",material.getString("alphaMode"))
        val converted=document.toString()
        GlbBrushMaterials.apply(document,catalog())
        assertEquals(converted,document.toString())
    }
    @Test fun tiltModelUsesAllStudioSurfacesAndDiamondTransparency() {
        val catalog = studio()
        val surfaces = catalog.enabledIds.mapNotNull { catalog[it] }.filter { it.isArcSurface }
        assertEquals(11, surfaces.size)
        val coordinates = listOf(0f to 0f, 2f to 0f, 2f to 2f, 0f to 2f, 0f to 0f)
        val points = coordinates.map { (x, y) -> StrokePoint(floatArrayOf(x, y, 0f), floatArrayOf(0f, 0f, 0f, 1f), .8f) }
        val strokes = surfaces.indices.map { BrushStroke(it, floatArrayOf(.2f,.4f,.8f,1f), .4f, 1f, points) }
        val file = File.createTempFile("tilt-surfaces", ".glb").apply { deleteOnExit() }
        val report = TiltMesher.write(TiltSketch(strokes, surfaces.map { it.material.guid }, false), file) { guid ->
            catalog[guid]!!.let { BrushStyle(it.name, studioProfile=it) }
        }
        val doc = GlbInspector.json(file)
        val primitives = doc.getJSONArray("meshes").getJSONObject(0).getJSONArray("primitives")
        val accessors = doc.getJSONArray("accessors")
        assertEquals(11, primitives.length())
        for ((index, profile) in surfaces.withIndex()) {
            val primitive = primitives.getJSONObject(index)
            val count = accessors.getJSONObject(primitive.getInt("indices")).getInt("count") / 3
            val material = doc.getJSONArray("materials").getJSONObject(primitive.getInt("material"))
            when (profile.name) {
                "Outlined Membrane" -> assertEquals(34, count)
                "Matte Membrane", "Shiny Membrane", "Diamond Membrane" -> assertEquals(2, count)
                else -> {
                    val pos = accessors.getJSONObject(primitive.getJSONObject("attributes").getInt("POSITION"))
                    assertTrue(profile.name, pos.getJSONArray("max").getDouble(2) > pos.getJSONArray("min").getDouble(2))
                }
            }
            if (profile.name.startsWith("Diamond ")) assertEquals("BLEND", material.getString("alphaMode"))
        }
        assertEquals(GlbInspector.inspect(file).triangleCount, report.triangleCount)
    }
    @Test fun tiltSquareDiamondAndBeveledSectionsRetainDistinctGeometryAndUvs() {
        val catalog = studio()
        val profiles = catalog.enabledIds.mapNotNull { catalog[it] }.filter { it.section != null }
        assertEquals(3, profiles.size)
        val points = listOf(0f, 2f, 4f).map { y ->
            StrokePoint(floatArrayOf(0f, y, 0f), floatArrayOf(0f, 0f, 0f, 1f), 1f)
        }
        for (profile in profiles) {
            val stroke = BrushStroke(0, floatArrayOf(1f,.5f,.2f,1f), 2f, 1f, points)
            val guid = profile.material.guid
            val output = File.createTempFile("tilt-section", ".glb").apply { deleteOnExit() }
            val report = TiltMesher.write(TiltSketch(listOf(stroke), listOf(guid), false), output) {
                BrushStyle(profile.name, studioProfile=profile)
            }
            val doc = GlbInspector.json(output)
            val attributes = doc.getJSONArray("meshes").getJSONObject(0).getJSONArray("primitives")
                .getJSONObject(0).getJSONObject("attributes")
            val access = doc.getJSONArray("accessors")
            val pos = access.getJSONObject(attributes.getInt("POSITION"))
            val depth = pos.getJSONArray("max").getDouble(2) - pos.getJSONArray("min").getDouble(2)
            when (profile.name) {
                "Square Paper" -> assertEquals(.075, depth, .0001)
                "Thick Geometry" -> assertEquals(.025, depth, .0001)
                "3D Printing Brush" -> assertEquals(.2, depth, .0001)
            }
            assertEquals(1, report.tubeStrokes)
            assertEquals(6L * profile.section!!.size, report.triangleCount)
            val uv = access.getJSONObject(attributes.getInt("TEXCOORD_0"))
            val view = doc.getJSONArray("bufferViews").getJSONObject(uv.getInt("bufferView"))
            val bytes = java.nio.ByteBuffer.wrap(output.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val offset = GlbContainer.bin(output).start.toInt() + view.optInt("byteOffset") + uv.getInt("byteOffset")
            val stride = view.getInt("byteStride")
            assertTrue((0 until uv.getInt("count")).any { bytes.getFloat(offset + it * stride) > .5f })
        }
    }
    @Test fun tiltStationarySurfaceAndSectionKeepTheirLegacyCards() {
        val catalog = studio()
        val point = StrokePoint(floatArrayOf(1f, 2f, 3f), floatArrayOf(0f, 0f, 0f, 1f), 1f)
        for (name in listOf("Matte Hull", "Outlined Membrane", "Square Paper")) {
            val profile = catalog.enabledIds.mapNotNull { catalog[it] }.single { it.name == name }
            for (pointCount in 1..2) {
                val stroke = BrushStroke(0, floatArrayOf(1f, 1f, 1f, 1f), .2f, 1f, List(pointCount) { point })
                val output = File.createTempFile("tilt-tap", ".glb").apply { deleteOnExit() }
                val report = TiltMesher.write(TiltSketch(listOf(stroke), listOf(profile.material.guid), false), output) {
                    BrushStyle(profile.name, studioProfile=profile)
                }
                assertEquals(pointCount * 2L, report.triangleCount)
            }
        }
    }
    private fun catalog()=BrushRenderCatalog(JSONObject(File("src/main/assets/brush-rendering.json").readText()))
    private fun material(guid:String,name:String="brush_test",mode:String="OPAQUE")=JSONObject()
        .put("name",name).put("alphaMode",mode).put("extensions",JSONObject()
            .put("GOOGLE_tilt_brush_material",JSONObject().put("guid",guid)))
        .put("pbrMetallicRoughness",JSONObject().put("baseColorFactor",JSONArray(listOf(.2,.4,.6,.8)))
            .put("baseColorTexture",JSONObject().put("index",2)))
    @Test fun distinguishesSolidCutoutSelfLitFromAdditiveAndLitMaterials() {
        val marker=material("429ed64a-4e97-4466-84d3-145a861ef684").put("alphaCutoff",.067)
        val soft=material("accb32f5-4509-454f-93f8-1df3fd31df1b","brush_SoftHighlighter","BLEND")
        val hull=material("a8fea537-da7c-4d4b-817f-24f074725d6d")
        val paper=material("f1114e2e-eb8d-4fde-915a-6e653b54e9f5","brush_Paper","MASK")
        val litWire=material("62fef968-e842-3224-4a0e-1fdb7cfb745c","brush_Wire (Lit)")
        val originalPaper=paper.toString();val originalWire=litWire.toString()
        val j=JSONObject().put("materials",JSONArray(listOf(marker,soft,hull,paper,litWire)))
        assertEquals(BrushMaterialReport(3,1,1),GlbBrushMaterials.apply(j,catalog()))
        assertTrue(marker.getJSONObject("extensions").has("KHR_materials_unlit"))
        assertEquals("MASK",marker.getString("alphaMode"));assertEquals(.067,marker.getDouble("alphaCutoff"),0.0)
        assertEquals("BLEND",soft.getString("alphaMode"));assertTrue(soft.getString("name").startsWith("OBV_ADD_0_"))
        assertEquals("MASK",hull.getString("alphaMode"))
        assertEquals(originalPaper,paper.toString());assertEquals(originalWire,litWire.toString())
        assertEquals(2,marker.getJSONObject("pbrMetallicRoughness").getJSONObject("baseColorTexture").getInt("index"))
        assertEquals(.8,marker.getJSONObject("pbrMetallicRoughness").getJSONArray("baseColorFactor").getDouble(3),0.0)
    }
    @Test fun allOfficialYesNamesAreCoveredAndUnknownMaterialsStayUntouched() {
        val source=JSONObject(File("src/main/assets/brush-rendering.json").readText())
        val entries=source.getJSONArray("entries")
        val names=mutableSetOf<String>()
        for(i in 0 until entries.length()) {
            val entry=entries.getJSONObject(i)
            if(entry.getBoolean("selfLit")) {
                names.add(entry.getString("officialName"))
                val m=material(entry.getString("guid"),"brush_${entry.getString("officialName")}")
                assertTrue(catalog().match(m)!!.selfLit)
            }
        }
        assertEquals(48,names.size)
        val m=material("00000000-0000-0000-0000-000000000000","My custom glowing material")
        val j=JSONObject().put("materials",JSONArray(listOf(m)));val before=j.toString()
        assertEquals(BrushMaterialReport(),GlbBrushMaterials.apply(j,catalog()));assertEquals(before,j.toString())
    }
    @Test fun conversionIsIdempotentAndRetainsOtherExtensions() {
        val m=material("dce872c2-7b49-4684-b59b-c45387949c5c","brush_Hypercolor")
        m.getJSONObject("extensions").put("CustomExtension",JSONObject().put("value",42))
        val j=JSONObject().put("materials",JSONArray(listOf(m))).put("extensionsUsed",JSONArray(listOf("CustomExtension")))
        assertEquals(1,GlbBrushMaterials.apply(j,catalog()).selfLit)
        val once=j.toString();GlbBrushMaterials.apply(j,catalog());assertEquals(once,j.toString())
        assertEquals(42,m.getJSONObject("extensions").getJSONObject("CustomExtension").getInt("value"))
        assertEquals(2,j.getJSONArray("extensionsUsed").length())
    }
}
