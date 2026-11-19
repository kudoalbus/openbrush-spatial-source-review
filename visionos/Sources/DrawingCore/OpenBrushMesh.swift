import Foundation
import simd

extension StrokeMesh {
    static func openBrush(stroke: PaintStroke, part: DisplayPart, profile: OpenBrushProfile,
                          arcs: [Float], pressures: [Float], tileDistances: [Float] = []) -> StrokeMesh {
        var points = stroke.fragment(part.span, arcs: arcs)
        var distances = stroke.fragmentDistances(part.span, arcs: arcs)
        let length = max(arcs.last ?? 0, 0.0001)
        if profile.shape == .pinched || profile.section == .lens,
           points.count == 2, part.span.start < length / 2, part.span.end > length / 2 {
            distances.insert(length / 2, at: 1)
            points.insert(stroke.point(at: length / 2, arcs: arcs), at: 1)
        }
        if profile.wave != nil {
            let extra = (1..<32).map { length * Float($0) / 32 }.filter { sample in
                sample > part.span.start + 1e-6 && sample < part.span.end - 1e-6 &&
                    !distances.contains(where: { abs($0 - sample) < 1e-6 })
            }
            distances = (distances + extra).sorted()
            points = distances.map { stroke.point(at: $0, arcs: arcs) }
        }
        let tube = profile.geometry == .tube
        let corners = profile.section.corners
        let sides = corners.isEmpty ? (tube ? profile.sides : 1) : corners.count
        let ringSize = profile.faceted ? sides * 2 : sides + 1
        var mesh = StrokeMesh()
        var previousRight = SIMD3<Float>.zero
        let rows = profile.textureLayout?.atlasRows ?? 1
        let row = stroke.id.utf8.reduce(0) { ($0 * 31 + Int($1)) % rows }

        for i in points.indices {
            let point = points[i]
            let tangent = unit(points[min(i + 1, points.count - 1)].position - points[max(i - 1, 0)].position)
            let reference = abs(tangent.y) < 0.9 ? SIMD3<Float>(0, 1, 0) : SIMD3<Float>(1, 0, 0)
            let fallback = unit(simd_cross(tangent, reference))
            let projected = previousRight - tangent * simd_dot(previousRight, tangent)
            let desiredRight = simd_cross(tangent, point.normal)
            let candidate = tube && corners.isEmpty && i > 0 ? projected : desiredRight
            var right = unit(candidate, fallback: unit(projected, fallback: fallback))
            if i > 0 && (tube || !profile.singleSided) && simd_dot(right, previousRight) < 0 { right = -right }
            previousRight = right
            let normal = unit(simd_cross(right, tangent))
            let pressure = interpolate(at: distances[i], arcs: arcs, values: pressures)
            let width = stroke.width * max(0.001, profile.pressureMin + (1 - profile.pressureMin) * pressure)
            let u = distances[i] / length
            let baseRadius = width * 0.5 * (tube ? profile.radiusMultiplier : 1) * (profile.wave?.widthScale ?? 1)
            let radius = shapedRadius(baseRadius, at: distances[i], progress: u, length: length,
                                      strokeWidth: stroke.width, pressure: pressure, profile: profile, arcs: arcs)

            let textureU = tileDistances.isEmpty ? u : interpolate(at: distances[i], arcs: arcs, values: tileDistances)
            for vertex in 0..<ringSize {
                let side = profile.faceted ? vertex / 2 + vertex % 2 : vertex
                let v = Float(side) / Float(sides)
                let angle = v * 2 * .pi + profile.crossSectionAngle
                if !corners.isEmpty {
                    let corner = corners[side % sides]
                    let face = vertex / 2
                    let edge = corners[(face + 1) % sides] - corners[face]
                    let thickness = profile.section == .lens ? max(0.002, abs(sin(u * .pi))) : 1
                    mesh.positions.append(point.position + (right * corner.x + normal * corner.y * thickness) * radius)
                    mesh.normals.append(unit(right * edge.y * thickness - normal * edge.x))
                    mesh.uv.append([textureU, v])
                    continue
                }
                let radial = tube ? right * cos(angle) + normal * sin(angle) : right * (v * 2 - 1)
                mesh.positions.append(point.position + radial * radius)
                if let wave = profile.wave {
                    mesh.waveProgress.append(u)
                    mesh.waveDirections.append(wave.expandsTube ? radial * min(1, radius / max(stroke.width * 0.5, 0.0001)) : right)
                }
                if profile.faceted {
                    let faceAngle = (Float(vertex / 2) + 0.5) / Float(sides) * 2 * .pi + profile.crossSectionAngle
                    mesh.normals.append(right * cos(faceAngle) + normal * sin(faceAngle))
                } else {
                    mesh.normals.append(tube ? radial : normal)
                }
                mesh.uv.append([textureU, (Float(row) + v) / Float(rows)])
            }
            if i > 0 {
                for side in 0..<sides {
                    let offset = profile.faceted ? side * 2 : side
                    let a = UInt32((i - 1) * ringSize + offset)
                    let c = UInt32(i * ringSize + offset)
                    mesh.indices += tube ? [a, c, a + 1, a + 1, c, c + 1] : [a, a + 1, c, a + 1, c + 1, c]
                }
            }
        }

        if tube && profile.capped {
            for i in [0, points.count - 1] {
                let center = UInt32(mesh.positions.count)
                let neighbor = i == 0 ? 1 : i - 1
                let normal = unit(points[i].position - points[neighbor].position)
                mesh.positions.append(points[i].position)
                mesh.normals.append(normal)
                mesh.uv.append([distances[i] / length, 0.5])
                if let wave = profile.wave {
                    mesh.waveProgress.append(distances[i] / length)
                    mesh.waveDirections.append(wave.expandsTube ? .zero : mesh.waveDirections[i * ringSize])
                }
                for side in 0...sides {
                    let offset = profile.faceted ? (side % sides) * 2 : side
                    let source = i * ringSize + offset
                    mesh.positions.append(mesh.positions[source])
                    mesh.normals.append(normal)
                    mesh.uv.append(mesh.uv[source])
                    if profile.wave != nil {
                        mesh.waveProgress.append(mesh.waveProgress[source])
                        mesh.waveDirections.append(mesh.waveDirections[source])
                    }
                    if side < sides {
                        let a = center + 1 + UInt32(side)
                        mesh.indices += i == 0 ? [center, a, a + 1] : [center, a + 1, a]
                    }
                }
            }
        }
        return mesh
    }

    // Apply the silhouette against the full stroke arc so edits never restart the taper.
    private static func shapedRadius(_ radius: Float, at distance: Float, progress: Float, length: Float,
                                     strokeWidth: Float, pressure: Float, profile: OpenBrushProfile, arcs: [Float]) -> Float {
        let result: Float
        switch profile.shape {
        case .constant: result = radius
        case .pinched: result = radius * max(0.002, sin(progress * .pi))
        case .lofted:
            let taperSamples = min(4, (arcs.count - 1) / 2)
            let leading = max(0.0001, arcs[taperSamples])
            let trailing = max(0.0001, length - arcs[arcs.count - 1 - taperSamples])
            result = radius * min(1, distance / leading, (length - distance) / trailing)
        case .sine: result = radius * abs(sin(progress * .pi))
        case .comet: result = radius * sin(progress * 1.5 + 1.55)
        case .tapered: result = radius * (1 - progress) * (profile.name == "Spikes" ? 1.1 : 1)
        case .petal:
            result = radius * abs(sin(progress * .pi)) + strokeWidth * 1.5 * pressure * pow(progress, 3)
        }
        return max(radius * 0.001, result)
    }

    static func smoothPressures(stroke: PaintStroke, arcs: [Float], profile: OpenBrushProfile) -> [Float] {
        guard let first = stroke.points.first else { return [] }
        var result = [profile.zeroInitialPressure ? 0 : first.pressure]
        for i in 1..<stroke.points.count {
            let distance = arcs[i] - arcs[i - 1]
            let weight = profile.pressureWindowMeters > 1e-6 ? pow(0.1, distance / profile.pressureWindowMeters) : 0
            result.append(result[i - 1] * weight + stroke.points[i].pressure * (1 - weight))
        }
        return result
    }

    static func tileDistances(stroke: PaintStroke, arcs: [Float], pressures: [Float], profile: OpenBrushProfile) -> [Float] {
        guard let rate = profile.textureLayout?.tileRate else { return [] }
        var distances: [Float] = [0]
        for i in 1..<arcs.count {
            let width = stroke.width * (profile.pressureMin + (1 - profile.pressureMin) * pressures[i])
            let crossSection = profile.geometry == .tube && profile.section == .round
                ? width * .pi * profile.radiusMultiplier : width
            let repeatDistance = (arcs[i] - arcs[i - 1]) * rate / max(crossSection, 0.0001)
            distances.append(distances[i - 1] + repeatDistance)
        }
        return distances
    }

    private static func interpolate(at distance: Float, arcs: [Float], values: [Float]) -> Float {
        var low = 0, high = arcs.count - 1
        while low < high {
            let middle = (low + high) / 2
            if arcs[middle] < distance { low = middle + 1 } else { high = middle }
        }
        guard low > 0, arcs[low] - arcs[low - 1] > 1e-8 else { return values[low] }
        let t = (distance - arcs[low - 1]) / (arcs[low] - arcs[low - 1])
        return values[low - 1] + (values[low] - values[low - 1]) * t
    }
}
