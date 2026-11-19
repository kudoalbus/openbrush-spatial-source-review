import Foundation
import simd

/// PICO's finger pose classifier, fed by the corresponding ARKit joints in meters.
struct PicoHandPose {
    struct Finger {
        var root, middle, distal, tip: SIMD3<Float>

        var bend: Float? {
            let segments = [middle - root, distal - middle, tip - distal]
            guard segments.allSatisfy({ (0.002...0.10).contains(simd_length($0)) }) else { return nil }
            let directions = segments.map { simd_normalize($0) }
            let cosine = min(simd_dot(directions[0], directions[1]), simd_dot(directions[1], directions[2]))
            return 180 - acos(max(-1, min(1, cosine))) * 180 / .pi
        }
    }

    let open: Bool
    let indexStraight: Bool
    let requested: SpatialTool?
    let held: Set<SpatialTool>
    let index, thumb, recolor, normal: SIMD3<Float>

    init?(wrist: SIMD3<Float>, fingers: [Finger], thumbBase: SIMD3<Float>,
          thumbDistal: SIMD3<Float>, thumbTip: SIMD3<Float>) {
        guard fingers.count == 4 else { return nil }
        let angles = fingers.compactMap(\.bend)
        let width = simd_distance(fingers[0].root, fingers[3].root)
        let a = thumbDistal - thumbBase, b = thumbTip - thumbDistal
        let palm = simd_cross(fingers[0].root - wrist, fingers[3].root - wrist)
        guard angles.count == 4, (0.025...0.15).contains(width), simd_length(palm) >= 0.0001,
              (0.003...0.08).contains(simd_length(a)), (0.003...0.08).contains(simd_length(b)) else { return nil }
        let thumbAlignment = simd_dot(simd_normalize(a), simd_normalize(b))
        let thumbDistance = simd_distance(thumbTip, fingers[0].root)
        let tucked = thumbDistance < width * 0.65
        let thumbCurled = tucked || thumbAlignment < 0.42
        let thumbStraight = !tucked && thumbAlignment > 0.82
        let indexDirection = simd_normalize(fingers[0].tip - fingers[0].root)
        let middleDirection = simd_normalize(fingers[1].tip - fingers[1].root)
        let alignment = simd_dot(indexDirection, middleDirection)
        let forward = simd_normalize(indexDirection + middleDirection)
        let offset = fingers[1].tip - fingers[0].tip
        let gap = simd_length(offset - forward * simd_dot(offset, forward)) / width
        let distance = simd_length(offset) / width

        index = fingers[0].tip
        thumb = thumbTip
        recolor = (fingers[0].tip + fingers[1].tip) * 0.5
        normal = simd_normalize(palm)
        open = angles.allSatisfy { $0 >= 155 }
        indexStraight = angles[0] >= 155
        let outerCurled = angles[2] < 125 && angles[3] < 125
        if outerCurled && indexStraight && angles[1] >= 155 && thumbCurled &&
            alignment > 0.94 && gap < 0.34 && distance < 0.70 { requested = .recolor }
        else if outerCurled && angles[1] < 125 && indexStraight && thumbCurled { requested = .draw }
        else if outerCurled && angles[1] < 125 && angles[0] < 125 && thumbStraight { requested = .erase }
        else { requested = nil }

        var tools = Set<SpatialTool>()
        let outerRelaxed = angles[2] < 145 && angles[3] < 145
        let thumbRelaxed = thumbDistance < width * 0.75 || thumbAlignment < 0.60
        if outerRelaxed && angles[1] < 145 && angles[0] >= 140 && thumbRelaxed { tools.insert(.draw) }
        if outerRelaxed && angles[1] < 145 && angles[0] < 145 &&
            thumbDistance > width * 0.55 && thumbAlignment > 0.72 { tools.insert(.erase) }
        if outerRelaxed && angles[0] >= 140 && angles[1] >= 140 && thumbRelaxed &&
            alignment > 0.90 && gap < 0.42 && distance < 0.80 { tools.insert(.recolor) }
        held = tools
    }

    func tip(for tool: SpatialTool?) -> SIMD3<Float> {
        switch tool {
        case .erase: thumb
        case .recolor: recolor
        default: index
        }
    }
}

/// Open hand arms the next pose. A change of tool, tracking loss, or crossing
/// the UI releases it; a held pose alone cannot start another edit.
struct PicoHandGate {
    private(set) var ready = false
    private var candidate: SpatialTool?
    private var since: TimeInterval = 0
    private var active: SpatialTool?
    private var last: TimeInterval?

    mutating func update(open: Bool, requested: SpatialTool?, held: Set<SpatialTool>,
                         at time: TimeInterval, blocked: Bool = false) -> SpatialTool? {
        if let last, time < last || time - last > SpatialStrokeCapture.maxGap { self = .init() }
        last = time
        if blocked { self = .init(); return nil }
        if open { ready = true; active = nil; candidate = nil; return nil }
        if let active {
            if requested == active || held.contains(active) { return active }
            self = .init()
            return nil
        }
        guard ready, let requested else { candidate = nil; return nil }
        if candidate != requested { candidate = requested; since = time; return nil }
        if time - since >= 0.120 { active = requested }
        return active
    }
}
