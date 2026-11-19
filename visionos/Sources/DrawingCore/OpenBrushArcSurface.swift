import simd

enum OpenBrushArcSurface {
    struct Sample {
        let position: SIMD3<Float>
        let arc: Float
    }

    struct Vertex {
        let sample: Sample
        let normal: SIMD3<Float>

        func interpolated(to other: Self, at arc: Float) -> Self {
            let t = (arc - sample.arc) / (other.sample.arc - sample.arc)
            return Self(sample: .init(position: simd_mix(sample.position, other.sample.position, SIMD3(repeating: t)), arc: arc),
                        normal: unit(simd_mix(normal, other.normal, SIMD3(repeating: t)), fallback: normal))
        }
    }

    static func groups(_ parts: [DisplayPart]) -> [Range<Int>] {
        var result: [Range<Int>] = []
        var start = 0
        while start < parts.count {
            var end = start + 1
            while end < parts.count, parts[end].span.start <= parts[end - 1].span.end + 1e-6 { end += 1 }
            result.append(start..<end)
            start = end
        }
        return result
    }

    static func append(_ triangle: [Vertex], to mesh: inout StrokeMesh, in span: ArcSpan, includesUpperBoundary: Bool) {
        let distances = triangle.map(\.sample.arc)
        guard let minimum = distances.min(), let maximum = distances.max(),
              maximum >= span.start, minimum <= span.end else { return }
        if minimum == span.end && !includesUpperBoundary { return }
        let afterStart = clip(triangle, at: span.start) { $0 >= span.start }
        let polygon = clip(afterStart, at: span.end) { $0 <= span.end }
        guard polygon.count >= 3 else { return }
        let base = UInt32(mesh.positions.count)
        mesh.positions += polygon.map(\.sample.position)
        mesh.normals += polygon.map(\.normal)
        for i in 1..<(polygon.count - 1) { mesh.indices += [base, base + UInt32(i), base + UInt32(i + 1)] }
    }

    private static func clip(_ polygon: [Vertex], at boundary: Float, keeps: (Float) -> Bool) -> [Vertex] {
        guard var previous = polygon.last else { return [] }
        var output: [Vertex] = []
        for vertex in polygon {
            let before = keeps(previous.sample.arc)
            let after = keeps(vertex.sample.arc)
            if before != after && previous.sample.arc != boundary && vertex.sample.arc != boundary {
                output.append(previous.interpolated(to: vertex, at: boundary))
            }
            if after { output.append(vertex) }
            previous = vertex
        }
        return output
    }
}
