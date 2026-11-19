import Accelerate
import AVFAudio
import Observation
import QuartzCore
import Synchronization

@MainActor @Observable
final class OpenBrushAudioInput {
    enum State { case off, requesting, active, denied, unavailable }

    static let shared = OpenBrushAudioInput()
    private(set) var state: State = .off
    var isEnabled: Bool { state == .active || state == .requesting }

    @ObservationIgnored private var engine: AVAudioEngine?
    @ObservationIgnored private var signal: OpenBrushAudioSignal?
    @ObservationIgnored private var interruption: (any NSObjectProtocol)?
    @ObservationIgnored private var configurationChange: (any NSObjectProtocol)?
    @ObservationIgnored private var generation = 0

    var frame: OpenBrushAudioFrame { signal?.frame ?? .silent }

    func setEnabled(_ enabled: Bool) {
        if !enabled {
            stop()
            return
        }
        guard !isEnabled else { return }
        generation += 1
        let request = generation
        state = .requesting
        Task {
            let granted = await AVAudioApplication.requestRecordPermission()
            guard request == generation else { return }
            guard granted else {
                state = .denied
                return
            }
            do {
                try start()
                state = .active
            } catch {
                stop()
                state = .unavailable
            }
        }
    }

    func stop() {
        generation += 1
        if let interruption { NotificationCenter.default.removeObserver(interruption) }
        if let configurationChange { NotificationCenter.default.removeObserver(configurationChange) }
        interruption = nil
        configurationChange = nil
        if let engine {
            engine.inputNode.removeTap(onBus: 0)
            engine.stop()
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
        engine = nil
        signal = nil
        state = .off
    }

    private func start() throws {
        let audioSession = AVAudioSession.sharedInstance()
        try audioSession.setCategory(.playAndRecord, mode: .measurement, options: [.mixWithOthers])
        try audioSession.setActive(true)
        let engine = AVAudioEngine()
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0, format.commonFormat == .pcmFormatFloat32,
              let analyzer = OpenBrushAudioAnalyzer() else {
            try? audioSession.setActive(false, options: .notifyOthersOnDeactivation)
            throw WorkspaceError.invalid("Microphone input unavailable")
        }
        let signal = OpenBrushAudioSignal()
        input.installTap(onBus: 0, bufferSize: 1024, format: format, block: signal.tap(using: analyzer))
        self.engine = engine
        self.signal = signal
        try engine.start()
        let activeGeneration = generation
        interruption = NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification,
                                                               object: audioSession, queue: nil) { [weak self] note in
            guard (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt) ==
                    AVAudioSession.InterruptionType.began.rawValue else { return }
            Task { @MainActor in
                guard let self, self.generation == activeGeneration else { return }
                self.stop()
            }
        }
        configurationChange = NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange,
                                                                     object: engine, queue: nil) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.generation == activeGeneration, self.state == .active else { return }
                self.stop()
                self.state = .unavailable
            }
        }
    }
}

private final class OpenBrushAudioSignal: Sendable {
    private struct Sample: Sendable {
        let frame: OpenBrushAudioFrame
        let time: TimeInterval
    }

    private struct State: Sendable {
        var samples = Array(repeating: Float.zero, count: OpenBrushAudioAnalyzer.windowSize)
        var filled = 0
        var latest: Sample?
    }

    private let state = Mutex(State())

    var frame: OpenBrushAudioFrame {
        let now = CACurrentMediaTime()
        return state.withLock { state in
            guard let sample = state.latest, now - sample.time < 0.25 else { return .silent }
            return sample.frame
        }
    }

    func tap(using analyzer: OpenBrushAudioAnalyzer) -> AVAudioNodeTapBlock {
        { buffer, _ in
            guard let channel = buffer.floatChannelData?[0] else { return }
            let stride = buffer.stride
            let length = Int(buffer.frameLength)
            let window: [Float]? = self.state.withLock { state in
                let needed = state.samples.count - state.filled
                let available = min(needed, length)
                let start = max(0, length - needed)
                for i in 0..<available { state.samples[state.filled + i] = channel[(start + i) * stride] }
                state.filled += available
                guard state.filled == state.samples.count else { return nil }
                state.filled = 0
                return state.samples
            }
            guard let window else { return }
            let frame = window.withUnsafeBufferPointer { analyzer.analyze($0) }
            let time = CACurrentMediaTime()
            self.state.withLock { $0.latest = Sample(frame: frame, time: time) }
        }
    }
}

// Accelerate's FFT plan is immutable after creation. Each call owns its work buffers.
final class OpenBrushAudioAnalyzer: @unchecked Sendable {
    static let windowSize = 512
    private static let logSize: vDSP_Length = 9
    private let setup: FFTSetup
    private let window: [Float]

    init?() {
        guard let setup = vDSP_create_fftsetup(Self.logSize, FFTRadix(kFFTRadix2)) else { return nil }
        self.setup = setup
        var window = Array(repeating: Float.zero, count: Self.windowSize)
        vDSP_hann_window(&window, vDSP_Length(window.count), Int32(vDSP_HANN_NORM))
        self.window = window
    }

    deinit { vDSP_destroy_fftsetup(setup) }

    func analyze(_ input: UnsafeBufferPointer<Float>) -> OpenBrushAudioFrame {
        let size = Self.windowSize
        let half = size / 2
        var real = Array(repeating: Float.zero, count: half)
        var imaginary = real
        var waveform = Array(repeating: Float.zero, count: 64)
        var energy: Float = 0
        for i in 0..<size {
            let sample = input[i]
            energy += sample * sample
            waveform[i / 8] += sample
            if i.isMultiple(of: 2) {
                real[i / 2] = sample * window[i]
            } else {
                imaginary[i / 2] = sample * window[i]
            }
        }
        real.withUnsafeMutableBufferPointer { real in
            imaginary.withUnsafeMutableBufferPointer { imaginary in
                var packed = DSPSplitComplex(realp: real.baseAddress!, imagp: imaginary.baseAddress!)
                vDSP_fft_zrip(setup, &packed, 1, Self.logSize, FFTDirection(FFT_FORWARD))
            }
        }
        var spectrum: [Float] = []
        for band in 0..<16 {
            let low = Int(pow(2, Float(band) / 2))
            let high = min(half, max(low + 1, Int(pow(2, Float(band + 1) / 2))))
            let peak = (low..<high).reduce(Float(0)) { max($0, hypot(real[$1], imaginary[$1])) }
            spectrum.append(min(1, peak * 4 / Float(size)))
        }
        return OpenBrushAudioFrame(waveform: waveform.map { max(-1, min(1, $0 * 0.5)) },
                                   spectrum: spectrum, level: min(1, sqrt(energy / Float(size)) * 5))
    }
}
