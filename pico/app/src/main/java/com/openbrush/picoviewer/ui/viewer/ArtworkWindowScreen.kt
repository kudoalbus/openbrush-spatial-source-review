package com.openbrush.picoviewer.ui.viewer

import androidx.compose.ui.res.stringResource
import com.openbrush.picoviewer.R
import android.os.Bundle
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.openbrush.picoviewer.data.ArtworkMetadata
import com.openbrush.picoviewer.data.DrawingWorkspaceFiles
import com.openbrush.picoviewer.ui.viewer.components.*
import com.pico.spatial.ui.design.*
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import kotlinx.coroutines.channels.Channel
@Composable
fun ArtworkWindowScreen(bundle:Bundle?) {
    val result=remember(bundle){
        runCatching {
            val id=bundle?.getString(DrawingWorkspaceFiles.WORKSPACE_ID)
            if(id!=null) ArtworkWindowSource.Drawing(id,requireNotNull(bundle.getString("title")))
            else ArtworkWindowSource.Model(ArtworkMetadata.decode(requireNotNull(bundle?.getString("artwork"))))
        }
    }
    val source=result.getOrNull()
    val art=(source as? ArtworkWindowSource.Model)?.artwork
    val preview=source as? ArtworkWindowSource.Drawing
    val instance=bundle?.getString("instance") ?: "restored"
    val navigator=LocalSpatialNavigator.current
    var useFileLighting by rememberSaveable { mutableStateOf(art?.lighting!=null) }
    var shown by rememberSaveable { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var windowSize by remember { mutableStateOf(IntSize.Zero) }
    var modelReady by remember { mutableStateOf(false) }
    var drawingInfo by remember { mutableStateOf<Pair<Int,Int>?>(null) }
    val motions=remember(instance){Channel<ArtworkMotion>(Channel.UNLIMITED)}
    DisposableEffect(motions){onDispose{motions.close()}}
    ArtworkWindowLayout(instance,Modifier.fillMaxSize().onSizeChanged{windowSize=it},model={
        Box(Modifier.fillMaxSize()) {
            if(source!=null)ArtworkView(source,instance,useFileLighting,shown,windowSize,motions,
                onReady={modelReady=true},onDrawingInfo={strokes,triangles->drawingInfo=strokes to triangles},onError={error=it})
        }
    },controls={
        Column(Modifier.fillMaxWidth()) {
                ControlSurface {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                        Text(preview?.title ?: art?.name ?: stringResource(R.string.work_open_failed),style=PicoTheme.typography.titleMedium,color=PicoTheme.colorScheme.labelPrimary,modifier=Modifier.weight(1f))
                        if(art!=null && art.gaussian==null) {
                            Button(onClick={useFileLighting=!useFileLighting},enabled=art.lighting!=null) {
                                Text(if(useFileLighting)stringResource(R.string.file_lighting) else stringResource(R.string.app_lighting))
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        Button(onClick={navigator.closeWindowContainer()}){Text(stringResource(R.string.close))}
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        AxisRotation.entries.forEach { step ->
                            Button(onClick={motions.trySend(ArtworkMotion.Rotate(step))},enabled=modelReady && shown,modifier=Modifier.weight(1f)) {Text(step.label)}
                        }
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(onClick={motions.trySend(ArtworkMotion.Center)},enabled=modelReady && shown){Text(stringResource(R.string.center))}
                        Button(onClick={shown=!shown},enabled=modelReady){Text(if(shown)stringResource(R.string.hide) else stringResource(R.string.show))}
                    }
                    if(preview!=null) {
                        val info=drawingInfo
                        val text=when {
                            info==null -> if(error==null) stringResource(R.string.building_preview) else ""
                            info.second==0 -> stringResource(R.string.no_visible_ink)
                            else -> stringResource(R.string.stroke_triangle_count, info.first, info.second)
                        }
                        if(text.isNotEmpty())Text(text,style=PicoTheme.typography.bodySmall,color=PicoTheme.colorScheme.labelSecondary)
                    }
                    if(art?.gaussian!=null)Text(stringResource(R.string.splat_count, art.gaussian.count),style=PicoTheme.typography.bodySmall,color=PicoTheme.colorScheme.labelSecondary)
                    art?.triangleCount?.let { count ->
                        Text(stringResource(R.string.triangle_count, count),style=PicoTheme.typography.bodySmall,color=PicoTheme.colorScheme.labelSecondary)
                    }
                    if(source==null)Text(result.exceptionOrNull()?.message.orEmpty(),style=PicoTheme.typography.bodySmall,color=PicoTheme.colorScheme.error)
                    error?.takeIf{it.isNotBlank()}?.let{Text(it,style=PicoTheme.typography.bodySmall,color=PicoTheme.colorScheme.error)}
                }
        }
    })
}
