package com.openbrush.picoviewer.domain.drawing

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Analyzes one PCM16 window with the same waveform and logarithmic bands as the visionOS port. */
internal class BrushAudioAnalyzer {
    private val window = FloatArray(WINDOW_SIZE) { (.5 - .5 * cos(2 * PI * it / WINDOW_SIZE)).toFloat() }
    private val bitReversed = IntArray(WINDOW_SIZE) { Integer.reverse(it) ushr (32 - 9) }
    private val cosine = FloatArray(WINDOW_SIZE / 2) { cos(-2 * PI * it / WINDOW_SIZE).toFloat() }
    private val sine = FloatArray(WINDOW_SIZE / 2) { sin(-2 * PI * it / WINDOW_SIZE).toFloat() }
    private val bandEdges = IntArray(17) { 2.0.pow(it / 2.0).toInt() }

    fun analyze(samples: ShortArray): BrushAudioFrame {
        require(samples.size == WINDOW_SIZE)
        val real = FloatArray(WINDOW_SIZE)
        val imaginary = FloatArray(WINDOW_SIZE)
        val waveform = FloatArray(64)
        var energy = 0f
        for (i in samples.indices) {
            val sample = samples[i] / 32768f
            energy += sample * sample
            waveform[i / 8] += sample
            real[bitReversed[i]] = sample * window[i]
        }
        var length = 2
        while (length <= WINDOW_SIZE) {
            val half = length / 2
            val stride = WINDOW_SIZE / length
            for (start in 0 until WINDOW_SIZE step length) {
                for (i in 0 until half) {
                    val low = start + i
                    val high = low + half
                    val twiddle = i * stride
                    val r = real[high] * cosine[twiddle] - imaginary[high] * sine[twiddle]
                    val im = real[high] * sine[twiddle] + imaginary[high] * cosine[twiddle]
                    real[high] = real[low] - r
                    imaginary[high] = imaginary[low] - im
                    real[low] += r
                    imaginary[low] += im
                }
            }
            length *= 2
        }
        val spectrum = FloatArray(16) { band ->
            val low = bandEdges[band]
            val high = maxOf(low + 1, bandEdges[band + 1])
            var peak = 0f
            for (bin in low until high) peak = maxOf(peak, hypot(real[bin], imaginary[bin]))
            (peak * 8f / WINDOW_SIZE).coerceAtMost(1f)
        }
        for (i in waveform.indices) waveform[i] = (waveform[i] * .5f).coerceIn(-1f, 1f)
        return BrushAudioFrame(waveform, spectrum, (sqrt(energy / WINDOW_SIZE) * 5f).coerceAtMost(1f))
    }

    companion object { const val WINDOW_SIZE = 512 }
}
