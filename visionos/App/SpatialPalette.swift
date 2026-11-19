import RealityKit
import SwiftUI
import UIKit

enum PaletteAction: String {
    case arm, tube, flat, fan, smaller, larger, undo, redo, hide
    case cyan, red, amber, green
    case draw, select, erase, recolor, save, brushBook, colorMode

    var swatch: InkColor? {
        switch self {
        case .cyan: .init(r: 0.12, g: 0.72, b: 1)
        case .red: .init(r: 0.95, g: 0.18, b: 0.22)
        case .amber: .init(r: 1, g: 0.63, b: 0.12)
        case .green: .init(r: 0.19, g: 0.78, b: 0.39)
        default: nil
        }
    }
}

struct PicoColor: Codable, Equatable {
    var hue: Double, saturation: Double, brightness: Double

    init(hue: Double, saturation: Double, brightness: Double) {
        self.hue = hue; self.saturation = saturation; self.brightness = brightness
    }

    init(_ ink: InkColor, previous: PicoColor? = nil) {
        let r = Double(ink.r), g = Double(ink.g), b = Double(ink.b)
        let hi = max(r, g, b), lo = min(r, g, b), difference = hi - lo
        let sector = hi == r ? (g - b) : hi == g ? (b - r) + 2 * difference : (r - g) + 4 * difference
        hue = difference > 1e-7 ? (sector / difference / 6 + 1).truncatingRemainder(dividingBy: 1) : previous?.hue ?? 0
        saturation = hi > 1e-7 ? difference / hi : previous?.saturation ?? 0
        brightness = hi
    }

    var ink: InkColor {
        let h = hue * 6
        let c = brightness * saturation
        let x = c * (1 - abs(h.truncatingRemainder(dividingBy: 2) - 1))
        let m = brightness - c
        let channels: (Double, Double, Double) = switch Int(h) % 6 {
        case 0: (c, x, 0)
        case 1: (x, c, 0)
        case 2: (0, c, x)
        case 3: (0, x, c)
        case 4: (x, 0, c)
        default: (c, 0, x)
        }
        return InkColor(r: Float(channels.0 + m), g: Float(channels.1 + m), b: Float(channels.2 + m))
    }

    var color: Color { Color(hue: hue, saturation: saturation, brightness: brightness) }
}

enum PicoPickerLayout {
    static let center = CGPoint(x: 240, y: 118)
    static let radius: Double = 71.5
    static let inner: Double = 81, outer: Double = 99
    static func favorite(_ i: Int) -> CGPoint { CGPoint(x: 240 + (i - 2) * 23, y: 26) }

    enum Input: Equatable {
        case wheel, brightness, favorite(Int)
    }

    static func hit(_ point: CGPoint) -> Input? {
        for i in 0..<5 where hypot(point.x - favorite(i).x, point.y - favorite(i).y) <= 10 { return .favorite(i) }
        let dx = point.x - center.x, dy = center.y - point.y
        let distance = hypot(dx, dy)
        if distance <= radius + 1.5 { return .wheel }
        if distance >= inner - 2 && distance <= outer + 2 && abs(atan2(dy, dx)) <= .pi / 3 { return .brightness }
        return nil
    }

    static func sample(_ point: CGPoint, input: Input, selected: PicoColor) -> PicoColor? {
        var color = selected
        let dx = point.x - center.x, dy = center.y - point.y
        let distance = hypot(dx, dy)
        let angle = atan2(dy, dx)
        switch input {
        case .wheel:
            guard distance <= radius + 18 else { return nil }
            if distance > 0.001 { color.hue = (angle / (2 * .pi) + 1).truncatingRemainder(dividingBy: 1) }
            color.saturation = min(1, distance / radius)
        case .brightness:
            guard distance >= inner - 18, distance <= outer + 18, abs(angle) <= .pi * 85 / 180 else { return nil }
            color.brightness = min(1, max(0, (angle + .pi / 3) / (2 * .pi / 3)))
        case .favorite: return nil
        }
        return color
    }
}

@MainActor
final class SpatialPalette {
    private struct Target: Decodable {
        let id: Int
        let position: [Float]
        let radius: Float
        let color: String?
        var center: SIMD3<Float> { [position[0], position[1], position[2]] }
        var ink: InkColor? {
            guard let color, let rgb = UInt32(color.dropFirst(), radix: 16) else { return nil }
            return .init(r: Float((rgb >> 16) & 255) / 255, g: Float((rgb >> 8) & 255) / 255, b: Float(rgb & 255) / 255)
        }
    }
    private struct Layout: Decodable { let targets: [Target] }

    let root = Entity()
    private let legacyRoot = Entity()
    private let pickerRoot = Entity()
    private let brushRoot = Entity()
    private var legacyModel: Entity?
    private var pickerModel: Entity?
    private var asset: Entity?
    private let fallback = ModelEntity(mesh: .generateBox(size: [0.29, 0.012, 0.26], cornerRadius: 0.006),
                                       materials: [UnlitMaterial(color: .init(red: 0.45, green: 0.27, blue: 0.12, alpha: 1))])
    private let ring = ModelEntity()
    private let colorRing = ModelEntity()
    private var targets: [Target] = []
    private var hoverTargets: [(target: Target, entity: Entity)] = []

    init() {
        let url = Bundle.main.url(forResource: "palette-targets", withExtension: "json")
        if let url, let data = try? Data(contentsOf: url),
           let layout = try? JSONDecoder().decode(Layout.self, from: data) { targets = layout.targets }
        for entity in [fallback, legacyRoot, pickerRoot, brushRoot, ring, colorRing] as [Entity] { root.addChild(entity) }
        for target in targets {
            let parent = (20...22).contains(target.id) ? brushRoot : legacyRoot
            addTarget(target, to: parent)
        }
        for (index, id) in [8, 9, 10, 200, 300].enumerated() {
            addTarget(.init(id: id, position: [Float(index - 1) * 0.032, 0.010, 0.079], radius: 0.0118, color: nil), to: pickerRoot)
        }
        ring.model = ModelComponent(mesh: Self.ringMesh(), materials: [UnlitMaterial(color: .systemYellow)])
        ring.isEnabled = false
        colorRing.model = ring.model
        colorRing.scale = SIMD3(repeating: 1.25)
        colorRing.isEnabled = false
    }

    func load() async {
        guard asset == nil, let url = Bundle.main.url(forResource: "PicoPalette", withExtension: "usdz"),
              let loaded = try? await Entity(contentsOf: url) else { return }
        asset = loaded
        root.addChild(loaded)
        fallback.isEnabled = false
        legacyModel = Self.find("PaletteLegacy", in: loaded)
        pickerModel = Self.find("PalettePicker", in: loaded)
        setPicker(pickerRoot.isEnabled)
    }

    static func find(_ prefix: String, in entity: Entity) -> Entity? {
        if entity.name == prefix || entity.name.hasPrefix(prefix + "_") { return entity }
        for child in entity.children {
            if let found = find(prefix, in: child) { return found }
        }
        return nil
    }

    func attachPicker(_ entity: Entity) {
        guard entity.parent !== root else { return }
        root.addChild(entity)
        entity.position = [-0.025, 0.011, 0]
        entity.orientation = simd_quatf(angle: -.pi / 2, axis: [1, 0, 0])
        Self.size(entity, width: 0.42, points: 420)
    }

    static func size(_ entity: Entity, width: Float, points: Float) {
        let extent = entity.visualBounds(relativeTo: entity).extents.x
        entity.scale = SIMD3(repeating: extent > 0.001 ? width / extent : width * 1360 / points)
    }

    private func addTarget(_ target: Target, to parent: Entity) {
        let hit = Entity()
        hit.name = "palette.\(target.id)"
        hit.position = target.center
        hit.components.set(InputTargetComponent())
        hit.components.set(CollisionComponent(shapes: [
            .generateBox(size: [2 * target.radius, 0.018, 2 * target.radius])
        ]))
        parent.addChild(hit)
        hoverTargets.append((target, hit))
    }

    private func setPicker(_ picker: Bool) {
        legacyRoot.isEnabled = !picker
        pickerRoot.isEnabled = picker
        legacyModel?.isEnabled = !picker
        pickerModel?.isEnabled = picker
    }

    func update(style: SpatialBrushStyle, visible: Bool, picker: Bool, canUndo: Bool, canRedo: Bool) {
        root.isEnabled = visible
        setPicker(picker)
        for (target, entity) in hoverTargets {
            entity.isEnabled = (target.id != 8 || canUndo) && (target.id != 9 || canRedo)
        }
        let brush: Int? = switch style.brush {
        case .tube: 20
        case .flat: 21
        case .fan: 22
        case .openBrush: nil
        }
        ring.isEnabled = false
        if let target = targets.first(where: { $0.id == brush }) {
            ring.isEnabled = true
            ring.position = target.center + [0, 0.010, 0]
        }
        if !picker, let color = targets.first(where: { $0.ink == style.color }) {
            colorRing.position = color.center + [0, 0.010, 0]
            colorRing.isEnabled = true
        } else { colorRing.isEnabled = false }
    }

    var hasAsset: Bool { asset != nil }

    func command(for entity: Entity) -> Int? {
        guard entity.name.hasPrefix("palette.") else { return nil }
        return Int(entity.name.dropFirst("palette.".count))
    }

    func swatch(for id: Int) -> InkColor? { targets.first { $0.id == id }?.ink }

    func protects(_ world: SIMD3<Float>) -> Bool {
        guard root.isEnabled else { return false }
        let p = root.convert(position: world, from: nil)
        return (-0.22...0.17).contains(p.x) && abs(p.z) < 0.16 && (-0.004...0.030).contains(p.y)
    }

    func hit(_ world: SIMD3<Float>) -> Int? {
        guard root.isEnabled else { return nil }
        let p = root.convert(position: world, from: nil)
        return hoverTargets.filter { target, entity in
            entity.isEnabled && entity.parent?.isEnabled == true && p.y >= 0.001 &&
                abs(p.y - target.center.y) <= 0.008 &&
                simd_length(SIMD2(p.x - target.center.x, p.z - target.center.z)) <= target.radius
        }.min { simd_distance($0.target.center, p) < simd_distance($1.target.center, p) }?.target.id
    }

    func pickerPoint(_ world: SIMD3<Float>, dragging: Bool) -> CGPoint? {
        guard root.isEnabled, pickerRoot.isEnabled else { return nil }
        let p = root.convert(position: world, from: nil)
        guard p.y >= 0.001, abs(p.y - 0.010) <= (dragging ? 0.025 : 0.009) else { return nil }
        return CGPoint(x: Double(p.x + 0.025) * 1000 + 210, y: Double(p.z) * 1000 + 145)
    }

    private static func ringMesh() -> MeshResource {
        var points: [SIMD3<Float>] = []
        var indices: [UInt32] = []
        for i in 0...48 {
            let t = Float(i) * 2 * .pi / 48
            points += [[0.017 * cos(t), 0, 0.017 * sin(t)], [0.019 * cos(t), 0, 0.019 * sin(t)]]
            if i > 0 { let a = UInt32(2 * i); indices += [a-2, a-1, a, a-1, a+1, a] }
        }
        var descriptor = MeshDescriptor()
        descriptor.positions = .init(points)
        descriptor.normals = .init(Array(repeating: [0, 1, 0], count: points.count))
        descriptor.primitives = .triangles(Array(indices.reversed()))
        return (try? MeshResource.generate(from: [descriptor])) ?? .generateSphere(radius: 0.002)
    }
}

struct PicoColorSurface: View {
    let picker: Bool
    let selected: PicoColor
    let favorites: [PicoColor?]
    let onChange: (PicoPickerLayout.Input, CGPoint) -> Void
    @State private var drag: PicoPickerLayout.Input?

    var body: some View {
        Canvas { context, _ in
            func wheel(_ center: CGPoint, radius: Double, brightness: Double) {
                let circle = Path(ellipseIn: CGRect(x: center.x-radius, y: center.y-radius, width: 2*radius, height: 2*radius))
                context.fill(circle, with: .conicGradient(Gradient(colors: [
                    .red, Color(red: 1, green: 0, blue: 1), .blue, .cyan, .green, .yellow, .red
                ]), center: center))
                context.fill(circle, with: .radialGradient(Gradient(colors: [.white, .white.opacity(0)]),
                                                             center: center, startRadius: 0, endRadius: radius))
                if brightness < 1 { context.fill(circle, with: .color(.black.opacity(1-brightness))) }
            }
            wheel(picker ? CGPoint(x: 331, y: 224) : CGPoint(x: 263, y: 190), radius: 6.1, brightness: 1)
            guard picker else { return }
            let center = PicoPickerLayout.center, r = PicoPickerLayout.radius
            wheel(center, radius: r, brightness: selected.brightness)
            func level(_ t: Double) -> Color { Color(hue: selected.hue, saturation: selected.saturation, brightness: t) }
            var arc = Path()
            arc.addArc(center: center, radius: 90, startAngle: .degrees(-60), endAngle: .degrees(60), clockwise: false)
            context.stroke(arc, with: .conicGradient(Gradient(stops: [
                .init(color: level(0.5), location: 0), .init(color: .black, location: 1/6),
                .init(color: level(1), location: 5/6), .init(color: level(0.5), location: 1)
            ]), center: center), lineWidth: PicoPickerLayout.outer - PicoPickerLayout.inner)
            let actions: [LocalizedStringKey] = ["Undo", "Redo", "Save", "Brush", "Switch"]
            for (i, action) in actions.enumerated() {
                context.draw(Text(action).font(.system(size: 5.7, weight: .semibold))
                    .foregroundStyle(Color(red: 0.21, green: 0.19, blue: 0.14)),
                             at: CGPoint(x: 203 + i*32, y: 245))
            }
            func dot(_ p: CGPoint, radius: Double, color: Color) {
                let path = Path(ellipseIn: CGRect(x: p.x - radius, y: p.y - radius, width: 2 * radius, height: 2 * radius))
                context.fill(path, with: .color(color))
                context.stroke(path, with: .color(Color(red: 0.91, green: 0.73, blue: 0.39)), lineWidth: 2)
            }
            dot(CGPoint(x: 165, y: 53), radius: 23.2, color: selected.color)
            for i in 0..<5 {
                let p = PicoPickerLayout.favorite(i)
                dot(p, radius: 7.8, color: favorites[i]?.color ?? .black.opacity(0.32))
                if favorites[i] == nil { context.draw(Text("+").font(.system(size: 11)).foregroundStyle(.white), at: p) }
            }
            let a = selected.hue * 2 * .pi, d = selected.saturation * r
            dot(CGPoint(x: center.x + cos(a) * d, y: center.y - sin(a) * d), radius: 4.5, color: selected.color)
            let v = (selected.brightness - 0.5) * 2 * .pi / 3
            dot(CGPoint(x: center.x + cos(v) * 90, y: center.y - sin(v) * 90), radius: 4.5, color: selected.color)
        }
        .frame(width: 420, height: 290)
        .contentShape(PicoPickerHitShape())
        .allowsHitTesting(picker)
        .gesture(DragGesture(minimumDistance: 0).onChanged { event in
            if drag == nil { drag = PicoPickerLayout.hit(event.startLocation) }
            if let drag, case .favorite = drag { return }
            if let drag { onChange(drag, event.location) }
        }.onEnded { event in
            if let drag, case .favorite = drag { onChange(drag, event.location) }
            drag = nil
        })
        .accessibilityLabel("Color wheel, brightness and favorite colors")
    }
}

private struct PicoPickerHitShape: Shape {
    func path(in rect: CGRect) -> Path {
        let c = PicoPickerLayout.center
        var path = Path(ellipseIn: CGRect(x: c.x - 73, y: c.y - 73, width: 146, height: 146))
        var arc = Path()
        arc.addArc(center: c, radius: 90, startAngle: .degrees(-60), endAngle: .degrees(60), clockwise: false)
        path.addPath(arc.strokedPath(.init(lineWidth: 21)))
        for i in 0..<5 {
            let p = PicoPickerLayout.favorite(i)
            path.addEllipse(in: CGRect(x: p.x - 10, y: p.y - 10, width: 20, height: 20))
        }
        return path
    }
}
