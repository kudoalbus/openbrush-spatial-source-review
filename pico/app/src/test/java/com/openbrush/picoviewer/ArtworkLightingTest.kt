// Source-review edition: exercises cases that run without a headset or the bundled artwork.
package com.openbrush.picoviewer
import com.openbrush.picoviewer.data.*
import com.openbrush.picoviewer.domain.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
class ArtworkLightingTest {
    private fun custom()=JSONObject("""{"Lights":{"Ambient":[74,128,255,255],"Shadow":{"Orientation":[0,0,0,2],"Color":[0.5,1.2,2.4,1]},"NoShadow":{"Orientation":[0.70710678,0,0,0.70710678],"Color":[0,0,0,1]}}}""")
    @Test fun parsesByteAmbientAndPreservesHdrLightColor() {
        val l=requireNotNull(ArtworkMetadata.fromTilt(custom()))
        assertEquals(74f/255,l.ambient[0],1e-6f);assertEquals(1f,l.ambient[2],0f)
        assertEquals(2.4f,l.lights[0].color[2],0f);assertEquals(1f,l.lights[0].orientation[3],0f)
        assertTrue(l.lights[0].shadow);assertFalse(l.lights[1].shadow)
    }
    @Test fun sourceQuaternionReflectionMatchesNativeMinusZLightDirection() {
        val q=listOf(.2f,.4f,-.1f,.88f).let{a->val n=sqrt(a.sumOf{(it*it).toDouble()}).toFloat();a.map{it/n}}
        val unity=TiltGeometry.rotate(q.toFloatArray(),floatArrayOf(0f,0f,1f))
        val native=TiltGeometry.rotate(AmbientKtx.picoQuaternion(q).toFloatArray(),floatArrayOf(0f,0f,-1f))
        assertArrayEquals(TiltGeometry.reflect(unity),native,1e-5f)
    }
    @Test fun metadataLightingOverridesPresetAndMissingFieldsDoNotInventLights() {
        val preset=ArtworkLighting(listOf(0f,0f,0f),emptyList(),"preset:test")
        assertEquals(preset,ArtworkMetadata.fromTilt(JSONObject("""{"EnvironmentPreset":"TEST"}""")){if(it=="test")preset else null})
        assertNull(ArtworkMetadata.fromTilt(JSONObject()))
        assertEquals("tilt:Lights",ArtworkMetadata.fromTilt(custom()){preset}!!.source)
        assertNull(ArtworkMetadata.fromTilt(JSONObject("""{"Lights":{"Ambient":[256,0,0]}}""")))
    }
    @Test fun invalidLightQuaternionDoesNotRejectOtherwiseReadableArtwork() {
        val j=custom();j.getJSONObject("Lights").getJSONObject("Shadow").put("Orientation",org.json.JSONArray(listOf(0,0,0,0)))
        assertNull(ArtworkMetadata.fromTilt(j))
    }
    @Test fun windowPayloadRoundTripsLightingAndKeepsOtherFileIndependent() {
        val a=Artwork("/cache/a.glb","A.tilt","TILT","color",lighting=ArtworkMetadata.fromTilt(custom()))
        val b=Artwork("/cache/b.glb","B.glb","GLB","color")
        val sa=ArtworkMetadata.encode(a);val sb=ArtworkMetadata.encode(b)
        assertEquals(a,ArtworkMetadata.decode(sa));assertEquals(b,ArtworkMetadata.decode(sb))
        assertNotEquals(ArtworkMetadata.decode(sa).path,ArtworkMetadata.decode(sb).path)
        assertNotNull(ArtworkMetadata.decode(sa).lighting);assertNull(ArtworkMetadata.decode(sb).lighting)
    }
    @Test fun ambientKtxContainsSixFacesFiveMipsAndConstantSh() {
        val bytes=AmbientKtx.bytes(listOf(.5f,.25f,0f));val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x04030201,b.getInt(12));assertEquals(6,b.getInt(52));assertEquals(5,b.getInt(56))
        val meta=b.getInt(60);val sh=String(bytes,68,b.getInt(64),Charsets.UTF_8)
        assertTrue(sh.startsWith("sh\u0000"));assertTrue(sh.contains("0 0 0"))
        var offset=64+meta
        for(level in 0..4){val edge=16 shr level;val length=b.getInt(offset);assertEquals(edge*edge*4,length);offset+=4;val first=b.getInt(offset);repeat(6*edge*edge){assertEquals(first,b.getInt(offset+it*4))};offset+=6*length}
        assertEquals(bytes.size,offset)
    }
    @Test fun bundledEnvironmentCatalogResolvesLegacyBlackPreset() {
        val j=JSONObject(File("src/main/assets/lighting-presets.json").readText())
        assertEquals(15,j.length())
        val l=requireNotNull(ArtworkMetadata.fromTilt(JSONObject("""{"EnvironmentPreset":"580b4529-ac50-4fe9-b8d2-635765a14893"}""")){key->j.optJSONObject(key)?.let(ArtworkMetadata::parseLighting)})
        assertEquals("preset:Black",l.source);assertEquals(2,l.lights.size)
    }
}
