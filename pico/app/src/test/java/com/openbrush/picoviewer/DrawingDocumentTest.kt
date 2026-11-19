package com.openbrush.picoviewer

import com.openbrush.picoviewer.data.DrawingDocumentCodec
import com.openbrush.picoviewer.domain.drawing.*
import org.junit.Assert.*
import org.junit.Test

class DrawingDocumentTest {
    private fun stroke()=PaintStroke("same_identity",PaintStyle(.02f,InkColor(.1f,.2f,.3f)),listOf(
        PaintPoint(V3.ZERO,.5f,0),PaintPoint(V3(1f,0f,0f),.8f,250)))
    @Test fun savedErasedIntervalsDoNotReappearWhenReopened() {
        val cut=PartialStrokeEraser.erase(stroke(),V3(.5f,0f,0f),V3(.5f,0f,0f),.1f)
        val restored=DrawingDocumentCodec.decode(DrawingDocumentCodec.encode(listOf(cut)))
        assertEquals(listOf(cut),restored)
        assertEquals(2,restored.single().fragments().size)
        assertEquals("same_identity",restored.single().id)
    }
    @Test fun fullyErasedStrokeRemainsInDocumentWithItsOriginalIdentity() {
        val cut=PartialStrokeEraser.erase(stroke(),V3.ZERO,V3(1f,0f,0f),.1f)
        val restored=DrawingDocumentCodec.decode(DrawingDocumentCodec.encode(listOf(cut)))
        assertEquals(1,restored.size);assertTrue(restored.single().fragments().isEmpty())
    }
    @Test fun duplicateStrokeIdentitiesAndInvalidVersionsAreRejected() {
        val encoded=DrawingDocumentCodec.encode(listOf(stroke(),stroke()))
        assertThrows(IllegalArgumentException::class.java) { DrawingDocumentCodec.decode(encoded) }
        assertThrows(IllegalArgumentException::class.java) { DrawingDocumentCodec.decode("{\"version\":999,\"units\":\"meters_rh\"}") }
    }
    @Test fun erasureOutsideOriginalCurveIsRejectedRatherThanSilentlyRestored() {
        val encoded=DrawingDocumentCodec.encode(listOf(stroke().copy(erased=listOf(ArcSpan(.2f,2f)))))
        assertThrows(IllegalArgumentException::class.java) { DrawingDocumentCodec.decode(encoded) }
    }
    @Test fun movingPanelDoesNotRepeatPressWithoutPhysicalWithdrawal() {
        val latch=PokeLatch()
        assertEquals(4,latch.update(4,V3.ZERO,0))
        assertNull(latch.update(null,V3.ZERO,200))
        assertNull(latch.update(4,V3.ZERO,300))
        assertNull(latch.update(null,V3(.03f,0f,0f),400))
        assertNull(latch.update(null,V3(.03f,0f,0f),501))
        assertEquals(4,latch.update(4,V3.ZERO,600))
    }
    @Test fun slidingAcrossButtonsWhileTouchingFiresOnlyOnePress() {
        val latch=PokeLatch()
        assertEquals(0,latch.update(0,V3.ZERO,0))
        assertNull(latch.update(1,V3(.1f,0f,0f),20))
        assertNull(latch.update(2,V3(.2f,0f,0f),40))
    }
}
