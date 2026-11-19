package com.openbrush.picoviewer

import com.openbrush.picoviewer.domain.drawing.*
import org.junit.Assert.*
import org.junit.Test

class PinchDrawingGateTest {
    private fun hand(gap: Float, x: Float = 0f) = DecodedHand(
        HandShape(FingerShape.CURLED,FingerShape.CURLED,FingerShape.CURLED,FingerShape.CURLED,FingerShape.CURLED),
        index=V3(x,0f,0f),thumb=V3(x,gap,0f),normal=V3(0f,0f,1f),
        heldTools=emptySet(),palmWidth=.08f
    )

    @Test fun pincersMustReopenAfterUiOrLostTracking() {
        val gate=PinchDrawingGate()
        assertFalse(gate.update(hand(.010f),0L,false))
        assertFalse(gate.update(hand(.041f),16L,false))
        assertTrue(gate.update(hand(.010f),32L,false))
        assertTrue(gate.update(hand(.028f),48L,false))
        assertFalse(gate.update(hand(.010f),64L,true))
        assertFalse(gate.update(hand(.010f),80L,false))
        assertFalse(gate.update(hand(.041f),96L,false))
        assertTrue(gate.update(hand(.010f),112L,false))
        assertFalse(gate.update(null,128L,false))
        assertFalse(gate.update(hand(.010f),144L,false))
        assertFalse(gate.update(hand(.041f),160L,false))
        assertTrue(gate.update(hand(.010f),176L,false))
        assertFalse(gate.update(hand(.010f),440L,false))
    }

    @Test fun simulatedPinchesFinishSeparateStrokesAtTheTipMidpoint() {
        val gate=PinchDrawingGate()
        val drawing=DrawingSession()
        listOf(
            hand(.04f),hand(.012f),hand(.012f,.02f),hand(.04f,.02f),
            hand(.012f,.04f),hand(.012f,.07f),hand(.04f,.07f)
        ).forEachIndexed { i, sample ->
            val time=i*16L
            val tool=if(gate.update(sample,time,false))PaintTool.DRAW else PaintTool.NONE
            drawing.update(tool,sample.pinchTip,time,normal=sample.normal)
        }
        assertEquals(2,drawing.document.strokes.size)
        assertEquals(.006f,drawing.document.strokes.first().points.first().position.y,1e-6f)
        assertEquals(.04f,drawing.document.strokes.last().points.first().position.x,1e-6f)
    }
}
