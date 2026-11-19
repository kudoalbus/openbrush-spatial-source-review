package com.openbrush.picoviewer.ui.capture

import androidx.compose.ui.res.stringResource
import com.openbrush.picoviewer.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openbrush.picoviewer.capture.CaptureSettings
import com.openbrush.picoviewer.capture.MixedRealityCapture
import com.pico.spatial.ui.design.*

/** Shared by the library menu and the in-studio attachment. No phone application is shipped yet. */
@Composable
fun MixedRealitySettings(onBack: () -> Unit, onControllerMode: (() -> Unit)? = null) {
    val context = LocalContext.current
    val capture = remember(context) { MixedRealityCapture.get(context) }
    val state by capture.state.collectAsState()
    val config = state.settings
    fun change(update: (CaptureSettings) -> CaptureSettings) { capture.updateSettings(update(config)) }
    Column(Modifier.fillMaxSize().background(Color(0xF0202831)).verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onBack) { Text(stringResource(R.string.back)) }
            Text(stringResource(R.string.mixed_reality), style = PicoTheme.typography.titleLarge)
        }
        Text(stringResource(R.string.phone_companion_pending), color = PicoTheme.colorScheme.labelSecondary)
        Text(stringResource(R.string.capture_description),
            style = PicoTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { change { it.copy(movingCamera = true) } }) { Text(checkedLabel(config.movingCamera, stringResource(R.string.camera_moving))) }
            Button(onClick = { change { it.copy(movingCamera = false) } }) { Text(checkedLabel(!config.movingCamera, stringResource(R.string.camera_fixed))) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for((id, label) in listOf("both" to stringResource(R.string.phone_both), "ios" to "iPhone", "android" to stringResource(R.string.phone_android))) {
                Button(onClick = { change { it.copy(phonePlatform = id) } }) { Text(if(config.phonePlatform == id) "✓ $label" else label) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { if(state.running) capture.stop() else capture.start() }) { Text(if(state.running) stringResource(R.string.phone_disconnect) else stringResource(R.string.phone_connect)) }
            if(state.running) Button(onClick = { capture.stop(); capture.start() }) { Text(stringResource(R.string.refresh_pairing)) }
        }
        Text(when {
            state.peerName != null -> stringResource(R.string.phone_connected, state.peerName.orEmpty())
            state.listening -> stringResource(R.string.phone_waiting)
            state.running -> stringResource(R.string.phone_starting)
            else -> stringResource(R.string.phone_disconnected)
        })
        if(state.running) SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.capture_device, state.serviceName))
                Text(stringResource(R.string.pairing_code, state.pairingCode), style = PicoTheme.typography.titleMedium)
                Text(stringResource(R.string.pairing_help))
                if(state.addresses.isEmpty()) Text(stringResource(R.string.network_unavailable))
                else state.addresses.forEach { Text(it, style = PicoTheme.typography.bodySmall) }
            }
        }
        Text(stringResource(R.string.spatial_alignment), style = PicoTheme.typography.titleMedium)
        val tracking = stringResource(if(state.stageReady) R.string.stage_tracking_ready else R.string.stage_tracking_wait)
        val alignment = state.calibration ?: stringResource(R.string.uncalibrated)
        Text("$tracking · $alignment")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for((id, label) in listOf("right_controller" to stringResource(R.string.controller_right), "left_controller" to stringResource(R.string.controller_left), "head" to stringResource(R.string.headset_shell))) {
                Button(onClick = { change { it.copy(calibrationSource = id) } }) { Text(if(config.calibrationSource == id) "✓ $label" else label) }
            }
        }
        Text(stringResource(R.string.marker_help), style = PicoTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for(mm in listOf(60, 80, 100)) Button(onClick = { change { it.copy(markerSizeMm = mm) } }) {
                Text(if(config.markerSizeMm == mm) "✓ ${mm / 10}cm" else "${mm / 10}cm")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            onControllerMode?.let { action -> Button(onClick = action) { Text(stringResource(R.string.controller_mode)) } }
            Button(onClick = capture::beginCalibration, enabled = state.peerName != null && state.stageReady) { Text(stringResource(R.string.recalibrate)) }
            Button(onClick = { capture.invalidate() }) { Text(stringResource(R.string.clear_calibration)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { change { it.copy(includePalette = !it.includePalette) } }) { Text(checkedLabel(config.includePalette, stringResource(if(config.includePalette) R.string.include_palette else R.string.exclude_palette))) }
            Button(onClick = { change { it.copy(poseHz = if(it.poseHz == 30) 15 else 30) } }) { Text(stringResource(R.string.pose_rate, config.poseHz)) }
        }
        Text(if(state.phoneRecording) stringResource(R.string.phone_recording) else if(state.phoneTracking) stringResource(R.string.phone_tracking_ready) else stringResource(R.string.phone_not_ready))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { capture.requestRecording(true) }, enabled = state.calibrated && state.phoneTracking && state.stageReady) { Text(stringResource(R.string.request_recording)) }
            Button(onClick = { capture.requestRecording(false) }, enabled = state.peerName != null) { Text(stringResource(R.string.request_stop_recording)) }
        }
        state.error?.let { Text(it, color = PicoTheme.colorScheme.error) }
        Text(stringResource(R.string.phone_companion_todo),
            color = PicoTheme.colorScheme.labelSecondary, style = PicoTheme.typography.bodySmall)
    }
}

private fun checkedLabel(selected: Boolean, label: String) = if (selected) "✓ $label" else label
