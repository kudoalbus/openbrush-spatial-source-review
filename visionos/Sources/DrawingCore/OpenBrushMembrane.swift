import simd

enum OpenBrushMembrane {
    typealias Sample = OpenBrushArcSurface.Sample
    typealias Vertex = OpenBrushArcSurface.Vertex

    struct Layers {
        var fill = StrokeMesh()
        var outline = StrokeMesh()
    }

    static func meshes(stroke: PaintStroke, parts: [DisplayPart], arcs: [Float], profile: OpenBrushProfile) -> [Layers] {
        var result = Array(repeating: Layers(), count: parts.count)
        for group in OpenBrushArcSurface.groups(parts) {
            let span = ArcSpan(start: parts[group.lowerBound].span.start, end: parts[group.upperBound - 1].span.end)
            let samples = boundary(stroke, in: span, arcs: arcs)
            let filled = triangles(samples, scale: max(stroke.width, span.end - span.start),
                                   faceted: profile.name == "Diamond Membrane")
            if filled.isEmpty {
                // Straight or self-intersecting gestures still show their boundary in the studio.
                for i in group {
                    var edge = stroke
                    edge.brush = SpatialBrush.tube.rawValue
                    edge.width *= 0.13
                    result[i].fill = StrokeMesh.build(stroke: edge, part: parts[i], sides: 6, arcs: arcs)
                }
                continue
            }
            let outline = profile.name == "Outlined Membrane"
                ? outlineTriangles(samples, width: stroke.width * 0.12) : []
            for i in group {
                let upper = i == group.upperBound - 1
                for triangle in filled {
                    OpenBrushArcSurface.append(triangle, to: &result[i].fill, in: parts[i].span, includesUpperBoundary: upper)
                }
                for triangle in outline {
                    OpenBrushArcSurface.append(triangle, to: &result[i].outline, in: parts[i].span, includesUpperBoundary: upper)
                }
            }
        }
        return result
    }

    private static func boundary(_ stroke: PaintStroke, in span: ArcSpan, arcs: [Float]) -> [Sample] {
        let points = stroke.fragment(span, arcs: arcs)
        let distances = stroke.fragmentDistances(span, arcs: arcs)
        let count = min(96, points.count)
        var result: [Sample] = []
        for i in 0..<count {
            let source = i * (points.count - 1) / (count - 1)
            let position = points[source].position
            if let last = result.last, simd_distance_squared(last.position, position) <= 1e-10 { continue }
            result.append(Sample(position: position, arc: distances[source]))
        }
        if result.count > 2, simd_distance_squared(result[0].position, result.last!.position) <= 1e-10 {
            result.removeLast()
        }
        return result
    }

    private static func triangles(_ boundary: [Sample], scale: Float, faceted: Bool) -> [[Vertex]] {
        guard boundary.count >= 3 else { return [] }
        let origin = boundary[0].position
        let points = boundary.map { ($0.position - origin) / scale }
        let newell = points.indices.reduce(SIMD3<Float>.zero) {
            $0 + simd_cross(points[$1], points[($1 + 1) % points.count])
        }
        let normal = unit(newell)
        let magnitude = SIMD3(abs(normal.x), abs(normal.y), abs(normal.z))
        let projection = points.map { point -> SIMD2<Float> in
            if magnitude.x >= magnitude.y && magnitude.x >= magnitude.z { return [point.y, point.z] }
            if magnitude.y >= magnitude.z { return [point.z, point.x] }
            return [point.x, point.y]
        }
        let area = projection.indices.reduce(Float(0)) {
            $0 + cross(projection[$1], projection[($1 + 1) % projection.count])
        }
        guard abs(area) > 1e-8 else { return [] }
        var ring = Array(projection.indices)
        if area < 0 { ring.reverse() }
        var faces: [[Int]] = []
        while ring.count > 3 {
            guard let ear = ring.indices.first(where: { index in
                let left = ring[(index + ring.count - 1) % ring.count]
                let middle = ring[index]
                let right = ring[(index + 1) % ring.count]
                let a = projection[left], b = projection[middle], c = projection[right]
                guard cross(b - a, c - a) > 1e-8 else { return false }
                return !ring.contains { other in
                    guard other != left, other != middle, other != right else { return false }
                    let p = projection[other]
                    return cross(b - a, p - a) >= -1e-8 && cross(c - b, p - b) >= -1e-8 &&
                        cross(a - c, p - c) >= -1e-8
                }
            }) else { return [] }
            faces.append([ring[(ear + ring.count - 1) % ring.count], ring[ear], ring[(ear + 1) % ring.count]])
            ring.remove(at: ear)
        }
        faces.append(ring)
        var totals = Array(repeating: SIMD3<Float>.zero, count: points.count)
        let normals = faces.map { face in
            let n = simd_cross(points[face[1]] - points[face[0]], points[face[2]] - points[face[0]])
            for i in face { totals[i] += n }
            return unit(n, fallback: normal)
        }
        return faces.enumerated().map { index, face in
            face.map { Vertex(sample: boundary[$0], normal: faceted ? normals[index] : unit(totals[$0], fallback: normals[index])) }
        }
    }

    private static func outlineTriangles(_ boundary: [Sample], width: Float) -> [[Vertex]] {
        var triangles: [[Vertex]] = []
        for i in boundary.indices {
            let a = boundary[i], b = boundary[(i + 1) % boundary.count]
            let tangent = unit(b.position - a.position)
            let axis = abs(tangent.y) < 0.9 ? SIMD3<Float>(0, 1, 0) : SIMD3<Float>(1, 0, 0)
            let right = unit(simd_cross(tangent, axis))
            let up = simd_cross(right, tangent)
            let corners = [right + up, -right + up, -right - up, right - up].map { $0 * width * 0.5 }
            for j in corners.indices {
                let k = (j + 1) % corners.count
                let normal = unit(corners[j] + corners[k])
                func vertex(_ sample: Sample, _ offset: SIMD3<Float>) -> Vertex {
                    Vertex(sample: Sample(position: sample.position + offset, arc: sample.arc), normal: normal)
                }
                let a0 = vertex(a, corners[j]), a1 = vertex(a, corners[k])
                let b0 = vertex(b, corners[j]), b1 = vertex(b, corners[k])
                triangles += [[a0, b0, a1], [a1, b0, b1]]
            }
        }
        return triangles
    }

    private static func cross(_ a: SIMD2<Float>, _ b: SIMD2<Float>) -> Float { a.x * b.y - a.y * b.x }
}
