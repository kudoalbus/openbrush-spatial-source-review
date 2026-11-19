import Foundation
import simd

struct StrokeMesh: Sendable {
    var positions: [SIMD3<Float>] = []
    var normals: [SIMD3<Float>] = []
    var uv: [SIMD2<Float>] = []
    var indices: [UInt32] = []
    var waveProgress: [Float] = []
    var waveDirections: [SIMD3<Float>] = []

    // Each color/visible interval gets independent end caps. Never bridge an erased gap.
    static func build(stroke: PaintStroke, part: DisplayPart, sides: Int = 12, arcs: [Float]? = nil) -> StrokeMesh {
        precondition(sides >= 3)
        let points = stroke.fragment(part.span, arcs: arcs)
        let tangents = points.indices.map { i in
            unit(points[min(i + 1, points.count - 1)].position - points[max(0, i - 1)].position)
        }
        let band = stroke.brush == "spatial_flat" || stroke.brush == "spatial_fan" || UUID(uuidString: stroke.brush) != nil
        let strands = stroke.brush == "spatial_fan" ? 7 : 1
        let ringSize = band ? 4 : sides
        var mesh = StrokeMesh()
        for strand in 0..<strands {
            let offset = mesh.positions.count
            var frame = SIMD3<Float>(0, 1, 0)
            var previousRight = SIMD3<Float>.zero
            var centers: [SIMD3<Float>] = []
            for i in points.indices {
                let tangent = tangents[i], point = points[i]
                let radius = stroke.radius(point.pressure)
                if band {
                    var right = simd_cross(tangent, point.normal)
                    if simd_length(right) < 0.01 { right = previousRight - tangent * simd_dot(previousRight, tangent) }
                    if simd_length(right) < 0.01 {
                        right = simd_cross(tangent, abs(tangent.y) < 0.9 ? SIMD3(0, 1, 0) : SIMD3(1, 0, 0))
                    }
                    right = unit(right)
                    if simd_length(previousRight) > 0.5 && simd_dot(right, previousRight) < 0 { right = -right }
                    previousRight = right
                    let normal = unit(simd_cross(right, tangent))
                    let lateral: Float = strands == 1 ? 0 : (Float(strand) / Float(strands - 1) * 2 - 1) * radius * 0.86
                    let center = point.position + right * lateral
                    centers.append(center)
                    let width = radius * (strands == 1 ? 1 : 0.075)
                    let depth = radius * (strands == 1 ? 0.08 : 0.035)
                    for pair: SIMD2<Float> in [SIMD2(1, 1), SIMD2(-1, 1), SIMD2(-1, -1), SIMD2(1, -1)] {
                        mesh.positions.append(center + right * (width * pair.x) + normal * (depth * pair.y))
                        mesh.normals.append(unit(right * pair.x + normal * pair.y))
                    }
                } else {
                    let projected = frame - tangent * simd_dot(frame, tangent)
                    frame = simd_length(projected) > 0.01 ? unit(projected) : unit(simd_cross(tangent, SIMD3(0, 0, 1)))
                    let binormal = unit(simd_cross(tangent, frame))
                    centers.append(point.position)
                    for side in 0..<sides {
                        let angle = 2 * Float.pi * Float(side) / Float(sides)
                        let radial = frame * cos(angle) + binormal * sin(angle)
                        mesh.positions.append(point.position + radial * radius)
                        mesh.normals.append(radial)
                    }
                }
                if i > 0 {
                    for side in 0..<ringSize {
                        let a = UInt32(offset + (i - 1) * ringSize + side)
                        let b = UInt32(offset + (i - 1) * ringSize + (side + 1) % ringSize)
                        let c = UInt32(offset + i * ringSize + side)
                        let d = UInt32(offset + i * ringSize + (side + 1) % ringSize)
                        mesh.indices += band ? [a, c, b, b, c, d] : [a, b, c, b, d, c]
                    }
                }
            }
            for end in [false, true] {
                let i = end ? points.count - 1 : 0
                let n = tangents[i] * (end ? Float(1) : -1)
                let center = UInt32(mesh.positions.count)
                mesh.positions.append(centers[i]); mesh.normals.append(n)
                let ring = UInt32(mesh.positions.count)
                for side in 0..<ringSize {
                    mesh.positions.append(mesh.positions[offset + i * ringSize + side]); mesh.normals.append(n)
                }
                for side in 0..<ringSize {
                    let a = ring + UInt32(side), b = ring + UInt32((side + 1) % ringSize)
                    let forward = end != band
                    mesh.indices += forward ? [center, a, b] : [center, b, a]
                }
            }
        }
        return mesh
    }
}
