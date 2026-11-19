import Foundation

struct DisplayPart: Equatable, Sendable {
    var strokeID: String
    var span: ArcSpan
    var color: InkColor
}

enum StrokeAppearance {
    // Arc edits are shared by the explicit sliders and the spatial contact tool.
    static func recolor(_ original: PaintStroke, in selection: ArcSpan, color: InkColor) -> PaintStroke {
        guard original.color.a > 0, selection.start.isFinite, selection.end.isFinite,
              selection.start >= 0, selection.end >= selection.start,
              color.components.allSatisfy({ $0.isFinite && (0...1).contains($0) }) else { return original }
        let cuts = original.visibleSpans.compactMap { $0.intersection(selection) }
        guard !cuts.isEmpty else { return original }
        var target = color
        target.a = original.color.a
        var next = original.recolored
        for cut in cuts {
            next = next.flatMap { old -> [ColorSpan] in
                if old.span.end <= cut.start || old.span.start >= cut.end { return [old] }
                var pieces: [ColorSpan] = []
                if old.span.start < cut.start { pieces.append(ColorSpan(span: ArcSpan(start: old.span.start, end: cut.start), color: old.color)) }
                if old.span.end > cut.end { pieces.append(ColorSpan(span: ArcSpan(start: cut.end, end: old.span.end), color: old.color)) }
                return pieces
            }
            if target != original.color { next.append(ColorSpan(span: cut, color: target)) }
        }
        var merged: [ColorSpan] = []
        for item in next.sorted(by: { $0.span.start < $1.span.start }) {
            if let last = merged.last, last.color == item.color, item.span.start <= last.span.end + 1e-6 {
                merged[merged.count - 1].span.end = max(last.span.end, item.span.end)
            } else { merged.append(item) }
        }
        guard merged.count <= 20_000 else { return original }
        var result = original
        result.recolored = merged
        return result
    }

    static func parts(_ stroke: PaintStroke) -> [DisplayPart] {
        guard stroke.color.a > 0 else { return [] }
        var ranges: [ColorSpan] = []
        var cursor: Float = 0
        for item in stroke.recolored {
            if item.span.start - cursor > 1e-6 {
                ranges.append(ColorSpan(span: ArcSpan(start: cursor, end: item.span.start), color: stroke.color))
            }
            ranges.append(item)
            cursor = item.span.end
        }
        let length = stroke.length
        if length - cursor > 1e-6 {
            ranges.append(ColorSpan(span: ArcSpan(start: cursor, end: length), color: stroke.color))
        }
        let visible = stroke.visibleSpans
        var parts: [DisplayPart] = []
        var i = 0, j = 0
        while i < ranges.count && j < visible.count {
            let range = ranges[i], span = visible[j]
            if let overlap = range.span.intersection(span) {
                parts.append(DisplayPart(strokeID: stroke.id, span: overlap, color: range.color))
            }
            if range.span.end < span.end { i += 1 }
            else { j += 1 }
        }
        return parts
    }
}

final class DrawingHistory {
    private(set) var document: DrawingDocument
    private var past: [DrawingDocument] = []
    private var future: [DrawingDocument] = []
    var canUndo: Bool { !past.isEmpty }
    var canRedo: Bool { !future.isEmpty }
    init(_ document: DrawingDocument) { self.document = document }
    func commit(_ next: DrawingDocument) {
        guard next != document else { return }
        past.append(document)
        if past.count > 100 { past.removeFirst() }
        future.removeAll()
        document = next
    }
    @discardableResult func undo() -> Bool {
        guard let previous = past.popLast() else { return false }
        future.append(document); document = previous
        return true
    }
    @discardableResult func redo() -> Bool {
        guard let next = future.popLast() else { return false }
        past.append(document); document = next
        return true
    }
}
