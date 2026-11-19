import RealityKit
import SwiftUI
import UIKit

enum PicoBookMode: String, CaseIterable, Identifiable {
    case basic, classic, extended
    var id: Self { self }
    var label: LocalizedStringKey {
        switch self {
        case .basic: "Basic"
        case .classic: "Classic"
        case .extended: "Experimental"
        }
    }
}

@MainActor @Observable
final class PicoBrushBook {
    struct Entry: Decodable, Identifiable {
        let id: String
        let name: String
        let renderer: String?
        var brush: SpatialBrush? {
            if let renderer { return SpatialBrush(rawValue: renderer) }
            guard OpenBrushCatalog.bundled.profile(for: id) != nil else { return nil }
            return SpatialBrush(rawValue: id)
        }
        var title: String {
            if let brush { return brush.localizedLabel }
            return Bundle.main.localizedString(forKey: name, value: name, table: "BrushNames")
        }
    }

    private struct Catalog: Decodable {
        var entries: [Entry]
        var basic, classic, extended: [String]
        var atlases: [String: [String]]
        var atlasCell, atlasGutter: Int
    }

    enum Command: Equatable {
        case mode(PicoBookMode), close, previous, next, page(Int), slot(Int)
    }

    let root = Entity()
    private var entriesByID: [String: Entry] = [:]
    private var catalog: Catalog?
    private var frame: Entity?
    private var basicModels: [Entity] = []
    private var images: [String: [UIImage]] = [:]
    var visible = false
    private(set) var mode = PicoBookMode.classic
    private(set) var classicPage = 1
    private(set) var extendedPage = 1
    private(set) var inspected: Entry?

    var loaded: Bool { frame != nil }
    var page: Int { mode == .basic ? 0 : mode == .classic ? classicPage : extendedPage }
    var pageCount: Int { max(1, (ids.count + 11) / 12) }
    private var ids: [String] {
        switch mode {
        case .basic: return catalog?.basic ?? []
        case .classic: return catalog?.classic ?? []
        case .extended:
            guard let catalog else { return [] }
            let existing = Set(catalog.extended + catalog.basic)
            return catalog.extended + catalog.entries.map(\.id).filter { !existing.contains($0) }
        }
    }
    var slots: [Entry] { ids.dropFirst(page * 12).prefix(12).compactMap { entriesByID[$0] } }

    init() {
        if let url = Bundle.main.url(forResource: "catalog", withExtension: "json"),
           let data = try? Data(contentsOf: url), let catalog = try? JSONDecoder().decode(Catalog.self, from: data) {
            self.catalog = catalog
            entriesByID = Dictionary(uniqueKeysWithValues: catalog.entries.map { ($0.id, $0) })
        }
        let defaults = UserDefaults.standard
        mode = defaults.string(forKey: "brushBookMode").flatMap(PicoBookMode.init(rawValue:)) ?? .classic
        classicPage = defaults.object(forKey: "classicBrushPage").map { _ in min(3, max(0, defaults.integer(forKey: "classicBrushPage"))) } ?? 1
        extendedPage = defaults.object(forKey: "extendedBrushPage").map { _ in min(8, max(0, defaults.integer(forKey: "extendedBrushPage"))) } ?? 1
        root.isEnabled = false
    }

    func load() async {
        guard frame == nil, let url = Bundle.main.url(forResource: "PicoBrushBook", withExtension: "usdz"),
              let asset = try? await Entity(contentsOf: url) else { return }
        frame = asset
        root.addChild(asset)
        for (slot, name) in ["round", "flat", "fan"].enumerated() {
            guard let url = Bundle.main.url(forResource: "PicoBrush-\(name)", withExtension: "usdz"),
                  let entity = try? await Entity(contentsOf: url) else { continue }
            let bounds = entity.visualBounds(relativeTo: entity)
            let longest = max(bounds.extents.x, bounds.extents.y, bounds.extents.z)
            let scale = longest > 0 ? 0.034 / longest : 1
            entity.scale = SIMD3(repeating: scale)
            entity.position = [Float(slot) * 0.048 - 0.072, 0.023, -0.061] - bounds.center * scale
            root.addChild(entity)
            basicModels.append(entity)
        }
        update()
    }

    func attach(_ entity: Entity) {
        guard entity.parent !== root else { return }
        root.addChild(entity)
        entity.position = [0, 0.020, -0.004]
        entity.orientation = simd_quatf(angle: -.pi / 2, axis: [1, 0, 0])
        SpatialPalette.size(entity, width: 0.224, points: 224)
    }

    func update() {
        root.isEnabled = visible
        basicModels.forEach { $0.isEnabled = mode == .basic }
        if let frame {
            for slot in 0..<12 {
                SpatialPalette.find("BookTile\(slot)", in: frame)?.isEnabled = slot < slots.count
            }
            for (prefix, enabled) in [("BookPrev", page > 0), ("BookNext", page < pageCount - 1)] {
                for part in ["Bezel", "Enamel", "Glyph"] {
                    SpatialPalette.find(prefix + part, in: frame)?.isEnabled = enabled
                }
            }
        }
    }

    func icon(_ index: Int) -> UIImage? {
        guard let catalog, let paths = catalog.atlases[mode.rawValue], paths.indices.contains(page),
              mode != .extended || page * 12 + index < catalog.extended.count else { return nil }
        let name = URL(fileURLWithPath: paths[page]).deletingPathExtension().lastPathComponent
        if let cached = images[name] { return cached.indices.contains(index) ? cached[index] : nil }
        guard let url = Bundle.main.url(forResource: name, withExtension: "png", subdirectory: "atlases"),
              let image = UIImage(contentsOfFile: url.path)?.cgImage else { return nil }
        let stride = catalog.atlasCell + catalog.atlasGutter * 2
        let icons = (0..<12).compactMap { i -> UIImage? in
            let rect = CGRect(x: i % 4 * stride + catalog.atlasGutter, y: i / 4 * stride + catalog.atlasGutter,
                              width: catalog.atlasCell, height: catalog.atlasCell)
            return image.cropping(to: rect).map { UIImage(cgImage: $0) }
        }
        images[name] = icons
        return icons.indices.contains(index) ? icons[index] : nil
    }

    func command(_ command: Command) -> SpatialBrush? {
        switch command {
        case .mode(let next): mode = next
        case .close: visible = false
        case .previous: setPage(page - 1)
        case .next: setPage(page + 1)
        case .page(let index): setPage(index)
        case .slot(let index):
            guard slots.indices.contains(index) else { return nil }
            inspected = slots[index]
            return inspected?.brush
        }
        inspected = nil
        UserDefaults.standard.set(mode.rawValue, forKey: "brushBookMode")
        UserDefaults.standard.set(classicPage, forKey: "classicBrushPage")
        UserDefaults.standard.set(extendedPage, forKey: "extendedBrushPage")
        return nil
    }

    private func setPage(_ value: Int) {
        let value = max(0, min(pageCount - 1, value))
        if mode == .classic { classicPage = value }
        if mode == .extended { extendedPage = value }
    }

    func protects(_ world: SIMD3<Float>) -> Bool {
        guard visible else { return false }
        let p = root.convert(position: world, from: nil)
        return abs(p.x) < 0.134 && abs(p.z) < 0.14 && (0.003...0.037).contains(p.y)
    }

    func hit(_ world: SIMD3<Float>) -> Command? {
        guard visible else { return nil }
        let p = root.convert(position: world, from: nil)
        guard (0.012...0.030).contains(p.y) else { return nil }
        if abs(p.z + 0.104) < 0.011 {
            for (x, mode) in [(Float(-0.024), PicoBookMode.basic), (0.018, .classic), (0.060, .extended)] where abs(p.x - x) <= 0.018 { return .mode(mode) }
            if abs(p.x - 0.102) < 0.010 { return .close }
        }
        if abs(p.z - 0.098) < 0.014 {
            if page > 0 && abs(p.x + 0.084) < 0.013 { return .previous }
            if page < pageCount - 1 && abs(p.x - 0.084) < 0.013 { return .next }
            for i in 0..<pageCount where abs(p.x - (Float(i) - Float(pageCount-1) / 2) * 0.010) < 0.004 { return .page(i) }
        }
        for i in slots.indices where abs(p.x - (Float(i % 4) - 1.5) * 0.048) < 0.022 &&
            abs(p.z - (-0.061 + Float(i / 4) * 0.052)) < 0.022 { return .slot(i) }
        return nil
    }
}

struct PicoBrushBookSurface: View {
    let book: PicoBrushBook
    let brush: SpatialBrush
    let onCommand: (PicoBrushBook.Command) -> Void

    private let ivory = Color(red: 0.96, green: 0.92, blue: 0.83)
    private let brass = Color(red: 0.94, green: 0.73, blue: 0.42)
    private let enamel = Color(red: 0.16, green: 0.25, blue: 0.26)
    private let printed = Color(red: 0.19, green: 0.13, blue: 0.08)

    var body: some View {
        ZStack(alignment: .topLeading) {
            Color.clear
            Text("Brushes").font(.system(size: 13, weight: .bold)).foregroundStyle(printed)
                .position(x: 26, y: 15)
            ForEach(Array(PicoBookMode.allCases.enumerated()), id: \.element) { i, mode in
                button(mode.label, command: .mode(mode), width: 36, selected: book.mode == mode)
                    .position(x: 88 + Double(i)*42, y: 15)
            }
            button("×", command: .close, width: 20).accessibilityLabel("Close brush book").position(x: 214, y: 15)
            ForEach(Array(book.slots.enumerated()), id: \.element.id) { i, entry in
                Button { onCommand(.slot(i)) } label: {
                    ZStack(alignment: .bottom) {
                        if let icon = book.icon(i) {
                            Image(uiImage: icon).resizable().interpolation(.high).scaledToFit()
                                .frame(width: 40, height: 40).opacity(entry.brush == nil ? 0.48 : 1)
                        }
                        if entry.brush != nil || book.icon(i) == nil {
                            Text(entry.title).font(.system(size: 7, weight: .bold))
                                .lineLimit(1).minimumScaleFactor(0.65).foregroundStyle(ivory)
                        }
                    }
                    .frame(width: 43, height: 43)
                    .background(book.loaded ? Color.clear : enamel, in: RoundedRectangle(cornerRadius: 3))
                    .overlay { RoundedRectangle(cornerRadius: 3).stroke(entry.brush == brush ? brass : .clear, lineWidth: 1.5) }
                    .contentShape(Rectangle())
                }.buttonStyle(.plain).hoverEffect().accessibilityLabel(entry.title)
                    .position(x: 40 + Double(i % 4) * 48, y: 58 + Double(i / 4) * 52)
            }
            if book.page > 0 { button("‹", command: .previous, width: 24, overlayExisting: book.loaded).accessibilityLabel("Previous page").position(x: 28, y: 217) }
            if book.page < book.pageCount - 1 { button("›", command: .next, width: 24, overlayExisting: book.loaded).accessibilityLabel("Next page").position(x: 196, y: 217) }
            HStack(spacing: 0) {
                ForEach(0..<book.pageCount, id: \.self) { page in
                    Button { onCommand(.page(page)) } label: {
                        Circle().fill(page == book.page ? brass : enamel).frame(width: 5, height: 5).frame(width: 10, height: 16)
                            .contentShape(Rectangle())
                    }.buttonStyle(.plain).accessibilityLabel("Page \(page + 1)")
                }
            }.position(x: 112, y: 217)
            Group {
                if let entry = book.inspected {
                    if entry.brush != nil { Text(entry.title) }
                    else { Text("\(entry.title) · preview only") }
                } else if book.mode == .basic { Text("Choose a basic brush") }
                else { Text("Brushes") }
            }.font(.system(size: 8, weight: .semibold)).foregroundStyle(printed).lineLimit(1)
                .minimumScaleFactor(0.55).frame(width: 168).position(x: 112, y: 202)
        }
        .frame(width: 224, height: 238)
        .background(book.loaded ? .clear : Color(red: 0.67, green: 0.49, blue: 0.28), in: RoundedRectangle(cornerRadius: 5))
    }

    private func button(_ title: LocalizedStringKey, command: PicoBrushBook.Command, width: Double,
                        selected: Bool = false, overlayExisting: Bool = false) -> some View {
        Button { onCommand(command) } label: {
            Text(title).font(.system(size: 10, weight: .semibold))
                .foregroundStyle(overlayExisting ? .clear : (selected ? brass : ivory))
                .lineLimit(1).minimumScaleFactor(0.65).frame(width: width, height: 17)
                .background(overlayExisting ? .clear : enamel, in: RoundedRectangle(cornerRadius: 2))
                .contentShape(Rectangle())
        }.buttonStyle(.plain).hoverEffect()
    }
}
