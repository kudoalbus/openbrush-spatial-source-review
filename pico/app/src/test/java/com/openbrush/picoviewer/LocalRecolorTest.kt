package com.openbrush.picoviewer

import com.openbrush.picoviewer.data.DrawingDocumentCodec
import com.openbrush.picoviewer.domain.drawing.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Authored with the local-recolor change; deliberately not run at user request. */
class LocalRecolorTest {
    private val blue=InkColor(0f,0f,1f)
    private val red=InkColor(1f,0f,0f)
    private val green=InkColor(0f,1f,0f)
    private fun line(brush:String="spatial_tube")=PaintStroke("original",PaintStyle(.02f,blue,brush),listOf(
        PaintPoint(V3.ZERO),PaintPoint(V3(1f,0f,0f),timeMs=1000)))
    private fun tint(s:PaintStroke,x:Float,color:InkColor=red,radius:Float=.05f)=
        StrokeAppearance.recolor(s,V3(x,0f,0f),V3(x,0f,0f),radius,color)
    private fun colorAt(s:PaintStroke,x:Float)=s.recolored.firstOrNull { x>it.span.start && x<it.span.end }?.color ?: s.style.color

    @Test fun sparseStrokeKeepsIdentityAndOnlyTheContactedPartChanges() {
        val original=line();val changed=tint(original,.5f)
        assertEquals(original.id,changed.id);assertSame(original.points,changed.points)
        assertEquals(original.style,changed.style);assertEquals(original.erased,changed.erased)
        assertEquals(red,colorAt(changed,.5f));assertEquals(blue,colorAt(changed,.1f))
        assertEquals(blue,colorAt(changed,.9f))
    }
    @Test fun latestColorWinsAndRepeatedContactDoesNotAccumulateIntervals() {
        val changed=tint(tint(line(),.5f,radius=.15f),.5f,green)
        assertEquals(red,colorAt(changed,.4f));assertEquals(green,colorAt(changed,.5f))
        assertEquals(red,colorAt(changed,.6f));assertEquals(3,changed.recolored.size)
        var repeated=changed
        repeat(20) { repeated=tint(repeated,.5f,green) }
        assertEquals(changed,repeated)
        val reset=tint(changed,.5f,blue,radius=.25f)
        assertTrue(reset.recolored.isEmpty())
    }
    @Test fun erasedAreasKeepTheirColorsAndCannotBeRepaintedIntoVisibility() {
        val original=tint(line(),.5f,red,radius=.1f)
        val erased=original.copy(erased=listOf(ArcSpan(.3f,.7f)))
        assertEquals(erased,tint(erased,.5f,green))
        val changed=StrokeAppearance.recolor(erased,V3(.2f,0f,0f),V3(.8f,0f,0f),.04f,green)
        assertEquals(erased.erased,changed.erased)
        assertEquals(red,colorAt(changed,.5f))
        assertEquals(green,colorAt(changed,.25f));assertEquals(green,colorAt(changed,.75f))
        assertTrue(StrokeAppearance.parts(changed).all { part -> part.visibleSpans().all { it.end<=.3f || it.start>=.7f } })
    }
    @Test fun allThreeBrushShapesPartitionVisibleGeometryByColor() {
        for(brush in listOf("spatial_tube","spatial_flat","spatial_fan")) {
            val changed=tint(line(brush),.5f)
            val parts=StrokeAppearance.parts(changed)
            assertEquals(setOf(blue,red),parts.map { it.style.color }.toSet())
            assertTrue(parts.all { it.id==changed.id && it.points===changed.points && it.recolored.isEmpty() })
            assertTrue(parts.all { StrokeTubeMesh.build(it).triangles.isNotEmpty() })
            val spans=parts.flatMap { it.visibleSpans() }.sortedBy { it.start }
            assertEquals(0f,spans.first().start,1e-6f);assertEquals(1f,spans.last().end,1e-6f)
            for(i in 1 until spans.size) assertEquals(spans[i-1].end,spans[i].start,1e-6f)
            assertEquals(parts.size,parts.map(StrokeAppearance::partId).toSet().size)
        }
    }
    @Test fun recolorPreservesOriginalAlphaAndDoesNothingToFullyTransparentInk() {
        val translucent=line().let { it.copy(style=it.style.copy(color=blue.copy(a=.3f))) }
        assertEquals(.3f,tint(translucent,.5f,red.copy(a=1f)).recolored.single().color.a,0f)
        val transparent=line().let { it.copy(style=it.style.copy(color=blue.copy(a=0f))) }
        assertEquals(transparent,tint(transparent,.5f))
        assertTrue(StrokeAppearance.parts(transparent).isEmpty())
    }
    @Test fun continuousGestureIsOneWholeStateUndoAndRedoAndFreezesSelectedColor() {
        val original=line();val session=DrawingSession(StrokeDocument(listOf(original)))
        session.style=session.style.copy(color=red)
        session.update(PaintTool.RECOLOR,V3(.4f,0f,0f),0)
        session.style=session.style.copy(color=green)
        session.update(PaintTool.RECOLOR,V3(.5f,0f,0f),20)
        session.finish()
        val result=session.document.strokes.single()
        assertEquals(red,colorAt(result,.45f));assertTrue(session.undo())
        assertEquals(listOf(original),session.document.strokes);assertFalse(session.document.canUndo)
        assertTrue(session.redo());assertEquals(listOf(result),session.document.strokes)
    }
    @Test fun trackingJumpDoesNotColorTheSpaceBetweenPositionsAndMissKeepsRedo() {
        val session=DrawingSession(StrokeDocument(listOf(line())))
        session.style=session.style.copy(color=red)
        session.update(PaintTool.RECOLOR,V3(.1f,0f,0f),0)
        session.update(PaintTool.RECOLOR,V3(.9f,0f,0f),20);session.finish()
        assertEquals(blue,colorAt(session.document.strokes.single(),.5f))
        session.undo()
        session.update(PaintTool.RECOLOR,V3(.5f,1f,0f),40);session.finish()
        assertTrue(session.document.canRedo)
    }
    @Test fun savedColorsAndErasureRoundTripAndVersionTwoStillLoads() {
        val changed=tint(line(),.5f).copy(erased=listOf(ArcSpan(.45f,.55f)))
        val json=DrawingDocumentCodec.encode(listOf(changed))
        assertEquals(3,JSONObject(json).getInt("version"))
        assertEquals(listOf(changed),DrawingDocumentCodec.decode(json))
        val old=JSONObject(DrawingDocumentCodec.encode(listOf(line()))).put("version",2)
        old.getJSONArray("strokes").getJSONObject(0).remove("recolored")
        assertEquals(listOf(line()),DrawingDocumentCodec.decode(old.toString()))
    }
    @Test fun malformedColorIntervalsAreRejected() {
        for(intervals in listOf(
            listOf(listOf(.2,.4,1,0,0,1),listOf(.3,.5,0,1,0,1)),
            listOf(listOf(.2,1.1,1,0,0,1)),
            listOf(listOf(.2,.4,1,0,0,.5)))) {
            val json=JSONObject(DrawingDocumentCodec.encode(listOf(line())))
            json.getJSONArray("strokes").getJSONObject(0).put("recolored",JSONArray(intervals))
            assertThrows(IllegalArgumentException::class.java) { DrawingDocumentCodec.decode(json.toString()) }
        }
    }
}
