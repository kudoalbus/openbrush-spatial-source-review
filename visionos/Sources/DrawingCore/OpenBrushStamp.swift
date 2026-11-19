// Spray spacing, size, rotation and atlas layout reference the Apache-2.0 Open Brush SprayBrush.
import Foundation
import simd

struct OpenBrushStamp {
    let arc: Float
    let center: SIMD3<Float>
    let forward: SIMD3<Float>
    let right: SIMD3<Float>
    let normal: SIMD3<Float>
    let uvOrigin: SIMD2<Float>
    let uvScale: Float
    let crossedCards: Bool

    static func place(on stroke: PaintStroke, arcs: [Float], pressures: [Float],
                      profile: OpenBrushProfile, spray: OpenBrushProfile.Spray, capacity: Int = 4096) -> [Self] {
        guard let length = arcs.last, length > 1e-6 else { return [] }
        // Bound geometry expansion for long imported control-point segments.
        let limit = min(capacity, max(128, stroke.points.count * 4))
        let minimumInterval = length / Float(limit)
        var rng = StampRandom(strokeID: stroke.id)
        var stamps: [Self] = []
        var arc: Float = 0
        var end = 1
        while arc < length, stamps.count < limit {
            while arcs[end] <= arc { end += 1 }
            let start = end - 1
            let t = (arc - arcs[start]) / (arcs[end] - arcs[start])
            let point = stroke.points[start].interpolated(to: stroke.points[end], t: t)
            let pressure = pressures[start] + (pressures[end] - pressures[start]) * t
            let size = max(0.0001, stroke.width * (profile.pressureMin + (1 - profile.pressureMin) * pressure))
            let tangent = unit(stroke.points[end].position - stroke.points[start].position)
            let reference = abs(tangent.y) < 0.9 ? SIMD3<Float>(0, 1, 0) : SIMD3<Float>(1, 0, 0)
            let side = unit(simd_cross(tangent, point.normal), fallback: unit(simd_cross(tangent, reference)))
            let normal = unit(simd_cross(side, tangent))
            let rotation = simd_quatf(angle: rng.signed() * spray.rotationDegrees * .pi / 180, axis: normal)
            let radius = size * (1 + rng.signed() * spray.sizeVariation) * 0.5
            let offset = rng.insideSphere() * size * spray.positionVariation
            let columns = spray.atlasColumns
            let tile = Int(rng.next() * Float(columns * columns))
            let uvScale = 1 / Float(columns)
            let uv = SIMD2(Float(tile % columns), Float(tile / columns)) * uvScale
            stamps.append(Self(arc: arc, center: point.position + offset, forward: rotation.act(tangent) * radius,
                               right: rotation.act(side) * radius, normal: normal, uvOrigin: uv, uvScale: uvScale,
                               crossedCards: spray.crossedCards))
            arc += max(minimumInterval, spray.fixedInterval ?? size / spray.rate)
        }
        return stamps
    }

    static func slices(on stroke: PaintStroke, arcs: [Float], pressures: [Float], profile: OpenBrushProfile) -> [Self] {
        var previousSide = SIMD3<Float>.zero
        return stroke.points.indices.compactMap { i in
            let before = stroke.points[max(0, i - 1)].position
            let after = stroke.points[min(stroke.points.count - 1, i + 1)].position
            let movement = after - before
            guard simd_length(movement) > 1e-7 else { return nil }
            let tangent = unit(movement)
            let reference = abs(tangent.y) < 0.9 ? SIMD3<Float>(0, 1, 0) : SIMD3<Float>(1, 0, 0)
            let desired = i == 0 ? simd_cross(tangent, stroke.points[i].normal)
                : previousSide - tangent * simd_dot(previousSide, tangent)
            let side = unit(desired, fallback: unit(simd_cross(tangent, reference)))
            previousSide = side
            let across = unit(simd_cross(side, tangent))
            let radius = stroke.width * (profile.pressureMin + (1 - profile.pressureMin) * pressures[i]) * 0.5
            // Keep the end card inside the half-open interval used for arc edits.
            let distance = i == stroke.points.count - 1 ? arcs[i].nextDown : arcs[i]
            return Self(arc: distance, center: stroke.points[i].position, forward: side * radius,
                        right: across * radius, normal: tangent, uvOrigin: .zero, uvScale: 1, crossedCards: false)
        }
    }

    static func mesh(_ stamps: [Self], in span: ArcSpan) -> StrokeMesh {
        var mesh = StrokeMesh()
        for stamp in marks(stamps, in: span) {
            appendCard(for: stamp, across: stamp.right, normal: stamp.normal, to: &mesh)
            if stamp.crossedCards {
                appendCard(for: stamp, across: stamp.normal * simd_length(stamp.right),
                           normal: -unit(stamp.right), to: &mesh)
            }
        }
        return mesh
    }

    // A stamp belongs to the color/visibility interval at its center.
    static func marks(_ stamps: [Self], in span: ArcSpan) -> ArraySlice<Self> {
        stamps[lowerBound(span.start, in: stamps)..<lowerBound(span.end, in: stamps)]
    }

    private static func lowerBound(_ arc: Float, in stamps: [Self]) -> Int {
        var low = 0
        var high = stamps.count
        while low < high {
            let middle = low + (high - low) / 2
            if stamps[middle].arc < arc { low = middle + 1 } else { high = middle }
        }
        return low
    }

    private static func appendCard(for stamp: Self, across: SIMD3<Float>, normal: SIMD3<Float>, to mesh: inout StrokeMesh) {
        let base = UInt32(mesh.positions.count)
        mesh.positions += [stamp.center - stamp.forward - across, stamp.center - stamp.forward + across,
                           stamp.center + stamp.forward - across, stamp.center + stamp.forward + across]
        mesh.normals += Array(repeating: normal, count: 4)
        mesh.uv += [stamp.uvOrigin, stamp.uvOrigin + [0, stamp.uvScale],
                    stamp.uvOrigin + [stamp.uvScale, 0], stamp.uvOrigin + SIMD2(repeating: stamp.uvScale)]
        mesh.indices += [base, base + 1, base + 2, base + 2, base + 1, base + 3]
    }
}

private struct StampRandom {
    private var state: UInt64

    init(strokeID: String) {
        // Keep marks fixed after edits and across launches. Swift Hasher is salted per process.
        state = strokeID.utf8.reduce(14_695_981_039_346_656_037) { ($0 ^ UInt64($1)) &* 1_099_511_628_211 }
    }

    mutating func next() -> Float {
        state &+= 0x9e3779b97f4a7c15
        var z = state
        z = (z ^ (z >> 30)) &* 0xbf58476d1ce4e5b9
        z = (z ^ (z >> 27)) &* 0x94d049bb133111eb
        return Float((z ^ (z >> 31)) >> 40) / 16_777_216
    }

    mutating func signed() -> Float { next() * 2 - 1 }

    mutating func insideSphere() -> SIMD3<Float> {
        let z = signed()
        let angle = next() * 2 * .pi
        let radius = sqrt(max(0, 1 - z * z))
        return SIMD3(cos(angle) * radius, sin(angle) * radius, z) * pow(next(), 1.0 / 3)
    }
}
