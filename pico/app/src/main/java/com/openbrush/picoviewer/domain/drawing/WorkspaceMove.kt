package com.openbrush.picoviewer.domain.drawing

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

internal data class WorkspaceMove(val previousCenter: V3, val center: V3, val axis: V3, val radians: Float) {
    fun apply(position: V3): V3 {
        val offset = position - previousCenter
        val c = cos(radians)
        val s = sin(radians)
        return center + offset*c + axis.cross(offset)*s + axis*(axis.dot(offset)*(1f-c))
    }
}

/** Incremental rigid motion of two tracked anchors, without changing document coordinates. */
internal class WorkspaceMoveGate {
    private data class Grip(val left: V3, val right: V3, val at: Long) {
        val center get() = (left+right)*.5f
        val axis get() = (right-left).unit()
    }
    private var previous: Grip? = null

    fun reset() { previous = null }

    fun update(left: V3?, right: V3?, now: Long): WorkspaceMove? {
        if (left == null || right == null || !left.finite() || !right.finite() || (right-left).length() < .065f) {
            reset()
            return null
        }
        val current = Grip(left,right,now)
        val prior = previous
        previous = current
        if (prior == null || now-prior.at !in 0L..250L || (left-prior.left).length() > .22f ||
            (right-prior.right).length() > .22f || (left==prior.left && right==prior.right)) return null
        val cross = prior.axis.cross(current.axis)
        val length = cross.length()
        val dot = prior.axis.dot(current.axis).coerceIn(-1f,1f)
        // Opposite axes from a tracking identity swap do not have a well-defined rotation.
        if (length < 1e-6f && dot < 0f) return null
        return WorkspaceMove(prior.center,current.center,cross.unit(),atan2(length,dot))
    }
}
