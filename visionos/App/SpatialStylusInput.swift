import ARKit
@preconcurrency import GameController
import QuartzCore
import RealityKit
import Spatial

@available(visionOS 26.0, *)
@MainActor
enum SpatialStylusInput {
    static func track(in studio: SpatialStudio, model: WorkspaceModel) async {
        guard AccessoryTrackingProvider.isSupported else { return }
        while !Task.isCancelled {
            if let stylus = GCStylus.styli.first(where: { $0.productCategory == GCProductCategorySpatialStylus }) {
                await track(stylus, in: studio, model: model)
                // Wait for a disconnect before retrying a failed or declined pairing.
                while GCStylus.styli.contains(stylus), !Task.isCancelled {
                    do { try await Task.sleep(for: .milliseconds(450)) } catch { return }
                }
            }
            studio.stylusStatus = nil
            do { try await Task.sleep(for: .milliseconds(450)) } catch { return }
        }
    }

    private static func track(_ stylus: GCStylus, in studio: SpatialStudio, model: WorkspaceModel) async {
        let session = ARKitSession()
        defer {
            session.stop()
            studio.endStylusStroke(model)
        }
        do {
            let accessory = try await Accessory(device: stylus)
            let permissions = await session.requestAuthorization(for: [.accessoryTracking])
            guard !Task.isCancelled else { return }
            guard permissions[.accessoryTracking] == .allowed else {
                studio.stylusStatus = String(localized: "Allow accessory tracking to use the stylus", table: "StudioInput")
                return
            }
            let provider = AccessoryTrackingProvider(accessories: [accessory])
            try await session.run([provider])
            studio.stylusStatus = String(localized: "Stylus: tip or front button draws · rear button erases", table: "StudioInput")

            var releasedSinceLoss = false
            var previousTimestamp: TimeInterval?
            var previousTool: SpatialTool?
            while !Task.isCancelled, GCStylus.styli.contains(stylus) {
                let buttons = stylus.input?.capture().buttons
                let tip = buttons?[.stylusTip]?.pressedInput
                let front = buttons?[.stylusPrimaryButton]?.pressedInput
                let rear = buttons?[.stylusSecondaryButton]?.pressedInput
                let tool: SpatialTool? = rear?.isPressed == true ? .erase :
                    (tip?.isPressed == true || front?.isPressed == true ? .draw : nil)
                let now = CACurrentMediaTime()

                if studio.acceptsStylusInput(model), let anchor = provider.latestAnchors.first, anchor.isTracked,
                   anchor.trackingState == .positionOrientationTracked, now - anchor.timestamp < 0.15,
                   let pose = pose(anchor, relativeTo: studio.renderer.root) {
                    if tool == nil { releasedSinceLoss = true }
                    if releasedSinceLoss, previousTimestamp != anchor.timestamp || previousTool != tool {
                        let pressure = tool == .draw ? max(tip?.value ?? 0, front?.value ?? 0) : 1
                        studio.useStylus(.init(position: pose.0, normal: pose.1, timestamp: now, pressure: pressure),
                                         tool: tool, model: model)
                    }
                    previousTimestamp = anchor.timestamp
                    previousTool = tool
                } else {
                    studio.endStylusStroke(model)
                    releasedSinceLoss = false
                    previousTimestamp = nil
                    previousTool = nil
                }
                try await Task.sleep(for: .milliseconds(12))
            }
        } catch is CancellationError {
            return
        } catch {
            studio.stylusStatus = String(localized: "Spatial stylus tracking unavailable", table: "StudioInput")
        }
    }

    private static func pose(_ anchor: AccessoryAnchor, relativeTo root: Entity) -> (SIMD3<Float>, SIMD3<Float>)? {
        let tipSpace = anchor.coordinateSpace(for: .aim, correction: .rendered)
        guard let point = try? tipSpace.convert(value: Point3DFloat.zero, to: root) else { return nil }
        let position = SIMD3(point.x, point.y, point.z)
        guard let forward = try? tipSpace.convert(value: Point3DFloat(x: 0, y: 0, z: 0.01), to: root) else {
            return (position, [0, 0, 1])
        }
        return (position, SIMD3(forward.x, forward.y, forward.z) - position)
    }
}
