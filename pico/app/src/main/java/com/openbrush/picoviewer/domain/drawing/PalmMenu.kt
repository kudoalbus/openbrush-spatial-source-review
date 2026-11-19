package com.openbrush.picoviewer.domain.drawing

/** Basis of the left-hand menu. The front of the palette is its local +Y. */
internal data class PalmMenuFrame(val x: V3, val y: V3, val z: V3) {
    companion object {
        fun left(wrist: V3, middle: V3, index: V3, little: V3): PalmMenuFrame? {
            val forward = middle - wrist
            val dorsal = (index - little).cross(forward)
            if (!forward.finite() || !dorsal.finite() || forward.length() <= .025f || dorsal.length() <= .0001f) {
                return null
            }
            val y = dorsal.unit() * -1f
            val z = forward.unit() * -1f
            val x = y.cross(z).unit()
            return PalmMenuFrame(x,z.cross(x),z)
        }
    }
}

internal enum class PalmMenuFace { FALLBACK, PALM, BACK, EDGE }

/** Stable presentation from palm orientation, independent of the right-hand drawing gesture. */
internal class PalmMenuGate {
    var face = PalmMenuFace.FALLBACK; private set
    private var candidate = face
    private var since = 0L
    private var lastTracked = -1L

    fun reset() {
        face = PalmMenuFace.FALLBACK
        candidate = face
        since = 0L
        lastTracked = -1L
    }

    fun update(up: Float?, now: Long, interacting: Boolean): PalmMenuFace {
        if (now < lastTracked) reset()
        if (up == null || !up.isFinite()) {
            val grace = if (interacting) 400L else 180L
            if (lastTracked < 0L || now-lastTracked > grace) reset()
            else candidate = face
            return face
        }
        lastTracked = now
        val desired = when {
            up >= .50f -> PalmMenuFace.PALM
            up <= -.50f -> PalmMenuFace.BACK
            face == PalmMenuFace.PALM && up >= .30f -> face
            face == PalmMenuFace.BACK && up <= -.30f -> face
            else -> PalmMenuFace.EDGE
        }
        val guarded = interacting && (face == PalmMenuFace.PALM || face == PalmMenuFace.BACK)
        if (guarded || desired == face) candidate = face
        else if (desired != candidate) {
            candidate = desired
            since = now
        } else if (now-since >= 120L) face = desired
        return face
    }
}
