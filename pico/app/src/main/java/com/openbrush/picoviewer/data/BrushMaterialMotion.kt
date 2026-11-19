package com.openbrush.picoviewer.data

import com.openbrush.picoviewer.domain.drawing.InkColor
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

internal enum class BrushPattern { RAINBOW, DISCO, INK_DROPS, RINGS, INVERTED_TOON, FAIRY, SPACE, DIGITAL, RACE }

/** Time is transient presentation state, measured in seconds since the studio render started. */
class BrushMaterialMotion internal constructor(
    val opacity: Float = 1f,
    val emission: Float = 0f,
    val tileRate: Float? = null,
    val scroll: Float = 0f,
    val pulsePeriod: Float = 0f,
    val hueRate: Float = 0f,
    internal val pattern: BrushPattern? = null,
    val wireframe: Boolean = false
) {
    fun offset(seconds: Double): Float = ((seconds * scroll) % 1.0).toFloat()

    fun pulse(seconds: Double): Float = if (pulsePeriod == 0f) 1f else
        .67f + .33f * sin((seconds % pulsePeriod) / pulsePeriod * 2 * PI).toFloat()

    fun color(ink: InkColor, seconds: Double): InkColor {
        if (hueRate == 0f) return ink
        val value = maxOf(ink.r, ink.g, ink.b)
        val chroma = value - minOf(ink.r, ink.g, ink.b)
        if (value == 0f) return ink
        val hue = when {
            chroma == 0f -> 0f
            value == ink.r -> (ink.g - ink.b) / chroma
            value == ink.g -> (ink.b - ink.r) / chroma + 2f
            else -> (ink.r - ink.g) / chroma + 4f
        }
        return hueColor(hue / 6f + ((seconds * hueRate) % 1).toFloat(),
            (chroma / value).coerceAtLeast(.45f), value, ink.a)
    }

    companion object {
        internal val brushStyles = mapOf(
            "Fire" to BrushMaterialMotion(.79f, 1.4f, scroll = -.73f, pulsePeriod = .44f),
            "Fire2" to BrushMaterialMotion(.70f, 1.85f, scroll = .83f, pulsePeriod = .32f),
            "Rainbow" to BrushMaterialMotion(.84f, .78f, .20f, -.32f, pattern = BrushPattern.RAINBOW),
            "Streamers" to BrushMaterialMotion(.75f, .54f, .50f, .48f, hueRate = .08f),
            "Hypercolor" to BrushMaterialMotion(scroll = .18f, hueRate = .24f),
            "Neon Pulse" to BrushMaterialMotion(.85f, 1.65f, .01f, -.38f, .88f),
            "Light Wire" to BrushMaterialMotion(.92f, 1.30f, .06f, -.61f),
            "Disco" to BrushMaterialMotion(1f, .85f, scroll = .56f, pulsePeriod = .45f, pattern = BrushPattern.DISCO),
            "Comet" to BrushMaterialMotion(.70f, 1.24f, scroll = -.38f),
            "Rain" to BrushMaterialMotion(.63f, .29f, 1f, .95f),
            "Leaky Pen" to BrushMaterialMotion(tileRate = .48f, pattern = BrushPattern.INK_DROPS),
            "Sparks" to BrushMaterialMotion(.74f, 1.42f, scroll = .92f, pulsePeriod = .29f),
            "Wind" to BrushMaterialMotion(.55f, .42f, .4f, -.43f),
            "Wireframe" to BrushMaterialMotion(.78f, .68f, pulsePeriod = 1.1f, wireframe = true),
            "Tube Toon Inverted" to BrushMaterialMotion(pattern = BrushPattern.INVERTED_TOON),
            "Plasma" to BrushMaterialMotion(.71f, 1.20f, 1f, -.72f, .78f, .10f),
            "Tapered Hue Shift" to BrushMaterialMotion(hueRate = .20f),
            "Lacewing" to BrushMaterialMotion(tileRate = .20f),
            "Marbled Rainbow" to BrushMaterialMotion(tileRate = .20f, scroll = .09f, hueRate = .16f),
            "Keijiro Tube" to BrushMaterialMotion(.83f, .93f, .15f, -.35f, pattern = BrushPattern.RINGS),
            "Lofted (Hue Shift)" to BrushMaterialMotion(1f, .36f, hueRate = .18f),
            "Bubble Wand" to BrushMaterialMotion(.68f, .33f, .15f, .36f, 1.4f),
            "Waveform" to BrushMaterialMotion(.82f, 1.22f, scroll = -.28f),
            "Waveform FFT" to BrushMaterialMotion(.70f, 1f, scroll = .34f, hueRate = .07f),
            "Waveform Tube" to BrushMaterialMotion(.75f, 1.10f, .50f, -.45f),
            "Electricity" to BrushMaterialMotion(.85f, 1.6f, pulsePeriod = .22f),
            "Chromatic Wave" to BrushMaterialMotion(.82f, .96f, .10f, .30f, pattern = BrushPattern.RAINBOW),
            "Fairy" to BrushMaterialMotion(.78f, .92f, .24f, -.26f, .9f, pattern = BrushPattern.FAIRY),
            "Space" to BrushMaterialMotion(.69f, .85f, .18f, .08f, pattern = BrushPattern.SPACE),
            "Digital" to BrushMaterialMotion(.77f, .68f, .25f, -.55f, .57f, pattern = BrushPattern.DIGITAL),
            "Race" to BrushMaterialMotion(.73f, 1.25f, .21f, -.93f, pattern = BrushPattern.RACE)
        )
    }
}

internal fun hueColor(hue: Float, saturation: Float, value: Float, alpha: Float = 1f): InkColor {
    val wheel = (hue - floor(hue)) * 6f
    fun channel(offset: Float): Float {
        val sector = (wheel + offset) % 6f
        return value * (1f - saturation * maxOf(0f, minOf(sector, 4f - sector, 1f)))
    }
    return InkColor(channel(5f), channel(3f), channel(1f), alpha)
}
