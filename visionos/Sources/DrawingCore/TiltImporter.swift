// Format reference: Open Brush File Format and the PICO TiltReader/TiltGeometry.
// Open Brush Toolkit reference parser: Copyright 2016 Google Inc., Apache-2.0.
import Foundation
import simd

struct TiltImport: Sendable {
    let document: DrawingDocument
    let skipped: Int
    let unmappedBrushes: Int
    let clampedWidths: Int
}

enum TiltImporter {
    private struct Metadata: Decodable {
        let BrushIndex: [String]
    }

    enum Destination { case editable, preview }

    static func read(_ url: URL, destination: Destination = .editable, catalog: OpenBrushCatalog = .bundled) throws -> TiltImport {
        try decode(Data(contentsOf: url, options: .mappedIfSafe), destination: destination, catalog: catalog)
    }

    static func decode(_ data: Data, destination: Destination = .editable, catalog: OpenBrushCatalog = .bundled) throws -> TiltImport {
        let archive = try TiltArchive.read(data)
        let metadata = try JSONDecoder().decode(Metadata.self, from: archive.metadata)
        var reader = LittleEndianReader(data: archive.sketch)
        guard try reader.u32() == 0xc576a5cd, (5...6).contains(try reader.u32()), try reader.u32() == 0 else {
            throw WorkspaceError.invalid("Only Open Brush sketch versions 5 and 6 are supported")
        }
        try reader.skip(Int(reader.u32()))
        let count = Int(try reader.u32())
        guard count <= reader.remaining / 36 else { throw WorkspaceError.invalid("Truncated TILT strokes") }
        if destination == .editable, count > DrawingCodec.maximumStrokes {
            throw WorkspaceError.invalid("TILT exceeds the editable v3 stroke limit. Open it as a model to preview it.")
        }
        var strokes: [PaintStroke] = []
        var totalPoints = 0, skipped = 0, clamped = 0
        var unknown: Set<String> = []
        for _ in 0..<count {
            let brushIndex = Int(try reader.u32())
            guard metadata.BrushIndex.indices.contains(brushIndex) else {
                throw WorkspaceError.invalid("Invalid TILT brush index")
            }
            let rgba = try (0..<4).map { _ in try min(1, max(0, reader.float())) }
            let size = try reader.float()
            let strokeMask = try reader.u32(), pointMask = try reader.u32()
            var scale: Float = 1
            for bit in 0..<32 where strokeMask & (1 << bit) != 0 {
                if bit >= 16 { try reader.skip(Int(reader.u32())) }
                else if bit == 1 { scale = try reader.float() }
                else { try reader.skip(4) }
            }
            let pointCount = Int(try reader.u32())
            totalPoints += pointCount
            guard pointCount <= reader.remaining / (28 + pointMask.nonzeroBitCount * 4) else {
                throw WorkspaceError.invalid("Truncated TILT points")
            }
            if destination == .editable,
               pointCount > DrawingCodec.maximumStrokePoints || totalPoints > DrawingCodec.maximumPoints {
                throw WorkspaceError.invalid("TILT exceeds editable v3 point limits. Open it as a model to preview it.")
            }
            let points = try (0..<pointCount).map { _ in try readPoint(&reader, mask: pointMask) }
            let width = size * scale * 0.1
            guard width.isFinite, size >= 0, scale >= 0 else { throw WorkspaceError.invalid("Invalid TILT brush width") }
            guard width > 0, points.count >= 2 else { skipped += 1; continue }
            let guid = metadata.BrushIndex[brushIndex].lowercased()
            let brush = SpatialBrush(rawValue: guid) ?? .flat
            if catalog.profile(for: guid) == nil { unknown.insert(guid) }
            let bounded = min(0.15, max(0.001, width))
            let stroke = PaintStroke(id: UUID().uuidString, brush: brush.rawValue, width: bounded,
                                     color: .init(r: rgba[0], g: rgba[1], b: rgba[2], a: rgba[3]),
                                     points: points, erased: [], recolored: [])
            guard stroke.length.isFinite else { throw WorkspaceError.invalid("TILT stroke extent is invalid") }
            guard stroke.length > 1e-6 else { skipped += 1; continue }
            if width != bounded { clamped += 1 }
            strokes.append(stroke)
        }
        guard !strokes.isEmpty else { throw WorkspaceError.invalid("TILT contains no editable strokes") }
        return TiltImport(document: DrawingDocument(strokes: strokes), skipped: skipped,
                          unmappedBrushes: unknown.count, clampedWidths: clamped)
    }

    private static func readPoint(_ reader: inout LittleEndianReader, mask: UInt32) throws -> PaintPoint {
        let x = try reader.float(), y = try reader.float(), z = try reader.float()
        let rotation = try SIMD4(reader.float(), reader.float(), reader.float(), reader.float())
        let extent = simd_length(rotation)
        let forward: SIMD3<Float>
        if extent.isFinite, extent > 1e-6 {
            forward = simd_quatf(vector: rotation / extent).act([0, 0, 1])
        } else {
            forward = [0, 0, 1]
        }
        var pressure: Float = 1
        var timeMs: Int64 = 0
        for bit in 0..<32 where mask & (1 << bit) != 0 {
            if bit == 0 { pressure = try min(1, max(0, reader.float())) }
            else if bit == 1 { timeMs = Int64(try reader.u32()) }
            else { try reader.skip(4) }
        }
        // PICO and Open Brush use the sketch's Unity-space control points.
        // Convert once into right-handed document meters, separate from room placement.
        return PaintPoint(position: [x * 0.1, y * 0.1, -z * 0.1], pressure: pressure,
                          timeMs: timeMs, normal: [forward.x, forward.y, -forward.z])
    }
}
