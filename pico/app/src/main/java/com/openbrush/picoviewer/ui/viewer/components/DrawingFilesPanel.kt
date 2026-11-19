package com.openbrush.picoviewer.ui.viewer.components

import androidx.compose.ui.res.stringResource
import com.openbrush.picoviewer.R
import androidx.compose.ui.platform.LocalConfiguration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openbrush.picoviewer.data.DrawingImportFormat
import com.openbrush.picoviewer.data.DrawingWorkspaceFiles
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import java.text.DateFormat
import java.util.Date

@Composable
internal fun DrawingFilesPanel(entries: List<DrawingWorkspaceFiles.Entry>?, activeId: String, creating: Boolean,
    error: String?, onNew: () -> Unit, onImport: (DrawingImportFormat) -> Unit, onOpen: (String) -> Unit,
    onPreview: (DrawingWorkspaceFiles.Entry) -> Unit, onExport: (String) -> Unit, onBack: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale) }
    val currentDrawing = stringResource(R.string.active_drawing)
    val listState = rememberLazyListState()
    val newest = entries?.firstOrNull()?.id
    LaunchedEffect(newest) {
        if (newest != null) listState.scrollToItem(0)
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.drawing_files), style = PicoTheme.typography.titleLarge)
            Button(onClick = onBack) { Text(stringResource(R.string.back_library)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onNew, enabled = !creating) { Text(stringResource(R.string.new_drawing)) }
            Button(onClick = { onImport(DrawingImportFormat.JSON) }) { Text(stringResource(R.string.open_drawing_json)) }
            Button(onClick = { onImport(DrawingImportFormat.TILT) }) { Text(stringResource(R.string.import_editable_tilt)) }
        }
        error?.let { Text(it, color = PicoTheme.colorScheme.error) }
        if (entries == null) {
            Text(stringResource(R.string.loading_drawings))
        } else {
            LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(entries, key = { it.id }) { entry ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val details = buildList {
                                if (entry.id == activeId) add(currentDrawing)
                                if (entry.modifiedAt > 0L) add(formatter.format(Date(entry.modifiedAt)))
                            }
                            if (details.isNotEmpty()) Text(details.joinToString(" · "), style = PicoTheme.typography.bodySmall)
                        }
                        Button(onClick = { onOpen(entry.id) }) { Text(stringResource(R.string.open)) }
                        Button(onClick = { onPreview(entry) }) { Text(stringResource(R.string.preview)) }
                        Button(onClick = { onExport(entry.id) }) { Text(stringResource(R.string.export)) }
                    }
                }
            }
        }
    }
}
