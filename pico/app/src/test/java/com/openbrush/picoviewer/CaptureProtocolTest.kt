package com.openbrush.picoviewer

import com.openbrush.picoviewer.capture.*
import com.openbrush.picoviewer.domain.drawing.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class CaptureProtocolTest {
    @Test fun calibratedCameraTransformRoundTripsRotatedTranslatedSpaces() {
        val a=CapturePose(listOf(2f,1f,-3f),listOf(0f,sqrt(.5f),0f,sqrt(.5f))).matrix()
        val b=CapturePose(listOf(-1f,2f,.5f),listOf(0f,0f,0f,1f)).matrix()
        val relative=CaptureTransform.multiply(CaptureTransform.inverse(a),b)
        assertArrayEquals(b,CaptureTransform.multiply(a,relative),1e-5f)
        assertArrayEquals(relative,CaptureTransform.read(CaptureTransform.json(relative)),0f)
    }
    @Test fun calibrationRejectsScaleReflectionAndNonFiniteValues() {
        val identity=CapturePose(listOf(0f,0f,0f),listOf(0f,0f,0f,1f)).matrix()
        for(value in listOf(2f,-1f,Float.POSITIVE_INFINITY)) {
            val invalid=identity.copyOf();invalid[0]=value
            assertThrows(Exception::class.java) { CaptureTransform.read(CaptureTransform.json(invalid)) }
        }
        assertThrows(IllegalArgumentException::class.java) { CapturePose(listOf(0f,0f,0f),listOf(0f,0f,0f,0f)) }
    }
    @Test fun meshPacketsUseVisibleFragmentsAndRetainOriginalStrokeIdentity() {
        val original=PaintStroke("one-original",PaintStyle(.02f),listOf(PaintPoint(V3.ZERO),PaintPoint(V3(1f,0f,0f))))
        val cut=original.copy(erased=listOf(ArcSpan(.4f,.6f)))
        val (hash,bytes)=CaptureProtocol.resource(cut)
        assertNotEquals(CaptureProtocol.resource(original).first,hash)
        val mesh=StrokeTubeMesh.build(cut)
        val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x314d424f,buffer.int)
        assertEquals(mesh.positions.size,buffer.int);assertEquals(mesh.triangles.size,buffer.int)
        assertEquals(16+mesh.positions.size*24+mesh.triangles.size*4,bytes.size)
        val metadata=CaptureProtocol.stroke(cut,hash)
        assertEquals(original.id,metadata.getString("id"))
        assertEquals(1,metadata.getJSONArray("erased").length())
        assertEquals(hash,CaptureProtocol.hash(bytes))
    }
}
