package com.openbrush.picoviewer.ui.viewer
import com.openbrush.picoviewer.data.ImportRequest
data class ViewerUiState(val pending:Int=0,val opened:Int=0,val error:String?=null)
sealed interface ViewerEvent {
    data class Import(val request:ImportRequest):ViewerEvent
    data object Demo:ViewerEvent
    data class LoadFailed(val message:String):ViewerEvent
}
