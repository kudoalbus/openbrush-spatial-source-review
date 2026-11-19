import Foundation
import RealityKit
import simd

enum StudioReferenceModel {
    enum Placement { case studio, window }
    // Copy while the Files security scope is open. RealityKit can then resolve
    // the package asynchronously from an app-owned location.
    static func stageUSDZ(_ url: URL, securityScoped: Bool) throws -> URL {
        let scoped = securityScoped && url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard url.pathExtension.lowercased() == "usdz" else {
            throw WorkspaceError.invalid(String(localized: "Choose a USDZ model"))
        }
        let values = try url.resourceValues(forKeys: [.isRegularFileKey])
        guard values.isRegularFile == true else {
            throw WorkspaceError.invalid(String(localized: "Choose a USDZ model"))
        }
        let manager = FileManager.default
        let folder = manager.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try manager.createDirectory(at: folder, withIntermediateDirectories: false)
        let staged = folder.appendingPathComponent("reference.usdz")
        do {
            try manager.copyItem(at: url, to: staged)
            return staged
        } catch {
            try? manager.removeItem(at: folder)
            throw error
        }
    }

    @MainActor
    static func load(_ url: URL, securityScoped: Bool, placement: Placement = .studio) async throws -> Entity {
        let staged = try await Task.detached(priority: .userInitiated) {
            try stageUSDZ(url, securityScoped: securityScoped)
        }.value
        defer { try? FileManager.default.removeItem(at: staged.deletingLastPathComponent()) }
        let model = try await Entity(contentsOf: staged)
        let bounds = model.visualBounds(relativeTo: nil)
        let extent = bounds.extents.max()
        guard extent.isFinite, extent > 1e-6,
              bounds.center.x.isFinite, bounds.center.y.isFinite, bounds.center.z.isFinite else {
            throw WorkspaceError.invalid(String(localized: "USDZ has no visible geometry"))
        }
        let reference = Entity()
        reference.addChild(model)
        let scale: Float = placement == .studio ? min(1, 0.28 / extent) : 0.19 / extent
        reference.scale = SIMD3(repeating: scale)
        let offset: SIMD3<Float> = placement == .studio ? [-0.43, -0.20, -0.16] : .zero
        reference.position = offset - bounds.center * scale
        return reference
    }
}
