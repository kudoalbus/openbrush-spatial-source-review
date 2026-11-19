package com.openbrush.picoviewer.domain.drawing

import kotlin.math.*

enum class FingerShape { STRAIGHT, CURLED, UNCERTAIN }
data class FingerBones(val root: V3,val middle: V3,val distal: V3,val tip: V3) {
    fun minAngleDegrees():Float? {
        if(listOf(root,middle,distal,tip).any { !it.finite() } ||
            listOf(root-middle,middle-distal,distal-tip).any { it.length()<1e-5f })return null
        return min(angle(root-middle,distal-middle),angle(middle-distal,tip-distal))
    }
    fun shape(): FingerShape {
        val angle=minAngleDegrees() ?: return FingerShape.UNCERTAIN
        return when { angle>=155f -> FingerShape.STRAIGHT
            angle<125f -> FingerShape.CURLED
            else -> FingerShape.UNCERTAIN }
    }
    private fun angle(a: V3,b: V3): Float {
        if(a.length()<1e-5f || b.length()<1e-5f) return 0f
        return (acos(a.unit().dot(b.unit()).coerceIn(-1f,1f))*180f/PI.toFloat())
    }
}
data class HandShape(val thumb: FingerShape,val index: FingerShape,val middle: FingerShape,
    val ring: FingerShape,val little: FingerShape,val fingersTogether: Boolean = false) {
    val open get()=listOf(index,middle,ring,little).all { it==FingerShape.STRAIGHT }
    val requested: PaintTool get() {
        val others=listOf(middle,ring,little).all { it==FingerShape.CURLED }
        return when {
            fingersTogether && thumb==FingerShape.CURLED && index==FingerShape.STRAIGHT && middle==FingerShape.STRAIGHT &&
                ring==FingerShape.CURLED && little==FingerShape.CURLED -> PaintTool.RECOLOR
            others && index==FingerShape.STRAIGHT && thumb==FingerShape.CURLED -> PaintTool.DRAW
            others && index==FingerShape.CURLED && thumb==FingerShape.STRAIGHT -> PaintTool.ERASE
            else -> PaintTool.NONE
        }
    }
}

/** An open hand arms input. UI, loss and tool changes require a fresh open hand. */
class HandToolGate(private val stableMs: Long = 120L) {
    private var armed=false
    private var candidate=PaintTool.NONE
    private var since=0L
    private var active=PaintTool.NONE
    private var lastTime=-1L
    fun reset() { armed=false;candidate=PaintTool.NONE;active=PaintTool.NONE;lastTime=-1L }
    fun update(shape: HandShape?,timeMs: Long,overUi: Boolean = false,heldTools:Set<PaintTool> = emptySet()): PaintTool {
        require(timeMs>=0L)
        if(lastTime>=0L && (timeMs<lastTime || timeMs-lastTime>250L)) reset()
        lastTime=timeMs
        if(shape==null || overUi) { armed=false;candidate=PaintTool.NONE;active=PaintTool.NONE;return PaintTool.NONE }
        if(shape.open) { armed=true;candidate=PaintTool.NONE;active=PaintTool.NONE;return PaintTool.NONE }
        val requested=shape.requested
        if(active!=PaintTool.NONE) {
            if(requested==active || active in heldTools) return active
            armed=false;candidate=PaintTool.NONE;active=PaintTool.NONE;return PaintTool.NONE
        }
        if(!armed || requested==PaintTool.NONE) { candidate=PaintTool.NONE;return PaintTool.NONE }
        if(requested!=candidate) { candidate=requested;since=timeMs;return PaintTool.NONE }
        if(timeMs-since>=stableMs) active=requested
        return active
    }
}

/** Arm with separated fingers. Keep a wider release threshold to ride through tracking jitter. */
internal class PinchDrawingGate {
    private var lastTime = -1L
    private var armed = false
    private var drawing = false

    fun reset() { lastTime = -1L; armed = false; drawing = false }

    fun update(hand: DecodedHand?, timeMs: Long, overUi: Boolean): Boolean {
        if (lastTime >= 0L && (timeMs < lastTime || timeMs-lastTime > 250L)) reset()
        if (hand == null || overUi) {
            reset()
            return false
        }
        lastTime = timeMs
        val gap = (hand.thumb-hand.index).length()
        val close = (hand.palmWidth*.26f).coerceIn(.012f,.023f)
        val release = (hand.palmWidth*.43f).coerceIn(.024f,.038f)
        if (gap >= release) {
            armed = true
            drawing = false
        } else if (armed && gap <= close) {
            drawing = true
        }
        return drawing
    }
}
