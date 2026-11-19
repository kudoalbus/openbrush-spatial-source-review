package com.openbrush.picoviewer.ui.viewer

import com.openbrush.picoviewer.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openbrush.picoviewer.data.ImportRequest
import com.openbrush.picoviewer.domain.model.Artwork
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
/** Import menu only. Complete imports create new windows, never replace older works. */
class ViewerViewModel(private val string: (Int)->String, private val open:suspend (ImportRequest?)->Artwork):ViewModel() {
    private val mutable=MutableStateFlow(ViewerUiState())
    val state=mutable.asStateFlow()
    private data class Request(val source:ImportRequest?)
    private val requests=Channel<Request>(32)
    private val completed=Channel<Artwork>(Channel.BUFFERED)
    val opened=completed.receiveAsFlow()
    init { viewModelScope.launch {
        for(request in requests) {
            try {
                val artwork=open(request.source)
                completed.send(artwork)
                mutable.update{it.copy(opened=it.opened+1)}
            } catch(e:CancellationException){throw e}
            catch(e:Exception){mutable.update{it.copy(error=e.message ?: string(R.string.file_load_failed))}}
            finally{mutable.update{it.copy(pending=it.pending-1)}}
        }
    } }
    fun onEvent(event:ViewerEvent) {
        when(event) {
            is ViewerEvent.Import,ViewerEvent.Demo -> {
                if(requests.trySend(Request((event as? ViewerEvent.Import)?.request)).isSuccess)
                    mutable.update{it.copy(pending=it.pending+1,error=null)}
                else mutable.update{it.copy(error=string(R.string.import_busy))}
            }
            is ViewerEvent.LoadFailed -> mutable.update{it.copy(error=event.message)}
        }
    }
}
