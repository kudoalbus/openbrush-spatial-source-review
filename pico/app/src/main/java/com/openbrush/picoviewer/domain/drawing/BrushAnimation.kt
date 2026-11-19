package com.openbrush.picoviewer.domain.drawing

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class BrushAudioFrame(
    private val waveform: FloatArray = FloatArray(0),
    private val spectrum: FloatArray = FloatArray(0),
    val level: Float = 0f
) {
    fun sample(progress: Float) = interpolate(waveform, progress)
    fun band(progress: Float) = interpolate(spectrum, progress)

    private fun interpolate(data: FloatArray, progress: Float): Float {
        if (data.isEmpty()) return 0f
        val index = progress.coerceIn(0f, 1f) * data.lastIndex
        val low = index.toInt()
        return data[low] + (data[minOf(low + 1, data.lastIndex)] - data[low]) * (index - low)
    }

    companion object { val SILENT = BrushAudioFrame() }
}

data class BrushAnimationFrame(val seconds: Double = 0.0, val audio: BrushAudioFrame = BrushAudioFrame.SILENT)

enum class BrushWave(val widthScale: Float) {
    WAVEFORM(.44f), FFT(.44f), TUBE(1f), ELECTRICITY(.21f), CHROMATIC(1f);

    fun displacement(progress: Float, frame: BrushAnimationFrame): Float {
        val clock = (frame.seconds % 100).toFloat()
        val audio = frame.audio
        val phase = progress * TAU
        return when (this) {
            WAVEFORM -> sin(phase * 4 - clock * TAU * .8f) * .18f + audio.sample(progress) * 1.4f
            FFT -> .16f * sin(phase * 8 - clock * TAU * .6f) + sqrt(audio.band(progress).coerceAtLeast(0f)) * .94f
            TUBE -> (.2f + .16f * sin(phase * 5 - clock * TAU)) * (1f + audio.level * 2)
            ELECTRICITY -> {
                val moving = progress * 11 - clock * 2.3f
                val zigzag = abs((moving - floor(moving)) * 4 - 2) - 1
                (.61f * zigzag + .16f * sin(phase * 19 + clock * TAU * 2)) * (.7f + audio.level)
            }
            CHROMATIC -> sin(phase * 3 - clock * TAU * .7f) * (.46f + audio.level * .9f)
        }
    }
}

enum class BrushParticle {
    BUBBLES, RISING_BUBBLES, EMBERS, SMOKE, SNOW, STARS, WAVEFORM, DANCE_FLOOR;

    internal data class Pose(val offset: V3, val scale: Float, val angle: Float = 0f)

    internal fun pose(arc: Float, length: Float, lifetime: Float, right: V3, normal: V3, frame: BrushAnimationFrame): Pose {
        val phase = (arc.toRawBits() * 0x9e3779b1u.toInt() and 0xffff) / 65536f
        val progress = arc / length
        val elapsed = ((frame.seconds % lifetime) / lifetime).toFloat() + phase
        val age = elapsed - floor(elapsed)
        val angle = (age + phase) * TAU
        val sway = sin(angle)
        val depth = cos(angle)
        val pose = when (this) {
            BUBBLES -> Pose(V3(sway * .70f, age * 1.35f, depth * .50f), .65f + age * .48f)
            RISING_BUBBLES -> Pose(V3(sway * .32f, age * 3.1f, depth * .27f), .62f + age * .52f)
            EMBERS -> Pose(V3(sway * age, age * 2.7f, depth * age), 1.05f - age * .72f, angle)
            SMOKE -> Pose(V3(age * (phase - .5f), age * 1.95f, age * depth * .65f), .68f + age * 1.12f, phase * TAU + age)
            SNOW -> Pose(V3(sway * .54f, -age * 2.25f, depth * .43f), .80f + phase * .25f, angle * .6f)
            STARS -> Pose(V3(sway * .30f, sin(angle * 2) * .22f, depth * .26f),
                .44f + .65f * abs(sin(angle * 1.5f)).pow(3), angle * .28f)
            WAVEFORM -> {
                val displacement = BrushWave.WAVEFORM.displacement(progress, frame)
                Pose(right * displacement + normal * (age * .48f), .62f + abs(displacement) * .38f, age * TAU * .5f)
            }
            DANCE_FLOOR -> {
                val beat = .14f + (sway + 1) * .12f + frame.audio.band(progress) * .82f
                return Pose(normal * beat, .78f + beat * .34f)
            }
        }
        fun smoothstep(start: Float, end: Float): Float {
            val x = ((age - start) / (end - start)).coerceIn(0f, 1f)
            return x * x * (3 - 2 * x)
        }
        return pose.copy(scale = pose.scale * smoothstep(0f, .12f) * (1 - smoothstep(.77f, 1f)))
    }
}

private val TAU = (2 * PI).toFloat()
