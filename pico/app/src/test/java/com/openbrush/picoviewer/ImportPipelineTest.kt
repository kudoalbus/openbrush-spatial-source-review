// Source-review edition: exercises cases that run without a headset or the bundled artwork.
package com.openbrush.picoviewer

import com.openbrush.picoviewer.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ImportPipelineTest {


    @Test fun editableTiltPreservesUnsignedTimestampsAndReflectedOrientation() {
        val bytes = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        bytes.putInt(0xc576a5cd.toInt()).putInt(6).putInt(0).putInt(0).putInt(1)
        bytes.putInt(0).putFloat(.25f).putFloat(.5f).putFloat(.75f).putFloat(1f)
        bytes.putFloat(.10f).putInt(0).putInt(3).putInt(2)
        for (i in 0..1) {
            bytes.putFloat(i.toFloat()).putFloat(2f).putFloat(3f)
            bytes.putFloat(0f).putFloat(.7071068f).putFloat(0f).putFloat(.7071068f)
            bytes.putFloat(.6f).putInt((0xF0000000L + i).toInt())
        }
        val input = TiltReader.parseSketch(bytes.array())
        val brush = "F72EC0E7-A844-4E38-82E3-140C44772699"
        val stroke = TiltDrawingImporter.convert(
            com.openbrush.picoviewer.domain.model.TiltSketch(input, listOf(brush), false)
        ).strokes.single()
        assertEquals(brush, stroke.style.brush)
        assertEquals(.1f, stroke.points.last().position.x, 1e-6f)
        assertEquals(-.3f, stroke.points.first().position.z, 1e-6f)
        assertEquals(1f, stroke.points.first().normal.x, 1e-6f)
        assertEquals(0f, stroke.points.first().normal.z, 1e-6f)
        assertEquals(0xF0000000L + 1, stroke.points.last().timeMs)
    }
    @Test fun rejectsTruncatedDataAndUnsupportedSketchVersions() {
        val bytes=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).putInt(0xc576a5cd.toInt()).putInt(5).putInt(0).putInt(0).putInt(1).array()
        assertThrows(IllegalArgumentException::class.java){TiltReader.parseSketch(bytes)}
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4,99)
        assertThrows(IllegalArgumentException::class.java){TiltReader.parseSketch(bytes)}
    }
    @Test fun rejectsOutOfRangeZipEntryAndMalformedContainer() {
        val bad=File.createTempFile("invalid", ".tilt").apply{writeBytes(ByteArray(32));deleteOnExit()}
        assertThrows(IllegalArgumentException::class.java){TiltReader.read(bad)}
    }
    @Test fun rejectsControlPointAllocationBombBeforeAllocating() {
        val bytes=ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0xc576a5cd.toInt()).putInt(5).putInt(0).putInt(0).putInt(1)
            .putInt(0).putFloat(1f).putFloat(0f).putFloat(0f).putFloat(1f)
            .putFloat(1f).putInt(0).putInt(0).putInt(Int.MAX_VALUE).array()
        assertThrows(IllegalArgumentException::class.java){TiltReader.parseSketch(bytes)}
    }
    @Test fun convertsSrgbMidGrayOnceAndPreservesEndpoints() {
        assertEquals(0f,TiltMesher.srgbToLinear(0f),0f)
        assertEquals(1f,TiltMesher.srgbToLinear(1f),1e-6f)
        assertEquals(.21404114f,TiltMesher.srgbToLinear(.5f),1e-6f)
    }
    @Test fun convertsLegacyUnsignedByteColorsPreservingAlphaAndNeverTwice() {
        val doc=org.json.JSONObject("""{"asset":{"version":"2.0","generator":"Tilt Brush 23.3 compatible (Actually: Open Brush 2.0)"},"buffers":[{"byteLength":48}],"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":12}],"accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},{"bufferView":1,"componentType":5121,"normalized":true,"count":3,"type":"VEC4"}],"meshes":[{"primitives":[{"attributes":{"POSITION":0,"COLOR_0":1}}]}]}""")
        val json=doc.toString().toByteArray();val n=(json.size+3)/4*4
        val input=File.createTempFile("legacy", ".glb").apply{deleteOnExit()}
        input.outputStream().use{out->
            out.write(ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).putInt(0x46546c67).putInt(2).putInt(28+n+48).putInt(n).putInt(0x4e4f534a).array());out.write(json);repeat(n-json.size){out.write(32)}
            out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(48).putInt(0x004e4942).array());out.write(ByteArray(36));repeat(3){out.write(byteArrayOf(128.toByte(),64,255.toByte(),128.toByte()))}
        }
        val output=File.createTempFile("linear", ".glb").apply{deleteOnExit()}
        assertEquals(1,LegacyVertexColors.convert(input,output))
        val j=GlbInspector.json(output);assertFalse(LegacyVertexColors.needsConversion(j))
        val index=j.getJSONArray("meshes").getJSONObject(0).getJSONArray("primitives").getJSONObject(0).getJSONObject("attributes").getInt("COLOR_0")
        val a=j.getJSONArray("accessors").getJSONObject(index);val v=j.getJSONArray("bufferViews").getJSONObject(a.getInt("bufferView"))
        val data=output.readBytes();val jl=ByteBuffer.wrap(data,12,4).order(ByteOrder.LITTLE_ENDIAN).int
        val color=ByteBuffer.wrap(data,28+jl+v.getInt("byteOffset"),16).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(TiltMesher.srgbToLinear(128/255f),color.float,1e-6f)
        assertEquals(TiltMesher.srgbToLinear(64/255f),color.float,1e-6f)
        assertEquals(1f,color.float,1e-6f)
        assertEquals(128/255f,color.float,1e-6f)
        assertEquals(0,LegacyVertexColors.convert(output,File(output.parentFile,"unused.glb")))
    }
}
