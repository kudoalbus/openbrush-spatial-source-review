import Foundation
import simd

struct OpenBrushParticle {
    struct Pose {
        let position: SIMD3<Float>
        let scale: Float
        let rotation: Float
    }

    let stamp: OpenBrushStamp
    let phase: Float
    let progress: Float

    init(stamp: OpenBrushStamp, strokeLength: Float = 1) {
        self.stamp = stamp
        progress = stamp.arc / max(1e-6, strokeLength)
        // Key the phase to the unedited arc so splitting or erasing a stroke cannot reshuffle it.
        let hash = stamp.arc.bitPattern &* 2_654_435_761
        phase = Float(hash & 0xffff) / 65536
    }

    func pose(at time: TimeInterval, style: OpenBrushProfile.Particle, width: Float, audio: OpenBrushAudioFrame = .silent) -> Pose {
        let elapsed = Float(time.truncatingRemainder(dividingBy: Double(style.lifetime))) / style.lifetime
        let offset = elapsed + phase
        let age = offset - floor(offset)
        let angle = (age + phase) * 2 * .pi
        let sway = sin(angle)
        let depth = cos(angle)
        let envelope = smoothstep(0, 0.12, age) * (1 - smoothstep(0.77, 1, age))
        let motion: SIMD3<Float>
        let size: Float
        let rotation: Float
        switch style.motion {
        case .bubbles:
            motion = [sway * 0.7, age * 1.35, depth * 0.5]
            size = 0.65 + age * 0.48
            rotation = 0
        case .risingBubbles:
            motion = [sway * 0.32, age * 3.1, depth * 0.27]
            size = 0.62 + age * 0.52
            rotation = 0
        case .embers:
            motion = [sway * age, age * 2.7, depth * age]
            size = 1.05 - age * 0.72
            rotation = angle
        case .smoke:
            motion = [age * (phase - 0.5), age * 1.95, age * depth * 0.65]
            size = 0.68 + age * 1.12
            rotation = phase * 2 * .pi + age
        case .snow:
            motion = [sway * 0.54, -age * 2.25, depth * 0.43]
            size = 0.8 + phase * 0.25
            rotation = angle * 0.6
        case .stars:
            motion = [sway * 0.3, sin(angle * 2) * 0.22, depth * 0.26]
            size = 0.44 + 0.65 * pow(abs(sin(angle * 1.5)), 3)
            rotation = angle * 0.28
        case .waveform:
            let displacement = OpenBrushWave.waveform.displacement(progress: progress, time: time, audio: audio)
            motion = unit(stamp.right) * displacement + stamp.normal * (age * 0.48)
            size = 0.62 + abs(displacement) * 0.38
            rotation = age * .pi
        case .danceFloor:
            let beat = 0.14 + (sway + 1) * 0.12 + audio.band(progress) * 0.82
            return Pose(position: stamp.center + stamp.normal * beat * width, scale: 0.78 + beat * 0.34, rotation: 0)
        }
        return Pose(position: stamp.center + motion * width, scale: size * envelope, rotation: rotation)
    }

    private func smoothstep(_ lower: Float, _ upper: Float, _ value: Float) -> Float {
        let x = min(1, max(0, (value - lower) / (upper - lower)))
        return x * x * (3 - 2 * x)
    }
}
