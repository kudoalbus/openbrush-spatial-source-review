package com.openbrush.picoviewer

import com.openbrush.picoviewer.domain.drawing.BrushAudioAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class BrushAudioAnalyzerTest {
    @Test fun silenceAndTonesProduceExpectedLevelAndBands() {
        val analyzer = BrushAudioAnalyzer()
        val size = BrushAudioAnalyzer.WINDOW_SIZE
        val silent = analyzer.analyze(ShortArray(size))
        assertEquals(0f, silent.level)
        assertEquals(0f, silent.sample(.4f))
        assertEquals(0f, silent.band(.8f))
        fun tone(bin: Int) = analyzer.analyze(ShortArray(size) { i ->
            (sin(2 * PI * bin * i / size) * 4096).toInt().toShort()
        })
        val low = tone(5)
        val high = tone(47)
        assertEquals(.442f, low.level, .002f)
        assertEquals(low.level, high.level, .002f)
        assertTrue(low.sample(0f) > 0f)
        assertTrue(low.band(5f / 15f) > .20f && low.band(11f / 15f) < .002f)
        assertTrue(high.band(11f / 15f) > .20f && high.band(5f / 15f) < .002f)
    }
}
