// Source-review edition: exercises cases that run without a headset or the bundled artwork.
package com.openbrush.picoviewer

import com.openbrush.picoviewer.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class GlbResourceTest {
    private val assets=File("src/main/assets")
    private fun catalog()=BrushTextureCatalog(JSONObject(File(assets,"brush-textures/catalog.json").readText()))
    private val marker="Brush_Marker-429ed64a-4e97-4466-84d3-145a861ef684-v10.0-MainTex.png"
    private val icing="Brush_Icing-2f212815-f4d3-c1a4-681a-feeaf9c6dc37-v10.0-BumpMap.png"
    private fun png()=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l9sAAAAASUVORK5CYII=")
    private fun model(vararg images:String):File {
        val doc=JSONObject("""{"asset":{"version":"2.0"},"buffers":[{"byteLength":6}],"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":6}],"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}]}""")
        doc.put("images",JSONArray(images.map{JSONObject().put("uri",it)}))
        val json=doc.toString().toByteArray();val padded=(json.size+3)/4*4
        return File.createTempFile("external-image", ".glb").apply{
            deleteOnExit();outputStream().use{out->
                out.write(ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).putInt(0x46546c67).putInt(2).putInt(28+padded+6).putInt(padded).putInt(0x4e4f534a).array())
                out.write(json);repeat(padded-json.size){out.write(32)}
                out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(6).putInt(0x004e4942).array())
                out.write(byteArrayOf(1,2,3,4,5,6))
            }
        }
    }
    private fun output()=File.createTempFile("embedded-images", ".glb").apply{deleteOnExit()}
    @Test fun matchesBrushIdentityAndSlotWithVersionedAndNameFallback() {
        val c=catalog()
        assertEquals("Marker",c.match(marker)!!.brush)
        assertEquals("BumpMap",c.match(icing)!!.slot)
        assertEquals("Marker",c.match(marker.replace("429ed64a","00000000"))!!.brush)
        assertNull(c.match(marker.replace("Marker","MarkerCustom").replace("429ed64a","00000000")))
        assertNull(c.match(icing.replace("BumpMap","MainTex")))
        assertNull(c.match("custom.png"))
    }
    @Test fun embedsBundledTexturePreservingGeometryAndAligningUnpaddedBin() {
        val input=model(marker,marker);val output=output()
        val report=GlbResources.embed(input,output,catalog(),{png().inputStream()})
        assertEquals(ResourceReport(1,0),report)
        val j=GlbInspector.json(output);assertFalse(GlbInspector.inspect(output).externalResources)
        val images=j.getJSONArray("images");val view=images.getJSONObject(0).getInt("bufferView")
        assertEquals(view,images.getJSONObject(1).getInt("bufferView"))
        assertEquals(8,j.getJSONArray("bufferViews").getJSONObject(view).getInt("byteOffset"))
        val bin=GlbContainer.bin(output);assertEquals(0,bin.length%4)
        val data=output.readBytes();assertArrayEquals(byteArrayOf(1,2,3,4,5,6),data.copyOfRange(bin.start.toInt(),bin.start.toInt()+6))
        val original=png()
        assertArrayEquals(original,data.copyOfRange(bin.start.toInt()+8,bin.start.toInt()+8+original.size))
    }
    @Test fun customFolderImageOverridesBuiltInEvenWhenItHasABrushName() {
        val input=model(icing);val output=output();val image=png()
        val report=GlbResources.embed(input,output,catalog(),{error("must not open bundled fallback")},{path->assertEquals(icing,path);image.inputStream()})
        assertEquals(ResourceReport(0,1),report)
        val j=GlbInspector.json(output);val v=j.getJSONArray("bufferViews").getJSONObject(1)
        assertEquals(image.size,v.getInt("byteLength"))
        val bin=GlbContainer.bin(output);val data=output.readBytes();assertArrayEquals(image,data.copyOfRange(bin.start.toInt()+8,bin.start.toInt()+8+image.size))
    }
    @Test fun readsEncodedNestedCompanionAndReportsHowToImportMissingImages() {
        val paths=mutableListOf<String>()
        assertEquals(1,GlbResources.embed(model("textures/my%20image+.png"),output(),catalog(),{error("not bundled")},{paths.add(it);png().inputStream()}).local)
        assertEquals(listOf("textures/my image+.png"),paths)
        val e=assertThrows(IllegalStateException::class.java){GlbResources.embed(model("custom.png"),output(),catalog(),{error("not bundled")})}
        assertTrue(e.message!!.contains("导入文件夹"))
    }
    @Test fun rejectsEscapingAndRemoteUrisBeforeOpeningAnyCompanions() {
        for(path in listOf("../image.png","%2e%2e/image.png","/image.png","https://host/image.png","file:image.png","textures\\image.png","textures/%00.png")) {
            assertThrows(IllegalArgumentException::class.java){GlbResources.embed(model(path),output(),catalog(),{error("must not read")},{error("must not read")})}
        }
    }
}
