package com.openbrush.picoviewer

import com.openbrush.picoviewer.data.DrawingDocumentCodec
import com.openbrush.picoviewer.domain.drawing.*
import org.junit.Assert.*
import org.junit.Test

class StrokeSelectionTest {
    private fun line(id: String, y: Float, alpha: Float = 1f) = PaintStroke(
        id, PaintStyle(color = InkColor(.25f, .5f, 1f, alpha)),
        listOf(PaintPoint(V3(0f, y, 0f)), PaintPoint(V3(1f, y, 0f)))
    )

    @Test fun hitUsesVisibleArcAndOriginalIdentity() {
        val edited = line("edited", 0f).copy(
            erased = listOf(ArcSpan(.35f, .65f)),
            recolored = listOf(ColorSpan(ArcSpan(.1f, .3f), InkColor(1f, 0f, 0f)))
        )
        val lower = line("lower", .02f)
        val hidden = line("transparent", 0f, 0f)
        val session = DrawingSession(StrokeDocument(listOf(lower, edited, hidden)))
        assertTrue(session.selectAt(V3(.2f, 0f, 0f)))
        assertEquals("edited", session.selectedStrokeId)
        assertTrue(session.selectAt(V3(.5f, 0f, 0f)))
        assertEquals("lower", session.selectedStrokeId)
        assertFalse(session.selectAt(V3(.5f, .10f, 0f)))
        assertEquals("lower", session.selectedStrokeId)
        assertEquals(0, session.document.revision)
    }

    @Test fun wholeDeletionIsOneUndoStepAndRestoresEditedSpansAndOrder() {
        val source = listOf(line("before", -.2f),
            line("edited", 0f).copy(erased = listOf(ArcSpan(.4f, .6f)),
                recolored = listOf(ColorSpan(ArcSpan(.2f, .4f), InkColor(0f, 1f, 0f)))),
            line("after", .2f))
        val session = DrawingSession(StrokeDocument(source))
        session.stepSelection(1)
        session.stepSelection(1)
        assertEquals("edited", session.selectedStrokeId)
        assertTrue(session.deleteSelected())
        assertNull(session.selectedStrokeId)
        assertEquals(1L, session.document.revision)
        assertEquals(listOf(source.first(), source.last()), session.document.strokes)
        assertTrue(session.undo())
        assertEquals(source, session.document.strokes)
        assertTrue(session.redo())
        val saved = DrawingDocumentCodec.decode(DrawingDocumentCodec.encode(session.document.strokes))
        assertEquals(listOf("before", "after"), saved.map { it.id })
    }
}
