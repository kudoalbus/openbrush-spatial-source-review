import Foundation
import RealityKit
import simd

@MainActor
final class SpatialStudioRenderer {
    let root = Entity()
    private let committedRoot = Entity()
    private let liveRoot = Entity()
    private let selectionRoot = Entity()
    let referenceRoot = Entity()
    private let cursor = ModelEntity(mesh: .generateSphere(radius: 0.01),
                                     materials: [UnlitMaterial(color: .init(white: 1, alpha: 1))])
    private var markedStroke: PaintStroke?
    private var committed: [String: (PaintStroke, Entity)] = [:]
    private var lastLiveUpdate: TimeInterval = -.infinity
    private var liveStrokeID: String?
    private var livePointCount = 0
    private var liveTail: Entity?
    private var frozenThrough = 0
    private static let previewChunkSize = 96
    var previewSegmentCount: Int { liveRoot.children.count }
    private(set) var renderedDocument: DrawingDocument?
    private(set) var failure: String?

    init() {
        root.addChild(committedRoot)
        root.addChild(liveRoot)
        root.addChild(selectionRoot)
        root.addChild(referenceRoot)
        root.addChild(cursor)
        cursor.isEnabled = false
    }

    func update(_ document: DrawingDocument) {
        guard renderedDocument != document else { return }
        do {
            var next: [String: (PaintStroke, Entity)] = [:]
            for stroke in document.strokes {
                if let cached = committed[stroke.id], cached.0 == stroke {
                    next[stroke.id] = cached
                } else {
                    let entity = try WorkspaceRenderer.entity(for: stroke, parts: StrokeAppearance.parts(stroke))
                    next[stroke.id] = (stroke, entity ?? Entity())
                }
            }
            for (id, old) in committed where next[id]?.1 !== old.1 {
                old.1.removeFromParent()
            }
            for value in next.values where value.1.parent == nil { committedRoot.addChild(value.1) }
            committed = next
            renderedDocument = document
            failure = nil
        } catch {
            failure = error.localizedDescription
        }
    }

    func select(_ id: String) {
        let stroke = committed[id]?.0
        guard stroke != markedStroke else { return }
        selectionRoot.children.removeAll()
        markedStroke = stroke
        guard let stroke, let first = stroke.visibleSpans.first, let last = stroke.visibleSpans.last else { return }
        for distance in [first.start, last.end] {
            let point = stroke.point(at: distance)
            let marker = ModelEntity(mesh: .generateSphere(radius: max(0.004, stroke.radius(point.pressure) * 0.7)),
                                     materials: [UnlitMaterial(color: .yellow)])
            marker.position = point.position
            selectionRoot.addChild(marker)
        }
    }

    func moveCursor(to position: SIMD3<Float>, radius: Float) {
        cursor.position = position
        cursor.scale = SIMD3(repeating: max(0.003, radius) / 0.01)
        cursor.isEnabled = true
    }

    func hideCursor() { cursor.isEnabled = false }

    func preview(_ stroke: PaintStroke?, at timestamp: TimeInterval) {
        guard let stroke else { clearPreview(); return }
        if stroke.id != liveStrokeID {
            clearPreview()
            liveStrokeID = stroke.id
        }
        let geometry = OpenBrushCatalog.bundled.profile(for: stroke.brush)?.geometry
        let fillsSurface = geometry == .hull || geometry == .membrane
        let refreshInterval: TimeInterval = fillsSurface ? 1.0 / 15 : 1.0 / 30
        guard livePointCount != stroke.points.count, timestamp - lastLiveUpdate >= refreshInterval else { return }
        lastLiveUpdate = timestamp
        do {
            // Retain completed preview chunks and regenerate just the tail.
            // Adjacent chunks share a control point. The committed mesh uses the full curve.
            while !fillsSurface && stroke.points.count - frozenThrough > Self.previewChunkSize {
                let next = frozenThrough + Self.previewChunkSize - 1
                if let chunk = try previewEntity(for: stroke, points: frozenThrough...next) {
                    liveRoot.addChild(chunk)
                }
                frozenThrough = next
            }
            let tail = try previewEntity(for: stroke, points: frozenThrough...(stroke.points.count - 1))
            liveTail?.removeFromParent()
            liveTail = tail
            if let tail { liveRoot.addChild(tail) }
            livePointCount = stroke.points.count
        } catch {
            failure = error.localizedDescription
        }
    }

    private func previewEntity(for stroke: PaintStroke, points: ClosedRange<Int>) throws -> ModelEntity? {
        var chunk = stroke
        chunk.points = Array(stroke.points[points])
        let part = DisplayPart(strokeID: stroke.id, span: ArcSpan(start: 0, end: chunk.length), color: stroke.color)
        return try WorkspaceRenderer.entity(for: chunk, parts: [part], sides: 8)
    }

    private func clearPreview() {
        guard liveStrokeID != nil else { return }
        liveRoot.children.removeAll()
        liveStrokeID = nil
        liveTail = nil
        frozenThrough = 0
        livePointCount = 0
        lastLiveUpdate = -.infinity
    }
}
