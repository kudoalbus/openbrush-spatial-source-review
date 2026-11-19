package com.openbrush.picoviewer.ui.studio

import android.os.SystemClock
import com.openbrush.picoviewer.capture.*
import com.openbrush.picoviewer.domain.drawing.DrawingSession
import com.openbrush.picoviewer.data.StudioBrushCatalog
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.controller.ControllerPose
import com.pico.spatial.tracking.hmd.HMDPose
import java.util.UUID

/** Native transforms are sampled on the scene thread; mesh serialization happens in the publisher. */
class CaptureSceneBridge(
    private val root: Entity, private val palette: Entity, private val capture: MixedRealityCapture,
    private val brushes: StudioBrushCatalog
) {
    private val stageId = UUID.randomUUID().toString()
    private var lastFrame = 0L
    private var active = false
    private var previousHead: CapturePose? = null
    private var previousHeadAtMs = 0L
    fun resume() { if(!active) { active = true; lastFrame = 0; previousHead = null; capture.enterStage(stageId) } }
    fun pause() { if(active) { active = false; capture.leaveStage(stageId) } }
    fun offer(nowMs: Long, head: HMDPose?, left: ControllerPose?, right: ControllerPose?, session: DrawingSession,
        headReceivedAtNs: Long?, controllersReceivedAtNs: Long?) {
        if(!active || !capture.state.value.running || nowMs - lastFrame < capture.intervalMs) return
        lastFrame = nowMs
        val headPose = head?.let { runCatching { pose(it.position, it.rotation) }.getOrNull() }
        val old = previousHead
        if(headPose != null && old != null && nowMs - previousHeadAtMs < 100L) {
            val distanceSquared = (0..2).sumOf { val d = headPose.position[it] - old.position[it]; (d*d).toDouble() }
            if(distanceSquared > .64) capture.resetOrigin(stageId, "检测到空间位姿跳变，请重新对齐")
        }
        previousHead = headPose; previousHeadAtMs = nowMs
        fun controller(value: ControllerPose?): CapturePose? = value?.let { p -> runCatching {
            val delta = p.position - requireNotNull(head).position
            val distanceSquared = delta.x*delta.x + delta.y*delta.y + delta.z*delta.z
            if(distanceSquared in .0025f..9f) pose(p.position, p.rotation) else null
        }.getOrNull() }
        capture.offer(CaptureFrame(stageId, SystemClock.elapsedRealtimeNanos(), session.generation,
            session.document.revision, headPose, controller(left), controller(right), matrix(root),
            if(palette.enabled) matrix(palette) else null,
            if(capture.wantsFrames) session.displayStrokes else emptyList(), headReceivedAtNs, controllersReceivedAtNs,brushes))
    }
    private fun pose(p: Vector3, q: Quat) = CapturePose(listOf(p.x, p.y, p.z), listOf(q.x, q.y, q.z, q.w))
    private fun matrix(entity: Entity): FloatArray {
        val origin = entity.convertPositionTo(Vector3(0f, 0f, 0f), null)
        val x = entity.convertPositionTo(Vector3(1f, 0f, 0f), null) - origin
        val y = entity.convertPositionTo(Vector3(0f, 1f, 0f), null) - origin
        val z = entity.convertPositionTo(Vector3(0f, 0f, 1f), null) - origin
        return floatArrayOf(x.x, x.y, x.z, 0f, y.x, y.y, y.z, 0f, z.x, z.y, z.z, 0f, origin.x, origin.y, origin.z, 1f)
    }
}
