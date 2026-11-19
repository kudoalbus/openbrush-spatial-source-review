import QuartzCore
import RealityKit
import simd

struct OpenBrushAnimationComponent: Component {
    enum Animation {
        case particles(OpenBrushParticleMesh)
        case material(OpenBrushMaterialAnimation)
        case wave(OpenBrushWaveMesh, OpenBrushMaterialAnimation?)
    }

    let animation: Animation
}

@MainActor
final class OpenBrushParticleMesh {
    let mesh: LowLevelMesh
    private let particles: [OpenBrushParticle]
    private let style: OpenBrushProfile.Particle
    private let width: Float
    private let cardCount: Int

    init(particles: [OpenBrushParticle], materialRanges: [(Range<Int>, Int)],
         style: OpenBrushProfile.Particle, width: Float) throws {
        self.particles = particles
        self.style = style
        self.width = width
        let cards = style.spray.crossedCards ? 2 : 1
        cardCount = cards
        mesh = try OpenBrushVertexSurface.mesh(vertices: particles.count * cards * 4, indices: particles.count * cards * 6)
        mesh.withUnsafeMutableIndices { raw in
            let indices = raw.bindMemory(to: UInt32.self)
            for card in 0..<(particles.count * cards) {
                let vertex = UInt32(card * 4)
                let index = card * 6
                indices[index] = vertex
                indices[index + 1] = vertex + 1
                indices[index + 2] = vertex + 2
                indices[index + 3] = vertex + 2
                indices[index + 4] = vertex + 1
                indices[index + 5] = vertex + 3
            }
        }
        mesh.withUnsafeMutableBytes(bufferIndex: 1) { raw in
            let vertices = raw.bindMemory(to: OpenBrushVertexSurface.self)
            for (i, particle) in particles.enumerated() {
                let stamp = particle.stamp
                for (card, normal) in [stamp.normal, -unit(stamp.right)].prefix(cards).enumerated() {
                    let index = (i * cards + card) * 4
                    vertices[index] = OpenBrushVertexSurface(normal: normal, uv: stamp.uvOrigin)
                    vertices[index + 1] = OpenBrushVertexSurface(normal: normal, uv: stamp.uvOrigin + [0, stamp.uvScale])
                    vertices[index + 2] = OpenBrushVertexSurface(normal: normal, uv: stamp.uvOrigin + [stamp.uvScale, 0])
                    vertices[index + 3] = OpenBrushVertexSurface(normal: normal, uv: stamp.uvOrigin + SIMD2(repeating: stamp.uvScale))
                }
            }
        }
        // Leave room for the full lifetime of every particle so camera culling cannot cut off the drift.
        let padding = SIMD3<Float>(repeating: width * 5)
        let parts = materialRanges.map { range, material in
            let first = particles[range.lowerBound].stamp.center
            let low = range.reduce(first) { simd_min($0, particles[$1].stamp.center) }
            let high = range.reduce(first) { simd_max($0, particles[$1].stamp.center) }
            return LowLevelMesh.Part(indexOffset: range.lowerBound * cards * 6 * MemoryLayout<UInt32>.stride,
                                     indexCount: range.count * cards * 6, materialIndex: material,
                                     bounds: BoundingBox(min: low - padding, max: high + padding))
        }
        mesh.parts.replaceAll(parts)
        update(at: CACurrentMediaTime())
    }

    func update(at time: TimeInterval, audio: OpenBrushAudioFrame = .silent) {
        mesh.replaceUnsafeMutableBytes(bufferIndex: 0) { raw in
            let vertices = raw.bindMemory(to: SIMD3<Float>.self)
            for (i, particle) in particles.enumerated() {
                let pose = particle.pose(at: time, style: style, width: width, audio: audio)
                let stamp = particle.stamp
                let cosine = cos(pose.rotation) * pose.scale
                let sine = sin(pose.rotation) * pose.scale
                let second = stamp.normal * simd_length(stamp.right)
                for (card, across) in [stamp.right, second].prefix(cardCount).enumerated() {
                    let forward = stamp.forward * cosine + across * sine
                    let right = across * cosine - stamp.forward * sine
                    let index = (i * cardCount + card) * 4
                    vertices[index] = pose.position - forward - right
                    vertices[index + 1] = pose.position - forward + right
                    vertices[index + 2] = pose.position + forward - right
                    vertices[index + 3] = pose.position + forward + right
                }
            }
        }
    }
}

struct OpenBrushAnimationSystem: System {
    private static let query = EntityQuery(where: .has(OpenBrushAnimationComponent.self))
    private var lastFrame: TimeInterval = -.infinity
    private var nextIndex = 0

    init(scene: RealityKit.Scene) {}

    mutating func update(context: SceneUpdateContext) {
        let matches = context.entities(matching: Self.query, updatingSystemWhen: .rendering)
        let now = CACurrentMediaTime()
        guard now - lastFrame >= 1.0 / 30 else { return }
        lastFrame = now
        let visible = matches.filter(\.isEnabledInHierarchy)
        guard !visible.isEmpty else { return }

        // Larger scenes advance in order across frames rather than blocking on every stroke.
        let deadline = now + 0.004
        let audio = OpenBrushAudioInput.shared.frame
        for _ in visible.indices {
            nextIndex %= visible.count
            let entity = visible[nextIndex]
            switch entity.components[OpenBrushAnimationComponent.self]?.animation {
            case .particles(let animation): animation.update(at: now, audio: audio)
            case .material(let animation): animation.update(entity, at: now)
            case .wave(let animation, let material):
                animation.update(at: now, audio: audio)
                material?.update(entity, at: now)
            case nil: break
            }
            nextIndex += 1
            if CACurrentMediaTime() >= deadline { break }
        }
    }
}
