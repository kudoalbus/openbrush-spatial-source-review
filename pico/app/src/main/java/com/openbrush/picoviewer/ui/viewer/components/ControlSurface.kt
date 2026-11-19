package com.openbrush.picoviewer.ui.viewer.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material

@Composable
fun ControlSurface(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).backgroundMaterial(enable=true,style=Material.Regular).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp),content=content)
}
