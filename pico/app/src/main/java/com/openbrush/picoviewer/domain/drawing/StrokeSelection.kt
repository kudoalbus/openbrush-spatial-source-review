package com.openbrush.picoviewer.domain.drawing

import kotlin.math.sqrt

internal object StrokeSelection {
    const val REACH_METERS = .018f

    fun selectable(stroke: PaintStroke): Boolean = stroke.style.color.a > 0f && stroke.visibleSpans().isNotEmpty()

    fun nearest(strokes: List<PaintStroke>, point: V3, brushExtent: (PaintStyle) -> Float?): String? {
        if (!point.finite()) return null
        var nearest: String? = null
        var best = REACH_METERS
        var bestDistance = Float.POSITIVE_INFINITY
        // Newest stroke wins exact ties. Sweep the visible arc ranges alongside the control segments.
        for (stroke in strokes.asReversed()) {
            if (stroke.style.color.a == 0f) continue
            val visible = stroke.visibleSpans()
            if (visible.isEmpty()) continue
            val extent = brushExtent(stroke.style)
            var spanIndex = 0
            for (i in 1 until stroke.points.size) {
                val from = stroke.distances[i - 1]
                val to = stroke.distances[i]
                while (spanIndex < visible.size && visible[spanIndex].end <= from) spanIndex++
                if (spanIndex == visible.size) break
                if (to <= from || visible[spanIndex].start >= to) continue
                val a = stroke.points[i - 1]
                val b = stroke.points[i]
                val delta = b.position - a.position
                val offset = point - a.position
                val projected = offset.dot(delta) / delta.dot(delta)
                val radius = extent ?: maxOf(stroke.style.radius(a.pressure), stroke.style.radius(b.pressure))
                var index = spanIndex
                while (index < visible.size && visible[index].start < to) {
                    val span = visible[index++]
                    val limit = projected.coerceIn((maxOf(from, span.start) - from) / (to - from),
                        (minOf(to, span.end) - from) / (to - from))
                    val separation = offset - delta * limit
                    val distance = separation.dot(separation)
                    val limitDistance = best + radius
                    if (distance > limitDistance * limitDistance) continue
                    val score = (sqrt(distance) - radius).coerceAtLeast(0f)
                    if (score < best || (score == best && distance < bestDistance)) {
                        best = score
                        bestDistance = distance
                        nearest = stroke.id
                    }
                }
            }
        }
        return nearest
    }
}
