import RealityKit
import SwiftUI

@MainActor @Observable
final class ArtworkLibrary {
    struct Item {
        enum Geometry {
            case triangles(Int)
            case splats(Int)
        }

        let name: String
        let entity: Entity
        let bounds: BoundingBox
        let geometry: Geometry
    }

    private(set) var items: [UUID: Item] = [:]
    private(set) var isLoading = false

    func open(_ url: URL, from folder: URL?, title: String? = nil) async throws -> UUID {
        isLoading = true
        defer { isLoading = false }
        let scoped = folder?.startAccessingSecurityScopedResource() == true
        defer { if scoped { folder?.stopAccessingSecurityScopedResource() } }
        let name = title ?? url.lastPathComponent
        let item: Item
        switch url.pathExtension.lowercased() {
        case "tilt":
            let drawing = try await Task.detached(priority: .userInitiated) {
                let fileScope = folder == nil && url.startAccessingSecurityScopedResource()
                defer { if fileScope { url.stopAccessingSecurityScopedResource() } }
                return try TiltImporter.read(url, destination: .preview).document
            }.value
            let preview = WorkspaceRenderer()
            guard !drawing.strokes.isEmpty, preview.update(drawing) else {
                throw WorkspaceError.invalid(String(localized: "No visible strokes in this artwork"))
            }
            let bounds = preview.root.visualBounds(relativeTo: preview.root)
            let extent = bounds.extents.max()
            guard extent.isFinite, extent > 0 else {
                throw WorkspaceError.invalid(String(localized: "No visible strokes in this artwork"))
            }
            preview.root.scale = SIMD3(repeating: 0.19 / extent)
            item = Item(name: name, entity: preview.root, bounds: preview.root.visualBounds(relativeTo: nil),
                        geometry: .triangles(preview.triangleCount))
        case "ply", "splat", "splt":
            #if targetEnvironment(simulator)
            throw WorkspaceError.invalid(String(localized: "Gaussian artwork requires a Vision Pro device",
                                                table: "ArtworkControls"))
            #else
            guard #available(visionOS 27, *) else {
                throw WorkspaceError.invalid(String(localized: "Gaussian artwork requires visionOS 27",
                                                    table: "ArtworkControls"))
            }
            let data = try await Task.detached(priority: .userInitiated) {
                let fileScope = folder == nil && url.startAccessingSecurityScopedResource()
                defer { if fileScope { url.stopAccessingSecurityScopedResource() } }
                return try GaussianArtworkEntity.prepare(GaussianArtwork.read(url))
            }.value
            let preview = try GaussianArtworkEntity.make(from: data)
            item = Item(name: name, entity: preview.entity, bounds: preview.bounds, geometry: .splats(data.count))
            #endif
        default:
            let entity = try await StudioReferenceModel.load(url, securityScoped: folder == nil, placement: .window)
            item = Item(name: name, entity: entity, bounds: entity.visualBounds(relativeTo: nil),
                        geometry: .triangles(Self.triangleCount(entity)))
        }
        let id = UUID()
        items[id] = item
        return id
    }

    private static func triangleCount(_ entity: Entity) -> Int {
        var count = 0
        if let mesh = entity.components[ModelComponent.self]?.mesh {
            let contents = mesh.contents
            let facesByModel = Dictionary(uniqueKeysWithValues: contents.models.map { model in
                (model.id, model.parts.reduce(0) { $0 + ($1.triangleIndices?.count ?? 0) / 3 })
            })
            count = contents.instances.reduce(0) { $0 + (facesByModel[$1.model] ?? 0) }
        }
        return entity.children.reduce(count) { $0 + triangleCount($1) }
    }

    func close(_ id: UUID) { items[id] = nil }
}

struct ArtworkWindow: View {
    let id: UUID
    let library: ArtworkLibrary
    @Environment(\.dismissWindow) private var dismissWindow
    @State private var root = Entity()
    @State private var artwork = Entity()
    @State private var dragStart: SIMD3<Float>?
    @State private var magnifyStart: Float?
    @State private var scale: Float = 1
    @State private var showControls = true
    @State private var renderingLimited = false
    @State private var renderingSubscription: EventSubscription?

    var body: some View {
        if let item = library.items[id] {
            RealityView { content, attachments in
                content.add(root)
                root.addChild(artwork)
                artwork.addChild(item.entity)
                artwork.components.set(InputTargetComponent())
                artwork.components.set(CollisionComponent(shapes: [
                    .generateBox(size: simd_max(item.bounds.extents, SIMD3(repeating: 0.015)))
                        .offsetBy(translation: item.bounds.center)
                ]))
                #if !targetEnvironment(simulator)
                if #available(visionOS 27, *), case .splats = item.geometry, let splat = item.entity.children.first {
                    renderingSubscription = content.subscribe(to: GaussianSplatEvents.RenderingChanged.self, on: splat) {
                        renderingLimited = $0.isRenderingLimited
                    }
                }
                #endif
                placeControls(attachments, bounds: item.bounds)
            } update: { _, attachments in
                artwork.scale = SIMD3(repeating: scale)
                placeControls(attachments, bounds: item.bounds)
            } attachments: {
                Attachment(id: "controls") { controls(item) }
            }
            .gesture(DragGesture().targetedToEntity(artwork).onChanged { value in
                if dragStart == nil { dragStart = artwork.position }
                let start = value.convert(value.startLocation3D, from: .local, to: root)
                let point = value.convert(value.location3D, from: .local, to: root)
                artwork.position = (dragStart ?? .zero) + point - start
                positionControls(bounds: item.bounds)
            }.onEnded { _ in dragStart = nil })
            .simultaneousGesture(MagnifyGesture().onChanged { value in
                if magnifyStart == nil { magnifyStart = scale }
                scale = min(2.5, max(0.4, (magnifyStart ?? 1) * Float(value.magnification)))
            }.onEnded { _ in magnifyStart = nil })
            .onDisappear {
                renderingSubscription?.cancel()
                library.close(id)
            }
        } else {
            ContentUnavailableView("Could not open artwork", systemImage: "cube")
        }
    }

    private func placeControls(_ attachments: RealityViewAttachments, bounds: BoundingBox) {
        guard let controls = attachments.entity(for: "controls") else { return }
        if controls.parent !== root {
            controls.name = "artwork-controls"
            root.addChild(controls)
            controls.scale = SIMD3(repeating: 0.84)
        }
        positionControls(bounds: bounds)
    }

    private func positionControls(bounds: BoundingBox) {
        guard let controls = root.findEntity(named: "artwork-controls") else { return }
        let centered = Transform(scale: artwork.scale, rotation: artwork.orientation, translation: .zero)
        let envelope = bounds.transformed(by: centered.matrix)
        // Reserve space below the model and in front of it for the toolbar.
        let minPosition = SIMD3<Float>(-0.27, -0.20, -0.27) - envelope.min
        let maxPosition = SIMD3<Float>(0.27, 0.26, 0.245) - envelope.max
        artwork.position = simd_clamp(artwork.position, simd_min(minPosition, maxPosition), maxPosition)
        let rotatedBounds = bounds.transformed(by: artwork.transform.matrix)
        controls.position = [rotatedBounds.center.x, rotatedBounds.min.y - 0.045, rotatedBounds.max.z + 0.018]
    }

    private func controls(_ item: ArtworkLibrary.Item) -> some View {
        HStack(spacing: 10) {
            if showControls {
                Button { rotate([1, 0, 0], item.bounds) } label: { Text("X").frame(minWidth: 28, minHeight: 28) }.accessibilityLabel("Rotate X")
                Button { rotate([0, 1, 0], item.bounds) } label: { Text("Y").frame(minWidth: 28, minHeight: 28) }.accessibilityLabel("Rotate Y")
                Button { rotate([0, 0, 1], item.bounds) } label: { Text("Z").frame(minWidth: 28, minHeight: 28) }.accessibilityLabel("Rotate Z")
                Slider(value: $scale, in: 0.4...2.5).frame(width: 95).accessibilityLabel("Scale")
                Button {
                    artwork.position = .zero
                    artwork.orientation = simd_quatf()
                    scale = 1
                    positionControls(bounds: item.bounds)
                } label: { Image(systemName: "scope") }.accessibilityLabel("Center")
                switch item.geometry {
                case .triangles(let count):
                    Text("Triangles: \(count)", tableName: "ArtworkControls").font(.caption2)
                case .splats(let count):
                    Text("Splats: \(count)", tableName: "ArtworkControls").font(.caption2)
                    if renderingLimited {
                        Image(systemName: "gauge.with.dots.needle.33percent")
                            .accessibilityLabel(Text("Rendering quality reduced", tableName: "ArtworkControls"))
                    }
                }
                Button { dismissWindow(id: "artwork", value: id) } label: {
                    Image(systemName: "xmark")
                }.accessibilityLabel("Close")
            }
            Button { showControls.toggle() } label: {
                Image(systemName: showControls ? "eye" : "eye.slash").frame(minWidth: 28, minHeight: 28)
            }.accessibilityLabel(showControls ? Text("Hide controls", tableName: "ArtworkControls") :
                                                Text("Show controls", tableName: "ArtworkControls"))
        }
        .font(.callout)
        .buttonStyle(.plain)
        .padding(.horizontal, showControls ? 14 : 8)
        .padding(.vertical, 8)
        .background(.black.opacity(0.60), in: Capsule())
    }

    private func rotate(_ axis: SIMD3<Float>, _ bounds: BoundingBox) {
        artwork.orientation = simd_quatf(angle: .pi / 2, axis: axis) * artwork.orientation
        positionControls(bounds: bounds)
    }
}
