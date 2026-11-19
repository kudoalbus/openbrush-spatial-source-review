import Foundation

struct OpenBrushMaterialStyle: Sendable {
    enum Pattern: Hashable, Sendable {
        case rainbow, disco, inkDrops, luminousRings, invertedToon, fairy, space, digital, race
    }

    var effect: OpenBrushProfile.Effect? = nil
    var tileRate: Float? = nil
    var scroll: Float = 0
    var pulsePeriod: Float = 0
    var hueRate: Float = 0
    var pattern: Pattern? = nil
    var luminanceMask = false
    var wireframe = false

    var isAnimated: Bool { scroll != 0 || pulsePeriod > 0 || hueRate != 0 }

    func offset(at time: TimeInterval) -> Float {
        Float((time * Double(scroll)).truncatingRemainder(dividingBy: 1))
    }

    func pulse(at time: TimeInterval) -> Float {
        guard pulsePeriod > 0 else { return 1 }
        let phase = Float(time.truncatingRemainder(dividingBy: Double(pulsePeriod))) / pulsePeriod
        return 0.67 + 0.33 * sin(phase * 2 * .pi)
    }

    func hueOffset(at time: TimeInterval) -> Float {
        Float((time * Double(hueRate)).truncatingRemainder(dividingBy: 1))
    }

    // Preserve the pinned textures and descriptor tiling. Speeds and emission use RealityKit units.
    static let brushes: [String: Self] = [
        "Fire": .init(effect: .init(opacity: 0.79, emission: 1.4), scroll: -0.73, pulsePeriod: 0.44),
        "Fire2": .init(effect: .init(opacity: 0.70, emission: 1.85), scroll: 0.83, pulsePeriod: 0.32, luminanceMask: true),
        "Rainbow": .init(effect: .init(opacity: 0.84, emission: 0.78), tileRate: 0.20, scroll: -0.32, pattern: .rainbow),
        "Streamers": .init(effect: .init(opacity: 0.75, emission: 0.54), tileRate: 0.50, scroll: 0.48, hueRate: 0.08),
        "Hypercolor": .init(scroll: 0.18, hueRate: 0.24),
        "Neon Pulse": .init(effect: .init(opacity: 0.85, emission: 1.65), tileRate: 0.01, scroll: -0.38, pulsePeriod: 0.88),
        "Light Wire": .init(effect: .init(opacity: 0.92, emission: 1.30), tileRate: 0.06, scroll: -0.61, luminanceMask: true),
        "Disco": .init(effect: .init(opacity: 1, emission: 0.85), scroll: 0.56, pulsePeriod: 0.45, pattern: .disco),
        "Comet": .init(effect: .init(opacity: 0.70, emission: 1.24), scroll: -0.38, luminanceMask: true),
        "Rain": .init(effect: .init(opacity: 0.63, emission: 0.29), tileRate: 1, scroll: 0.95, luminanceMask: true),
        "Leaky Pen": .init(tileRate: 0.48, pattern: .inkDrops),
        "Sparks": .init(effect: .init(opacity: 0.74, emission: 1.42), scroll: 0.92, pulsePeriod: 0.29, luminanceMask: true),
        "Wind": .init(effect: .init(opacity: 0.55, emission: 0.42), tileRate: 0.4, scroll: -0.43, luminanceMask: true),
        "Wireframe": .init(effect: .init(opacity: 0.78, emission: 0.68), pulsePeriod: 1.1, wireframe: true),
        "Tube Toon Inverted": .init(pattern: .invertedToon),
        "Plasma": .init(effect: .init(opacity: 0.71, emission: 1.20), tileRate: 1, scroll: -0.72,
                        pulsePeriod: 0.78, hueRate: 0.10, luminanceMask: true),
        "Tapered Hue Shift": .init(hueRate: 0.20),
        "Lacewing": .init(tileRate: 0.20),
        "Marbled Rainbow": .init(tileRate: 0.20, scroll: 0.09, hueRate: 0.16),
        "Keijiro Tube": .init(effect: .init(opacity: 0.83, emission: 0.93), tileRate: 0.15,
                             scroll: -0.35, pattern: .luminousRings),
        "Lofted (Hue Shift)": .init(effect: .init(opacity: 1, emission: 0.36), hueRate: 0.18),
        "Bubble Wand": .init(effect: .init(opacity: 0.68, emission: 0.33), tileRate: 0.15,
                             scroll: 0.36, pulsePeriod: 1.4, luminanceMask: true),
        "Waveform": .init(effect: .init(opacity: 0.82, emission: 1.22), scroll: -0.28),
        "Waveform FFT": .init(effect: .init(opacity: 0.70, emission: 1.0), scroll: 0.34, hueRate: 0.07),
        "Waveform Tube": .init(effect: .init(opacity: 0.75, emission: 1.1), tileRate: 0.5, scroll: -0.45),
        "Electricity": .init(effect: .init(opacity: 0.85, emission: 1.6), pulsePeriod: 0.22),
        "Chromatic Wave": .init(effect: .init(opacity: 0.82, emission: 0.96), tileRate: 0.1, scroll: 0.30, pattern: .rainbow),
        "Fairy": .init(effect: .init(opacity: 0.78, emission: 0.92), tileRate: 0.24, scroll: -0.26,
                       pulsePeriod: 0.9, pattern: .fairy),
        "Space": .init(effect: .init(opacity: 0.69, emission: 0.85), tileRate: 0.18, scroll: 0.08, pattern: .space),
        "Digital": .init(effect: .init(opacity: 0.77, emission: 0.68), tileRate: 0.25, scroll: -0.55,
                         pulsePeriod: 0.57, pattern: .digital),
        "Race": .init(effect: .init(opacity: 0.73, emission: 1.25), tileRate: 0.21, scroll: -0.93, pattern: .race)
    ]
}
