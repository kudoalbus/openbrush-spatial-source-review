import Foundation
import simd

struct InkColor: Hashable, Sendable {
    var r: Float
    var g: Float
    var b: Float
    var a: Float = 1
    var components: [Float] { [r, g, b, a] }
}

struct ArcSpan: Equatable, Sendable {
    var start: Float
    var end: Float
    func intersection(_ other: ArcSpan) -> ArcSpan? {
        let lo = max(start, other.start), hi = min(end, other.end)
        return hi - lo > 1e-6 ? ArcSpan(start: lo, end: hi) : nil
    }
}

struct ColorSpan: Equatable, Sendable {
    var span: ArcSpan
    var color: InkColor
}

struct PaintPoint: Equatable, Sendable {
    var position: SIMD3<Float>
    var pressure: Float
    var timeMs: Int64
    var normal: SIMD3<Float>

    func interpolated(to other: PaintPoint, t: Float) -> PaintPoint {
        if t <= 0 { return self }
        if t >= 1 { return other }
        let time = Double(timeMs) * (1 - Double(t)) + Double(other.timeMs) * Double(t)
        let boundedTime = time >= Double(Int64.max) ? Int64.max : Int64(max(0, time))
        return PaintPoint(position: position + (other.position - position) * t,
                   pressure: pressure + (other.pressure - pressure) * t,
                   timeMs: boundedTime,
                   normal: unit(normal + (other.normal - normal) * t, fallback: unit(normal)))
    }
}

func unit(_ vector: SIMD3<Float>, fallback: SIMD3<Float> = SIMD3(1, 0, 0)) -> SIMD3<Float> {
    let length = simd_length(vector)
    return length > 1e-7 ? vector / length : fallback
}

struct PaintStroke: Equatable, Sendable {
    var id: String
    var brush: String
    var width: Float
    var color: InkColor
    var points: [PaintPoint]
    // Keep the original document intervals. Normalization is a derived operation only.
    var erased: [ArcSpan]
    var recolored: [ColorSpan]

    var distances: [Float] {
        guard !points.isEmpty else { return [] }
        var result: [Float] = [0]
        for index in 1..<points.count {
            result.append(result.last! + simd_distance(points[index - 1].position, points[index].position))
        }
        return result
    }
    var length: Float { distances.last ?? 0 }
    func radius(_ pressure: Float) -> Float {
        width * 0.5 * (0.2 + 0.8 * min(1, max(0, pressure))) * (brush == "spatial_fan" ? 1.4 : 1)
    }
    var visibleSpans: [ArcSpan] {
        var cursor: Float = 0
        var result: [ArcSpan] = []
        let extent = length
        for cut in mergedSpans(erased, length: extent) {
            if cut.start > cursor + 1e-6 { result.append(ArcSpan(start: cursor, end: cut.start)) }
            cursor = max(cursor, cut.end)
        }
        if extent > cursor + 1e-6 { result.append(ArcSpan(start: cursor, end: extent)) }
        return result
    }
    func point(at distance: Float, arcs suppliedArcs: [Float]? = nil) -> PaintPoint {
        let arcs = suppliedArcs ?? distances
        let d = min(max(0, distance), arcs.last!)
        let upper = boundary(in: arcs) { $0 >= d }
        guard upper > 0 else { return points[0] }
        let extent = arcs[upper] - arcs[upper - 1]
        if extent < 1e-8 { return points[upper] }
        return points[upper - 1].interpolated(to: points[upper], t: (d - arcs[upper - 1]) / extent)
    }

    func fragment(_ span: ArcSpan, arcs suppliedArcs: [Float]? = nil) -> [PaintPoint] {
        let arcs = suppliedArcs ?? distances
        let indices = fragmentIndices(span, arcs: arcs)
        return [point(at: span.start, arcs: arcs)] + points[indices] + [point(at: span.end, arcs: arcs)]
    }

    func fragmentDistances(_ span: ArcSpan, arcs: [Float]) -> [Float] {
        [span.start] + arcs[fragmentIndices(span, arcs: arcs)] + [span.end]
    }

    private func fragmentIndices(_ span: ArcSpan, arcs: [Float]) -> Range<Int> {
        let first = boundary(in: arcs) { $0 > span.start + 1e-6 }
        let end = boundary(in: arcs) { $0 >= span.end - 1e-6 }
        return first..<max(first, end)
    }

    private func boundary(in values: [Float], where predicate: (Float) -> Bool) -> Int {
        var low = 0, high = values.count
        while low < high {
            let middle = low + (high - low) / 2
            if predicate(values[middle]) { high = middle }
            else { low = middle + 1 }
        }
        return low
    }

}

struct DrawingDocument: Equatable, Sendable {
    var strokes: [PaintStroke]
}

func mergedSpans(_ spans: [ArcSpan], length: Float) -> [ArcSpan] {
    let ordered = spans.map { ArcSpan(start: min(length, max(0, $0.start)), end: min(length, max(0, $0.end))) }
        .filter { $0.end - $0.start > 1e-6 }.sorted { $0.start < $1.start }
    var result: [ArcSpan] = []
    for span in ordered {
        if let last = result.last, span.start <= last.end + 1e-6 {
            result[result.count - 1].end = max(last.end, span.end)
        } else { result.append(span) }
    }
    return result
}
