import Foundation
import simd

// Adapters supply a point in document meters. Pressure is optional because hand
// tracking observes joint positions and does not measure force.
struct SpatialInputSample: Sendable {
    var position: SIMD3<Float>
    var normal: SIMD3<Float> = [0, 0, 1]
    var timestamp: TimeInterval
    var pressure: Float? = nil
}

enum SpatialContact {
    case released, engaged, indeterminate
}

struct HandPinch {
    private var contact: SpatialContact = .indeterminate

    mutating func update(distance: Float) -> SpatialContact {
        if distance <= 0.022 { contact = .engaged }
        if distance >= 0.035 { contact = .released }
        return contact
    }
}

enum SpatialBrush: RawRepresentable, CaseIterable, Identifiable, Hashable {
    case tube, flat, fan
    case openBrush(String)

    static let allCases: [Self] = [.tube, .flat, .fan]

    init?(rawValue: String) {
        switch rawValue {
        case "spatial_tube": self = .tube
        case "spatial_flat": self = .flat
        case "spatial_fan": self = .fan
        default:
            guard let guid = UUID(uuidString: rawValue) else { return nil }
            self = .openBrush(guid.uuidString.lowercased())
        }
    }

    var rawValue: String {
        switch self {
        case .tube: "spatial_tube"
        case .flat: "spatial_flat"
        case .fan: "spatial_fan"
        case .openBrush(let guid): guid
        }
    }

    var id: Self { self }
    var label: String {
        switch self {
        case .tube: "Tube"
        case .flat: "Flat"
        case .fan: "Fan"
        case .openBrush(let guid): OpenBrushCatalog.bundled.profile(for: guid)?.name ?? guid
        }
    }
}

struct SpatialBrushStyle {
    var brush: SpatialBrush = .tube
    var width: Float = 0.012
    var color = InkColor(r: 0.12, g: 0.72, b: 1)
}

struct SpatialStrokeCapture {
    private var ready = false
    private var candidate: SpatialInputSample?
    private var active: PaintStroke?
    private var lastSample: SpatialInputSample?
    private var filtered: SIMD3<Float> = .zero
    private var startTime: TimeInterval = 0
    private var pointLimit = 0
    private var acceptsTap = false
    static let maxGap: TimeInterval = 0.18

    var preview: PaintStroke? {
        guard let active, active.points.count >= 2 else { return nil }
        return active
    }

    // A completed pinch is a single history transaction. Losing tracking commits
    // only the points already seen and requires a fresh release before drawing.
    mutating func update(_ sample: SpatialInputSample, contact: SpatialContact,
                         style: SpatialBrushStyle, availablePoints: Int, acceptsTap: Bool = false) -> PaintStroke? {
        guard sample.timestamp.isFinite, sample.timestamp >= 0,
              sample.position.x.isFinite, sample.position.y.isFinite, sample.position.z.isFinite
        else { return interrupt() }

        if contact == .released {
            let completed = interrupt()
            ready = true
            return completed
        }
        guard ready, contact == .engaged else { return nil }

        if let lastSample {
            let elapsed = sample.timestamp - lastSample.timestamp
            if elapsed <= 0 { return nil }
            if elapsed > Self.maxGap || simd_distance(lastSample.position, sample.position) > 0.18 {
                return interrupt()
            }
        }
        lastSample = sample

        if active == nil {
            guard availablePoints >= 2 else { return interrupt() }
            guard let first = candidate else {
                candidate = sample
                return nil
            }
            guard sample.timestamp - first.timestamp >= 0.035 else { return nil }
            self.acceptsTap = acceptsTap
            startTime = first.timestamp
            filtered = first.position
            pointLimit = min(DrawingCodec.maximumStrokePoints, availablePoints)
            active = PaintStroke(id: UUID().uuidString, brush: style.brush.rawValue,
                                 width: style.width, color: style.color,
                                 points: [point(first, at: first.position)], erased: [], recolored: [])
            candidate = nil
        }

        // Higher gain during fast movement keeps the tip responsive. A minimum
        // spacing suppresses jitter and bounds the growth of stationary strokes.
        let travel = simd_distance(filtered, sample.position)
        filtered += (sample.position - filtered) * min(0.88, 0.37 + travel * 18)
        if let last = active?.points.last, simd_distance(filtered, last.position) >= 0.0015 {
            let next = point(sample, at: filtered)
            active?.points.append(next)
        }
        return active?.points.count == pointLimit ? interrupt() : nil
    }

    mutating func interrupt() -> PaintStroke? {
        let completed = acceptsTap ? active : preview
        self = SpatialStrokeCapture()
        return completed
    }

    private func point(_ sample: SpatialInputSample, at position: SIMD3<Float>) -> PaintPoint {
        let pressure = sample.pressure ?? 1
        return PaintPoint(position: position, pressure: pressure.isFinite ? min(1, max(0, pressure)) : 1,
                          timeMs: Int64(min(Double(Int64.max / 2), (sample.timestamp - startTime) * 1000)),
                          normal: unit(sample.normal, fallback: [0, 0, 1]))
    }
}
