package com.openbrush.picoviewer

import com.openbrush.picoviewer.domain.drawing.*
import com.openbrush.picoviewer.data.DrawingDocumentCodec
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** Replay anatomically structured joints through the classifier, gate, document and mesher. */
class HandPaintingFlowTest {
    private enum class Pose { OPEN, INDEX, THUMB, JOINED, SPREAD }
    private fun hand(pose:Pose,shift:V3=V3.ZERO,indexBend:Float=0f,thumbBend:Float=0f):List<V3> {
        val p=MutableList(26) { V3.ZERO }
        p[0]=V3(0f,-.005f,0f);p[1]=V3(0f,-.035f,0f)
        for((finger,root) in listOf(6 to V3(-.025f,0f,0f),11 to V3(-.008f,.007f,0f),
            16 to V3(.010f,.003f,0f),21 to V3(.027f,-.005f,0f))) {
            p[finger]=root-V3(0f,.012f,0f);p[finger+1]=root
            val extended=pose==Pose.OPEN || (pose==Pose.INDEX && finger==6) ||
                ((pose==Pose.JOINED || pose==Pose.SPREAD) && finger in listOf(6,11))
            p[finger+2]=root+V3(0f,.022f,0f)
            if(extended) {
                p[finger+3]=root+V3(0f,.042f,0f)
                val angle=if(finger==6)indexBend else 0f
                p[finger+4]=p[finger+3]+V3(0f,.020f*cos(angle),-.020f*sin(angle))
            } else {
                p[finger+3]=root+V3(0f,.022f,-.018f)
                p[finger+4]=root+V3(0f,.008f,-.018f)
            }
        }
        if(pose==Pose.SPREAD) {
            p[14]=p[14]+V3(.02f,0f,0f);p[15]=p[15]+V3(.04f,0f,0f)
        }
        p[2]=V3(-.028f,-.020f,0f);p[3]=V3(-.042f,-.007f,0f)
        if(pose==Pose.THUMB || pose==Pose.OPEN) {
            p[4]=V3(-.058f,.003f,0f)
            val direction=(p[4]-p[3]).unit()
            val bent=direction*cos(thumbBend)+V3(0f,0f,1f)*sin(thumbBend)
            p[5]=p[4]+bent*.022f
        } else {
            p[4]=V3(-.035f,.005f,.005f);p[5]=V3(-.030f,.005f,.001f)
        }
        return p.map { it+shift }
    }
    private fun at(pose:Pose,tip:V3,indexBend:Float=0f,thumbBend:Float=0f):List<V3> {
        val p=hand(pose,indexBend=indexBend,thumbBend=thumbBend)
        val anchor=p[if(pose==Pose.THUMB)5 else 10]
        return p.map { it+tip-anchor }
    }
    private inner class Replay(initial:List<PaintStroke> = emptyList()) {
        val gate=HandToolGate();val session=DrawingSession(StrokeDocument(initial))
        fun frame(joints:List<V3>?,time:Long,overUi:Boolean=false):PaintTool {
            val decoded=joints?.let(HandPoseDecoder::decode)
            val tool=gate.update(decoded?.shape,time,overUi,decoded?.heldTools ?: emptySet())
            val point=decoded?.tipFor(tool)
            session.update(tool,point,time,normal=decoded?.normal ?: V3(0f,0f,1f))
            return tool
        }
        fun draw() {
            frame(hand(Pose.OPEN),0)
            frame(at(Pose.INDEX,V3.ZERO),10)
            for(i in 0..10)frame(at(Pose.INDEX,V3(i*.01f,0f,0f)),140+i*15L)
            frame(hand(Pose.OPEN),310)
        }
    }
    @Test fun actualJointShapesDistinguishIndexThumbAndOpenHand() {
        assertEquals(PaintTool.DRAW,HandPoseDecoder.decode(hand(Pose.INDEX))?.shape?.requested)
        assertEquals(PaintTool.ERASE,HandPoseDecoder.decode(hand(Pose.THUMB))?.shape?.requested)
        assertTrue(requireNotNull(HandPoseDecoder.decode(hand(Pose.OPEN))).shape.open)
    }
    @Test fun joinedFingersRecolorButSpreadFingersDoNotAndPoseIsTransformInvariant() {
        val joined=hand(Pose.JOINED)
        val decoded=requireNotNull(HandPoseDecoder.decode(joined))
        assertEquals(PaintTool.RECOLOR,decoded.shape.requested)
        assertTrue((decoded.tipFor(PaintTool.RECOLOR)-(joined[10]+joined[15])*.5f).length()<1e-6f)
        for(scale in listOf(.8f,1.2f)) {
            val transformed=joined.map { V3(-it.y,it.x,it.z)*scale+V3(1f,-2f,3f) }
            assertEquals(PaintTool.RECOLOR,HandPoseDecoder.decode(transformed)?.shape?.requested)
        }
        val spread=requireNotNull(HandPoseDecoder.decode(hand(Pose.SPREAD)))
        assertEquals(PaintTool.NONE,spread.shape.requested)
        assertFalse(PaintTool.RECOLOR in spread.heldTools)
    }
    @Test fun joinedFingerGestureNeedsOpenAndDwellAndStopsOnSpreadUiOrLoss() {
        val replay=Replay()
        assertEquals(PaintTool.NONE,replay.frame(hand(Pose.JOINED),0))
        replay.frame(hand(Pose.OPEN),1)
        replay.frame(hand(Pose.JOINED),10)
        assertEquals(PaintTool.NONE,replay.frame(hand(Pose.JOINED),129))
        assertEquals(PaintTool.RECOLOR,replay.frame(hand(Pose.JOINED),130))
        assertEquals(PaintTool.NONE,replay.frame(hand(Pose.SPREAD),140))
        assertEquals(PaintTool.NONE,replay.frame(hand(Pose.JOINED),150))
        replay.frame(hand(Pose.OPEN),160);replay.frame(hand(Pose.JOINED),170)
        assertEquals(PaintTool.RECOLOR,replay.frame(hand(Pose.JOINED),290))
        assertEquals(PaintTool.NONE,replay.frame(hand(Pose.JOINED),300,overUi=true))
        assertEquals(PaintTool.NONE,replay.frame(hand(Pose.JOINED),310))
        replay.frame(null,320)
        assertEquals(PaintTool.NONE,replay.frame(hand(Pose.JOINED),330))
    }
    @Test fun joinedFingerReplayColorsVisiblePartAndOneUndoRestoresOriginalStroke() {
        val original=PaintStroke("source",PaintStyle(),listOf(PaintPoint(V3.ZERO),PaintPoint(V3(.2f,0f,0f))),
            erased=listOf(ArcSpan(.09f,.11f)))
        val replay=Replay(listOf(original));val red=InkColor(1f,0f,0f)
        replay.session.style=replay.session.style.copy(color=red)
        replay.frame(hand(Pose.OPEN),0);replay.frame(at(Pose.JOINED,V3(.07f,0f,0f)),10)
        assertEquals(PaintTool.RECOLOR,replay.frame(at(Pose.JOINED,V3(.07f,0f,0f)),140))
        replay.frame(at(Pose.JOINED,V3(.12f,0f,0f)),160)
        replay.frame(hand(Pose.OPEN),180)
        val changed=replay.session.document.strokes.single()
        assertTrue(changed.recolored.isNotEmpty());assertEquals(original.erased,changed.erased)
        assertTrue(changed.recolored.all { it.color==red && (it.span.end<=.09f || it.span.start>=.11f) })
        assertTrue(replay.session.undo());assertEquals(listOf(original),replay.session.document.strokes)
        assertFalse(replay.session.document.canUndo)
        assertTrue(replay.session.redo());assertEquals(listOf(changed),replay.session.document.strokes)
    }
    @Test fun jointClassificationIsInvariantToTranslationScaleAndRotation() {
        for(pose in listOf(Pose.INDEX,Pose.THUMB)) {
            val original=requireNotNull(HandPoseDecoder.decode(hand(pose)))
            val changed=hand(pose).map { V3(-it.y,it.x,it.z)*1.2f+V3(.5f,1.2f,-.6f) }
            assertEquals(original.shape.requested,HandPoseDecoder.decode(changed)?.shape?.requested)
        }
    }
    @Test fun jointReplayDrawsThenThumbErasesOnlyMiddleAndHistoryStaysWholeStroke() {
        val replay=Replay();replay.draw()
        val original=replay.session.document.strokes.single()
        replay.frame(at(Pose.THUMB,V3(.05f,0f,0f)),330)
        assertEquals(PaintTool.ERASE,replay.frame(at(Pose.THUMB,V3(.05f,0f,0f)),460))
        replay.frame(hand(Pose.OPEN),480)
        val erased=replay.session.document.strokes.single()
        assertEquals(2,erased.fragments().size);assertEquals(original.id,erased.id)
        assertSame(original.points,erased.points)
        assertTrue(replay.session.undo());assertEquals(listOf(original),replay.session.document.strokes)
        assertTrue(replay.session.undo());assertTrue(replay.session.document.strokes.isEmpty())
        assertTrue(replay.session.redo());assertEquals(listOf(original),replay.session.document.strokes)
        assertTrue(replay.session.redo());assertEquals(listOf(erased),replay.session.document.strokes)
        assertEquals(listOf(erased),DrawingDocumentCodec.decode(DrawingDocumentCodec.encode(listOf(erased))))
    }
    @Test fun slightIndexCurlNoiseHoldsExistingStrokeButCannotStartANewOne() {
        val replay=Replay();replay.frame(hand(Pose.OPEN),0)
        val noisy=at(Pose.INDEX,V3.ZERO,indexBend=PI.toFloat()/6)
        assertEquals(PaintTool.NONE,replay.frame(noisy,10))
        assertEquals(PaintTool.NONE,replay.frame(noisy,140))
        replay.frame(at(Pose.INDEX,V3.ZERO),150)
        assertEquals(PaintTool.DRAW,replay.frame(at(Pose.INDEX,V3.ZERO),280))
        assertEquals(PaintTool.DRAW,replay.frame(at(Pose.INDEX,V3(.01f,0f,0f),indexBend=PI.toFloat()/6),300))
        assertEquals(PaintTool.NONE,replay.frame(hand(Pose.OPEN),301))
        assertEquals(1,replay.session.document.strokes.size)
    }
    @Test fun slightThumbNoiseDoesNotSplitOneEraseIntoMultipleUndoCommands() {
        val original=PaintStroke("source",PaintStyle(),listOf(PaintPoint(V3.ZERO),PaintPoint(V3(.1f,0f,0f))))
        val replay=Replay(listOf(original));replay.frame(hand(Pose.OPEN),0)
        replay.frame(at(Pose.THUMB,V3(.04f,0f,0f)),10)
        replay.frame(at(Pose.THUMB,V3(.04f,0f,0f)),140)
        assertEquals(PaintTool.ERASE,replay.frame(at(Pose.THUMB,V3(.06f,0f,0f),thumbBend=.65f),160))
        replay.frame(hand(Pose.OPEN),180)
        assertTrue(replay.session.undo());assertEquals(listOf(original),replay.session.document.strokes)
        assertFalse(replay.session.document.canUndo)
    }
    @Test fun fastThumbCrossingBetweenTwoMissesStillCutsTheBrush() {
        val original=PaintStroke("source",PaintStyle(),listOf(PaintPoint(V3.ZERO),PaintPoint(V3(.1f,0f,0f))))
        val replay=Replay(listOf(original));replay.frame(hand(Pose.OPEN),0)
        replay.frame(at(Pose.THUMB,V3(.05f,-.07f,0f)),10)
        replay.frame(at(Pose.THUMB,V3(.05f,-.07f,0f)),140)
        replay.frame(at(Pose.THUMB,V3(.05f,.07f,0f)),160)
        replay.frame(hand(Pose.OPEN),180)
        assertEquals(2,replay.session.document.strokes.single().fragments().size)
    }
    @Test fun thumbDistanceDeadbandKeepsExistingEraseButCannotInitiateIt() {
        val p=hand(Pose.THUMB).toMutableList()
        p[4]=V3(-.0495f,-.0015f,0f);p[5]=V3(-.056f,.004f,0f)
        val shifted=p.map { it+V3(.11f,0f,0f)-p[5] }
        val decoded=requireNotNull(HandPoseDecoder.decode(shifted))
        assertEquals(PaintTool.NONE,decoded.shape.requested)
        assertTrue(PaintTool.ERASE in decoded.heldTools)
        val original=PaintStroke("held",PaintStyle(),listOf(PaintPoint(V3.ZERO),PaintPoint(V3(.16f,0f,0f))))
        val replay=Replay(listOf(original));replay.frame(hand(Pose.OPEN),0)
        assertEquals(PaintTool.NONE,replay.frame(shifted,10))
        assertEquals(PaintTool.NONE,replay.frame(shifted,140))
        replay.frame(at(Pose.THUMB,V3(.05f,0f,0f)),160)
        assertEquals(PaintTool.ERASE,replay.frame(at(Pose.THUMB,V3(.05f,0f,0f)),290))
        assertEquals(PaintTool.ERASE,replay.frame(shifted,310))
        assertEquals(PaintTool.NONE,replay.frame(hand(Pose.OPEN),311))
        assertTrue(replay.session.document.strokes.single().erased.any { it.end>.12f })
        assertTrue(replay.session.undo());assertEquals(listOf(original),replay.session.document.strokes)
    }
    @Test fun lostTrackingCommitsTheEraseAndRequiresFreshOpenHand() {
        val replay=Replay();replay.draw()
        replay.frame(at(Pose.THUMB,V3(.03f,0f,0f)),330)
        replay.frame(at(Pose.THUMB,V3(.03f,0f,0f)),460)
        replay.frame(null,480)
        val committed=replay.session.document.strokes
        assertEquals(PaintTool.NONE,replay.frame(at(Pose.THUMB,V3(.08f,0f,0f)),500))
        assertEquals(committed,replay.session.document.strokes)
        assertTrue(replay.session.undo());assertTrue(replay.session.document.strokes.single().erased.isEmpty())
    }
    @Test fun oneThumbGestureCanAffectSeveralOriginalBrushesAndUndoThemTogether() {
        val originals=(0..1).map { i -> PaintStroke("source_$i",PaintStyle(),listOf(
            PaintPoint(V3(0f,i*.008f,0f)),PaintPoint(V3(.1f,i*.008f,0f)))) }
        val replay=Replay(originals);replay.frame(hand(Pose.OPEN),0)
        replay.frame(at(Pose.THUMB,V3(.05f,0f,0f)),10)
        replay.frame(at(Pose.THUMB,V3(.05f,0f,0f)),140)
        replay.frame(hand(Pose.OPEN),160)
        assertTrue(replay.session.document.strokes.all { it.erased.isNotEmpty() })
        assertTrue(replay.session.undo());assertEquals(originals,replay.session.document.strokes)
        assertFalse(replay.session.document.canUndo)
    }
    @Test fun zeroMissingAndImplausibleBonesAreRejectedWithoutAnyPaint() {
        assertNull(HandPoseDecoder.decode(List(26) { V3.ZERO }))
        assertNull(HandPoseDecoder.decode(hand(Pose.INDEX).dropLast(1)))
        val invalid=hand(Pose.THUMB).toMutableList();invalid[10]=V3(5f,5f,5f)
        assertNull(HandPoseDecoder.decode(invalid))
        val replay=Replay();replay.frame(hand(Pose.OPEN),0)
        replay.frame(invalid,10);replay.frame(invalid,140)
        assertTrue(replay.session.document.strokes.isEmpty())
    }
}
