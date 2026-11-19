package com.openbrush.picoviewer.ui.viewer.components

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openbrush.picoviewer.R
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text

@Composable
internal fun LanguagePanel(onBack: () -> Unit) {
    val manager = LocalContext.current.getSystemService(LocaleManager::class.java)
    var selected by remember(manager) { mutableStateOf(manager.applicationLocales.toLanguageTags()) }
    val choices = listOf("" to stringResource(R.string.system_language), "zh-Hans" to "简体中文",
        "zh-Hant" to "繁體中文", "en" to "English")
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.language), style = PicoTheme.typography.titleLarge)
        for ((tag, label) in choices) {
            Button(onClick = {
                if (selected != tag) {
                    manager.applicationLocales = LocaleList.forLanguageTags(tag)
                    selected = tag
                }
            }) { Text(if (selected == tag) "✓ $label" else label) }
        }
        Button(onClick = onBack) { Text(stringResource(R.string.back_library)) }
    }
}
