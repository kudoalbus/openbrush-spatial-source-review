package com.openbrush.picoviewer

import com.openbrush.picoviewer.data.DrawingDocumentCodec
import com.openbrush.picoviewer.domain.drawing.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class WorkspaceMoveTest {
    @Test fun twoAnchorsTranslateAndRotateAroundTheirMidpoint() {
        val gate=WorkspaceMoveGate()
        assertNull(gate.update(V3(-.1f,0f,0f),V3(.1f,0f,0f),0L))
        val motion=requireNotNull(gate.update(V3(.06f,-.08f,0f),V3(.06f,.12f,0f),16L))
        assertEquals(PI.toFloat()/2,motion.radians,1e-6f)
        val transformed=motion.apply(V3(.04f,0f,0f))
        assertEquals(.06f,transformed.x,1e-6f)
        assertEquals(.06f,transformed.y,1e-6f)
        assertEquals(0f,transformed.z,1e-6f)
        val paint=PaintStroke("document-ink",PaintStyle(),listOf(PaintPoint(V3.ZERO),PaintPoint(V3(.04f,0f,0f))))
        assertEquals(listOf(paint),DrawingDocumentCodec.decode(DrawingDocumentCodec.encode(listOf(paint))))
        assertEquals(.04f,paint.length,1e-6f)
    }

    @Test fun separationLossOrTeleportRearmsWithoutMovingTheDocument() {
        val gate=WorkspaceMoveGate()
        val left=V3(-.11f,0f,0f)
        val right=V3(.11f,0f,0f)
        assertNull(gate.update(left,right,0L))
        assertNull(gate.update(null,right,16L))
        assertNull(gate.update(left,right,32L))
        assertNull(gate.update(V3.ZERO,V3(.02f,0f,0f),48L))
        assertNull(gate.update(left,right,64L))
        val jump=V3(.5f,0f,0f)
        assertNull(gate.update(left+jump,right+jump,80L))
        assertNull(gate.update(left+jump,right+jump,400L))
        val small=V3(0f,.04f,0f)
        val move=requireNotNull(gate.update(left+jump+small,right+jump+small,416L))
        val translated=move.apply(V3.ZERO)
        assertEquals(0f,translated.x,1e-6f)
        assertEquals(.04f,translated.y,1e-6f)
    }
}
