package com.openbrush.picoviewer.domain.drawing

import kotlin.math.max
import kotlin.math.min

/** Local RGB edits never change original points, identity, alpha or erased intervals. */
object StrokeAppearance {
    fun recolor(stroke: PaintStroke,from: V3,to: V3,radius: Float,color: InkColor,brushExtent: Float? = null): PaintStroke {
        if(stroke.style.color.a==0f) return stroke
        val visible=stroke.visibleSpans()
        val cuts=mergeSpans(PartialStrokeEraser.contactSpans(stroke,from,to,radius,brushExtent).flatMap { hit ->
            visible.mapNotNull { span ->
                val start=max(hit.start,span.start);val end=min(hit.end,span.end)
                if(end-start>1e-6f) ArcSpan(start,end) else null
            }
        },stroke.length)
        if(cuts.isEmpty()) return stroke
        val target=color.copy(a=stroke.style.color.a)
        var next=stroke.recolored
        for(cut in cuts) {
            next=next.flatMap { old ->
                val s=old.span
                if(s.end<=cut.start || s.start>=cut.end) listOf(old) else buildList {
                    if(s.start<cut.start) add(old.copy(span=ArcSpan(s.start,cut.start)))
                    if(s.end>cut.end) add(old.copy(span=ArcSpan(cut.end,s.end)))
                }
            } + if(target==stroke.style.color) emptyList() else listOf(ColorSpan(cut,target))
        }
        val result=mutableListOf<ColorSpan>()
        for(item in next.sortedBy { it.span.start }) {
            val last=result.lastOrNull()
            if(last!=null && last.color==item.color && item.span.start<=last.span.end+1e-6f)
                result[result.lastIndex]=last.copy(span=ArcSpan(last.span.start,max(last.span.end,item.span.end)))
            else result.add(item)
        }
        // Keep editable workspaces bounded; preserve the previous state if saturated.
        return if(result.size>20_000 || result==stroke.recolored) stroke else stroke.copy(recolored=result)
    }

    /** Derived display parts only. History and saves keep ONE original stroke. */
    fun parts(stroke: PaintStroke): List<PaintStroke> {
        if(stroke.style.color.a==0f || stroke.visibleSpans().isEmpty()) return emptyList()
        if(stroke.recolored.isEmpty()) return listOf(stroke)
        val groups=linkedMapOf<InkColor,MutableList<ArcSpan>>()
        var cursor=0f
        fun add(color: InkColor,start: Float,end: Float) {
            if(end-start>1e-6f) groups.getOrPut(color) { mutableListOf() }.add(ArcSpan(start,end))
        }
        for(item in stroke.recolored) {
            add(stroke.style.color,cursor,item.span.start)
            add(item.color,item.span.start,item.span.end)
            cursor=item.span.end
        }
        add(stroke.style.color,cursor,stroke.length)
        return groups.mapNotNull { (color,spans) ->
            val covered=mergeSpans(spans,stroke.length)
            var at=0f
            val hidden=buildList {
                addAll(stroke.erased)
                for(span in covered) { if(span.start>at) add(ArcSpan(at,span.start));at=span.end }
                if(at<stroke.length) add(ArcSpan(at,stroke.length))
            }
            stroke.copy(style=stroke.style.copy(color=color),erased=mergeSpans(hidden,stroke.length),recolored=emptyList())
                .takeIf { it.visibleSpans().isNotEmpty() }
        }
    }

    /** Stable display key; original ID remains available separately for editing/history. */
    fun partId(stroke: PaintStroke): String = stroke.id+":color:"+with(stroke.style.color) {
        listOf(r,g,b,a).joinToString("_") { it.toBits().toUInt().toString(16) }
    }
}
