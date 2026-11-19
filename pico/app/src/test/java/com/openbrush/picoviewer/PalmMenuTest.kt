package com.openbrush.picoviewer

import com.openbrush.picoviewer.domain.drawing.*
import org.junit.Assert.*
import org.junit.Test

class PalmMenuTest {
    @Test fun leftHandGeometryDistinguishesPalmAndBack() {
        fun pose(thumbSide: Float) = requireNotNull(PalmMenuFrame.left(
            V3.ZERO,V3(0f,0f,-.08f),V3(thumbSide*.04f,0f,-.08f),V3(-thumbSide*.03f,0f,-.07f)))
        val palm=pose(-1f)
        val back=pose(1f)
        assertEquals(1f,palm.y.y,1e-6f)
        assertEquals(-1f,back.y.y,1e-6f)
        assertEquals(1f,palm.x.cross(palm.y).dot(palm.z),1e-6f)
        assertNull(PalmMenuFrame.left(V3.ZERO,V3.ZERO,V3.ZERO,V3.ZERO))
    }

    @Test fun flippingRequiresDwellAndDefersWhileRightHandIsInteracting() {
        val gate=PalmMenuGate()
        assertEquals(PalmMenuFace.FALLBACK,gate.update(.8f,0L,false))
        assertEquals(PalmMenuFace.PALM,gate.update(.8f,121L,false))
        assertEquals(PalmMenuFace.PALM,gate.update(.36f,140L,false))
        assertEquals(PalmMenuFace.PALM,gate.update(-.8f,170L,true))
        assertEquals(PalmMenuFace.PALM,gate.update(-.8f,400L,true))
        assertEquals(PalmMenuFace.PALM,gate.update(-.8f,430L,false))
        assertEquals(PalmMenuFace.BACK,gate.update(-.8f,551L,false))
        assertEquals(PalmMenuFace.BACK,gate.update(0f,565L,false))
        assertEquals(PalmMenuFace.EDGE,gate.update(0f,686L,false))
    }

    @Test fun failedTrackingKeepsMenuBrieflyThenRestoresControllerOrSimulatorFallback() {
        val gate=PalmMenuGate()
        gate.update(-.9f,0L,false)
        assertEquals(PalmMenuFace.BACK,gate.update(-.9f,121L,false))
        assertEquals(PalmMenuFace.BACK,gate.update(null,300L,false))
        assertEquals(PalmMenuFace.BACK,gate.update(null,460L,true))
        assertEquals(PalmMenuFace.FALLBACK,gate.update(null,523L,true))
        assertEquals(PalmMenuFace.FALLBACK,gate.update(.9f,540L,false))
    }
}
