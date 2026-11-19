import simd

struct PalmMenuPose {
    enum Face { case palm, back }

    let face: Face
    let position: SIMD3<Float>
    let orientation: simd_quatf

    init?(wrist: SIMD3<Float>, middle: SIMD3<Float>, index: SIMD3<Float>, little: SIMD3<Float>,
          isLeft: Bool, previousFace: Face?) {
        let forward = middle - wrist
        let normal = simd_cross(index - little, forward)
        guard simd_length(forward) > 0.025, simd_length(normal) > 0.0001 else { return nil }
        let palmUp = simd_normalize(normal) * (isLeft ? -1 : 1)
        if palmUp.y >= 0.32 { face = .palm }
        else if palmUp.y <= -0.32 { face = .back }
        else if let previousFace { face = previousFace }
        else { return nil }
        let z = -simd_normalize(forward)
        let x = simd_normalize(simd_cross(palmUp, z))
        let y = simd_cross(z, x)
        position = (wrist + middle) * 0.5
        orientation = simd_quatf(simd_float3x3(columns: (x, y, z)))
    }
}
