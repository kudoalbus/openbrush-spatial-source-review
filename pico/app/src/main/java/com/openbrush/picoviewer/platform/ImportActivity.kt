package com.openbrush.picoviewer.platform

import com.openbrush.picoviewer.R

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.provider.OpenableColumns
import android.os.Bundle
import android.widget.Toast
import com.openbrush.picoviewer.data.ImportRequests
import com.openbrush.picoviewer.data.ImportRequest
import com.openbrush.picoviewer.data.DocumentTree
import com.openbrush.picoviewer.data.DrawingWorkspaceFiles
import com.openbrush.picoviewer.data.DrawingImportFormat
import com.openbrush.picoviewer.data.repository.ArtworkRepository
import android.net.Uri
import kotlinx.coroutines.*

/** Delegates file selection to Android's Storage Access Framework. No custom 2D UI. */
class ImportActivity:Activity() {
    private val scope=MainScope()
    private var selectedTree:Uri?=null
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        selectedTree=savedInstanceState?.getString("tree")?.let(Uri::parse)
        if(selectedTree!=null) { chooseModel(selectedTree!!);return }
        if(savedInstanceState==null) {
            @Suppress("DEPRECATION")
            if(intent.hasExtra(DrawingWorkspaceFiles.WORKSPACE_ID)) {
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type="application/json"
                    putExtra(Intent.EXTRA_TITLE,intent.getStringExtra(EXPORT_TITLE) ?: "openbrush-workspace-v3.json")
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                },3)
            } else if(intent.getBooleanExtra("folder",false)) {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                },2)
            } else {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type="*/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },1)
            }
        }
    }
    @Deprecated("Android Activity callback bridge")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        val uri=data?.data
        if(resultCode!=RESULT_OK || uri==null){finish();return}
        if(requestCode==3) {
            val id=requireNotNull(intent.getStringExtra(DrawingWorkspaceFiles.WORKSPACE_ID))
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { DrawingWorkspaceFiles(applicationContext).export(id,uri) }
                    Toast.makeText(this@ImportActivity,getString(R.string.drawing_exported),Toast.LENGTH_SHORT).show()
                } catch(e:CancellationException) { throw e }
                catch(e:Exception) { Toast.makeText(this@ImportActivity,getString(R.string.drawing_export_failed, e.message.orEmpty()),Toast.LENGTH_LONG).show() }
                finish()
            }
        } else if(requestCode==2) {
            selectedTree=uri
            try {contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}catch(_:SecurityException){}
            chooseModel(uri)
        } else {
            val drawing = intent.getStringExtra(DRAWING_FORMAT)?.let(DrawingImportFormat::valueOf)
            if (drawing == null) {
                ImportRequests.requests.tryEmit(ImportRequest(uri))
                finish()
            } else scope.launch {
                val name = withContext(Dispatchers.IO) {
                    runCatching {
                        contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        }
                    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf {
                        it.endsWith(".json",true) || it.endsWith(".tilt",true)
                    }
                }
                ImportRequests.requests.tryEmit(ImportRequest(uri,drawing=drawing,displayName=name))
                finish()
            }
        }
    }
    private fun chooseModel(tree:Uri) {
        scope.launch {
            try {
                val models=withContext(Dispatchers.IO){DocumentTree(contentResolver,tree).scan().filter {
                    it.path.substringAfterLast('.',"").lowercase() in ArtworkRepository.modelExtensions
                }.sortedBy{it.path}}
                require(models.isNotEmpty()){getString(R.string.folder_no_models)}
                AlertDialog.Builder(this@ImportActivity).setTitle(getString(R.string.select_folder_model))
                    .setItems(models.map{it.path}.toTypedArray()){_,index ->
                        val model=models[index]
                        ImportRequests.requests.tryEmit(ImportRequest(model.uri,tree,model.path));finish()
                    }.setNegativeButton(getString(R.string.cancel)){_,_->finish()}.setOnCancelListener{finish()}.show()
            } catch(e:CancellationException){throw e}
            catch(e:Exception){AlertDialog.Builder(this@ImportActivity).setTitle(getString(R.string.folder_import_failed))
                .setMessage(e.message).setPositiveButton(getString(R.string.close)){_,_->finish()}.setOnCancelListener{finish()}.show()}
        }
    }
    override fun onSaveInstanceState(outState:Bundle){selectedTree?.let{outState.putString("tree",it.toString())};super.onSaveInstanceState(outState)}
    override fun onDestroy(){scope.cancel();super.onDestroy()}

    companion object {
        const val DRAWING_FORMAT = "drawingImportFormat"
        const val EXPORT_TITLE = "drawingExportTitle"
    }
}
