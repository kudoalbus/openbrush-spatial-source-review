package com.openbrush.picoviewer.data

import com.openbrush.picoviewer.domain.drawing.InkColor
import com.openbrush.picoviewer.domain.drawing.PaintPoint
import com.openbrush.picoviewer.domain.drawing.PaintStroke
import com.openbrush.picoviewer.domain.drawing.PaintStyle
import com.openbrush.picoviewer.domain.drawing.V3
import com.openbrush.picoviewer.domain.model.TiltSketch
import java.util.UUID

internal object TiltDrawingImporter {
    data class Result(val strokes: List<PaintStroke>, val notices: List<String>)

    fun convert(sketch: TiltSketch): Result {
        val limitMessage = "TILT 超出可编辑画作上限，请通过「打开文件」作为模型查看"
        require(sketch.strokes.size <= DrawingDocumentCodec.MAX_STROKES) { limitMessage }
        require(sketch.strokes.sumOf { it.points.size } <= DrawingDocumentCodec.MAX_POINTS) { limitMessage }
        var skipped = 0
        var clamped = 0
        val forward = floatArrayOf(0f, 0f, 1f)
        val strokes = buildList {
            for (stroke in sketch.strokes) {
                require(stroke.points.size <= DrawingDocumentCodec.MAX_STROKE_POINTS) { limitMessage }
                val width = stroke.size * stroke.scale * .1f
                require(width.isFinite()) { "TILT 笔宽无效" }
                if (width <= 0f || stroke.points.size < 2) {
                    skipped++
                    continue
                }
                val points = stroke.points.map {
                    val position = TiltGeometry.position(it.position)
                    val normal = TiltGeometry.reflect(TiltGeometry.rotate(it.rotation, forward))
                    PaintPoint(V3(position[0], position[1], position[2]), it.pressure, it.timeMs,
                        V3(normal[0], normal[1], normal[2]))
                }
                val length = points.indices.drop(1).fold(0f) { sum, i ->
                    sum + (points[i].position - points[i - 1].position).length()
                }
                require(length.isFinite()) { "TILT 空间范围无效" }
                if (length <= 1e-6f) {
                    skipped++
                    continue
                }
                val editableWidth = width.coerceIn(.001f, .15f)
                if (editableWidth != width) clamped++
                val color = stroke.color
                val style = PaintStyle(editableWidth, InkColor(color[0], color[1], color[2], color[3]),
                    sketch.brushes[stroke.brush])
                add(PaintStroke(UUID.randomUUID().toString(), style, points))
            }
        }
        require(strokes.isNotEmpty()) { "TILT 文件没有可编辑笔触" }
        val notices = buildList {
            if (skipped > 0) add("跳过 $skipped 条空或退化笔画")
            if (clamped > 0) add("$clamped 条笔画宽度调到 v3 支持范围")
            if (sketch.hasExternalModels) add("画室仅导入笔画，TILT 引用的外部模型不会随画作导入")
        }
        return Result(strokes, notices)
    }
}
