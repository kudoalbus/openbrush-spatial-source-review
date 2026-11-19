package com.openbrush.picoviewer.domain.drawing

import kotlin.math.*

/** Swept thumb sphere vs stroke tube. Clips segments, including between sparse control points. */
object PartialStrokeEraser {
    fun erase(stroke: PaintStroke,from: V3,to: V3,radius: Float,brushExtent: Float? = null): PaintStroke {
        val cuts=contactSpans(stroke,from,to,radius,brushExtent)
        if(cuts.isEmpty()) return stroke
        val combined=mergeSpans(stroke.erased+cuts,stroke.length)
        return if(combined==stroke.erased) stroke else stroke.copy(erased=combined)
    }
    /** Shared swept contact geometry; returned intervals use original stroke arc length. */
    fun contactSpans(stroke: PaintStroke,from: V3,to: V3,radius: Float,brushExtent: Float? = null): List<ArcSpan> {
        require(from.finite() && to.finite() && radius.isFinite() && radius>0f)
        val cuts=mutableListOf<ArcSpan>()
        for(i in 1 until stroke.points.size) {
            val a=stroke.points[i-1];val b=stroke.points[i]
            val distance=stroke.distances[i]-stroke.distances[i-1]
            if(distance<1e-7f) continue
            // Include the brush surface, not only its centerline. Using the larger endpoint
            // radius is conservative for linearly varying pressure within one short segment.
            val contact=radius+(brushExtent ?: max(stroke.style.radius(a.pressure),stroke.style.radius(b.pressure)))
            if(!overlaps(a.position,b.position,from,to,contact)) continue
            for(hit in capsuleIntervals(a.position,b.position,from,to,contact))
                cuts.add(ArcSpan(stroke.distances[i-1]+distance*hit.start,stroke.distances[i-1]+distance*hit.end))
        }
        return mergeSpans(cuts,stroke.length)
    }
    private fun overlaps(a: V3,b: V3,c: V3,d: V3,r: Float): Boolean =
        min(a.x,b.x)<=max(c.x,d.x)+r && max(a.x,b.x)>=min(c.x,d.x)-r &&
        min(a.y,b.y)<=max(c.y,d.y)+r && max(a.y,b.y)>=min(c.y,d.y)-r &&
        min(a.z,b.z)<=max(c.z,d.z)+r && max(a.z,b.z)>=min(c.z,d.z)-r

    private fun capsuleIntervals(a: V3,b: V3,from: V3,to: V3,radius: Float): List<ArcSpan> {
        val axis=to-from; val length2=axis.dot(axis)
        val delta=b-a; val offset=a-from
        if(length2<1e-12f) return insideQuadratic(offset,delta,radius,0f,1f)?.let(::listOf) ?: emptyList()
        val q0=offset.dot(axis)/length2;val qd=delta.dot(axis)/length2
        val breaks=mutableListOf(0f,1f)
        if(abs(qd)>1e-10f) for(q in listOf(0f,1f)) {
            val t=(q-q0)/qd; if(t>0f && t<1f) breaks.add(t)
        }
        breaks.sort()
        return buildList {
            for(i in 1 until breaks.size) {
                val lo=breaks[i-1];val hi=breaks[i];val q=q0+qd*(lo+hi)*.5f
                val pair=when {
                    q<0f -> offset to delta
                    q>1f -> (a-to) to delta
                    else -> (offset-axis*q0) to (delta-axis*qd)
                }
                insideQuadratic(pair.first,pair.second,radius,lo,hi)?.let(::add)
            }
        }
    }
    private fun insideQuadratic(offset: V3,delta: V3,r: Float,lo: Float,hi: Float): ArcSpan? {
        val aa=delta.dot(delta).toDouble()
        val bb=2.0*offset.dot(delta)
        val cc=offset.dot(offset).toDouble()-r.toDouble()*r
        if(aa<1e-15) return if(cc<=0.0) ArcSpan(lo,hi) else null
        val discriminant=bb*bb-4.0*aa*cc
        if(discriminant<0.0) return null
        val root=sqrt(discriminant)
        val start=max(lo.toDouble(),(-bb-root)/(2*aa)).toFloat()
        val end=min(hi.toDouble(),(-bb+root)/(2*aa)).toFloat()
        return if(end-start>1e-6f) ArcSpan(start.coerceIn(lo,hi),end.coerceIn(lo,hi)) else null
    }
}
