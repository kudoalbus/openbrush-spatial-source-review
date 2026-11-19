// OpenBrush Spatial Kotlin adaptation of Open Brush / Tilt Brush behavior. See pico/NOTICE.
package com.openbrush.picoviewer.domain.drawing

import com.openbrush.picoviewer.data.StudioBrushProfile
import kotlin.math.abs

/** Directed sphere samples and arc clipping from the visionOS port of Open Brush HullBrush. */
internal object OriginalBrushHull {
    private const val EPSILON = 1e-6f
    private data class Face(val a: Int, val b: Int, val c: Int, val normal: V3)
    private data class Edge(val a: Int, val b: Int)

    fun triangles(stroke: PaintStroke, span: ArcSpan, profile: StudioBrushProfile): List<List<SurfaceVertex>> {
        val clouds = if (profile.name == "Concave Hull") quillClouds(stroke, span, profile) else
            listOf(sampleStroke(stroke, span, profile))
        return clouds.flatMap { samples ->
            val faces = hull(samples, maxOf(stroke.style.width, samples.last().arc - samples.first().arc))
            val smooth = if (profile.name == "Smooth Hull") {
                val totals = Array(samples.size) { V3.ZERO }
                for (face in faces) {
                    val a = samples[face.a].position
                    val area = (samples[face.b].position - a).cross(samples[face.c].position - a)
                    for (i in listOf(face.a, face.b, face.c)) totals[i] = totals[i] + area
                }
                totals
            } else null
            faces.map { face ->
                listOf(face.a, face.b, face.c).map {
                    SurfaceVertex(samples[it], smooth?.get(it)?.unit(face.normal) ?: face.normal)
                }
            }
        }
    }

    private fun quillClouds(stroke: PaintStroke, span: ArcSpan, profile: StudioBrushProfile): List<List<SurfaceSample>> {
        val arcs = surfaceArcs(stroke, span)
        val points = arcs.map(stroke::pointAt)
        val rings = points.mapIndexed { index, point ->
            val direction = points[minOf(index + 1, points.lastIndex)].position -
                points[maxOf(index - 1, 0)].position
            val tangent = direction.unit()
            val width = side(tangent, point.normal) * radius(stroke, point, profile)
            val thickness = width.cross(tangent) * .02f
            listOf(width + thickness, thickness - width, width - thickness, (width + thickness) * -1f).map {
                SurfaceSample(point.position + it, arcs[index])
            }
        }
        return (1 until rings.size).map { end ->
            rings.subList(maxOf(0, end - 4), end + 1).flatten()
        }
    }

    private fun sampleStroke(stroke: PaintStroke, span: ArcSpan, profile: StudioBrushProfile): List<SurfaceSample> {
        val start = stroke.pointAt(span.start)
        val samples = mutableListOf(SurfaceSample(start.position, span.start))
        var previous = start.position
        for (arc in surfaceArcs(stroke, span).drop(1)) {
            val point = stroke.pointAt(arc)
            val direction = point.position - previous
            previous = point.position
            if (direction.dot(direction) <= EPSILON * EPSILON) continue
            val tangent = direction.unit()
            val side = side(tangent, point.normal)
            val up = side.cross(tangent)
            val radius = radius(stroke, point, profile)
            val front = if (span.end < stroke.length) maxOf(0f, span.end - arc) else radius
            samples.add(SurfaceSample(point.position + tangent * minOf(radius, front), arc))
            for (radial in listOf(side, up, side * -1f, up * -1f)) {
                samples.add(SurfaceSample(point.position + tangent * minOf(radius * .70710677f, front) +
                    radial * (radius * .70710677f), arc))
                samples.add(SurfaceSample(point.position + radial * radius, arc))
            }
        }
        return samples
    }

    private fun side(tangent: V3, normal: V3): V3 {
        val axis = if (abs(tangent.y) < .9f) V3(0f, 1f, 0f) else V3(1f, 0f, 0f)
        return tangent.cross(normal).unit(tangent.cross(axis).unit())
    }

    private fun radius(stroke: PaintStroke, point: PaintPoint, profile: StudioBrushProfile): Float {
        val minimum = profile.geometry.pressureMin
        return stroke.style.width * .5f * (minimum + (1f - minimum) * point.pressure)
    }

    private fun hull(samples: List<SurfaceSample>, scale: Float): List<Face> {
        if (samples.size < 4) return emptyList()
        val points = samples.map { (it.position - samples[0].position) * (1f / scale) }
        val b = points.indices.maxBy { points[it].dot(points[it]) }
        if (points[b].length() <= EPSILON) return emptyList()
        val axis = points[b].unit()
        val c = points.indices.maxBy { axis.cross(points[it]).length() }
        val plane = axis.cross(points[c])
        if (plane.length() <= EPSILON) return emptyList()
        val normal = plane.unit()
        val d = points.indices.maxBy { abs(normal.dot(points[it])) }
        if (abs(normal.dot(points[d])) <= EPSILON) return emptyList()
        val interior = (points[b] + points[c] + points[d]) * .25f
        fun face(a: Int, b: Int, c: Int): Face? {
            val cross = (points[b] - points[a]).cross(points[c] - points[a])
            val length = cross.length()
            if (length <= EPSILON * EPSILON) return null
            val n = cross * (1f / length)
            return if (n.dot(interior - points[a]) > 0f) Face(a, c, b, n * -1f) else Face(a, b, c, n)
        }
        val seeds = setOf(0, b, c, d)
        val faces = listOfNotNull(face(0, b, c), face(0, b, d), face(0, c, d), face(b, c, d)).toMutableList()
        for (i in points.indices) {
            if (i in seeds) continue
            val horizon = mutableSetOf<Edge>()
            fun toggle(a: Int, b: Int) {
                val edge = Edge(minOf(a, b), maxOf(a, b))
                if (!horizon.add(edge)) horizon.remove(edge)
            }
            faces.removeAll { triangle ->
                val visible = triangle.normal.dot(points[i] - points[triangle.a]) > EPSILON
                if (visible) {
                    toggle(triangle.a, triangle.b)
                    toggle(triangle.b, triangle.c)
                    toggle(triangle.c, triangle.a)
                }
                visible
            }
            for (edge in horizon.sortedWith(compareBy(Edge::a, Edge::b))) face(edge.a, edge.b, i)?.let(faces::add)
        }
        return faces
    }

}
