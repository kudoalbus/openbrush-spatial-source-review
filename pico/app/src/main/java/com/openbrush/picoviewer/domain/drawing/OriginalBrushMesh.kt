// OpenBrush Spatial Kotlin adaptation of Open Brush / Tilt Brush behavior. See pico/NOTICE.
package com.openbrush.picoviewer.domain.drawing

import com.openbrush.picoviewer.data.StudioBrushProfile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Static strip and tube path, following the visionOS Open Brush profile and full-stroke UV rules. */
object OriginalBrushMesh {
    fun build(stroke: PaintStroke, profile: StudioBrushProfile, original: PaintStroke = stroke,
        animation: BrushAnimationFrame = BrushAnimationFrame()): StrokeMeshData {
        val shape = profile.geometry
        if (profile.isArcSurface) return OriginalBrushSurface.build(original, stroke.visibleSpans(), profile)
        val tube = shape.kind == "TUBE"
        val section = profile.section
        val sides = section?.size ?: if (tube) shape.sides else 1
        val ringSize = if (profile.faceted) sides * 2 else sides + 1
        val positions = mutableListOf<V3>()
        val normals = mutableListOf<V3>()
        val triangles = mutableListOf<Int>()
        val uv = mutableListOf<Pair<Float, Float>>()
        val arcs = stroke.distances
        val pressure = FloatArray(arcs.size)
        val tile = FloatArray(arcs.size)
        pressure[0] = if (shape.zeroInitialPressure) 0f else stroke.points.first().pressure
        for (i in 1 until arcs.size) {
            val delta = arcs[i] - arcs[i - 1]
            val weight = if (shape.pressureWindowMeters > 1e-6f) .1f.pow(delta / shape.pressureWindowMeters) else 0f
            pressure[i] = pressure[i - 1] * weight + stroke.points[i].pressure * (1f - weight)
            profile.tileRate?.let { rate ->
                val fullWidth = stroke.style.width * (shape.pressureMin + (1f - shape.pressureMin) * pressure[i])
                val perimeter = if (tube && section == null) fullWidth * PI.toFloat() * shape.radiusMultiplier else fullWidth
                tile[i] = tile[i - 1] + delta * rate / perimeter.coerceAtLeast(.0001f)
            }
        }
        profile.spray?.let { return stamps(stroke, profile, pressure, animation) }
        if (profile.name == "Slice") return slices(stroke, profile, pressure)
        val row = stroke.id.toByteArray(Charsets.UTF_8).fold(0) { acc, byte ->
            (acc * 31 + (byte.toInt() and 255)) % profile.atlasRows
        }
        fun textureU(i: Int, t: Float, distance: Float): Float =
            if (profile.tileRate == null) distance / stroke.length else tile[i] + (tile[i + 1] - tile[i]) * t
        for (span in stroke.visibleSpans()) {
            val knots = buildList {
                add(span.start)
                for (distance in arcs) {
                    if (distance > last() + 1e-6f && distance < span.end - 1e-6f) add(distance)
                }
                add(span.end)
            }
            val middle = stroke.length * .5f
            val wave = profile.wave
            val samples = when {
                wave != null -> {
                    val segments = (64f * (span.end - span.start) / stroke.length).toInt()
                    val added = (1 until segments).map { span.start + (span.end - span.start) * it / segments }
                    (knots + added).distinct().sorted()
                }
                knots.size == 2 && (profile.modifier == -1 || profile.name == "Thick Geometry") &&
                    middle > span.start && middle < span.end -> listOf(span.start, middle, span.end)
                else -> knots
            }
            val points = samples.map(stroke::pointAt)
            val base = positions.size
            var previousRight = V3.ZERO
            var startCenter = points.first().position
            var endCenter = points.last().position
            var segment = 0
            for (i in points.indices) {
                val point = points[i]
                val tangent = (points[minOf(i + 1, points.lastIndex)].position -
                    points[maxOf(i - 1, 0)].position).unit()
                val reference = if (abs(tangent.y) < .9f) V3(0f, 1f, 0f) else V3(1f, 0f, 0f)
                val projected = previousRight - tangent * previousRight.dot(tangent)
                val candidate = if (tube && section == null && i > 0) projected else tangent.cross(point.normal)
                var right = candidate.unit(projected.unit(tangent.cross(reference).unit()))
                if ((tube || !shape.singleSided) && right.dot(previousRight) < 0f) right = right * -1f
                previousRight = right
                val normal = right.cross(tangent).unit()
                val distance = samples[i]
                while (segment < arcs.size - 2 && arcs[segment + 1] <= distance) segment++
                val extent = arcs[segment + 1] - arcs[segment]
                val t = if (extent > 1e-8f) (distance - arcs[segment]) / extent else 0f
                val filtered = pressure[segment] + (pressure[segment + 1] - pressure[segment]) * t
                val width = stroke.style.width * (shape.pressureMin + (1f - shape.pressureMin) * filtered).coerceAtLeast(.001f)
                val baseRadius = width * .5f * if (tube) shape.radiusMultiplier else 1f
                val progress = distance / stroke.length
                val radius = maxOf(baseRadius * .001f, when (profile.modifier) {
                    -1 -> baseRadius * sin(progress * PI.toFloat()).coerceAtLeast(.002f)
                    1 -> {
                        val taper = minOf(4, (arcs.size - 1) / 2)
                        val leading = arcs[taper].coerceAtLeast(.0001f)
                        val trailing = (stroke.length - arcs[arcs.lastIndex - taper]).coerceAtLeast(.0001f)
                        baseRadius * minOf(1f, distance / leading, (stroke.length - distance) / trailing)
                    }
                    2 -> baseRadius * abs(sin(progress * PI.toFloat()))
                    4 -> baseRadius * (1f - progress) * if (profile.name == "Spikes") 1.1f else 1f
                    5 -> baseRadius * abs(sin(progress * PI.toFloat())) + stroke.style.width * 1.5f * filtered * progress.pow(3)
                    else -> baseRadius
                }) * (wave?.widthScale ?: 1f)
                val displacement = wave?.displacement(progress, animation)?.times(stroke.style.width) ?: 0f
                val center = if (wave == BrushWave.TUBE) point.position else point.position + right * displacement
                if (i == 0) startCenter = center
                if (i == points.lastIndex) endCenter = center
                val u = textureU(segment, t, distance)
                for (vertex in 0 until ringSize) {
                    val side = if (profile.faceted) vertex / 2 + vertex % 2 else vertex
                    val v = side / sides.toFloat()
                    val angle = v * 2f * PI.toFloat() + shape.crossSectionAngle
                    if (section != null) {
                        val (x, y) = section[side % sides]
                        val (x0, y0) = section[vertex / 2]
                        val (x1, y1) = section[(vertex / 2 + 1) % sides]
                        val thickness = if (profile.name == "Thick Geometry") abs(sin(progress * PI.toFloat())).coerceAtLeast(.002f) else 1f
                        positions.add(point.position + (right * x + normal * (y * thickness)) * radius)
                        normals.add((right * ((y1-y0)*thickness) - normal * (x1-x0)).unit())
                        uv.add(u to v)
                        continue
                    }
                    val radial = if (tube) right * cos(angle) + normal * sin(angle) else right * (2f * v - 1f)
                    val growth = if (wave == BrushWave.TUBE)
                        displacement * (radius / (stroke.style.width * .5f)).coerceAtMost(1f) else 0f
                    positions.add(center + radial * (radius + growth))
                    val faceAngle = (vertex / 2 + .5f) / sides * 2f * PI.toFloat() + shape.crossSectionAngle
                    normals.add(when {
                        profile.faceted -> right * cos(faceAngle) + normal * sin(faceAngle)
                        tube -> radial
                        else -> normal
                    })
                    uv.add(u to ((row + v) / profile.atlasRows))
                }
                if (i == 0) continue
                for (side in 0 until sides) {
                    val offset = if (profile.faceted) side * 2 else side
                    val a = base + (i - 1) * ringSize + offset
                    val c = base + i * ringSize + offset
                    // Match the declared outward normal under PICO's counter-clockwise culling.
                    if (tube) triangles.addAll(listOf(a, c, a + 1, a + 1, c, c + 1))
                    else triangles.addAll(listOf(a, a + 1, c, a + 1, c + 1, c))
                }
            }
            if (!tube || profile.modifier == 1 || profile.modifier == 5) continue
            for (end in listOf(false, true)) {
                val i = if (end) points.lastIndex else 0
                val neighbor = if (end) i - 1 else 1
                val normal = (points[i].position - points[neighbor].position).unit()
                val center = positions.size
                val first = base + i * ringSize
                positions.add(if (end) endCenter else startCenter)
                normals.add(normal)
                uv.add(uv[first].first to ((row + .5f) / profile.atlasRows))
                val capStart = positions.size
                for (side in 0..sides) {
                    val source = if (profile.faceted) minOf(side * 2, ringSize - 1) else side
                    positions.add(positions[first + source])
                    normals.add(normal)
                    uv.add(uv[first + source])
                }
                for (side in 0 until sides) {
                    val a = capStart + side
                    triangles.addAll(if (end) listOf(center, a + 1, a) else listOf(center, a, a + 1))
                }
            }
        }
        return StrokeMeshData(positions, normals, triangles, uv)
    }

    private fun stamps(stroke: PaintStroke, profile: StudioBrushProfile, pressure: FloatArray,
        animation: BrushAnimationFrame): StrokeMeshData {
        val spray = requireNotNull(profile.spray)
        val positions = mutableListOf<V3>()
        val normals = mutableListOf<V3>()
        val triangles = mutableListOf<Int>()
        val uv = mutableListOf<Pair<Float, Float>>()
        val limit = minOf(4096, maxOf(128, stroke.points.size * 4))
        val visible = stroke.visibleSpans()
        var seed = stroke.id.toByteArray(Charsets.UTF_8).fold(14695981039346656037UL) { a, b ->
            (a xor (b.toInt() and 255).toULong()) * 1099511628211UL
        }
        fun random(): Float {
            seed += 0x9e3779b97f4a7c15UL
            var z = seed
            z = (z xor (z shr 30)) * 0xbf58476d1ce4e5b9UL
            z = (z xor (z shr 27)) * 0x94d049bb133111ebUL
            return ((z xor (z shr 31)) shr 40).toFloat() / 16777216f
        }
        var arc = 0f
        var end = 1
        var span = 0
        for (mark in 0 until limit) {
            if (arc >= stroke.length || span == visible.size) break
            while (stroke.distances[end] <= arc) end++
            val startArc = stroke.distances[end - 1]
            val t = (arc - startArc) / (stroke.distances[end] - startArc)
            val p = stroke.points[end - 1].between(stroke.points[end], t)
            val filtered = pressure[end - 1] + (pressure[end] - pressure[end - 1]) * t
            val minPressure = profile.geometry.pressureMin
            val size = (stroke.style.width * (minPressure + (1f - minPressure) * filtered)).coerceAtLeast(.0001f)
            val forward = (stroke.points[end].position - stroke.points[end - 1].position).unit()
            val axis = if (abs(forward.y) < .9f) V3(0f, 1f, 0f) else V3(1f, 0f, 0f)
            val right = forward.cross(p.normal).unit(forward.cross(axis).unit())
            val normal = right.cross(forward).unit()
            val baseAngle = (random()*2f-1f) * spray.rotation * PI.toFloat()/180f
            val baseRadius = size * (1f + (random()*2f-1f)*spray.variation) * .5f
            val z = random()*2f-1f
            val azimuth = random()*2f*PI.toFloat()
            val xy = sqrt((1f-z*z).coerceAtLeast(0f))
            val anchor = p.position + V3(cos(azimuth)*xy, sin(azimuth)*xy, z) * (size*spray.scatter*random().pow(1f/3f))
            val cell = (random() * spray.columns * spray.columns).toInt()
            while (span < visible.size && visible[span].end <= arc) span++
            if (span < visible.size && arc >= visible[span].start) {
                val pose = profile.particle?.let { it.motion.pose(arc, stroke.length, it.lifetime, right, normal, animation) }
                val center = anchor + (pose?.offset ?: V3.ZERO) * stroke.style.width
                val radius = baseRadius * (pose?.scale ?: 1f).coerceAtLeast(.001f)
                val angle = baseAngle + (pose?.angle ?: 0f)
                val along = (forward*cos(angle) + normal.cross(forward)*sin(angle))*radius
                val across = (right*cos(angle) + normal.cross(right)*sin(angle))*radius
                val step = 1f/spray.columns
                val u = cell%spray.columns*step
                val v = cell/spray.columns*step
                fun card(lateral: V3, facing: V3) = appendCard(positions,normals,triangles,uv,
                    center,along,lateral,facing,u,v,step)
                card(across,normal)
                if (spray.crossed) card(normal * radius, across.unit() * -1f)
            }
            arc += maxOf(stroke.length/limit, spray.fixedInterval ?: (size/spray.rate))
        }
        return StrokeMeshData(positions,normals,triangles,uv)
    }

    private fun slices(stroke: PaintStroke, profile: StudioBrushProfile, pressures: FloatArray): StrokeMeshData {
        val positions = mutableListOf<V3>()
        val normals = mutableListOf<V3>()
        val triangles = mutableListOf<Int>()
        val uv = mutableListOf<Pair<Float, Float>>()
        val visible = stroke.visibleSpans()
        var span = 0
        var previous = V3.ZERO
        for ((i, point) in stroke.points.withIndex()) {
            val arc = if (i == stroke.points.lastIndex) Math.nextDown(stroke.length) else stroke.distances[i]
            val movement = stroke.points[minOf(i + 1, stroke.points.lastIndex)].position -
                stroke.points[maxOf(i - 1, 0)].position
            if (movement.length() < 1e-7f) continue
            val tangent = movement.unit()
            val axis = if (abs(tangent.y) < .9f) V3(0f,1f,0f) else V3(1f,0f,0f)
            val desired = if (i == 0) tangent.cross(point.normal) else previous - tangent * previous.dot(tangent)
            val side = desired.unit(tangent.cross(axis).unit())
            previous = side
            while (span < visible.size && visible[span].end <= arc) span++
            if (span == visible.size) break
            if (arc < visible[span].start) continue
            val minimum = profile.geometry.pressureMin
            val radius = stroke.style.width * .5f * (minimum + (1f - minimum) * pressures[i])
            appendCard(positions,normals,triangles,uv,point.position,side*radius,
                side.cross(tangent).unit()*radius,tangent,0f,0f,1f)
        }
        return StrokeMeshData(positions,normals,triangles,uv)
    }

    private fun appendCard(positions: MutableList<V3>, normals: MutableList<V3>, triangles: MutableList<Int>,
        uv: MutableList<Pair<Float, Float>>, center: V3, along: V3, across: V3, normal: V3, u: Float, v: Float, step: Float) {
        val index = positions.size
        positions.addAll(listOf(center-along-across, center-along+across, center+along-across, center+along+across))
        repeat(4) { normals.add(normal) }
        uv.addAll(listOf(u to v, u to v+step, u+step to v, u+step to v+step))
        triangles.addAll(listOf(index,index+1,index+2,index+2,index+1,index+3))
    }
}
