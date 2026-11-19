package com.openbrush.picoviewer.ui.viewer.components

import androidx.compose.ui.res.stringResource
import com.openbrush.picoviewer.R
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.*
import com.openbrush.picoviewer.ui.viewer.*
@Composable
fun ViewerContent(state:ViewerUiState,onEvent:(ViewerEvent)->Unit,onOpen:()->Unit,onOpenFolder:()->Unit,onDraw:()->Unit,
    onMixedReality:()->Unit,onDrawingFiles:()->Unit,onLanguage:()->Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp),verticalArrangement=Arrangement.spacedBy(14.dp),horizontalAlignment=Alignment.Start) {
        Text(stringResource(R.string.library_title),style=PicoTheme.typography.titleLarge,color=PicoTheme.colorScheme.labelPrimary)
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Button(onClick=onOpen){Text(stringResource(R.string.open_file))}
            Button(onClick=onOpenFolder){Text(stringResource(R.string.import_folder))}
            Button(onClick={onEvent(ViewerEvent.Demo)}){Text(stringResource(R.string.example))}
            Button(onClick=onDraw){Text(stringResource(R.string.start_drawing))}
        }
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Button(onClick=onDrawingFiles){Text(stringResource(R.string.drawing_files))}
            Button(onClick=onMixedReality){Text(stringResource(R.string.mixed_reality))}
            Button(onClick=onLanguage){Text(stringResource(R.string.language))}
        }
        Text(stringResource(R.string.library_formats),style=PicoTheme.typography.bodyMedium,color=PicoTheme.colorScheme.labelSecondary)
        Text(stringResource(R.string.library_controls),style=PicoTheme.typography.bodyMedium,color=PicoTheme.colorScheme.labelSecondary)
        Text(if(state.pending>0)stringResource(R.string.preparing_works, state.pending) else stringResource(R.string.opened_works, state.opened),color=PicoTheme.colorScheme.labelPrimary)
        state.error?.let{Text(it,color=PicoTheme.colorScheme.error,style=PicoTheme.typography.bodySmall)}
    }
}
