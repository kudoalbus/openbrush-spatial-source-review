package com.openbrush.picoviewer

import com.openbrush.picoviewer.domain.drawing.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class SpatialPaintingTest {
    private val straight=FingerShape.STRAIGHT
    private val curled=FingerShape.CURLED
    private val open=HandShape(straight,straight,straight,straight,straight)
    private val pointing=HandShape(curled,straight,curled,curled,curled)
    private val thumbsUp=HandShape(straight,curled,curled,curled,curled)
    private fun line(id: String = "original",y: Float = 0f) = PaintStroke(id,PaintStyle(.02f),listOf(
        PaintPoint(V3(0f,y,0f)),PaintPoint(V3(1f,y,0f),timeMs=1000L)))

    @Test fun onlyIndexAndOnlyThumbHaveDifferentTools() {
        assertEquals(PaintTool.DRAW,pointing.requested)
        assertEquals(PaintTool.ERASE,thumbsUp.requested)
        assertEquals(PaintTool.NONE,open.requested)
        assertEquals(PaintTool.NONE,HandShape(straight,straight,curled,curled,curled).requested)
        assertEquals(PaintTool.NONE,pointing.copy(middle=FingerShape.UNCERTAIN).requested)
    }
    @Test fun startRequiresOpenAndStablePoseButOpenStopsImmediately() {
        val gate=HandToolGate()
        assertEquals(PaintTool.NONE,gate.update(pointing,0L))
        gate.update(open,1L)
        assertEquals(PaintTool.NONE,gate.update(pointing,10L))
        assertEquals(PaintTool.NONE,gate.update(pointing,129L))
        assertEquals(PaintTool.DRAW,gate.update(pointing,130L))
        assertEquals(PaintTool.NONE,gate.update(open,131L))
    }
    @Test fun uiAndTrackingLossRequireFreshOpenHand() {
        val gate=HandToolGate(0L)
        gate.update(open,0);gate.update(pointing,1);assertEquals(PaintTool.DRAW,gate.update(pointing,2))
        assertEquals(PaintTool.NONE,gate.update(pointing,3,overUi=true))
        assertEquals(PaintTool.NONE,gate.update(pointing,4))
        gate.update(open,5);gate.update(thumbsUp,6);assertEquals(PaintTool.ERASE,gate.update(thumbsUp,7))
        gate.update(null,8)
        assertEquals(PaintTool.NONE,gate.update(thumbsUp,9))
    }
    @Test fun stalledFramesDoNotResumeDrawingOrErasing() {
        val gate=HandToolGate(0)
        gate.update(open,0);gate.update(thumbsUp,1);gate.update(thumbsUp,2)
        assertEquals(PaintTool.NONE,gate.update(thumbsUp,300))
    }
    @Test fun poseSwitchDoesNotEraseUntilHandHasOpened() {
        val gate=HandToolGate(0)
        gate.update(open,0);gate.update(pointing,1);gate.update(pointing,2)
        assertEquals(PaintTool.NONE,gate.update(thumbsUp,3))
        assertEquals(PaintTool.NONE,gate.update(thumbsUp,4))
        gate.update(open,5);gate.update(thumbsUp,6)
        assertEquals(PaintTool.ERASE,gate.update(thumbsUp,7))
    }
    @Test fun fingerClassificationIsInvariantToPositionAndScale() {
        fun bones(scale: Float,shift: V3) = FingerBones(shift,shift+V3(0f,scale,0f),shift+V3(0f,2*scale,0f),shift+V3(0f,3*scale,0f))
        assertEquals(straight,bones(.02f,V3.ZERO).shape())
        assertEquals(straight,bones(.04f,V3(1f,-2f,3f)).shape())
        assertEquals(curled,FingerBones(V3.ZERO,V3(0f,1f,0f),V3(1f,1f,0f),V3(1f,0f,0f)).shape())
    }
    @Test fun eraserCutsSparseSegmentRatherThanDeletingWholeStroke() {
        val original=line()
        val cut=PartialStrokeEraser.erase(original,V3(.5f,0f,0f),V3(.5f,0f,0f),.1f)
        assertEquals(original.id,cut.id)
        assertSame(original.points,cut.points)
        assertEquals(2,cut.visibleSpans().size)
        assertEquals(.39f,cut.visibleSpans()[0].end,1e-4f)
        assertEquals(.61f,cut.visibleSpans()[1].start,1e-4f)
    }
    @Test fun sweptThumbDoesNotTunnelWhenBothEndpointsMiss() {
        val cut=PartialStrokeEraser.erase(line(),V3(.5f,-.5f,0f),V3(.5f,.5f,0f),.1f)
        assertEquals(2,cut.visibleSpans().size)
        assertEquals(.39f,cut.visibleSpans()[0].end,1e-4f)
        assertEquals(.61f,cut.visibleSpans()[1].start,1e-4f)
    }
    @Test fun nearbyStrokeOutsideEraserRemainsTheSameObject() {
        val original=line(y=.2f)
        assertSame(original,PartialStrokeEraser.erase(original,V3(.5f,-.5f,1f),V3(.5f,.5f,1f),.1f))
    }
    @Test fun contactIncludesVisibleBrushWidth() {
        val cut=PartialStrokeEraser.erase(line(),V3(.5f,.025f,0f),V3(.5f,.025f,0f),.02f)
        assertFalse(cut.erased.isEmpty())
    }
    @Test fun repeatedCutsMergeAndPreserveTheMiddleIsland() {
        val first=PartialStrokeEraser.erase(line(),V3(.25f,0f,0f),V3(.25f,0f,0f),.05f)
        val again=PartialStrokeEraser.erase(first,V3(.25f,0f,0f),V3(.25f,0f,0f),.05f)
        assertSame(first,again)
        val second=PartialStrokeEraser.erase(first,V3(.75f,0f,0f),V3(.75f,0f,0f),.05f)
        assertEquals(3,second.visibleSpans().size)
        assertEquals(2,second.erased.size)
    }
    @Test fun completeEraseCanHaveNoVisibleFragments() {
        val cut=PartialStrokeEraser.erase(line(),V3.ZERO,V3(1f,0f,0f),.1f)
        assertTrue(cut.fragments().isEmpty())
    }
    @Test fun undoOfDrawingIsWholeOriginalStrokeEvenIfItHasManyPoints() {
        val session=DrawingSession()
        for(i in 0..20) session.update(PaintTool.DRAW,V3(i*.005f,0f,0f),i*10L)
        session.finish()
        assertEquals(1,session.document.strokes.size)
        assertEquals(21,session.document.strokes.single().points.size)
        assertTrue(session.undo());assertTrue(session.document.strokes.isEmpty())
        assertTrue(session.redo());assertEquals(21,session.document.strokes.single().points.size)
    }
    @Test fun eraseGestureIsOneWholeStateUndoAcrossMultipleOriginalStrokes() {
        val originals=listOf(line("one"),line("two",.01f))
        val session=DrawingSession(StrokeDocument(originals))
        session.eraserRadius=.03f
        session.update(PaintTool.ERASE,V3(.3f,0f,0f),0)
        session.update(PaintTool.ERASE,V3(.4f,0f,0f),10)
        session.update(PaintTool.ERASE,V3(.5f,0f,0f),20)
        assertEquals(originals,session.document.strokes) // live preview is not a series of history edits
        assertTrue(session.displayStrokes.all { it.erased.isNotEmpty() })
        session.finish()
        val cut=session.document.strokes
        assertTrue(session.undo());assertEquals(originals,session.document.strokes)
        assertFalse(session.document.canUndo)
        assertTrue(session.redo());assertEquals(cut,session.document.strokes)
        assertEquals(listOf("one","two"),session.document.strokes.map { it.id })
    }
    @Test fun noHitEraseDoesNotConsumeUndoOrClearRedo() {
        val doc=StrokeDocument();doc.commit(listOf(line()));doc.undo()
        val session=DrawingSession(doc)
        session.update(PaintTool.ERASE,V3.ZERO,0);session.finish()
        assertFalse(doc.canUndo);assertTrue(doc.canRedo)
    }
    @Test fun newEditAfterUndoClearsRedoButNotOriginalStrokeIds() {
        val doc=StrokeDocument();doc.commit(listOf(line()));doc.undo()
        doc.commit(listOf(line("new")))
        assertFalse(doc.canRedo);assertEquals("new",doc.strokes.single().id)
    }
    @Test fun thumbTrackingJumpDoesNotEraseInterveningStroke() {
        val session=DrawingSession(StrokeDocument(listOf(line())))
        session.update(PaintTool.ERASE,V3(.5f,-1f,0f),0)
        session.update(PaintTool.ERASE,V3(.5f,1f,0f),10)
        session.finish()
        assertTrue(session.document.strokes.single().erased.isEmpty())
    }
    @Test fun renderedFragmentsHaveCapsAndNoTrianglesAcrossErasedGap() {
        val cut=PartialStrokeEraser.erase(line(),V3(.5f,0f,0f),V3(.5f,0f,0f),.1f)
        val mesh=StrokeTubeMesh.build(cut)
        assertFalse(mesh.triangles.isEmpty())
        assertEquals(mesh.positions.size,mesh.normals.size)
        assertTrue(mesh.triangles.all { it in mesh.positions.indices })
        for(t in mesh.triangles.chunked(3)) {
            val xs=t.map { mesh.positions[it].x }
            assertTrue(xs.all { it<=.3901f } || xs.all { it>=.6099f })
        }
        assertTrue(mesh.normals.all { abs(it.length()-1f)<.001f })
    }
    @Test fun invalidNumericInputIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { PaintPoint(V3(Float.NaN,0f,0f)) }
        assertThrows(IllegalArgumentException::class.java) { PartialStrokeEraser.erase(line(),V3.ZERO,V3.ZERO,-1f) }
    }
    @Test fun missingFingerBonesAreNotClassifiedAsAnIntentionalFist() {
        assertEquals(FingerShape.UNCERTAIN,FingerBones(V3.ZERO,V3.ZERO,V3.ZERO,V3.ZERO).shape())
    }
    @Test fun eraseThenUndoThenUndoRemovesTheWholeOriginalStroke() {
        val document=StrokeDocument();document.commit(listOf(line()))
        val session=DrawingSession(document)
        session.update(PaintTool.ERASE,V3(.5f,0f,0f),0);session.finish()
        val erased=document.strokes
        assertTrue(session.undo());assertTrue(document.strokes.single().erased.isEmpty())
        assertTrue(session.undo());assertTrue(document.strokes.isEmpty())
        assertTrue(session.redo());assertTrue(document.strokes.single().erased.isEmpty())
        assertTrue(session.redo());assertEquals(erased,document.strokes)
    }
}
