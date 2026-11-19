package com.openbrush.picoviewer.data

import android.net.Uri
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow

/** File picker bridge; no broad filesystem permission or all-files access. */
object ImportRequests {
    val requests = MutableSharedFlow<ImportRequest>(extraBufferCapacity=1,onBufferOverflow=BufferOverflow.DROP_OLDEST)
}

enum class DrawingImportFormat { JSON, TILT }

data class ImportRequest(val uri:Uri,val treeUri:Uri?=null,val modelPath:String?=null,
    val drawing:DrawingImportFormat?=null,val displayName:String?=null)
