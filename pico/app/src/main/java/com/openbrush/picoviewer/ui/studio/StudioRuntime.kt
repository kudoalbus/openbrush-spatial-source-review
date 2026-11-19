package com.openbrush.picoviewer.ui.studio

import com.openbrush.picoviewer.R
import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

import android.os.SystemClock
import android.util.Log
import com.openbrush.picoviewer.domain.drawing.*
import com.openbrush.picoviewer.capture.MixedRealityCapture
import com.openbrush.picoviewer.data.StudioBrushCatalog
import com.pico.spatial.core.ecs.*
import com.pico.spatial.core.ecs.resource.*
import com.pico.spatial.core.math.*
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.hand.*
import com.pico.spatial.tracking.hand.HandJoint.Index.*
import com.pico.spatial.tracking.controller.*
import com.pico.spatial.tracking.hmd.*
import com.pico.spatial.ui.foundation.content.Source
import com.pico.spatial.ui.foundation.content.loadFrom
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*
import java.util.UUID

data class StudioInputUi(val selecting: Boolean = false,val hasSelection: Boolean = false,
    val pinch: Boolean = false,val moving: Boolean = false)

class StudioSystem : com.pico.spatial.core.ecs.System() {
    override fun update(context: SceneUpdateContext) {
        runtimes.forEach { it.diagnose(context.scene) }
        runtimes.forEach { if(it.root.valid && it.root.scene==context.scene) it.frame() }
    }
    companion object { val runtimes=CopyOnWriteArraySet<StudioRuntime>() }
}

enum class StudioPanel { MIXED_REALITY, REFERENCE }

/** Native input is copied by listeners and consumed only on the scene update thread. */
class StudioRuntime(val root: Entity,private val hud: Entity,private val palette: Palette3D,private val brushBook:BrushBook3D,
    private val capturePanel: Entity,private val capture: MixedRealityCapture,val session: DrawingSession,
    private val brushes: StudioBrushCatalog, private val context: Context, private val onPanel: (StudioPanel?)->Unit,
    private val referenceLayer: Entity,
    private val onStatus: (String)->Unit,private val onSave: (List<PaintStroke>,Boolean)->Unit,
    initialPinch: Boolean,private val onInputUi: (StudioInputUi)->Unit,
    audioFrame: ()->BrushAudioFrame) {
    private data class Timed<T>(val data: T,val at: Long = SystemClock.elapsedRealtime())
    private val hands=HandTrackingProvider()
    private val controllers=ControllerTrackingProvider()
    private val head=HMDTrackingProvider()
    private val handData=AtomicReference<Timed<HandTrackingData>?>()
    private val poseData=AtomicReference<Timed<ControllerTrackingData>?>()
    private val actionData=AtomicReference<Timed<ControllerActionData>?>()
    private val headData=AtomicReference<Timed<HMDTrackingData>?>()
    private val handListener=DataProvider.DataListener<HandTrackingData> { handData.set(Timed(it)) }
    private val poseListener=DataProvider.DataListener<ControllerTrackingData> { poseData.set(Timed(it)) }
    private val actionListener=ControllerTrackingProvider.ControllerActionListener { actionData.set(Timed(it)) }
    private val headListener=DataProvider.DataListener<HMDTrackingData> { headData.set(Timed(it)) }
    private val gate=HandToolGate()
    private val pinchGate=PinchDrawingGate()
    private val leftMovePinch=PinchDrawingGate()
    private val rightMovePinch=PinchDrawingGate()
    private val workspaceMove=WorkspaceMoveGate()
    private val palmMenu=PalmMenuGate()
    private var menuFace=PalmMenuFace.FALLBACK
    private var lastMenuHand: Pair<HandPose,PalmMenuFrame>?=null
    private var manualBookOpen: Boolean?=null
    private val poke=PokeLatch()
    private val commands=ConcurrentLinkedQueue<Int>()
    private data class ColorPointer(val phase:Int,val u:Float,val v:Float)
    private val colorPointers=ConcurrentLinkedQueue<ColorPointer>()
    private var colorCapture:Int?=null
    private var colorSource=0 // 1: tracked index, 2: controller ray, 3: OS pointer
    private val renderer=StrokeRenderer(root,brushes,audioFrame) { onStatus(it);Log.e("OpenBrushDrawing",it) }
    private val captureBridge=CaptureSceneBridge(root,palette.entity,capture,brushes)
    private var configurationVisible=false
    private var capturePanelSized=false
    private var referenceModel: Entity?=null
    private var referenceFile: File?=null
    private val reference=Entity().also { root.addChild(it) }
    private val cursorMesh=MeshResource.createSphere(.01f).also { it.toGlobal() }
    private val cursorMaterial=UnlitMaterial.create(BlendingMode.TRANSPARENT).also {
        it.setBaseColor(Color4(.1f,.7f,1f,.5f));it.setApplyToneMapping(false);it.toGlobal()
    }
    private val cursor=ModelEntity(cursorMesh,cursorMaterial).also { root.addChild(it);it.enabled=false }
    var controllerMode=false; private set
    var selectedTool=PaintTool.DRAW; private set
    private var enabled=false
    private var saveAndExitPending=false
    private var hudSized=false
    private var lastTouchFinger=com.pico.spatial.tracking.hand.HandJoint.Index.INDEX_TIP
    private var lastGeneration=-1L
    private var lastSelected: String?=null
    private var selectionMode=false
    private var pinchMode=initialPinch
    private var moving=false
    private var selectionLatched=false
    private var lastSelectionProbe=0L
    private var lastInputUi=StudioInputUi(pinch=initialPinch)
    private var lastRevision=session.document.revision
    private var lastRender=0L
    private var lastStatus=0L
    private var lastBookScroll=0L
    private var lastFrame=0L
    private var released=false
    private var previousActions: ControllerActionData?=null
    private var lastUi=false
    private var lastInput: V3?=null
    private var closed=false
    private var demoErase: Pair<Long,V3>?=null
    private var diagnosticLogged=false
    private var workspacePlaced=false
    init {
        hands.addListener(handListener);controllers.addListener(poseListener);head.addListener(headListener)
        controllers.addControllerActionListener(actionListener)
        com.pico.spatial.core.ecs.System.register(StudioSystem::class.java)
        StudioSystem.runtimes.add(this)
        session.style=session.style.copy(color=palette.currentColor)
        resume()
    }
    fun command(cell: Int) { if(!saveAndExitPending)commands.add(cell) }
    fun bookTap(u:Float,v:Float) { brushBook.surfaceCommand(u,v)?.let(::mouseCommand) }
    fun paletteSurface(phase:Int,u:Float,v:Float) {
        if(!closed && enabled && !saveAndExitPending && !configurationVisible && palette.entity.enabled && !hardwareInputActive()) {
            colorPointers.add(ColorPointer(phase,u,v))
        }
    }
    private fun hardwareInputActive():Boolean {
        val now=SystemClock.elapsedRealtime()
        return handData.get()?.let { now-it.at<250L && decode(it.data.right)!=null }==true ||
            poseData.get()?.let { frame -> now-frame.at<250L && frame.data.right?.let {
                (it.position-head.latestData.hmdPose.position).v3().length() in .1f..1.5f
            }==true }==true
    }
    fun mouseCommand(cell: Int) {
        Log.i("OpenBrushDrawing","Palette press=$cell closed=$closed enabled=$enabled")
        // Ignore OS-emulated taps while raw hardware is active; direct poke/ray owns the event.
        if(!hardwareInputActive()) command(cell)
    }
    fun pause() {
        endColor();colorPointers.clear();poke.reset()
        brushBook.hide()
        resetPalmMenu()
        resetWorkspaceMove()
        captureBridge.pause()
        renderer.pauseAnimations()
        enabled=false;session.finish();gate.reset();pinchGate.reset();released=false;lastInput=null;demoErase=null;selectionLatched=false
        hands.stop();controllers.stop();head.stop();handData.set(null);poseData.set(null);actionData.set(null)
        if(!saveAndExitPending)onSave(session.document.strokes,false)
    }
    fun resume() {
        if(closed || enabled)return
        captureBridge.resume()
        renderer.resumeAnimations(session.document.strokes,session.selectedStrokeId)
        enabled=true;gate.reset();released=false;previousActions=null
        head.start()
        if(controllerMode) controllers.start() else hands.start()
    }
    fun diagnose(scene: Scene) {
        if(!diagnosticLogged) {
            diagnosticLogged=true
            Log.i("OpenBrushDrawing","Studio scope enabled=$enabled valid=${root.valid} owner=${root.scene} tick=$scene head=${head.latestData.hmdPose} paletteEnabled=${palette.entity.enabled}")
        }
    }
    fun frame() {
        if(closed || !enabled || saveAndExitPending)return
        val now=SystemClock.elapsedRealtime()
        if(lastFrame>0 && now-lastFrame>250L) { endColor();session.finish();gate.reset();pinchGate.reset();released=false;lastInput=null }
        val frameDelta=if(lastFrame==0L).016f else (now-lastFrame)/1000f
        lastFrame=now
        try {
            while(!saveAndExitPending) handleCommand(commands.poll() ?: break)
            if(closed || !enabled || saveAndExitPending)return
            placeWorkspace(now)
            if(moving && workspacePlaced && !configurationVisible)moveWorkspace(now)
            placePalette(now,frameDelta)
            processColorPointers()
            palette.select(session.style)
            if(workspacePlaced && headData.get()?.let { now-it.at<200L }==true) {
                brushBook.place(head.latestData.hmdPose,palette,menuFace==PalmMenuFace.BACK)
            }
            brushBook.select(session.style.brush)
            val demo=demoErase
            if(configurationVisible) { session.finish();gate.reset();pinchGate.reset();released=false;cursor.enabled=false;lastInput=null }
            else if(demo!=null) {
                if(now>=demo.first) {
                    session.update(PaintTool.ERASE,demo.second,now);session.finish()
                    showCursor(demo.second,PaintTool.ERASE);demoErase=null;gate.reset();released=false
                    Log.i("OpenBrushDrawing","Demo local erase revision=${session.document.revision} visible=${session.document.strokes.last().visibleSpans()}")
                }
            } else if(!workspacePlaced) { session.finish();cursor.enabled=false }
            else if(controllerMode) controllerFrame(now) else handFrame(now)
            val selected=session.selectedStrokeId
            if((session.generation!=lastGeneration || selected!=lastSelected) && now-lastRender>=33L) {
                renderer.offer(session.displayStrokes,selected)
                lastGeneration=session.generation;lastSelected=selected;lastRender=now
            }
            val inputUi=StudioInputUi(selectionMode,selected!=null,pinchMode,moving)
            if(inputUi!=lastInputUi) {
                lastInputUi=inputUi
                onInputUi(inputUi)
            }
            if(session.document.revision!=lastRevision) {
                lastRevision=session.document.revision;onSave(session.document.strokes,false)
            }
            // Capture errors must never interrupt drawing. Only value snapshots leave this thread.
            runCatching {
                val trackedHead=headData.get()?.takeIf { now-it.at<200L }
                val trackedControllers=poseData.get()?.takeIf { now-it.at<200L }
                captureBridge.offer(now,trackedHead?.data?.hmdPose,trackedControllers?.data?.left,trackedControllers?.data?.right,session,
                    trackedHead?.at?.times(1_000_000L),trackedControllers?.at?.times(1_000_000L))
            }.onFailure { Log.w("OpenBrushCapture","Capture frame skipped",it) }
            if(now-lastStatus>=500L) {
                val support=if(controllerMode) controllers.supportState else hands.supportState
                val description=when(support) {
                    DataProvider.SupportState.SUPPORTED -> if(lastInput==null) context.getString(R.string.tracking_wait) else context.getString(R.string.tracking_ready)
                    DataProvider.SupportState.NOT_IN_FULL_SPACE -> context.getString(R.string.tracking_full_space)
                    DataProvider.SupportState.WITHOUT_PERMISSION -> context.getString(R.string.tracking_permission)
                    else -> context.getString(R.string.tracking_unavailable, support)
                }
                val modeStatus=if(moving) {
                    if(controllerMode) context.getString(R.string.move_grip_hint) else context.getString(R.string.move_pinch_hint)
                } else if(selectionMode) {
                    if(selected!=null) context.getString(R.string.stroke_selected) else if(controllerMode) context.getString(R.string.select_trigger_hint) else context.getString(R.string.select_finger_hint)
                } else if(workspacePlaced && pinchMode && !controllerMode && lastInput!=null) context.getString(R.string.pinch_hint)
                else if(workspacePlaced) description else context.getString(R.string.head_wait)
                val inputLabel=context.getString(if(controllerMode) R.string.controller else R.string.hands)
                val summary=context.getString(R.string.studio_status,inputLabel,modeStatus,brushLabel(),
                    (session.style.width*1000).roundToInt(),session.document.strokes.size)
                val sampleLimit=if(session.sampleLimitReached) " · "+context.getString(R.string.stroke_limit) else ""
                onStatus(summary+sampleLimit)
                lastStatus=now
            }
        } catch(e:Exception) {
            session.finish();gate.reset();released=false;cursor.enabled=false
            onStatus(context.getString(R.string.drawing_paused, e.message.orEmpty()));Log.e("OpenBrushDrawing","Frame failed",e)
        }
    }
    private fun placeWorkspace(now: Long) {
        if(workspacePlaced)return
        val pose=headData.get()?.takeIf { now-it.at<500L }?.data?.hmdPose ?: return
        // Reopen the editable document in front of the user. Never treat the provider's
        // constructor's zero pose as a real tracking sample or move the workspace mid-stroke.
        var low: V3?=null;var high: V3?=null
        session.document.strokes.forEach { stroke -> stroke.points.forEach { point ->
            val p=point.position
            low=low?.let { V3(min(it.x,p.x),min(it.y,p.y),min(it.z,p.z)) } ?: p
            high=high?.let { V3(max(it.x,p.x),max(it.y,p.y),max(it.z,p.z)) } ?: p
        } }
        val center=if(low!=null && high!=null) (requireNotNull(low)+requireNotNull(high))*.5f else V3.ZERO
        val parent=root.getParent()
        val rotation=parent?.convertRotationFrom(pose.rotation,null) ?: pose.rotation
        val target=pose.position+pose.rotation.rotateVector(Vector3(0f,-.06f,-.65f))
        val origin=(parent?.convertPositionFrom(target,null) ?: target)-rotation.rotateVector(Vector3(center.x,center.y,center.z))
        root.transform().setPosition(origin).setQuaternion(rotation)
        val referenceParent=referenceLayer.getParent()
        val referencePosition=pose.position+pose.rotation.rotateVector(Vector3(.39f,-.10f,-.83f))
        referenceLayer.transform()
            .setPosition(referenceParent?.convertPositionFrom(referencePosition,null) ?: referencePosition)
            .setQuaternion(referenceParent?.convertRotationFrom(pose.rotation,null) ?: pose.rotation)
        referenceLayer.enabled=true
        workspacePlaced=true
        Log.i("OpenBrushDrawing","Workspace placed from fresh head pose; restored=${session.document.strokes.size}")
    }

    private fun moveWorkspace(now: Long) {
        val anchors=if(controllerMode) {
            val action=actionData.get()?.takeIf { now-it.at<200L }?.data
            val pose=poseData.get()?.takeIf { now-it.at<200L }?.data
            val left=pose?.left?.position
            val right=pose?.right?.position
            if(action?.left?.gripPressed==true && action.right.gripPressed && left!=null && right!=null &&
                !hudHit(left).guard && !hudHit(right).guard) left.v3() to right.v3() else null
        } else {
            val hands=handData.get()?.takeIf { now-it.at<200L }?.data
            val left=decode(hands?.left)
            val right=decode(hands?.right)
            val overHud=listOfNotNull(left?.pinchTip,right?.pinchTip).any { hudHit(it.native()).guard }
            val leftHeld=leftMovePinch.update(left,now,overHud)
            val rightHeld=rightMovePinch.update(right,now,overHud)
            if(leftHeld && rightHeld && left!=null && right!=null)left.pinchTip to right.pinchTip else null
        }
        val move=workspaceMove.update(anchors?.first,anchors?.second,now) ?: return
        val parent=root.getParent()
        val origin=move.apply(root.convertPositionTo(Vector3.ZERO,null).v3()).native()
        val worldRotation=root.convertRotationTo(Quat(0f,0f,0f,1f),null)
        val rotation=if(move.radians==0f)worldRotation else
            Quat(rotAngle=move.radians,rotAxis=move.axis.native())*worldRotation
        val q=parent?.convertRotationFrom(rotation,null) ?: rotation
        val length=sqrt(q.x*q.x+q.y*q.y+q.z*q.z+q.w*q.w)
        root.transform().setPosition(parent?.convertPositionFrom(origin,null) ?: origin)
            .setQuaternion(Quat(q.x/length,q.y/length,q.z/length,q.w/length))
    }

    private fun resetWorkspaceMove() {
        workspaceMove.reset()
        leftMovePinch.reset()
        rightMovePinch.reset()
    }
    private fun placePalette(now: Long,delta:Float) {
        if(!hudSized) {
            val bounds=hud.getVisualBounds(relativeTo=hud,recursive=true)
            if(bounds.size.x>1e-6f) { hud.transform().setScaleVector(Vector3(.50f/bounds.size.x));hudSized=true }
        }
        val view=head.latestData.hmdPose
        val tracking=if(controllerMode)null else handData.get()?.takeIf { now-it.at<200L }?.data
        val leftHand=tracking?.left?.takeIf { decode(it)!=null }
        val observed=leftHand?.let { hand ->
            fun point(joint: com.pico.spatial.tracking.hand.HandJoint.Index)=hand.joint(joint).position.v3()
            PalmMenuFrame.left(point(WRIST),point(MIDDLE_PROXIMAL),point(INDEX_PROXIMAL),point(LITTLE_PROXIMAL))
        }
        val nearby=tracking?.right?.takeIf { it.handJoints.size==26 }?.let { right ->
            val palm=(leftHand ?: lastMenuHand?.first)?.joint(PALM)?.position
            MENU_FINGERS.any { joint ->
                val tip=right.joint(joint).position
                palette.guards(tip) || brushBook.guards(tip) || (palm!=null && (tip-palm).v3().length()<.12f)
            }
        }==true
        val interacting=nearby || colorCapture!=null
        val face=palmMenu.update(observed?.y?.y,now,interacting)
        if(face!=menuFace) {
            menuFace=face
            manualBookOpen=null
            endColor();colorPointers.clear();poke.reset()
            Log.i("OpenBrushDrawing","Left menu face=$face")
        }
        if(leftHand!=null && observed!=null) {
            val faceTurningAway=when(face) {
                PalmMenuFace.PALM -> observed.y.y<.30f
                PalmMenuFace.BACK -> observed.y.y>-.30f
                else -> false
            }
            if(!interacting || !faceTurningAway || lastMenuHand==null)lastMenuHand=leftHand to observed
        }
        if(face==PalmMenuFace.FALLBACK && observed==null)lastMenuHand=null
        val menuHand=lastMenuHand?.takeUnless { face==PalmMenuFace.FALLBACK }
        val leftController=if(!controllerMode)null else poseData.get()?.takeIf { now-it.at<250L }?.data?.left
            ?.takeIf { (it.position-view.position).v3().length() in .1f..1.5f }
        palette.place(view,menuHand?.first,menuHand?.second,leftController,delta)
        palette.entity.enabled=!configurationVisible && !moving && (face==PalmMenuFace.PALM || face==PalmMenuFace.FALLBACK)
        brushBook.setOpen(!configurationVisible && !moving && (manualBookOpen ?: (face==PalmMenuFace.BACK)))
        hud.transform().setPosition(root.convertPositionFrom(view.position+view.rotation.rotateVector(Vector3(0f,-.32f,-.85f)),null))
        hud.transform().setQuaternion(root.convertRotationFrom(view.rotation,null))
        if(configurationVisible) {
            if(!capturePanelSized) {
                val bounds=capturePanel.getVisualBounds(relativeTo=capturePanel,recursive=true)
                if(bounds.size.x>1e-6f) { capturePanel.transform().setScaleVector(Vector3(.68f/bounds.size.x));capturePanelSized=true }
            }
            capturePanel.transform().setPosition(root.convertPositionFrom(view.position+view.rotation.rotateVector(Vector3(0f,-.03f,-.9f)),null))
            capturePanel.transform().setQuaternion(root.convertRotationFrom(view.rotation,null))
        }
        val anchor=menuHand?.first?.joint(PALM)?.position ?: leftController?.position ?: view.position
        reference.transform().setPosition(root.convertPositionFrom(anchor,null))
        reference.transform().setQuaternion(root.convertRotationFrom(menuHand?.first?.joint(PALM)?.rotation ?: leftController?.rotation ?: view.rotation,null))
    }
    private fun resetPalmMenu() {
        palmMenu.reset()
        menuFace=PalmMenuFace.FALLBACK
        lastMenuHand=null
        manualBookOpen=null
    }
    private data class PanelHit(val guard: Boolean,val cell: Int?)
    private fun hudHit(world: Vector3): PanelHit {
        if(!hudSized)return PanelHit(false,null)
        val bounds=hud.getVisualBounds(relativeTo=hud,recursive=true)
        if(bounds.size.x<=0f || bounds.size.y<=0f)return PanelHit(false,null)
        val p=hud.convertPositionFrom(world,null)
        val u=(p.x-bounds.center.x)/bounds.size.x+.5f
        val v=.5f-(p.y-bounds.center.y)/bounds.size.y
        val center=hud.convertPositionTo(bounds.center,null)
        val normal=hud.convertRotationTo(Quat(0f,0f,0f,1f),null).rotateVector(Vector3(0f,0f,1f))
        val depth=abs((world-center).dot(normal))
        val guard=u in -.06f..1.06f && v in -.06f..1.06f && depth<.07f
        return PanelHit(guard,if(u in 0f..1f && v in 0f..1f && depth<.014f) hudCell(u,v) else null)
    }
    private fun beginColor(id:Int,point:V3,source:Int) {
        endColor();session.finish();gate.reset();released=false;demoErase=null
        val color=palette.sampleColor(id,point,false) ?: return
        colorCapture=id;colorSource=source;session.style=session.style.copy(color=color)
    }
    private fun moveColor(point:V3,source:Int) {
        val id=colorCapture ?: return
        if(colorSource!=source)return
        val color=palette.sampleColor(id,point,true)
        if(color==null)endColor() else session.style=session.style.copy(color=color)
    }
    private fun endColor() {
        colorCapture=null;colorSource=0;palette.commit()
    }
    private fun processColorPointers() {
        while(true) {
            val event=colorPointers.poll() ?: break
            if(configurationVisible || hardwareInputActive()) { if(colorSource==3)endColor();continue }
            val point=palette.surfacePoint(event.u,event.v)
            when(event.phase) {
                0-> {
                    endColor()
                    val id=point?.let(palette::surfaceTarget) ?: continue
                    if(PaletteColorCommands.continuous(id))beginColor(id,point,3) else handleCommand(id)
                }
                1->if(colorSource==3) { if(point!=null)moveColor(point,3) else endColor() }
                2->if(colorSource==3) {
                    // Android may deliver the final coordinate only with UP, after the last MOVE.
                    if(point!=null)moveColor(point,3)
                    endColor()
                }
            }
        }
    }
    private fun handFrame(now: Long) {
        val pose=handData.get()?.takeIf { now-it.at<200L }?.data?.right
        val decoded=decode(pose)
        if(decoded==null) { if(colorSource==1)endColor();gate.update(null,now);pinchGate.reset();session.finish();selectionLatched=false;cursor.enabled=false;lastInput=null;return }
        val shape=decoded.shape
        val index=Vector3(decoded.index.x,decoded.index.y,decoded.index.z)
        val thumb=Vector3(decoded.thumb.x,decoded.thumb.y,decoded.thumb.z)
        val fingerIds=listOf(INDEX_TIP,THUMB_TIP,MIDDLE_TIP,RING_TIP,LITTLE_TIP)
        val tipPoints=fingerIds.associateWith { requireNotNull(pose).joint(it).position }
        val bookTarget=if(shape.index==FingerShape.STRAIGHT)brushBook.touch(index) else null
        brushBook.setHover(if(shape.index==FingerShape.STRAIGHT)brushBook.hover(index) else null)
        val indexTarget=if(bookTarget==null && shape.index==FingerShape.STRAIGHT)palette.touch(index) else null
        val brushContact=if(bookTarget!=null || indexTarget!=null)null else tipPoints.entries.firstNotNullOfOrNull { (finger,point) ->
            palette.touch(point,brushesOnly=true)?.let { finger to it.id }
        }
        val hudContact=hudHit(index)
        val command=bookTarget ?: indexTarget?.id ?: brushContact?.second ?: if(shape.index==FingerShape.STRAIGHT)hudContact.cell else null
        val contactFinger=if(bookTarget!=null || indexTarget!=null || hudContact.cell!=null)INDEX_TIP else brushContact?.first ?: lastTouchFinger
        val probe=tipPoints[if(command!=null)contactFinger else lastTouchFinger] ?: index
        poke.update(command,reference.convertPositionFrom(probe,null).v3(),now)?.let {
            lastTouchFinger=contactFinger
            if(PaletteColorCommands.continuous(it))beginColor(it,palette.local(index),1) else handleCommand(it)
        }
        if(colorSource==1) {
            if(shape.index==FingerShape.STRAIGHT && bookTarget==null)moveColor(palette.local(index),1) else endColor()
        }
        val overUi=tipPoints.values.any { palette.guards(it) || brushBook.guards(it) } || hudContact.guard || hudHit(thumb).guard
        val gesture=gate.update(shape,now,overUi,decoded.heldTools)
        val usePinch=pinchMode && !selectionMode
        val tool=when {
            !usePinch -> gesture
            pinchGate.update(decoded,now,overUi) -> PaintTool.DRAW
            gesture==PaintTool.DRAW -> PaintTool.NONE
            else -> gesture
        }
        val cursorTool=if(selectionMode)PaintTool.DRAW else if(tool!=PaintTool.NONE)tool else shape.requested
        val tip=if(usePinch && cursorTool!=PaintTool.ERASE && cursorTool!=PaintTool.RECOLOR)decoded.pinchTip
            else decoded.tipFor(cursorTool)
        val world=Vector3(tip.x,tip.y,tip.z)
        val point=root.convertPositionFrom(world,null).v3()
        if(lastInput!=null && (point-requireNotNull(lastInput)).length()>.2f) { gate.reset();pinchGate.reset();session.finish();lastInput=point;return }
        lastInput=point
        val normalWorld=decoded.normal
        val normal=(root.convertPositionFrom(world+Vector3(normalWorld.x,normalWorld.y,normalWorld.z),null).v3()-point).unit()
        applyInput(tool,point,now,1f,normal)
        showCursor(point,cursorTool)
    }
    private fun decode(pose: HandPose?):DecodedHand? {
        if(pose==null || pose.handJoints.size!=26)return null
        val points=Array<V3?>(26) { null }
        for(joint in pose.handJoints) {
            if(points[joint.index.ordinal]!=null)return null
            points[joint.index.ordinal]=joint.position.v3()
        }
        return HandPoseDecoder.decode(points.map { it ?: return null })
    }
    private fun controllerFrame(now: Long) {
        val pose=poseData.get()?.takeIf { now-it.at<200L }?.data?.right
        val action=actionData.get()?.takeIf { now-it.at<200L }?.data
        if(pose==null || action==null) { if(colorSource==2)endColor();session.finish();selectionLatched=false;released=false;cursor.enabled=false;lastInput=null;return }
        val right=action.right;val old=previousActions
        val triggered=right.triggerPressed || right.triggerValue>.15f
        val down=triggered && old?.right?.let { it.triggerPressed || it.triggerValue>.15f }!=true
        if(action.left.xButtonPressed && old?.left?.xButtonPressed!=true) { session.undo();released=false }
        if(action.left.yButtonPressed && old?.left?.yButtonPressed!=true) { session.redo();released=false }
        if(right.bButtonPressed && old?.right?.bButtonPressed!=true) { session.finish();released=false }
        val ray=pose.rotation.rotateVector(Vector3(0f,0f,-1f))
        val (paletteHit,paletteGuard)=palette.ray(pose.position,ray)
        val (bookHit,bookGuard)=brushBook.ray(pose.position,ray)
        val bookBlock=brushBook.blockingDistance(pose.position,ray)
        brushBook.setHover(bookHit?.command)
        val center=hud.convertPositionTo(Vector3.ZERO,null)
        val normal=hud.convertRotationTo(Quat(0f,0f,0f,1f),null).rotateVector(Vector3(0f,0f,1f))
        val divisor=ray.dot(normal)
        val distance=if(abs(divisor)>1e-5f) (center-pose.position).dot(normal)/divisor else -1f
        val hit=if(distance in 0f..2f) hudHit(pose.position+ray*distance) else PanelHit(false,null)
        val uiCommands=mutableListOf<Pair<Float,Int>>()
        paletteHit?.let { uiCommands.add(it.distance to it.target.id) }
        bookHit?.let { uiCommands.add(it.distance to it.command) }
        if(hit.cell!=null)uiCommands.add(distance to hit.cell)
        if(down)uiCommands.filter { bookBlock==null || it.first<=bookBlock+.015f }.minByOrNull { it.first }?.second?.let { id ->
            if(PaletteColorCommands.continuous(id))palette.rayPoint(pose.position,ray)?.first?.let { beginColor(id,it,2) }
            else handleCommand(id)
        }
        if(!triggered && bookGuard && kotlin.math.abs(right.thumbstickValue.x)>.65f && now-lastBookScroll>=350L) {
            handleCommand(if(right.thumbstickValue.x>0)BrushBookCommands.NEXT else BrushBookCommands.PREVIOUS)
            lastBookScroll=now
        }
        val grip=right.gripPressed || action.left.gripPressed
        if(colorSource==2) {
            val sample=palette.rayPoint(pose.position,ray)
            if(triggered && !grip && !right.bButtonPressed && sample!=null && (bookBlock==null || sample.second<=bookBlock+.015f))moveColor(sample.first,2)
            else endColor()
        }
        if(!triggered) { released=true;lastUi=false }
        if(hit.guard || paletteGuard || bookGuard || right.bButtonPressed || grip) { released=false;lastUi=true }
        val point=root.convertPositionFrom(pose.position+ray*.04f,null).v3();lastInput=point
        if(!triggered && !grip && abs(right.thumbstickValue.x)>.2f && !hit.guard && !paletteGuard && !bookGuard) {
            session.style=session.style.copy(width=(session.style.width+right.thumbstickValue.x*.0002f).coerceIn(.001f,.15f))
        }
        val availableTool=if(selectionMode) PaintTool.DRAW else selectedTool
        val tool=if(triggered && released && !lastUi && !grip) availableTool else PaintTool.NONE
        val n=pose.rotation.rotateVector(Vector3(0f,1f,0f))
        val localNormal=(root.convertPositionFrom(pose.position+n,null).v3()-root.convertPositionFrom(pose.position,null).v3()).unit()
        applyInput(tool,point,now,right.triggerValue.coerceIn(0f,1f),localNormal)
        showCursor(point,availableTool);previousActions=action
    }

    private fun applyInput(tool: PaintTool,point: V3,now: Long,pressure: Float,normal: V3) {
        if(moving)return
        if(!selectionMode) {
            session.update(tool,point,now,pressure,normal)
        } else if(tool!=PaintTool.DRAW) {
            selectionLatched=false
        } else if(!selectionLatched && now-lastSelectionProbe>=60L) {
            lastSelectionProbe=now
            selectionLatched=session.selectAt(point)
        }
    }

    private fun setSelectionMode(active: Boolean) {
        session.finish()
        if(active && moving)setMoving(false)
        selectionMode=active
        selectionLatched=false
        demoErase=null
        gate.reset()
        pinchGate.reset()
        released=false
        if(!active) session.clearSelection()
    }
    private fun setMoving(active:Boolean) {
        session.finish()
        if(active)setSelectionMode(false)
        moving=active
        resetWorkspaceMove()
        gate.reset();pinchGate.reset();released=false;lastInput=null
        if(active) { cursor.enabled=false;brushBook.hide() }
    }
    private fun showCursor(point: V3,tool: PaintTool) {
        if(moving) { cursor.enabled=false;return }
        cursor.enabled=true
        val radius=when {
            selectionMode -> StrokeSelection.REACH_METERS
            tool==PaintTool.ERASE -> session.eraserRadius
            tool==PaintTool.RECOLOR -> session.recolorRadius
            else -> session.style.width*.5f
        }
        cursor.transform().setPosition(Vector3(point.x,point.y,point.z)).setScaleVector(Vector3(radius/.01f))
        val c=session.style.color
        cursorMaterial.setBaseColor(when {
            selectionMode -> Color4(1f,.55f,.04f,.42f)
            tool==PaintTool.ERASE -> Color4(1f,.1f,.05f,.35f)
            else -> Color4(c.r,c.g,c.b,.65f)
        })
    }
    private fun handleCommand(cell: Int) {
        endColor()
        session.finish();gate.reset();released=false;demoErase=null
        if(PaletteColorCommands.owns(cell)) {
            palette.colorCommand(cell)?.let { session.style=session.style.copy(color=it) }
            return
        }
        if(BrushBookCommands.owns(cell)) {
            when(cell) {
                BrushBookCommands.TOGGLE,BrushBookCommands.CLOSE -> {
                    if(moving)return
                    val open=cell==BrushBookCommands.TOGGLE && !brushBook.visible && !configurationVisible
                    manualBookOpen=open
                    brushBook.setOpen(open)
                }
                else -> brushBook.activate(cell)?.let { session.style=session.style.copy(brush=it) }
            }
            return
        }
        when(cell) {
            in 0..7 -> {
                val color=requireNotNull(palette.layout.targets.first { it.id==cell }.color)
                session.style=session.style.copy(color=color);palette.syncColor(color)
            }
            in 20..22 -> session.style=session.style.copy(brush=requireNotNull(palette.layout.targets.first { it.id==cell }.brush))
            30 -> session.style=session.style.copy(width=(session.style.width/1.3f).coerceAtLeast(.001f))
            32 -> session.style=session.style.copy(width=(session.style.width*1.3f).coerceAtMost(.15f))
            33 -> {
                if(moving) setMoving(false)
                else if(selectionMode) setSelectionMode(false)
                else selectedTool=if(selectedTool==PaintTool.DRAW) PaintTool.ERASE else PaintTool.DRAW
            }
            SELECT -> setSelectionMode(!selectionMode)
            PREVIOUS_STROKE, NEXT_STROKE -> {
                if(!selectionMode) setSelectionMode(true)
                session.stepSelection(if(cell==PREVIOUS_STROKE) -1 else 1)
            }
            DELETE_STROKE -> session.deleteSelected()
            PINCH_DRAWING -> {
                if(moving)setMoving(false)
                setSelectionMode(false)
                pinchMode=!pinchMode
            }
            MOVE_WORKSPACE -> setMoving(!moving)
            34, REFERENCE_MENU -> {
                if(moving)setMoving(false)
                brushBook.hide()
                manualBookOpen=null
                configurationVisible=true;capturePanelSized=false;capturePanel.enabled=true
                hud.enabled=false;palette.entity.enabled=false
                onPanel(if(cell==REFERENCE_MENU) StudioPanel.REFERENCE else StudioPanel.MIXED_REALITY)
            }
            35 -> if(!controllerMode) {
                hands.stop();handData.set(null);controllers.start();controllerMode=true;previousActions=null;lastInput=null
                resetPalmMenu()
                resetWorkspaceMove()
            }
            37 -> {
                configurationVisible=false;capturePanel.enabled=false;hud.enabled=true;onPanel(null)
            }
            8 -> session.undo()
            9 -> session.redo()
            10 -> { session.finish();onSave(session.document.strokes,false) }
            11 -> {
                hands.stop();controllers.stop();handData.set(null);poseData.set(null);actionData.set(null)
                controllerMode=!controllerMode;previousActions=null;lastInput=null;session.finish();gate.reset();pinchGate.reset()
                resetPalmMenu()
                resetWorkspaceMove()
                if(controllerMode) controllers.start() else hands.start()
            }
            12 -> {
                endColor();colorPointers.clear();commands.clear();session.finish()
                saveAndExitPending=true
                gate.reset();pinchGate.reset();released=false;lastInput=null;cursor.enabled=false
                onSave(session.document.strokes,true)
            }
            13 -> {
                if(!workspacePlaced) { onStatus(context.getString(R.string.example_wait));return }
                if(moving)setMoving(false)
                if(selectionMode) setSelectionMode(false)
                // A real editable sample, owned by the same document and whole-stroke history.
                val headPose=head.latestData.hmdPose
                val points=(0..80).map { i ->
                    val x=(i/80f-.5f)*.48f
                    val y=sin(i/80f*PI.toFloat()*2f)*.1f
                    val world=headPose.position+headPose.rotation.rotateVector(Vector3(x,y-.06f,-.65f))
                    PaintPoint(root.convertPositionFrom(world,null).v3(),timeMs=i*10L)
                }
                val sample=PaintStroke(UUID.randomUUID().toString(),session.style,points)
                session.document.commit(session.document.strokes+sample)
                renderer.offer(session.document.strokes,session.selectedStrokeId)
                demoErase=(SystemClock.elapsedRealtime()+1000L) to sample.points[40].position
            }
        }
        Log.i("OpenBrushDrawing","command=$cell brush=${session.style.brush} color=${session.style.color} strokes=${session.document.strokes.size} revision=${session.document.revision}")
    }
    fun saveAndExitFailed() {
        saveAndExitPending=false
        lastFrame=0L
    }

    suspend fun loadReference(file: File) {
        val model=Entity.loadFrom(Source.file(file))
        val holder=Entity()
        try {
            holder.addChild(model)
            val bounds=model.getVisualBounds(relativeTo=holder,recursive=true)
            val extent=maxOf(bounds.size.x,bounds.size.y,bounds.size.z)
            if(!extent.isFinite() || extent<=1e-6f || !bounds.center.v3().finite()) {
                error(context.getString(R.string.reference_empty))
            }
            if(closed || !referenceLayer.valid)throw CancellationException("Studio closed")
            val scale=minOf(1f,.26f/extent)
            holder.transform().setScaleVector(Vector3(scale)).setPosition(bounds.center*(-scale))
            referenceLayer.addChild(holder)
            clearReference()
            referenceModel=holder
            referenceFile=file
            Log.i("OpenBrushDrawing","Reference loaded extent=$extent scale=$scale")
        } catch(e:Exception) {
            if(holder.valid)holder.destroy()
            if(model.valid)model.destroy()
            throw e
        }
    }

    fun showReference(shown: Boolean) {
        referenceModel?.let { if(it.valid)it.enabled=shown }
        Log.i("OpenBrushDrawing","Reference visible=$shown")
    }

    fun clearReference() {
        referenceModel?.let { if(it.valid)it.destroy() }
        referenceFile?.delete()
        referenceModel=null
        referenceFile=null
    }

    fun close() {
        if(closed)return
        pause();closed=true;StudioSystem.runtimes.remove(this)
        hands.removeListener(handListener);controllers.removeListener(poseListener);head.removeListener(headListener)
        controllers.removeControllerActionListener(actionListener)
        brushBook.close();palette.close();renderer.close();if(cursor.valid)cursor.destroy();cursorMesh.close();cursorMaterial.close()
        clearReference()
        if(referenceLayer.valid)referenceLayer.destroy()
        if(root.valid)root.destroy()
    }
    private fun Entity.transform()=requireNotNull(components[TransformComponent::class.java])
    private fun Vector3.v3()=V3(x,y,z)
    private fun V3.native()=Vector3(x,y,z)
    private fun Vector3.dot(other: Vector3)=x*other.x+y*other.y+z*other.z
    fun modelTap(entity:Entity?) {
        val viewer=head.latestData.hmdPose.position
        (brushBook.commandFor(entity,viewer) ?: palette.commandFor(entity,viewer))?.let(::mouseCommand)
    }
    private fun brushLabel()=brushes[session.style.brush]?.name
        ?: when(session.style.brush) { "spatial_flat"->context.getString(R.string.brush_flat);"spatial_fan"->context.getString(R.string.brush_fan);else->context.getString(R.string.brush_round) }
    companion object {
        private val MENU_FINGERS=listOf(INDEX_TIP,THUMB_TIP,MIDDLE_TIP,RING_TIP,LITTLE_TIP)
        const val SELECT=38
        const val PREVIOUS_STROKE=39
        const val NEXT_STROKE=40
        const val DELETE_STROKE=41
        const val PINCH_DRAWING=42
        const val MOVE_WORKSPACE=43
        const val REFERENCE_MENU=44
        private val HUD_COMMANDS=listOf(11,30,32,33,BrushBookCommands.TOGGLE,13,34,12)
        private val SELECTION_COMMANDS=listOf(SELECT,PREVIOUS_STROKE,NEXT_STROKE,DELETE_STROKE,PINCH_DRAWING,MOVE_WORKSPACE,REFERENCE_MENU)
        fun hudCell(u:Float,v:Float):Int? {
            if(u !in 0f..1f)return null
            val row=when {
                v>=.38f && v<.68f -> HUD_COMMANDS
                v in .70f..1f -> SELECTION_COMMANDS
                else -> return null
            }
            return row[(u*row.size).toInt().coerceIn(0,row.lastIndex)]
        }
    }
}
