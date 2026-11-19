import QuartzCore
import RealityKit
import simd

struct OpenBrushVertexSurface {
    let normal: SIMD3<Float>
    let uv: SIMD2<Float>

    @MainActor static func mesh(vertices: Int, indices: Int) throws -> LowLevelMesh {
        try LowLevelMesh(descriptor: .init(
            vertexCapacity: vertices,
            vertexAttributes: [
                .init(semantic: .position, format: .float3, layoutIndex: 0, offset: 0),
                .init(semantic: .normal, format: .float3, layoutIndex: 1, offset: MemoryLayout<Self>.offset(of: \.normal)!),
                .init(semantic: .uv0, format: .float2, layoutIndex: 1, offset: MemoryLayout<Self>.offset(of: \.uv)!)
            ],
            vertexLayouts: [
                .init(bufferIndex: 0, bufferStride: MemoryLayout<SIMD3<Float>>.stride),
                .init(bufferIndex: 1, bufferStride: MemoryLayout<Self>.stride)
            ],
            indexCapacity: indices, indexType: .uint32
        ))
    }
}

@MainActor
final class OpenBrushWaveMesh {
    private struct Vertex {
        let position: SIMD3<Float>
        let direction: SIMD3<Float>
        let progress: Float
    }

    let mesh: LowLevelMesh
    private let baseVertices: [Vertex]
    private let wave: OpenBrushWave
    private let width: Float

    init(parts: [(StrokeMesh, Int)], wave: OpenBrushWave, width: Float) throws {
        self.wave = wave
        self.width = width
        var base: [Vertex] = []
        var surface: [OpenBrushVertexSurface] = []
        var indices: [UInt32] = []
        var meshParts: [LowLevelMesh.Part] = []
        for (part, material) in parts {
            let vertexOffset = UInt32(base.count)
            let indexOffset = indices.count
            for i in part.positions.indices {
                base.append(Vertex(position: part.positions[i], direction: part.waveDirections[i], progress: part.waveProgress[i]))
                surface.append(OpenBrushVertexSurface(normal: part.normals[i], uv: part.uv[i]))
            }
            indices.append(contentsOf: part.indices.map { $0 + vertexOffset })
            let origin = part.positions[0]
            let low = part.positions.reduce(origin) { simd_min($0, $1) }
            let high = part.positions.reduce(origin) { simd_max($0, $1) }
            let margin = SIMD3<Float>(repeating: width * 4)
            meshParts.append(.init(indexOffset: indexOffset * MemoryLayout<UInt32>.stride, indexCount: part.indices.count,
                                   materialIndex: material, bounds: .init(min: low - margin, max: high + margin)))
        }
        baseVertices = base
        mesh = try OpenBrushVertexSurface.mesh(vertices: base.count, indices: indices.count)
        mesh.withUnsafeMutableIndices { buffer in indices.withUnsafeBytes { buffer.copyMemory(from: $0) } }
        mesh.withUnsafeMutableBytes(bufferIndex: 1) { buffer in surface.withUnsafeBytes { buffer.copyMemory(from: $0) } }
        mesh.parts.replaceAll(meshParts)
        update(at: CACurrentMediaTime(), audio: .silent)
    }

    func update(at time: TimeInterval, audio: OpenBrushAudioFrame) {
        mesh.replaceUnsafeMutableBytes(bufferIndex: 0) { raw in
            let vertices = raw.bindMemory(to: SIMD3<Float>.self)
            // Adjacent vertices in a ring share progress, so evaluate the signal once per ring.
            var progress: Float = -1
            var displacement: Float = 0
            for (index, base) in baseVertices.enumerated() {
                if progress != base.progress {
                    progress = base.progress
                    displacement = wave.displacement(progress: progress, time: time, audio: audio) * width
                }
                vertices[index] = base.position + base.direction * displacement
            }
        }
    }
}
