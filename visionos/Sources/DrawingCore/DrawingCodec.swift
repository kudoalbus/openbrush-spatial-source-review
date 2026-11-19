import Foundation
import simd

enum WorkspaceError: Error, LocalizedError {
    case invalid(String)
    var errorDescription: String? { if case .invalid(let reason) = self { return reason }; return nil }
}

private func require(_ condition: Bool, _ reason: String) throws {
    if !condition { throw WorkspaceError.invalid(reason) }
}

// Decode timestamps as Int64, never via Double (which loses integers above 2^53).
private struct WirePoint: Codable {
    var point: PaintPoint
    var arity: Int
    init(_ point: PaintPoint) { self.point = point; arity = 8 }
    init(from decoder: Decoder) throws {
        var row = try decoder.unkeyedContainer()
        arity = row.count ?? 0
        try require(arity == 5 || arity == 8, "Control points must contain 5 or 8 values")
        let position = SIMD3(try row.decode(Float.self), try row.decode(Float.self), try row.decode(Float.self))
        let pressure = try row.decode(Float.self)
        let time = try row.decode(Int64.self)
        let normal = arity == 5 ? SIMD3<Float>(0, 0, 1) :
            SIMD3(try row.decode(Float.self), try row.decode(Float.self), try row.decode(Float.self))
        point = PaintPoint(position: position, pressure: pressure, timeMs: time, normal: normal)
    }
    func encode(to encoder: Encoder) throws {
        var row = encoder.unkeyedContainer()
        try row.encode(point.position.x); try row.encode(point.position.y); try row.encode(point.position.z)
        try row.encode(point.pressure); try row.encode(point.timeMs)
        try row.encode(point.normal.x); try row.encode(point.normal.y); try row.encode(point.normal.z)
    }
}

private struct WireStroke: Codable {
    var id: String
    var brush: String
    var width: Float
    var color: [Float]
    var points: [WirePoint]
    var erased: [[Float]]
    var recolored: [[Float]]?
}
private struct WireDocument: Codable {
    var version: Int
    var units: String
    var strokes: [WireStroke]
}

enum DrawingCodec {
    static let maximumStrokes = 2_000
    static let maximumStrokePoints = 4_096
    static let maximumPoints = 200_000
    static func read(_ url: URL) throws -> DrawingDocument {
        try decode(Data(contentsOf: url, options: .mappedIfSafe))
    }
    static func decode(_ data: Data) throws -> DrawingDocument {
        let raw = try JSONDecoder().decode(WireDocument.self, from: data)
        try require((1...3).contains(raw.version) && raw.units == "meters_rh", "Unsupported version or coordinate units")
        try require(raw.strokes.count <= maximumStrokes, "Too many strokes")
        var pointCount = 0
        var ids = Set<String>()
        let strokes = try raw.strokes.map { row -> PaintStroke in
            try require(!row.id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && ids.insert(row.id).inserted,
                        "Empty or duplicate stroke identity")
            try require(row.width.isFinite && (0.001...0.15).contains(row.width), "Invalid stroke width")
            let color = try ink(row.color)
            try require((2...maximumStrokePoints).contains(row.points.count), "Invalid control-point count")
            pointCount += row.points.count
            try require(pointCount <= maximumPoints, "Too many control points")
            for wire in row.points {
                let p = wire.point
                try require(wire.arity == (raw.version == 1 ? 5 : 8), "Control-point arity does not match version")
                let values = [p.position.x, p.position.y, p.position.z, p.pressure, p.normal.x, p.normal.y, p.normal.z]
                try require(values.allSatisfy(\.isFinite) && (0...1).contains(p.pressure) && p.timeMs >= 0 && simd_length(p.normal) > 0.01,
                            "Invalid control point")
            }
            var stroke = PaintStroke(id: row.id, brush: row.brush, width: row.width, color: color,
                                     points: row.points.map(\.point), erased: [], recolored: [])
            let length = stroke.length
            try require(length.isFinite && length > 1e-6, "Stroke has no finite arc length")
            try require(row.erased.count <= 20_000, "Too many erasure intervals")
            stroke.erased = try row.erased.map { values in
                try require(values.count == 2, "Erasure intervals require two values")
                let interval = try span(values[0], values[1])
                try require(interval.end <= length + 0.0001, "Erasure exceeds original stroke")
                return interval
            }
            if raw.version == 3 {
                guard let colors = row.recolored else { throw WorkspaceError.invalid("v3 requires recolored") }
                try require(colors.count <= 20_000, "Too many color intervals")
                var end: Float = 0
                stroke.recolored = try colors.map { values in
                    try require(values.count == 6, "Color intervals require six values")
                    let interval = try span(values[0], values[1])
                    try require(interval.start >= end && interval.end <= length && interval.end - interval.start > 1e-6,
                                "Color intervals overlap or exceed original stroke")
                    end = interval.end
                    let tint = try ink(Array(values[2...5]))
                    try require(tint.a == color.a, "Local color cannot change original alpha")
                    return ColorSpan(span: interval, color: tint)
                }
            }
            return stroke
        }
        return DrawingDocument(strokes: strokes)
    }
    static func encode(_ document: DrawingDocument) throws -> Data {
        let rows = document.strokes.map { s in
            WireStroke(id: s.id, brush: s.brush, width: s.width, color: s.color.components,
                       points: s.points.map(WirePoint.init), erased: s.erased.map { [$0.start, $0.end] },
                       recolored: s.recolored.map { [$0.span.start, $0.span.end] + $0.color.components })
        }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        let data = try encoder.encode(WireDocument(version: 3, units: "meters_rh", strokes: rows))
        _ = try decode(data) // Never write a document that our reader or the shared limits reject.
        return data
    }
    static func save(_ document: DrawingDocument, to url: URL) throws {
        try encode(document).write(to: url, options: .atomic)
    }
    private static func span(_ start: Float, _ end: Float) throws -> ArcSpan {
        try require(start.isFinite && end.isFinite && start >= 0 && end >= start, "Invalid arc interval")
        return ArcSpan(start: start, end: end)
    }
    private static func ink(_ values: [Float]) throws -> InkColor {
        try require(values.count == 4 && values.allSatisfy { $0.isFinite && (0...1).contains($0) }, "Invalid RGBA color")
        return InkColor(r: values[0], g: values[1], b: values[2], a: values[3])
    }
}
