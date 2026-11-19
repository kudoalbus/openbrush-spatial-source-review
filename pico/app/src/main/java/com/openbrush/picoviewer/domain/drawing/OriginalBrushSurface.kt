// OpenBrush Spatial Kotlin adaptation of Open Brush / Tilt Brush behavior. See pico/NOTICE.
package com.openbrush.picoviewer.domain.drawing

import com.openbrush.picoviewer.data.StudioBrushProfile

internal data class SurfaceSample(val position: V3, val arc: Float)
internal data class SurfaceVertex(val sample: SurfaceSample, val normal: V3)

private const val ARC_EPSILON = 1e-6f

internal fun surfaceArcs(stroke: PaintStroke, span: ArcSpan): List<Float> {
    val arcs = stroke.distances
    fun lowerBound(distance: Float): Int {
        var left = 0
        var right = arcs.size
        while (left < right) {
            val middle = (left + right) / 2
            if (arcs[middle] < distance) left = middle + 1 else right = middle
        }
        return left
    }
    val first = lowerBound(span.start + ARC_EPSILON)
    val interiorCount = (lowerBound(span.end - ARC_EPSILON) - first).coerceAtLeast(0)
    val count = minOf(96, interiorCount + 2)
    return buildList {
        add(span.start)
        for (i in 0 until count - 2) add(arcs[first + i * interiorCount / (count - 2)])
        add(span.end)
    }
}

/** Erasures split the original surface; recolors only clip its triangles at the saved arc boundaries. */
internal object OriginalBrushSurface {
    fun build(stroke: PaintStroke, colors: List<ArcSpan>, profile: StudioBrushProfile): StrokeMeshData {
        val positions = mutableListOf<V3>()
        val normals = mutableListOf<V3>()
        val indices = mutableListOf<Int>()
        val membrane = profile.geometry.kind == "MEMBRANE"
        for (span in stroke.visibleSpans()) {
            val colorSpans = colors.mapNotNull { color ->
                val start = maxOf(span.start, color.start)
                val end = minOf(span.end, color.end)
                if (end - start > ARC_EPSILON) ArcSpan(start, end) else null
            }
            if (colorSpans.isEmpty()) continue
            val triangles = if (membrane) OriginalBrushMembrane.triangles(stroke, span, profile) else
                OriginalBrushHull.triangles(stroke, span, profile)
            if (membrane && triangles.isEmpty()) {
                for (range in colorSpans) {
                    val edge = stroke.copy(
                        style = stroke.style.copy(brush = "spatial_tube", width = (stroke.style.width * .13f).coerceAtLeast(.001f)),
                        erased = listOf(ArcSpan(0f, range.start), ArcSpan(range.end, stroke.length))
                    )
                    val mesh = StrokeTubeMesh.build(edge, sides = 6)
                    val base = positions.size
                    positions.addAll(mesh.positions)
                    normals.addAll(mesh.normals)
                    indices.addAll(mesh.triangles.map { base + it })
                }
                continue
            }
            for (triangle in triangles) for (range in colorSpans) {
                val min = triangle.minOf { it.sample.arc }
                val max = triangle.maxOf { it.sample.arc }
                // A triangle at a shared boundary only belongs to the following color.
                if (min > range.end || max < range.start || (min == range.end && range.end != span.end)) continue
                val polygon = clip(clip(triangle, range.start, true), range.end, false)
                if (polygon.size < 3) continue
                val base = positions.size
                for (vertex in polygon) {
                    positions.add(vertex.sample.position)
                    normals.add(vertex.normal)
                }
                for (i in 1 until polygon.lastIndex) indices.addAll(listOf(base, base + i, base + i + 1))
            }
        }
        return StrokeMeshData(positions, normals, indices)
    }

    private fun clip(polygon: List<SurfaceVertex>, boundary: Float, keepAbove: Boolean): List<SurfaceVertex> {
        var previous = polygon.lastOrNull() ?: return emptyList()
        fun keeps(arc: Float) = if (keepAbove) arc >= boundary else arc <= boundary
        return buildList {
            for (vertex in polygon) {
                val a = previous.sample.arc
                val b = vertex.sample.arc
                if (keeps(a) != keeps(b) && a != boundary && b != boundary) {
                    val t = (boundary - a) / (b - a)
                    val point = previous.sample.position + (vertex.sample.position - previous.sample.position) * t
                    val normal = (previous.normal + (vertex.normal - previous.normal) * t).unit(previous.normal)
                    add(SurfaceVertex(SurfaceSample(point, boundary), normal))
                }
                if (keeps(b)) add(vertex)
                previous = vertex
            }
        }
    }
}
