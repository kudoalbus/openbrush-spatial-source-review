import Foundation

struct OpenBrushAudioFrame: Sendable {
    var waveform: [Float] = []
    var spectrum: [Float] = []
    var level: Float = 0

    static let silent = Self()

    func sample(_ progress: Float) -> Float { interpolate(waveform, at: progress) }
    func band(_ progress: Float) -> Float { interpolate(spectrum, at: progress) }

    private func interpolate(_ data: [Float], at progress: Float) -> Float {
        guard !data.isEmpty else { return 0 }
        let index = max(0, min(1, progress)) * Float(data.count - 1)
        let low = Int(index)
        let high = min(low + 1, data.count - 1)
        return data[low] + (data[high] - data[low]) * (index - Float(low))
    }
}

enum OpenBrushWave: Equatable, Sendable {
    case waveform, fft, tube, electricity, chromatic

    var widthScale: Float {
        switch self {
        case .electricity: 0.21
        case .waveform, .fft: 0.44
        case .tube, .chromatic: 1
        }
    }

    var expandsTube: Bool { self == .tube }

    func displacement(progress: Float, time: TimeInterval, audio: OpenBrushAudioFrame) -> Float {
        // Reduce the clock before narrowing to Float so long sessions keep smooth motion.
        let clock = Float(time.truncatingRemainder(dividingBy: 100))
        let phase = progress * 2 * Float.pi
        switch self {
        case .waveform:
            return sin(phase * 4 - clock * 2 * .pi * 0.8) * 0.18 + audio.sample(progress) * 1.4
        case .fft:
            return 0.16 * sin(phase * 8 - clock * 2 * .pi * 0.6) + sqrt(max(0, audio.band(progress))) * 0.94
        case .tube:
            return (0.2 + 0.16 * sin(phase * 5 - clock * 2 * .pi)) * (1 + audio.level * 2)
        case .electricity:
            let clockPhase = progress * 11 - clock * 2.3
            let zigzag = abs((clockPhase - floor(clockPhase)) * 4 - 2) - 1
            return (0.61 * zigzag + 0.16 * sin(phase * 19 + clock * 4 * .pi)) * (0.7 + audio.level)
        case .chromatic:
            return sin(phase * 3 - clock * 2 * .pi * 0.7) * (0.46 + audio.level * 0.9)
        }
    }

    static let brushes: [String: Self] = [
        "Waveform": .waveform, "Waveform FFT": .fft, "Waveform Tube": .tube,
        "Electricity": .electricity, "Chromatic Wave": .chromatic
    ]
}
