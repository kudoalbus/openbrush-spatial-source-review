package com.openbrush.picoviewer.ui.viewer

import com.openbrush.picoviewer.R

import android.content.Intent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openbrush.picoviewer.data.ImportRequests
import com.openbrush.picoviewer.data.repository.ArtworkRepository
import com.openbrush.picoviewer.domain.usecase.OpenArtwork
import com.openbrush.picoviewer.platform.ImportActivity
import com.openbrush.picoviewer.ui.viewer.components.ViewerContent
import com.openbrush.picoviewer.ui.viewer.components.DrawingFilesPanel
import com.openbrush.picoviewer.ui.viewer.components.LanguagePanel
import com.openbrush.picoviewer.ui.capture.MixedRealitySettings
import com.openbrush.picoviewer.data.ArtworkMetadata
import com.openbrush.picoviewer.data.DrawingWorkspaceFiles
import com.openbrush.picoviewer.data.DrawingImportFormat
import android.widget.Toast
import com.openbrush.picoviewer.ARTWORK_WINDOW
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.util.UUID
import android.util.Log
import com.openbrush.picoviewer.DRAWING_STAGE
import com.pico.spatial.ui.platform.containers.StageStyle
import com.pico.spatial.ui.platform.containers.OpenStageResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ViewerScreen() {
    val context=LocalContext.current.applicationContext
    val factory=remember(context){object:ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T:ViewModel> create(modelClass:Class<T>):T = ViewerViewModel(context::getString, OpenArtwork(ArtworkRepository(context))::invoke) as T
    }}
    val vm:ViewerViewModel=viewModel(factory=factory)
    val state by vm.state.collectAsStateWithLifecycle()
    val navigator=LocalSpatialNavigator.current
    val scope=rememberCoroutineScope()
    val workspaces=remember(context) { DrawingWorkspaceFiles(context) }
    var showMixedReality by remember { mutableStateOf(false) }
    var showDrawingFiles by remember { mutableStateOf(false) }
    var showLanguage by remember { mutableStateOf(false) }
    var drawingEntries by remember { mutableStateOf<List<DrawingWorkspaceFiles.Entry>?>(null) }
    var activeDrawing by remember { mutableStateOf(workspaces.activeId) }
    var libraryRevision by remember { mutableIntStateOf(0) }
    var creatingDrawing by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(showDrawingFiles, libraryRevision, lifecycle) {
        if (showDrawingFiles) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            drawingEntries = withContext(Dispatchers.IO) { workspaces.entries() }
        }
    }
    LaunchedEffect(vm,navigator){vm.opened.collect { artwork ->
        try {
            val tag=UUID.randomUUID().toString()
            navigator.openWindowContainer(ARTWORK_WINDOW,tag,Bundle().apply{putString("artwork",ArtworkMetadata.encode(artwork));putString("instance",tag)})
            Log.i("OpenBrushWindows","Opened instance=$tag file=${artwork.name}")
        } catch(e:Exception){vm.onEvent(ViewerEvent.LoadFailed(context.getString(R.string.work_window_failed, e.message.orEmpty())))}
    }}
    suspend fun openWorkspace(id: String) {
        val args=Bundle().apply { putString(DrawingWorkspaceFiles.WORKSPACE_ID,id) }
        when(val result=navigator.openStage(DRAWING_STAGE,style=StageStyle.Mixed,bundle=args)) {
            is OpenStageResult.Allowed -> {
                workspaces.select(id)
                activeDrawing = id
                navigator.minimizeWindowContainer()
            }
            else -> vm.onEvent(ViewerEvent.LoadFailed(context.getString(R.string.studio_open_failed, result)))
        }
    }
    LaunchedEffect(vm,navigator) {
        ImportRequests.requests.collect { request ->
            val format = request.drawing
            if (format == null) {
                vm.onEvent(ViewerEvent.Import(request))
            } else try {
                val imported=withContext(Dispatchers.IO) { workspaces.import(request.uri, format, request.displayName) }
                if (imported.notices.isNotEmpty()) {
                    Toast.makeText(context, imported.notices.joinToString("。"), Toast.LENGTH_LONG).show()
                }
                // Keep the successfully imported copy available even if the Stage cannot open.
                workspaces.select(imported.id)
                activeDrawing = imported.id
                libraryRevision++
                openWorkspace(imported.id)
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { vm.onEvent(ViewerEvent.LoadFailed(context.getString(R.string.drawing_import_failed, e.message.orEmpty()))) }
        }
    }
    fun openPicker(folder:Boolean=false,drawing:DrawingImportFormat?=null,exportId:String?=null) {
        val intent=Intent(context,ImportActivity::class.java).putExtra("folder",folder)
            .putExtra(ImportActivity.DRAWING_FORMAT,drawing?.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if(exportId != null) {
            intent.putExtra(DrawingWorkspaceFiles.WORKSPACE_ID, exportId)
            intent.putExtra(ImportActivity.EXPORT_TITLE, workspaces.title(exportId) + ".json")
        }
        try { context.startActivity(intent) }
        catch(e:Exception){vm.onEvent(ViewerEvent.LoadFailed(context.getString(R.string.picker_failed, e.message.orEmpty())))}
    }
    if(showLanguage) {
        LanguagePanel(onBack={showLanguage=false})
        return
    }
    if(showMixedReality) {
        MixedRealitySettings(onBack={showMixedReality=false})
        return
    }
    if(showDrawingFiles) {
        DrawingFilesPanel(drawingEntries, activeDrawing, creatingDrawing, state.error,
            onNew = {
                if (!creatingDrawing) {
                    creatingDrawing = true
                    scope.launch {
                        try {
                            val id = withContext(Dispatchers.IO) { workspaces.create() }
                            libraryRevision++
                            openWorkspace(id)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { vm.onEvent(ViewerEvent.LoadFailed(context.getString(R.string.drawing_create_failed, e.message.orEmpty()))) }
                        finally { creatingDrawing = false }
                    }
                }
            },
            onImport = { format -> openPicker(drawing= format) },
            onOpen = { id -> scope.launch { openWorkspace(id) } },
            onPreview = { entry ->
                scope.launch {
                    try {
                        val tag=UUID.randomUUID().toString()
                        val args=Bundle().apply {
                            putString(DrawingWorkspaceFiles.WORKSPACE_ID,entry.id)
                            putString("title",entry.title)
                            putString("instance",tag)
                        }
                        navigator.openWindowContainer(ARTWORK_WINDOW,tag,args)
                    } catch(e:CancellationException) { throw e }
                    catch(e:Exception) { vm.onEvent(ViewerEvent.LoadFailed(context.getString(R.string.drawing_preview_failed, e.message.orEmpty()))) }
                }
            },
            onExport = { id -> openPicker(exportId = id) },
            onBack = { showDrawingFiles = false })
        return
    }
    ViewerContent(state,vm::onEvent,onOpen={openPicker()},onOpenFolder={openPicker(folder=true)},
        onMixedReality={showMixedReality=true},
        onDraw={scope.launch { openWorkspace(workspaces.activeId) }},
        onDrawingFiles={drawingEntries=null;showDrawingFiles=true},onLanguage={showLanguage=true})
}
