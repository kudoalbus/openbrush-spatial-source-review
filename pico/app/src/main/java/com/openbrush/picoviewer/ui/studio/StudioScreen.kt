package com.openbrush.picoviewer.ui.studio

import androidx.compose.ui.res.stringResource
import com.openbrush.picoviewer.R
import android.content.Context

import android.graphics.Paint
import android.net.Uri
import android.util.Log
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openbrush.picoviewer.data.DrawingWorkspaceFiles
import com.openbrush.picoviewer.data.PaletteCatalog
import com.openbrush.picoviewer.data.BrushBookCatalogCodec
import com.openbrush.picoviewer.data.StudioBrushCatalog
import com.openbrush.picoviewer.data.repository.ArtworkRepository
import com.openbrush.picoviewer.capture.MixedRealityCapture
import com.openbrush.picoviewer.ui.capture.MixedRealitySettings
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import com.openbrush.picoviewer.domain.drawing.*
import com.openbrush.picoviewer.LIBRARY_WINDOW
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.ui.design.*
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.content.panelSize
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

private class WorkspaceSaver(files: DrawingWorkspaceFiles,id: String,report: (Int,Boolean,Result<Unit>)->Unit) {
    private data class Request(val strokes: List<PaintStroke>,val exit: Boolean)
    // Exit pauses document edits and autosaves until this request completes.
    private val queue=Channel<Request>(Channel.CONFLATED)
    private val worker=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    init { worker.launch {
        for(request in queue) {
            val result=runCatching { files.write(id,request.strokes) }
            withContext(Dispatchers.Main) {
                report(request.strokes.size,request.exit,result)
            }
        }
        worker.cancel()
    } }
    fun save(strokes: List<PaintStroke>,exit: Boolean) { queue.trySend(Request(strokes,exit)) }
    fun close() { queue.close() } // drain queued snapshots even after Stage disposal
}

@Composable
fun StudioScreen(workspaceId: String) {
    val context=LocalContext.current
    val navigator=LocalSpatialNavigator.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val uiScope=rememberCoroutineScope()
    val files=remember(context) { DrawingWorkspaceFiles(context.applicationContext) }
    var initial by remember { mutableStateOf<List<PaintStroke>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var recoveryAvailable by remember { mutableStateOf(false) }
    var recovering by remember { mutableStateOf(false) }
    var loadRevision by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf(context.getString(R.string.restore_drawing)) }
    var savingAndExiting by remember { mutableStateOf(false) }
    var saveNotice by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val inputPreferences=remember(context) { context.getSharedPreferences("studio-input",android.content.Context.MODE_PRIVATE) }
    var inputUi by remember(inputPreferences) { mutableStateOf(StudioInputUi(pinch=inputPreferences.getBoolean("pinch",false))) }
    val microphone = remember { StudioMicrophone() }
    var micEnabled by remember { mutableStateOf(false) }
    var micError by remember { mutableStateOf<String?>(null) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Log.i("OpenBrushAudio","Microphone permission granted=$granted")
        micEnabled = granted
        micError=if(granted)null else context.getString(R.string.mic_permission_denied)
    }
    LaunchedEffect(lifecycle,micEnabled) {
        if (micEnabled) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                microphone.capture()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e("OpenBrushAudio","Microphone capture failed",failure)
                micEnabled = false
                micError = failure.message ?: context.getString(R.string.mic_unavailable)
            }
        }
    }
    var runtime by remember { mutableStateOf<StudioRuntime?>(null) }
    var studioPanel by remember { mutableStateOf<StudioPanel?>(null) }
    var referenceName by remember { mutableStateOf<String?>(null) }
    var referenceShown by remember { mutableStateOf(false) }
    var referenceError by remember { mutableStateOf<String?>(null) }
    var referenceUri by remember { mutableStateOf<Uri?>(null) }
    val repository=remember(context) { ArtworkRepository(context.applicationContext) }
    val referencePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null)referenceUri=uri
    }
    LaunchedEffect(referenceUri,runtime) {
        val chosen=referenceUri ?: return@LaunchedEffect
        val active=runtime ?: return@LaunchedEffect
        referenceError=null
        try {
            val (file,name)=withContext(NonCancellable) { repository.stageUsdReference(chosen) }
            try {
                ensureActive()
                active.loadReference(file)
            } catch(e:Exception) {
                file.delete()
                throw e
            }
            referenceName=name
            referenceShown=true
        } catch(e:CancellationException) {
            throw e
        } catch(e:Exception) {
            referenceError=context.getString(R.string.reference_load_failed,e.message.orEmpty())
        } finally {
            referenceUri=null
        }
    }
    var brushBookUi by remember { mutableStateOf(BrushBookUiState()) }
    var paletteColorUi by remember { mutableStateOf(PaletteColorUiState(true,HsvInk(.59f,.78f,.8f),List(5){null})) }
    val saver=remember(files,workspaceId) { WorkspaceSaver(files,workspaceId) { count,exit,result ->
        result.onSuccess {
            saveError=null
            saveNotice=context.getString(R.string.saved_strokes,count)
            if(exit) uiScope.launch { navigator.restoreWindowContainer(LIBRARY_WINDOW);navigator.closeStage() }
        }.onFailure {
            Log.e("OpenBrushDrawing","Workspace save failed (exit=$exit)",it)
            saveError=context.getString(R.string.save_failed,it.message.orEmpty())
            saveNotice=null
            if(exit) {
                savingAndExiting=false
                runtime?.saveAndExitFailed()
            }
        }
    } }
    LaunchedEffect(saveNotice) {
        if(saveNotice!=null) {
            delay(1500L)
            saveNotice=null
        }
    }
    LaunchedEffect(files,workspaceId,loadRevision) {
        val loaded=withContext(Dispatchers.IO) { runCatching { files.read(workspaceId) } }
        loaded.fold({ initial=it;loadError=null;status=context.getString(R.string.gesture_hint) }, {
            loadError=it.message ?: context.getString(R.string.file_load_failed)
            recoveryAvailable=withContext(Dispatchers.IO) { files.canRecover(workspaceId) }
        })
    }
    DisposableEffect(runtime,lifecycle) {
        val active=runtime
        val observer=LifecycleEventObserver { _,event ->
            if(event==Lifecycle.Event.ON_PAUSE) active?.pause()
            if(event==Lifecycle.Event.ON_RESUME) active?.resume()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer);active?.close() }
    }
    DisposableEffect(saver) { onDispose { runtime?.close();saver.close() } }
    if(loadError!=null) {
        Column {
            Text(stringResource(R.string.restore_failed, loadError.orEmpty()))
            if(recoveryAvailable) {
                Text(stringResource(R.string.recover_previous_help))
                Button(enabled=!recovering,onClick={
                    recovering=true
                    uiScope.launch {
                        try {
                            withContext(Dispatchers.IO) { files.recoverPrevious(workspaceId) }
                            loadRevision++
                        } catch(e:CancellationException) {
                            throw e
                        } catch(e:Exception) {
                            loadError=e.message ?: context.getString(R.string.file_load_failed)
                        } finally {
                            recovering=false
                        }
                    }
                }) { Text(stringResource(R.string.recover_previous)) }
            }
            Button(onClick={uiScope.launch { navigator.restoreWindowContainer(LIBRARY_WINDOW);navigator.closeStage() }}) {
                Text(stringResource(R.string.back_library))
            }
        }
        return
    }
    val loaded=initial ?: return
    val bookHeaderSize=panelSize(.224f,.020f)
    val bookCaptionSize=panelSize(.160f,.009f)
    val bookSurfaceSize=panelSize(BrushBookLayout.SURFACE_WIDTH,BrushBookLayout.SURFACE_HEIGHT)
    val paletteSurfaceSize=panelSize(PalettePickerLayout.PANEL_WIDTH,PalettePickerLayout.PANEL_HEIGHT)
    SpatialView(Modifier.fillMaxSize().pointerInput(runtime) {
        detectSpatialTapGesture(context) { tap -> runtime?.modelTap(tap.targetEntity) }
    },attachments={
        AttachmentPanel("StudioControls",size=IntSize(1080,200)) {
            val message=if(savingAndExiting) stringResource(R.string.saving_drawing) else saveError ?: micError ?: saveNotice ?: status
            StudioControls(message, micEnabled, inputUi, onMicrophone = {
                micError=null
                if (micEnabled) micEnabled = false
                else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    micEnabled = true
                } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }) { runtime?.mouseCommand(it) }
        }
        AttachmentPanel("MixedRealityConfiguration",size=IntSize(900,1000)) {
            when(studioPanel) {
                StudioPanel.MIXED_REALITY -> MixedRealitySettings(
                    onBack={runtime?.command(37)},onControllerMode={runtime?.command(35)})
                StudioPanel.REFERENCE -> StudioReferenceSettings(
                    referenceName,referenceShown,referenceUri!=null,referenceError,
                    onOpen={
                        try {
                            referencePicker.launch(arrayOf("*/*"))
                        } catch(e:Exception) {
                            referenceError=context.getString(R.string.picker_failed,e.message.orEmpty())
                        }
                    },
                    onToggle={referenceShown=!referenceShown;runtime?.showReference(referenceShown)},
                    onRemove={runtime?.clearReference();referenceName=null;referenceShown=false},
                    onBack={runtime?.command(37)})
                null -> Unit
            }
        }
        AttachmentPanel("BrushBookHeader",size=bookHeaderSize) {
            BrushBookHeader(brushBookUi) { runtime?.mouseCommand(it) }
        }
        AttachmentPanel("BrushBookCaption",size=bookCaptionSize) { BrushBookCaption(brushBookUi) }
        AttachmentPanel("BrushBookSurface",size=bookSurfaceSize) {
            BrushBookSurface { u,v -> runtime?.bookTap(u,v) }
        }
        AttachmentPanel("PaletteColors",size=paletteSurfaceSize) {
            PaletteColorSurface(paletteColorUi) { phase,u,v -> runtime?.paletteSurface(phase,u,v) }
        }
    },initial={content,attachments ->
        val root=Entity();content.addEntity(root)
        val referenceLayer=Entity().also { content.addEntity(it);it.enabled=false }
        var brushBook:BrushBook3D?=null
        var createdPalette:Palette3D?=null
        try {
            val panel=requireNotNull(attachments.entity("StudioControls"))
            root.addChild(panel)
            val capturePanel=requireNotNull(attachments.entity("MixedRealityConfiguration"))
            root.addChild(capturePanel);capturePanel.enabled=false
            val layout=withContext(Dispatchers.IO) { PaletteCatalog.decode(context.assets.open("palette-targets.json").bufferedReader().use { it.readText() }) }
            val model=Entity.loadSuspend("asset://left-hand-palette.glb")
            val palette=Palette3D(root,model,layout,context.cacheDir,requireNotNull(attachments.entity("PaletteColors")),
                context.getSharedPreferences("palette-colors",android.content.Context.MODE_PRIVATE)) { paletteColorUi=it }
            createdPalette=palette
            val (bookCatalog,studioBrushes)=withContext(Dispatchers.IO) {
                fun readAsset(path: String) = context.assets.open(path).bufferedReader().use { it.readText() }
                val catalog = BrushBookCatalogCodec.decode(readAsset("brush-library/catalog.json"))
                val brushes = StudioBrushCatalog.load(catalog, ::readAsset)
                catalog.withStudioBrushes(brushes.enabledIds) to brushes
            }
            val bookFrame=Entity.loadSuspend("asset://brush-library/brush-book-frame.glb").also { root.addChild(it) }
            val basics=bookCatalog.basic.map { id -> Entity.loadSuspend("asset://"+requireNotNull(bookCatalog.entries.getValue(id).model)).also { root.addChild(it) } }
            val book=BrushBook3D(root,bookFrame,requireNotNull(attachments.entity("BrushBookHeader")),
                requireNotNull(attachments.entity("BrushBookCaption")),requireNotNull(attachments.entity("BrushBookSurface")),basics,bookCatalog,
                context.getSharedPreferences("brush-book",Context.MODE_PRIVATE),context) { brushBookUi=it }
            brushBook=book
            book.initialize()
            palette.illuminate(bookFrame);basics.forEach(palette::illuminate)
            val session = DrawingSession(StrokeDocument(loaded),studioBrushes::contactRadius)
            runtime=StudioRuntime(root,panel,palette,book,capturePanel,MixedRealityCapture.get(context),session,studioBrushes,context,
                onPanel={studioPanel=it},referenceLayer=referenceLayer,
                onStatus={status=it},onSave={strokes,exit ->
                    if(exit)savingAndExiting=true
                    saver.save(strokes,exit)
                },initialPinch=inputUi.pinch,onInputUi={
                    if(it.pinch!=inputUi.pinch) inputPreferences.edit().putBoolean("pinch",it.pinch).apply()
                    inputUi=it
                },audioFrame=microphone::frame)
            Log.i("OpenBrushDrawing","Studio ready restored=${loaded.size}")
        } catch(e:Exception) {
            brushBook?.close()
            createdPalette?.close()
            if(referenceLayer.valid)referenceLayer.destroy()
            if(root.valid)root.destroy()
            loadError=context.getString(R.string.studio_start_failed, e.message.orEmpty())
            Log.e("OpenBrushDrawing","Studio initialization",e)
        }
    })
}

@Composable
private fun StudioControls(status:String,micEnabled:Boolean,input:StudioInputUi,onMicrophone:()->Unit,onPress:(Int)->Unit) {
    val latestPress by rememberUpdatedState(onPress)
    val latestMicrophone by rememberUpdatedState(onMicrophone)
    val paint=remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign=Paint.Align.CENTER;typeface=android.graphics.Typeface.DEFAULT } }
    val labels=listOf(stringResource(R.string.hand_controller),stringResource(R.string.brush_thinner),stringResource(R.string.brush_thicker),stringResource(R.string.pen_eraser),stringResource(R.string.brush_book),stringResource(R.string.example),stringResource(R.string.capture_short),stringResource(R.string.save_return))
    val micLabel=stringResource(if(micEnabled) R.string.mic_on else R.string.mic_off)
    val toolLabels=listOf(
        stringResource(if(input.selecting) R.string.stop_selecting else R.string.select_stroke),
        stringResource(R.string.prev_stroke),stringResource(R.string.next_stroke),stringResource(R.string.delete_stroke),
        stringResource(if(input.pinch) R.string.pinch_on else R.string.pinch_off),
        stringResource(if(input.moving) R.string.stop_moving else R.string.move_drawing),
        stringResource(R.string.reference_model),
    )
    Canvas(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { p ->
        val u = p.x / size.width
        val v = p.y / size.height
        if (u >= .82f && v < .36f) latestMicrophone()
        else StudioRuntime.hudCell(u,v)?.let(latestPress)
    } }) {
        drawRect(Color(0xE8202831))
        val w=size.width;val h=size.height
        fun label(text:String,x:Float,y:Float,font:Float,maxWidth:Float) {
            paint.textSize=font;paint.color=android.graphics.Color.WHITE
            val measured=paint.measureText(text)
            if(measured>maxWidth)paint.textSize*=maxWidth/measured
            drawIntoCanvas { it.nativeCanvas.drawText(text,x,y,paint) }
        }
        label(status,w*.41f,h*.26f,h*.12f,w*.79f)
        drawRect(if (micEnabled) Color(0xFF38694E) else Color(0xFF34556C),Offset(w*.82f,3f),Size(w*.18f-3f,h*.34f))
        label(micLabel,w*.91f,h*.26f,h*.12f,w*.16f)
        fun row(texts: List<String>,start: Float,end: Float,colors: (Int)->Color) {
            val width=w/texts.size
            for(i in texts.indices) {
                val left=i*width
                drawRect(colors(i),Offset(left+3f,h*start),Size(width-6f,h*(end-start)))
                label(texts[i],left+width*.5f,h*(start+(end-start)*.65f),h*.13f,width-16f)
            }
        }
        row(labels,.38f,.68f) { Color(0xFF34556C) }
        row(toolLabels,.70f,.98f) { index ->
            when {
                (index==0 && input.selecting) || (index==4 && input.pinch) || (index==5 && input.moving) -> Color(0xFF38694E)
                index==3 && input.hasSelection -> Color(0xFF853E38)
                index==3 -> Color(0xFF38434C)
                else -> Color(0xFF34556C)
            }
        }
    }
}
