// OpenBrush Spatial Kotlin adaptation of Open Brush / Tilt Brush behavior. See pico/NOTICE.
package com.openbrush.picoviewer.domain.drawing

import com.openbrush.picoviewer.data.StudioBrushProfile
import kotlin.math.abs

internal object OriginalBrushMembrane {
    fun triangles(stroke: PaintStroke, span: ArcSpan, profile: StudioBrushProfile): List<List<SurfaceVertex>> {
        val boundary = mutableListOf<SurfaceSample>()
        for (arc in surfaceArcs(stroke, span)) {
            val point = stroke.pointAt(arc).position
            val previous = boundary.lastOrNull()
            if (previous == null || (previous.position - point).length() > 1e-5f) {
                boundary.add(SurfaceSample(point, arc))
            }
        }
        // Drop the repeated position of a closed gesture before triangulating.
        val closed = (boundary.first().position - boundary.last().position).length() <= 1e-5f
        if (boundary.size > 2 && closed) boundary.removeAt(boundary.lastIndex)
        if (boundary.size < 3) return emptyList()
        val origin = boundary.first().position
        val scale = maxOf(stroke.style.width, span.end - span.start)
        val points = boundary.map { (it.position - origin) * (1f / scale) }
        val area = points.indices.fold(V3.ZERO) { sum, i -> sum + points[i].cross(points[(i + 1) % points.size]) }
        if (area.length() <= 1e-8f) return emptyList()
        val axis = listOf(V3(1f, 0f, 0f), V3(0f, 1f, 0f), V3(0f, 0f, 1f)).maxBy { abs(area.dot(it)) }
        fun turn(a: Int, b: Int, c: Int): Float = (points[b] - points[a]).cross(points[c] - points[a]).dot(axis)

        val ring = points.indices.toMutableList()
        if (axis.dot(area) < 0f) ring.reverse()
        val faces = mutableListOf<List<Int>>()
        while (ring.size > 3) {
            val ear = ring.indices.firstOrNull { i ->
                val a = ring[(i + ring.lastIndex) % ring.size]
                val b = ring[i]
                val c = ring[(i + 1) % ring.size]
                turn(a, b, c) > 1e-8f && ring.none { p ->
                    p != a && p != b && p != c &&
                        turn(a, b, p) >= -1e-8f && turn(b, c, p) >= -1e-8f && turn(c, a, p) >= -1e-8f
                }
            } ?: return emptyList()
            faces.add(listOf(ring[(ear + ring.lastIndex) % ring.size], ring[ear], ring[(ear + 1) % ring.size]))
            ring.removeAt(ear)
        }
        faces.add(ring)
        val totals = Array(points.size) { V3.ZERO }
        val faceNormals = faces.map { (a, b, c) ->
            val normal = (points[b] - points[a]).cross(points[c] - points[a])
            for (i in listOf(a, b, c)) totals[i] += normal
            normal.unit(axis)
        }
        val faceted = profile.name == "Diamond Membrane"
        val fill = faces.mapIndexed { i, face ->
            face.map { vertex ->
                val normal = if (faceted) faceNormals[i] else totals[vertex].unit(faceNormals[i])
                SurfaceVertex(boundary[vertex], normal)
            }
        }
        return if (profile.name == "Outlined Membrane") fill + outline(boundary, stroke.style.width * .12f) else fill
    }

    private fun outline(boundary: List<SurfaceSample>, width: Float): List<List<SurfaceVertex>> = buildList {
        for (i in boundary.indices) {
            val a = boundary[i]
            val b = boundary[(i + 1) % boundary.size]
            val tangent = (b.position - a.position).unit()
            val axis = if (abs(tangent.y) < .9f) V3(0f, 1f, 0f) else V3(1f, 0f, 0f)
            val right = tangent.cross(axis).unit() * (width * .5f)
            val up = right.cross(tangent)
            val corners = listOf(right + up, up - right, (right + up) * -1f, right - up)
            for (j in corners.indices) {
                val x = corners[j]
                val y = corners[(j + 1) % corners.size]
                val normal = (x + y).unit()
                fun vertex(sample: SurfaceSample, offset: V3) =
                    SurfaceVertex(sample.copy(position = sample.position + offset), normal)
                val a0 = vertex(a, x)
                val a1 = vertex(a, y)
                val b0 = vertex(b, x)
                val b1 = vertex(b, y)
                add(listOf(a0, b0, a1))
                add(listOf(a1, b0, b1))
            }
        }
    }
}
