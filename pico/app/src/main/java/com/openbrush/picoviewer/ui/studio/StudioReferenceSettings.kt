package com.openbrush.picoviewer.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openbrush.picoviewer.R
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text

@Composable
internal fun StudioReferenceSettings(name: String?, shown: Boolean, loading: Boolean, error: String?,
    onOpen: () -> Unit, onToggle: () -> Unit, onRemove: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color(0xF0202831)).padding(24.dp),
        verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Button(onClick=onBack) { Text(stringResource(R.string.back)) }
            Text(stringResource(R.string.reference_model),style=PicoTheme.typography.titleLarge)
        }
        Button(onClick=onOpen,enabled=!loading) { Text(stringResource(R.string.reference_open)) }
        if(loading) Text(stringResource(R.string.building_preview))
        if(name!=null) {
            Text(name,style=PicoTheme.typography.titleMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Button(onClick=onToggle,enabled=!loading) {
                    Text(stringResource(if(shown) R.string.hide else R.string.show))
                }
                Button(onClick=onRemove,enabled=!loading) { Text(stringResource(R.string.reference_remove)) }
            }
        }
        if(error!=null)Text(error,color=PicoTheme.colorScheme.error)
    }
}
