import Metal
import RealityKit

@available(visionOS 27.0, *)
enum GaussianArtworkEntity {
    #if !targetEnvironment(simulator)
    // Buffers are populated by the loader and handed off once, without subsequent mutation.
    struct Prepared: @unchecked Sendable {
        let count: Int
        let degree: UInt8
        let position: GaussianSplatResource.BufferDescriptor
        let scale: GaussianSplatResource.BufferDescriptor
        let rotation: GaussianSplatResource.BufferDescriptor
        let opacity: GaussianSplatResource.BufferDescriptor
        let harmonics: GaussianSplatResource.BufferDescriptor
        let isLinear: Bool
        let min: SIMD3<Float>
        let max: SIMD3<Float>
    }

    static func prepare(_ data: GaussianArtwork) throws -> Prepared {
        let count = data.count
        let harmonics = Int(data.degree + 1) * Int(data.degree + 1)
        let buffers = try data.bytes.withUnsafeBytes { input in
            let position = try buffer(input, data.stride, count, offset: 0, size: 12, format: .float3)
            let scale = try buffer(input, data.stride, count, offset: 12, size: 12, format: .float3)
            let rotation = try buffer(input, data.stride, count, offset: 24, size: 16, format: .float4)
            let opacity = try buffer(input, data.stride, count, offset: 40, size: 4, format: .float)
            let sh = try buffer(input, data.stride, count, offset: 44, size: harmonics * 12, format: .float3)
            return Prepared(count: count, degree: data.degree, position: position, scale: scale, rotation: rotation,
                            opacity: opacity, harmonics: sh, isLinear: data.usesLinearScaleAndOpacity,
                            min: data.min, max: data.max)
        }
        return buffers
    }

    @MainActor
    static func make(from data: Prepared) throws -> (entity: Entity, bounds: BoundingBox) {
        let buffers = try GaussianSplatResource.BufferResource(
            count: data.count, position: data.position, scale: data.scale, rotation: data.rotation, opacity: data.opacity,
            sphericalHarmonics: (data.harmonics, GaussianSplatResource.SphericalHarmonicDegree(rawValue: data.degree) ?? .zero)
        )
        let resource = GaussianSplatResource(buffers)
        resource.scaleActivation = data.isLinear ? .identity : .exponential
        resource.opacityActivation = data.isLinear ? .identity : .sigmoid

        let content = Entity()
        content.components.set(GaussianSplatComponent(resource))
        let model = Entity()
        model.addChild(content)
        let extent = data.max - data.min
        let scale = 0.19 / extent.max()
        content.scale = SIMD3(repeating: scale)
        content.position = -(data.min + extent * 0.5) * scale
        let halfSize = extent * (0.5 * scale)
        return (model, BoundingBox(min: -halfSize, max: halfSize))
    }

    private static func buffer(_ input: UnsafeRawBufferPointer, _ stride: Int, _ count: Int,
                               offset: Int, size: Int, format: MTLAttributeFormat) throws
        -> GaussianSplatResource.BufferDescriptor {
        let byteCount = count * size
        let capacity = (byteCount + 15) & ~15
        let storage = try LowLevelBuffer(descriptor: .init(capacity: capacity, sizeMultiple: 16))
        storage.withUnsafeMutableBytes { destination in
            let source = input.baseAddress!.advanced(by: offset)
            let target = destination.baseAddress!
            for row in 0..<count {
                target.advanced(by: row * size).copyMemory(from: source.advanced(by: row * stride), byteCount: size)
            }
        }
        return .init(buffer: storage, format: format, stride: size, offset: 0)
    }
    #endif
}
