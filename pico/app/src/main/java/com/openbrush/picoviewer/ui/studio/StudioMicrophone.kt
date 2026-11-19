package com.openbrush.picoviewer.ui.studio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.openbrush.picoviewer.domain.drawing.BrushAudioAnalyzer
import com.openbrush.picoviewer.domain.drawing.BrushAudioFrame
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

internal class StudioMicrophone {
    private data class Reading(val frame: BrushAudioFrame, val time: Long)
    private val latest = AtomicReference<Reading?>()

    fun frame(): BrushAudioFrame = latest.get()?.takeIf { SystemClock.elapsedRealtime() - it.time < 250L }?.frame
        ?: BrushAudioFrame.SILENT

    @SuppressLint("MissingPermission")
    suspend fun capture() = withContext(Dispatchers.IO) {
        val rate = 48_000
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val buffer = AudioRecord.getMinBufferSize(rate, channel, encoding)
        check(buffer > 0) { "麦克风不支持单声道 PCM" }
        val record = AudioRecord(MediaRecorder.AudioSource.MIC, rate, channel, encoding, maxOf(buffer, 4096))
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "无法初始化麦克风" }
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "无法启动麦克风" }
            Log.i("OpenBrushAudio","Microphone recording started")
            val analyzer = BrushAudioAnalyzer()
            val samples = ShortArray(BrushAudioAnalyzer.WINDOW_SIZE)
            var filled = 0
            while (isActive) {
                val count = record.read(samples, filled, samples.size - filled, AudioRecord.READ_NON_BLOCKING)
                check(count >= 0) { "麦克风读取失败 ($count)" }
                filled += count
                if (filled == samples.size) {
                    latest.set(Reading(analyzer.analyze(samples), SystemClock.elapsedRealtime()))
                    filled = 0
                }
                if (count == 0) delay(10)
            }
        } finally {
            latest.set(null)
            record.release()
            Log.i("OpenBrushAudio","Microphone released")
        }
    }
}
