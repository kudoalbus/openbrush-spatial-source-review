package com.openbrush.picoviewer.domain.drawing

import kotlin.math.*

/** Authoring coordinates are right-handed, in document-local meters. */
data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(b: V3) = V3(x+b.x,y+b.y,z+b.z)
    operator fun minus(b: V3) = V3(x-b.x,y-b.y,z-b.z)
    operator fun times(s: Float) = V3(x*s,y*s,z*s)
    fun dot(b: V3) = x*b.x+y*b.y+z*b.z
    fun cross(b: V3) = V3(y*b.z-z*b.y,z*b.x-x*b.z,x*b.y-y*b.x)
    fun length() = sqrt(dot(this))
    fun unit(fallback: V3 = V3(1f,0f,0f)): V3 = length().let { if(it>1e-7f) this*(1f/it) else fallback }
    fun finite() = x.isFinite() && y.isFinite() && z.isFinite()
    companion object { val ZERO = V3(0f,0f,0f) }
}
data class InkColor(val r: Float, val g: Float, val b: Float, val a: Float = 1f) {
    init { require(listOf(r,g,b,a).all { it.isFinite() && it in 0f..1f }) }
}
data class PaintStyle(val width: Float = .012f, val color: InkColor = InkColor(.05f,.65f,1f), val brush: String = "spatial_tube") {
    init { require(width.isFinite() && width in .001f.. .15f) }
    fun radius(pressure: Float) = width*.5f*(.2f+.8f*pressure.coerceIn(0f,1f))*(if(brush=="spatial_fan")1.4f else 1f)
}
data class PaintPoint(val position: V3, val pressure: Float = 1f, val timeMs: Long = 0L,val normal:V3=V3(0f,0f,1f)) {
    init { require(position.finite() && pressure.isFinite() && pressure in 0f..1f && timeMs>=0L && normal.finite() && normal.length()>.01f) }
    fun between(other: PaintPoint,t: Float) = PaintPoint(position+(other.position-position)*t,
        pressure+(other.pressure-pressure)*t,(timeMs+(other.timeMs-timeMs)*t.toDouble()).toLong(),
        (normal+(other.normal-normal)*t).unit(normal.unit()))
}
data class ArcSpan(val start: Float, val end: Float) {
    init { require(start.isFinite() && end.isFinite() && start>=0f && end>=start) }
}

/** Erasing changes only these intervals: fragments remain ONE original stroke for history. */
data class ColorSpan(val span: ArcSpan, val color: InkColor)

data class PaintStroke(val id: String, val style: PaintStyle, val points: List<PaintPoint>, val erased: List<ArcSpan> = emptyList(),
    val recolored: List<ColorSpan> = emptyList()) {
    init { require(id.isNotBlank() && points.size>=2) }
    val distances: List<Float> = buildList {
        var distance=0f; add(distance)
        for(i in 1 until points.size) { distance+=(points[i].position-points[i-1].position).length(); add(distance) }
    }
    val length: Float get() = distances.last()
    init { require(length.isFinite() && length>1e-6f) }
    fun visibleSpans(): List<ArcSpan> {
        var cursor=0f
        return buildList {
            for(span in mergeSpans(erased,length)) {
                if(span.start>cursor+1e-6f) add(ArcSpan(cursor,span.start))
                cursor=max(cursor,span.end)
            }
            if(length>cursor+1e-6f) add(ArcSpan(cursor,length))
        }
    }
    fun pointAt(distance: Float): PaintPoint {
        val d=distance.coerceIn(0f,length)
        var lo=0;var hi=distances.lastIndex
        while(lo<hi) { val mid=(lo+hi)/2; if(distances[mid]<d) lo=mid+1 else hi=mid }
        if(lo==0) return points.first()
        val extent=distances[lo]-distances[lo-1]
        return if(extent<1e-8f) points[lo] else points[lo-1].between(points[lo],(d-distances[lo-1])/extent)
    }
    fun fragments(): List<List<PaintPoint>> = visibleSpans().map { span ->
        buildList {
            add(pointAt(span.start))
            points.indices.forEach { i -> if(distances[i]>span.start+1e-6f && distances[i]<span.end-1e-6f) add(points[i]) }
            add(pointAt(span.end))
        }
    }
}

fun mergeSpans(spans: List<ArcSpan>,length: Float): List<ArcSpan> {
    val ordered=spans.map { ArcSpan(it.start.coerceIn(0f,length),it.end.coerceIn(0f,length)) }
        .filter { it.end-it.start>1e-6f }.sortedBy { it.start }
    val result=mutableListOf<ArcSpan>()
    for(span in ordered) {
        val last=result.lastOrNull()
        if(last!=null && span.start<=last.end+1e-6f) result[result.lastIndex]=ArcSpan(last.start,max(last.end,span.end))
        else result.add(span)
    }
    return result
}
