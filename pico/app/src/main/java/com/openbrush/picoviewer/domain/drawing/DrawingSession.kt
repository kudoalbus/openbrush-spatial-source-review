package com.openbrush.picoviewer.domain.drawing

import java.util.UUID

enum class PaintTool { NONE, DRAW, ERASE, RECOLOR }

/** History always swaps complete immutable stroke states; no per-point undo commands. */
class StrokeDocument(initial: List<PaintStroke> = emptyList()) {
    private data class Change(val before: List<PaintStroke>,val after: List<PaintStroke>)
    var strokes: List<PaintStroke> = initial.toList(); private set
    init { validate(strokes) }
    private val past=ArrayDeque<Change>()
    private val future=ArrayDeque<Change>()
    var revision=0L; private set
    val canUndo get()=past.isNotEmpty()
    val canRedo get()=future.isNotEmpty()
    fun commit(next: List<PaintStroke>) {
        if(next==strokes) return
        validate(next)
        past.addLast(Change(strokes,next.toList()))
        while(past.size>100) past.removeFirst()
        future.clear();strokes=next.toList();revision++
    }
    fun undo(): Boolean {
        val change=past.removeLastOrNull() ?: return false
        future.addLast(change);strokes=change.before;revision++;return true
    }
    fun redo(): Boolean {
        val change=future.removeLastOrNull() ?: return false
        past.addLast(change);strokes=change.after;revision++;return true
    }
    private fun validate(strokes: List<PaintStroke>) {
        require(strokes.size<=2000 && strokes.sumOf { it.points.size }<=200_000) { "绘画文档达到笔触／控制点上限" }
        require(strokes.map { it.id }.distinct().size==strokes.size)
    }
}

class DrawingSession(val document: StrokeDocument = StrokeDocument(),
    private val brushExtent: (PaintStyle) -> Float? = { null }) {
    var style=PaintStyle()
    var eraserRadius=.018f
        set(value) { require(value.isFinite() && value in .002f.. .1f);field=value }
    var recolorRadius=.018f
        set(value) { require(value.isFinite() && value in .002f.. .1f);field=value }
    var generation=0L; private set
    var tool=PaintTool.NONE; private set
    var sampleLimitReached=false; private set
    var selectedStrokeId: String? = null; private set
    private var frozenStyle=style
    private var currentId=""
    private val points=mutableListOf<PaintPoint>()
    private var working: List<PaintStroke>?=null
    private var lastErase: V3?=null
    private var startMs=0L
    val displayStrokes: List<PaintStroke> get() {
        val base=working ?: document.strokes
        return if(points.size>=2) base+PaintStroke(currentId,frozenStyle,points.toList()) else base
    }
    fun update(requested: PaintTool,position: V3?,timeMs: Long,pressure: Float = 1f,normal:V3=V3(0f,0f,1f)) {
        require(timeMs>=0L)
        if(position==null || !position.finite()) { finish();return }
        if(requested!=tool) {
            finish()
            if(requested==PaintTool.DRAW && (document.strokes.size>=2000 || document.strokes.sumOf { it.points.size }>=199_999)) {
                sampleLimitReached=true;return
            }
            tool=requested;startMs=timeMs;sampleLimitReached=false
            if(tool==PaintTool.DRAW) { currentId=UUID.randomUUID().toString();frozenStyle=style }
            if(tool==PaintTool.ERASE || tool==PaintTool.RECOLOR) { working=document.strokes;frozenStyle=style }
        }
        when(tool) {
            PaintTool.DRAW -> {
                val last=points.lastOrNull()
                // Reject discontinuous tracking; never bridge a teleport or tracking-origin jump.
                if(last!=null && (position-last.position).length()>.2f) { finish();return }
                if(last==null || (position-last.position).length()>=.001f) {
                    if(points.size>=4096 || points.size+document.strokes.sumOf { it.points.size }>=200_000) { sampleLimitReached=true;return }
                    points.add(PaintPoint(position,pressure.coerceIn(0f,1f),(timeMs-startMs).coerceAtLeast(0L),normal.unit()))
                    generation++
                }
            }
            PaintTool.ERASE, PaintTool.RECOLOR -> {
                val previous=lastErase
                // After a discontinuity edit only at the new contact point, never along the jump.
                val from=if(previous!=null && (position-previous).length()<=.2f) previous else position
                val old=working ?: document.strokes
                val next=old.map {
                    val extent = brushExtent(it.style)
                    if(tool==PaintTool.ERASE) PartialStrokeEraser.erase(it,from,position,eraserRadius,extent)
                    else StrokeAppearance.recolor(it,from,position,recolorRadius,frozenStyle.color,extent)
                }
                if(next!=old) { working=next;generation++ }
                lastErase=position
            }
            PaintTool.NONE -> Unit
        }
    }
    fun finish() {
        if(tool==PaintTool.DRAW && points.size>=2) document.commit(document.strokes+PaintStroke(currentId,frozenStyle,points.toList()))
        if(tool==PaintTool.ERASE || tool==PaintTool.RECOLOR) {
            working?.let(document::commit)
            clearUnavailableSelection()
        }
        if(tool!=PaintTool.NONE) generation++
        points.clear();working=null;lastErase=null;tool=PaintTool.NONE
    }
    fun undo(): Boolean { finish();return document.undo().also { if(it) { generation++;clearUnavailableSelection() } } }
    fun redo(): Boolean { finish();return document.redo().also { if(it) { generation++;clearUnavailableSelection() } } }

    fun selectAt(point: V3): Boolean {
        val hit = StrokeSelection.nearest(document.strokes, point, brushExtent) ?: return false
        selectedStrokeId = hit
        return true
    }

    fun stepSelection(direction: Int) {
        require(direction == -1 || direction == 1)
        finish()
        val visible = document.strokes.filter(StrokeSelection::selectable)
        val index = visible.indexOfFirst { it.id == selectedStrokeId }
        selectedStrokeId = when {
            visible.isEmpty() -> null
            index < 0 -> if (direction > 0) visible.first().id else visible.last().id
            else -> visible[(index + direction + visible.size) % visible.size].id
        }
    }

    fun clearSelection() { selectedStrokeId = null }

    fun deleteSelected(): Boolean {
        finish()
        val id = selectedStrokeId ?: return false
        val next = document.strokes.filterNot { it.id == id }
        clearSelection()
        if (next.size == document.strokes.size) return false
        document.commit(next)
        generation++
        return true
    }

    private fun clearUnavailableSelection() {
        val id = selectedStrokeId ?: return
        if (document.strokes.none { it.id == id && StrokeSelection.selectable(it) }) clearSelection()
    }
}
