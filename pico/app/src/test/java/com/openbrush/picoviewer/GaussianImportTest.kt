package com.openbrush.picoviewer

import com.openbrush.picoviewer.data.ArtworkMetadata
import com.openbrush.picoviewer.data.GaussianImport
import com.openbrush.picoviewer.domain.model.Artwork
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln

class GaussianImportTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun packedSpltConvertsToNativePlyAndKeepsBoundsColorAndOpacity() {
        val packed=files.newFile("sample.splt")
        packed.writeBytes(ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(.2f).putFloat(-.3f).putFloat(.4f)
            .putFloat(.1f).putFloat(.02f).putFloat(.03f)
            .put(byteArrayOf(255.toByte(),0,128.toByte(),64,255.toByte(),128.toByte(),128.toByte(),128.toByte())).array())
        val native=files.newFile("converted.ply")
        val result=GaussianImport.prepare(packed,native)
        assertEquals(1,result.info.count)
        assertEquals(0f,result.info.lower.x,1e-6f)
        assertEquals(.4f,result.info.upper.x,1e-6f)
        val row=ByteBuffer.wrap(native.readBytes().takeLast(68).toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(.5f/.2820948f,row.getFloat(24),1e-6f)
        assertEquals(-.5f/.2820948f,row.getFloat(28),1e-6f)
        val alpha=64f/255f
        assertEquals(ln(alpha/(1-alpha)),row.getFloat(36),1e-6f)
        assertEquals(ln(.1f),row.getFloat(40),1e-6f)
        assertEquals(1f,row.getFloat(52),1e-6f)
        val read=GaussianImport.prepare(native,files.newFile("unused.ply"))
        assertEquals(native,read.file)
        assertEquals(result.info.lower.z,read.info.lower.z,1e-6f)
        assertEquals(result.info.upper.y,read.info.upper.y,1e-6f)
        val artwork=Artwork(result.file.path,"sample","SPLT","1 point",gaussian=result.info)
        assertEquals(artwork,ArtworkMetadata.decode(ArtworkMetadata.encode(artwork)))
    }

    @Test fun bigEndianPlyNormalizesAndTruncatedInputIsRejected() {
        val fields="x y z f_dc_0 f_dc_1 f_dc_2 opacity scale_0 scale_1 scale_2 rot_0 rot_1 rot_2 rot_3".split(' ')
        val source=files.newFile("big.ply")
        val header=("ply\nformat binary_big_endian 1.0\nelement vertex 1\n"+
            fields.joinToString("") { "property float $it\n" }+"end_header\n").toByteArray()
        val row=ByteBuffer.allocate(56).order(ByteOrder.BIG_ENDIAN)
        for(value in listOf(2f,3f,4f,0f,0f,0f,0f,ln(.1f),ln(.1f),ln(.1f),1f,0f,0f,0f))row.putFloat(value)
        source.writeBytes(header+row.array())
        val canonical=files.newFile("little.ply")
        val normalized=GaussianImport.prepare(source,canonical)
        assertEquals(canonical,normalized.file)
        assertEquals(normalized.info,GaussianImport.prepare(canonical,files.newFile("copy.ply")).info)
        source.writeBytes(header+row.array().copyOf(52))
        val failed=files.newFile("failed.ply")
        assertThrows(IllegalArgumentException::class.java) { GaussianImport.prepare(source,failed) }
        assertFalse(failed.exists())
    }
}
