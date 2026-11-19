package com.openbrush.picoviewer.ui.studio

import android.graphics.Bitmap
import android.graphics.Color
import com.openbrush.picoviewer.data.BrushPattern
import com.openbrush.picoviewer.data.hueColor
import com.openbrush.picoviewer.domain.drawing.InkColor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

internal object BrushPatternTexture {
    fun bitmap(pattern: BrushPattern, additive: Boolean): Bitmap {
        val width = 192
        val height = 32
        val pixels = IntArray(width * height) { i ->
            val color = sample(pattern, (i % width + .5f) / width, (i / width + .5f) / height)
            // Additive native materials add RGB directly, so black out the transparent texels.
            val gain = if (additive) color.a else 1f
            fun byte(channel: Float) = (channel * 255f).roundToInt()
            Color.argb(byte(if (additive) 1f else color.a), byte(color.r * gain), byte(color.g * gain), byte(color.b * gain))
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    fun sample(pattern: BrushPattern, u: Float, v: Float): InkColor = when (pattern) {
        BrushPattern.RAINBOW -> hueColor(u, .90f, 1f)
        BrushPattern.DISCO -> {
            val x = u * 16f
            val y = v * 8f
            val hue = ((x.toInt() * 19 + y.toInt() * 7) % 32) / 32f
            hueColor(hue, .78f, if (fraction(x) < .12f || fraction(y) < .10f) .22f else 1f)
        }
        BrushPattern.INK_DROPS -> {
            val spill = maxOf(0.0, cos(u * PI * 8)).pow(12).toFloat() * .49f
            val halfWidth = if (v < .64f) .21f + spill else .22f
            gray(.87f, if (abs(v - .64f) < halfWidth) 1f else 0f)
        }
        BrushPattern.RINGS -> {
            val stripe = abs(sin(u * PI * 6 + v * PI * 2)).pow(6).toFloat()
            gray(.21f + stripe * .79f, (.34f + stripe * .66f).coerceAtMost(1f))
        }
        BrushPattern.INVERTED_TOON -> {
            val side = abs(cos(v * 2 * PI))
            gray(when { side > .84 -> 1f; side > .43 -> .43f; else -> .13f })
        }
        BrushPattern.FAIRY, BrushPattern.SPACE -> {
            val starfield = pattern == BrushPattern.SPACE
            val x = u * if (starfield) 8 else 13
            val y = v * if (starfield) 3 else 4
            val cell = x.toInt() * 41 + y.toInt() * 29
            val dx = fraction(x) - (.21f + (cell * 53 % 59) / 100f)
            val dy = fraction(y) - (.21f + (cell * 97 % 59) / 100f)
            val intensity = maxOf(0f, 1f - hypot(dx, dy) / if (starfield) .11f else .19f)
            val nebula = if (starfield) .24f * maxOf(0.0, sin(u * 2 * PI) * cos(v * PI)).pow(2).toFloat() else 0f
            gray(minOf(1f, .55f + intensity), minOf(1f, intensity + nebula))
        }
        BrushPattern.DIGITAL -> {
            val x = u * 18f
            val y = v * 6f
            val cell = (x.toInt() * 19 + y.toInt() * 7) % 13
            val active = cell < 7 && fraction(x) < .8f && fraction(y) < .73f
            gray(.56f + cell * .03f, if (active) 1f else 0f)
        }
        BrushPattern.RACE -> {
            val lane = v * 5f
            val tail = fraction(u * 4f + lane.toInt() * .31f)
            val streak = maxOf(0f, 1f - abs(fraction(lane) - .5f) * 3f) * maxOf(0f, 1f - tail * 1.4f)
            gray(.78f + streak * .22f, streak * streak)
        }
    }

    private fun gray(value: Float, alpha: Float = 1f) = InkColor(value, value, value, alpha)
    private fun fraction(value: Float) = value - floor(value)
}
