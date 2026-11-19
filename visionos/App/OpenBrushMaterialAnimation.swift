import RealityKit
import UIKit

@MainActor
struct OpenBrushMaterialAnimation {
    let style: OpenBrushMaterialStyle
    let materials: [any Material]

    func update(_ entity: Entity, at time: TimeInterval) {
        guard var model = entity.components[ModelComponent.self] else { return }
        let offset = SIMD2<Float>(style.offset(at: time), 0)
        let pulse = style.pulse(at: time)
        let hue = CGFloat(style.hueOffset(at: time))
        model.materials = materials.map { material in
            if var lit = material as? PhysicallyBasedMaterial {
                lit.textureCoordinateTransform.offset += offset
                lit.emissiveIntensity *= pulse
                if hue != 0 {
                    lit.baseColor.tint = rotated(lit.baseColor.tint, by: hue)
                    lit.emissiveColor.color = rotated(lit.emissiveColor.color, by: hue)
                }
                return lit
            }
            if var unlit = material as? UnlitMaterial {
                unlit.textureCoordinateTransform.offset += offset
                if hue != 0 { unlit.color.tint = rotated(unlit.color.tint, by: hue) }
                return unlit
            }
            return material
        }
        entity.components.set(model)
    }

    private func rotated(_ color: UIColor, by offset: CGFloat) -> UIColor {
        var hue: CGFloat = 0, saturation: CGFloat = 0, brightness: CGFloat = 0, alpha: CGFloat = 1
        guard color.getHue(&hue, saturation: &saturation, brightness: &brightness, alpha: &alpha) else { return color }
        let value = hue + offset
        return UIColor(hue: value - floor(value), saturation: max(0.45, saturation), brightness: brightness, alpha: alpha)
    }
}

@MainActor
enum OpenBrushPatternTexture {
    private static var cache: [OpenBrushMaterialStyle.Pattern: TextureResource] = [:]

    static func make(_ pattern: OpenBrushMaterialStyle.Pattern) throws -> TextureResource {
        if let resource = cache[pattern] { return resource }
        let width = 192, height = 32
        let image = UIGraphicsImageRenderer(size: CGSize(width: width, height: height),
                                            format: UIGraphicsImageRendererFormat().atUnitScale()).image { context in
            for y in 0..<height {
                for x in 0..<width {
                    let u = (CGFloat(x) + 0.5) / CGFloat(width)
                    let v = (CGFloat(y) + 0.5) / CGFloat(height)
                    color(pattern, u: u, v: v).setFill()
                    context.fill(CGRect(x: x, y: y, width: 1, height: 1))
                }
            }
        }
        guard let pixels = image.cgImage else { throw WorkspaceError.invalid("Could not generate brush pattern") }
        let resource = try TextureResource(image: pixels, options: .init(semantic: .color))
        cache[pattern] = resource
        return resource
    }

    private static func color(_ pattern: OpenBrushMaterialStyle.Pattern, u: CGFloat, v: CGFloat) -> UIColor {
        switch pattern {
        case .rainbow:
            return UIColor(hue: u, saturation: 0.9, brightness: 1, alpha: 1)
        case .disco:
            let cell = Int(u * 16), band = Int(v * 8)
            let hue = CGFloat((cell * 19 + band * 7) % 32) / 32
            let border = (u * 16).truncatingRemainder(dividingBy: 1) < 0.12 || (v * 8).truncatingRemainder(dividingBy: 1) < 0.10
            return UIColor(hue: hue, saturation: 0.78, brightness: border ? 0.22 : 1, alpha: 1)
        case .inkDrops:
            let y = abs(v - 0.64)
            let spill = pow(max(0, cos(u * .pi * 8)), 12) * 0.49
            let width = v < 0.64 ? 0.21 + spill : 0.22
            return UIColor(white: 0.87, alpha: y < width ? 1 : 0)
        case .luminousRings:
            let stripe = pow(abs(sin(u * .pi * 6 + v * .pi * 2)), 6)
            return UIColor(white: 0.21 + stripe * 0.79, alpha: 0.34 + stripe * 0.66)
        case .invertedToon:
            let side = abs(cos(v * 2 * .pi))
            return UIColor(white: side > 0.84 ? 1 : side > 0.43 ? 0.43 : 0.13, alpha: 1)
        case .fairy, .space:
            let columns = pattern == .fairy ? 13 : 8
            let rows = pattern == .fairy ? 4 : 3
            let x = u * CGFloat(columns), y = v * CGFloat(rows)
            let cell = Int(x) * 41 + Int(y) * 29
            let centerX = 0.21 + CGFloat((cell * 53) % 59) / 100
            let centerY = 0.21 + CGFloat((cell * 97) % 59) / 100
            let dx = x - floor(x) - centerX, dy = y - floor(y) - centerY
            let radius = pattern == .fairy ? 0.19 : 0.11
            let intensity = max(0, 1 - hypot(dx, dy) / radius)
            let nebula = pattern == .space ? 0.24 * pow(max(0, sin(u * 2 * .pi) * cos(v * .pi)), 2) : 0
            return UIColor(white: min(1, 0.55 + intensity), alpha: min(1, intensity + nebula))
        case .digital:
            let x = u * 18, y = v * 6
            let cell = (Int(x) * 19 + Int(y) * 7) % 13
            let active = cell < 7 && x - floor(x) < 0.8 && y - floor(y) < 0.73
            return UIColor(white: 0.56 + CGFloat(cell) * 0.03, alpha: active ? 1 : 0)
        case .race:
            let lane = Int(v * 5)
            let progress = u * 4 + CGFloat(lane) * 0.31
            let tail = progress - floor(progress)
            let center = v * 5 - CGFloat(lane)
            let streak = max(0, 1 - abs(center - 0.5) * 3) * max(0, 1 - tail * 1.4)
            return UIColor(white: 0.78 + streak * 0.22, alpha: pow(streak, 2))
        }
    }
}

private extension UIGraphicsImageRendererFormat {
    func atUnitScale() -> UIGraphicsImageRendererFormat {
        scale = 1
        opaque = false
        return self
    }
}
