package com.openbrush.picoviewer.data

import com.openbrush.picoviewer.domain.drawing.*
import com.openbrush.picoviewer.domain.model.BrushStroke

/** Apply the studio geometry algorithms in local meters, including TILT widths outside the editable range. */
internal object TiltStudioMesh {
    private val empty = StrokeMeshData(emptyList(), emptyList(), emptyList())

    fun build(stroke: BrushStroke, profile: StudioBrushProfile): StrokeMeshData {
        val width = stroke.size * stroke.scale * .1f
        require(width.isFinite())
        if (width <= 1e-8f || stroke.points.size < 2) return empty

        val origin = TiltGeometry.position(stroke.points.first().position).let { V3(it[0], it[1], it[2]) }
        // Within the editable range use real meters so the studio's geometric tolerances also match.
        val meshWidth = width.coerceIn(.001f, .15f)
        val scale = meshWidth / width
        val points = stroke.points.map { control ->
            val p = TiltGeometry.position(control.position)
            val n = TiltGeometry.reflect(TiltGeometry.rotate(control.rotation, floatArrayOf(0f, 0f, 1f)))
            PaintPoint((V3(p[0], p[1], p[2]) - origin) * scale, control.pressure, control.timeMs, V3(n[0], n[1], n[2]))
        }
        var length = 0f
        for (i in 1 until points.size) length += (points[i].position - points[i-1].position).length()
        if (length <= 1e-6f) return empty
        val style = PaintStyle(meshWidth, InkColor(1f, 1f, 1f), profile.material.guid)
        val meshProfile = if (profile.section == null) profile else profile.copy(
            geometry = profile.geometry.copy(pressureWindowMeters = profile.geometry.pressureWindowMeters * stroke.scale * scale)
        )
        val mesh = OriginalBrushMesh.build(PaintStroke("tilt-model", style, points), meshProfile)
        return mesh.copy(positions = mesh.positions.map { origin + it * (1f / scale) })
    }
}
