// Directed-sphere samples reference the Apache-2.0 Open Brush HullBrush.
import Foundation
import simd

struct OpenBrushHull {
    private typealias Sample = OpenBrushArcSurface.Sample
    private typealias Vertex = OpenBrushArcSurface.Vertex
    private struct Face {
        var a, b, c: Int
        let normal: SIMD3<Float>
        var edges: [Edge] { [Edge(a, b), Edge(b, c), Edge(c, a)] }
    }
    private struct Edge: Hashable {
        let a, b: Int
        init(_ a: Int, _ b: Int) {
            self.a = min(a, b)
            self.b = max(a, b)
        }
    }

    private let samples: [Sample]
    private let faces: [Face]
    private let smoothNormals: [SIMD3<Float>]?
    private static let epsilon: Float = 1e-6

    static func meshes(stroke: PaintStroke, parts: [DisplayPart], arcs: [Float], profile: OpenBrushProfile) -> [StrokeMesh] {
        var result: [StrokeMesh] = []
        // A gap in the visible arc gets independent hulls. Adjacent colors clip the same hull.
        for group in OpenBrushArcSurface.groups(parts) {
            let span = ArcSpan(start: parts[group.lowerBound].span.start, end: parts[group.upperBound - 1].span.end)
            let hulls: [Self]
            if profile.name == "Concave Hull" {
                hulls = quillHulls(stroke, in: span, arcs: arcs, profile: profile)
            } else {
                let samples = sampleStroke(stroke, in: span, arcs: arcs, profile: profile)
                hulls = [build(samples, scale: max(stroke.width, span.end - span.start),
                               smooth: profile.name == "Smooth Hull")].compactMap { $0 }
            }
            for i in group {
                var mesh = StrokeMesh()
                for hull in hulls {
                    hull.append(to: &mesh, in: parts[i].span, includesUpperBoundary: i == group.upperBound - 1)
                }
                result.append(mesh)
            }
        }
        return result
    }

    private static func sampleStroke(_ stroke: PaintStroke, in span: ArcSpan,
                                     arcs: [Float], profile: OpenBrushProfile) -> [Sample] {
        let points = stroke.fragment(span, arcs: arcs)
        let distances = stroke.fragmentDistances(span, arcs: arcs)
        // Limit the geometric work per hull without truncating the stroke or altering its saved points.
        let count = min(96, points.count)
        let cutAtEnd = span.end < (arcs.last ?? 0)
        var samples = [Sample(position: points[0].position, arc: distances[0])]
        for knot in 1..<count {
            let i = knot * (points.count - 1) / (count - 1)
            let direction = points[i].position - points[i - 1].position
            guard simd_length_squared(direction) > 1e-12 else { continue }
            let tangent = unit(direction)
            let axis = abs(tangent.y) < 0.9 ? SIMD3<Float>(0, 1, 0) : SIMD3<Float>(1, 0, 0)
            let side = unit(simd_cross(tangent, points[i].normal), fallback: unit(simd_cross(tangent, axis)))
            let up = simd_cross(side, tangent)
            let radius = stroke.width * 0.5 * (profile.pressureMin + (1 - profile.pressureMin) * points[i].pressure)
            let center = points[i].position
            let arc = distances[i]
            let frontLimit = cutAtEnd ? max(0, span.end - arc) : radius
            samples.append(Sample(position: center + tangent * min(radius, frontLimit), arc: arc))
            for angle: Float in [.pi / 4, .pi / 2] {
                let axial = min(radius * max(0, cos(angle)), frontLimit)
                for radial in [side, up, -side, -up] {
                    let point = center + tangent * axial + radial * (sin(angle) * radius)
                    samples.append(Sample(position: point, arc: arc))
                }
            }
        }
        return samples
    }

    private static func quillHulls(_ stroke: PaintStroke, in span: ArcSpan, arcs: [Float],
                                   profile: OpenBrushProfile) -> [Self] {
        let points = stroke.fragment(span, arcs: arcs)
        let distances = stroke.fragmentDistances(span, arcs: arcs)
        let count = min(96, points.count)
        let knotSamples = (0..<count).map { knot -> [Sample] in
            let i = knot * (points.count - 1) / (count - 1)
            let point = points[i]
            let tangent = unit(points[min(i + 1, points.count - 1)].position - points[max(i - 1, 0)].position)
            let reference = abs(tangent.y) < 0.9 ? SIMD3<Float>(0, 1, 0) : SIMD3<Float>(1, 0, 0)
            let right = unit(simd_cross(tangent, point.normal), fallback: unit(simd_cross(tangent, reference)))
            let radius = stroke.width * 0.5 * (profile.pressureMin + (1 - profile.pressureMin) * point.pressure)
            let width = right * radius
            // A small thickness allows the five-knot quill hull to render for coplanar hand samples.
            let thickness = unit(simd_cross(right, tangent)) * radius * 0.02
            return [-width + thickness, width + thickness, -width - thickness, width - thickness].map {
                Sample(position: point.position + $0, arc: distances[i])
            }
        }
        return (1..<count).compactMap { end in
            let start = max(0, end - 4)
            let samples = knotSamples[start...end].flatMap { $0 }
            let length = samples.last!.arc - samples[0].arc
            return build(samples, scale: max(stroke.width, length), smooth: false)
        }
    }

    private static func build(_ samples: [Sample], scale: Float, smooth: Bool) -> Self? {
        guard samples.count >= 4 else { return nil }
        let origin = samples[0].position
        let points = samples.map { ($0.position - origin) / scale }
        let indices = points.indices
        guard let b = indices.max(by: { simd_length_squared(points[$0]) < simd_length_squared(points[$1]) }),
              simd_length_squared(points[b]) > epsilon * epsilon else { return nil }
        let axis = unit(points[b])
        guard let c = indices.max(by: {
            simd_length_squared(simd_cross(axis, points[$0])) < simd_length_squared(simd_cross(axis, points[$1]))
        }) else { return nil }
        let plane = simd_cross(axis, points[c])
        guard simd_length_squared(plane) > epsilon * epsilon else { return nil }
        let normal = unit(plane)
        guard let d = indices.max(by: { abs(simd_dot(normal, points[$0])) < abs(simd_dot(normal, points[$1])) }),
              abs(simd_dot(normal, points[d])) > epsilon else { return nil }
        let interior = (points[b] + points[c] + points[d]) / 4

        func face(_ a: Int, _ b: Int, _ c: Int) -> Face? {
            let raw = simd_cross(points[b] - points[a], points[c] - points[a])
            let length = simd_length(raw)
            guard length > epsilon * epsilon else { return nil }
            let n = raw / length
            if simd_dot(n, interior - points[a]) > 0 { return Face(a: a, b: c, c: b, normal: -n) }
            return Face(a: a, b: b, c: c, normal: n)
        }

        let seed: Set<Int> = [0, b, c, d]
        var faces = [face(0, b, c), face(0, b, d), face(0, c, d), face(b, c, d)].compactMap { $0 }
        for i in indices where !seed.contains(i) {
            var horizon = Set<Edge>()
            faces.removeAll { triangle in
                guard simd_dot(triangle.normal, points[i] - points[triangle.a]) > epsilon else { return false }
                for edge in triangle.edges {
                    if !horizon.insert(edge).inserted { horizon.remove(edge) }
                }
                return true
            }
            for edge in horizon.sorted(by: { ($0.a, $0.b) < ($1.a, $1.b) }) {
                if let triangle = face(edge.a, edge.b, i) { faces.append(triangle) }
            }
        }
        var normals: [SIMD3<Float>]? = nil
        if smooth {
            var totals = Array(repeating: SIMD3<Float>.zero, count: points.count)
            for f in faces {
                let weighted = simd_cross(points[f.b] - points[f.a], points[f.c] - points[f.a])
                for index in [f.a, f.b, f.c] { totals[index] += weighted }
            }
            normals = totals.map { unit($0) }
        }
        return Self(samples: samples, faces: faces, smoothNormals: normals)
    }

    private func append(to mesh: inout StrokeMesh, in span: ArcSpan, includesUpperBoundary: Bool) {
        for face in faces {
            let triangle = [face.a, face.b, face.c].map {
                Vertex(sample: samples[$0], normal: smoothNormals?[$0] ?? face.normal)
            }
            OpenBrushArcSurface.append(triangle, to: &mesh, in: span, includesUpperBoundary: includesUpperBoundary)
        }
    }
}
