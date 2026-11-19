import simd

enum SpatialTool: String, CaseIterable, Identifiable, Sendable {
    case draw, select, erase, recolor
    var id: Self { self }
    var label: String { rawValue.capitalized }
}

enum SpatialStrokeEdit {
    static func select(in document: DrawingDocument, at position: SIMD3<Float>) -> String? {
        var best: (id: String, distance: Float)?
        for stroke in document.strokes where stroke.color.a > 0 {
            let hits = contacts(stroke, from: position, to: position, radius: 0.023)
            let visible = stroke.visibleSpans
            for hit in hits {
                for part in visible {
                    guard let span = hit.intersection(part) else { continue }
                    let distance = simd_distance(stroke.point(at: (span.start + span.end) / 2).position, position)
                    if distance < (best?.distance ?? .infinity) { best = (stroke.id, distance) }
                }
            }
        }
        return best?.id
    }

    /// Bounds reject untouched strokes before the swept-sphere calculation.
    /// The result is committed to history once for the whole hand gesture.
    static func edits(_ tool: SpatialTool, in strokes: [PaintStroke], along path: PaintStroke) -> [PaintStroke] {
        guard tool == .erase || tool == .recolor, let first = path.points.first else { return [] }
        let low = path.points.reduce(first.position) { simd_min($0, $1.position) }
        let high = path.points.reduce(first.position) { simd_max($0, $1.position) }
        return strokes.compactMap { stroke in
            guard let start = stroke.points.first, stroke.color.a > 0 else { return nil }
            let reach = SIMD3(repeating: max(0.012, path.width * 0.5) + stroke.radius(1))
            let minPoint = stroke.points.reduce(start.position) { simd_min($0, $1.position) } - reach
            let maxPoint = stroke.points.reduce(start.position) { simd_max($0, $1.position) } + reach
            guard maxPoint.x >= low.x, maxPoint.y >= low.y, maxPoint.z >= low.z,
                  minPoint.x <= high.x, minPoint.y <= high.y, minPoint.z <= high.z else { return nil }
            let edit = apply(tool, to: stroke, along: path)
            return edit != stroke ? edit : nil
        }
    }

    static func apply(_ tool: SpatialTool, to original: PaintStroke, along path: PaintStroke) -> PaintStroke {
        guard tool == .erase || tool == .recolor, !path.points.isEmpty else { return original }
        let radius = max(0.012, path.width * 0.5)
        let arcs = original.distances
        let length = arcs.last ?? 0
        var cuts: [ArcSpan] = []
        for start in stride(from: 0, to: path.points.count, by: 32) {
            let batch = (start..<min(start + 32, path.points.count)).flatMap { i in
                contacts(original, arcs: arcs, from: path.points[max(0, i - 1)].position,
                         to: path.points[i].position, radius: radius)
            }
            if !batch.isEmpty { cuts = mergedSpans(cuts + batch, length: length) }
        }
        guard !cuts.isEmpty else { return original }
        if tool == .recolor {
            return cuts.reduce(original) { StrokeAppearance.recolor($0, in: $1, color: path.color) }
        }

        let visible = original.visibleSpans
        guard cuts.contains(where: { cut in visible.contains { cut.intersection($0) != nil } }) else { return original }
        let erased = mergedSpans(original.erased + cuts, length: length)
        guard erased.count <= 20_000 else { return original }
        var stroke = original
        stroke.erased = erased
        return stroke
    }

    // A swept sphere (capsule) clips centerline segments even when the two
    // control points lie outside contact. Add the stroke's local brush radius.
    static func contacts(_ stroke: PaintStroke, from: SIMD3<Float>, to: SIMD3<Float>, radius: Float) -> [ArcSpan] {
        contacts(stroke, arcs: stroke.distances, from: from, to: to, radius: radius)
    }

    private static func contacts(_ stroke: PaintStroke, arcs: [Float],
                                 from: SIMD3<Float>, to: SIMD3<Float>, radius: Float) -> [ArcSpan] {
        guard stroke.points.count >= 2 else { return [] }
        let low = simd_min(from, to)
        let high = simd_max(from, to)
        var result: [ArcSpan] = []
        for i in 1..<stroke.points.count {
            let a = stroke.points[i - 1], b = stroke.points[i]
            let distance = arcs[i] - arcs[i - 1]
            guard distance > 1e-7 else { continue }
            let reach = radius + max(stroke.radius(a.pressure), stroke.radius(b.pressure))
            let upper = simd_max(a.position, b.position) + SIMD3(repeating: reach)
            let lower = simd_min(a.position, b.position) - SIMD3(repeating: reach)
            guard upper.x >= low.x, upper.y >= low.y, upper.z >= low.z,
                  lower.x <= high.x, lower.y <= high.y, lower.z <= high.z else { continue }
            for span in capsule(a.position, b.position, from, to, reach) {
                result.append(ArcSpan(start: arcs[i - 1] + distance * span.start,
                                      end: arcs[i - 1] + distance * span.end))
            }
        }
        return result
    }

    private static func capsule(_ a: SIMD3<Float>, _ b: SIMD3<Float>, _ from: SIMD3<Float>,
                                _ to: SIMD3<Float>, _ radius: Float) -> [ArcSpan] {
        let axis = to - from, delta = b - a, offset = a - from
        let lengthSquared = simd_length_squared(axis)
        guard lengthSquared > 1e-12 else {
            return inside(offset, delta, radius, 0, 1).map { [$0] } ?? []
        }
        let q0 = simd_dot(offset, axis) / lengthSquared
        let qd = simd_dot(delta, axis) / lengthSquared
        var boundaries: [Float] = [0, 1]
        if abs(qd) > 1e-10 {
            for cap: Float in [0, 1] {
                let t = (cap - q0) / qd
                if t > 0 && t < 1 { boundaries.append(t) }
            }
        }
        boundaries.sort()
        return zip(boundaries, boundaries.dropFirst()).compactMap { lo, hi in
            let projection = q0 + qd * (lo + hi) * 0.5
            if projection < 0 { return inside(offset, delta, radius, lo, hi) }
            if projection > 1 { return inside(a - to, delta, radius, lo, hi) }
            return inside(offset - axis * q0, delta - axis * qd, radius, lo, hi)
        }
    }

    private static func inside(_ offset: SIMD3<Float>, _ delta: SIMD3<Float>, _ radius: Float,
                               _ lo: Float, _ hi: Float) -> ArcSpan? {
        let aa = Double(simd_length_squared(delta))
        let bb = 2 * Double(simd_dot(offset, delta))
        let cc = Double(simd_length_squared(offset)) - Double(radius) * Double(radius)
        if aa < 1e-15 { return cc <= 0 ? ArcSpan(start: lo, end: hi) : nil }
        let discriminant = bb * bb - 4 * aa * cc
        guard discriminant >= 0 else { return nil }
        let root = discriminant.squareRoot()
        let start = max(lo, Float((-bb - root) / (2 * aa)))
        let end = min(hi, Float((-bb + root) / (2 * aa)))
        return end - start > 1e-6 ? ArcSpan(start: start, end: end) : nil
    }
}
