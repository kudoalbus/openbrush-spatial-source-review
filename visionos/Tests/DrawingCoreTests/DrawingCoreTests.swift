import XCTest
import simd
@testable import DrawingCore

final class DrawingCoreTests: XCTestCase {
    private var fixtureURL: URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("ReviewResources/workspace-v3.json")
    }
    private let orange = InkColor(r: 1, g: 0.55, b: 0.05)
    private func fixture() throws -> DrawingDocument { try DrawingCodec.read(fixtureURL) }
    private func rawFixture() throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: fixtureURL)) as? [String: Any])
    }
    private func data(_ raw: [String: Any]) throws -> Data { try JSONSerialization.data(withJSONObject: raw) }
    private func edited(_ original: DrawingDocument) -> DrawingDocument {
        DrawingDocument(strokes: original.strokes.map {
            StrokeAppearance.recolor($0, in: ArcSpan(start: 0.10, end: 0.18), color: orange)
        })
    }
    func testSharedFixtureHasExactColorsAndGap() throws {
        let doc = try fixture(), s = doc.strokes[0]
        XCTAssertEqual(s.id, "interop-stroke-1")
        XCTAssertEqual(s.length, 0.32, accuracy: 1e-6)
        let parts = StrokeAppearance.parts(s)
        XCTAssertEqual(parts.map(\.span), [.init(start: 0, end: 0.08), .init(start: 0.08, end: 0.12),
                                         .init(start: 0.16, end: 0.20), .init(start: 0.20, end: 0.32)])
        XCTAssertEqual(parts.map(\.color), [s.color, s.recolored[0].color, s.recolored[1].color, s.color])
        XCTAssertEqual(try DrawingCodec.decode(DrawingCodec.encode(doc)), doc)
    }
    func testVersionsOneAndTwoUpgradeToThree() throws {
        for version in [1, 2] {
            var raw = try rawFixture()
            raw["version"] = version
            var rows = try XCTUnwrap(raw["strokes"] as? [[String: Any]])
            rows[0].removeValue(forKey: "recolored")
            if version == 1 {
                rows[0]["points"] = (rows[0]["points"] as! [[Any]]).map { Array($0.prefix(5)) }
            }
            raw["strokes"] = rows
            let doc = try DrawingCodec.decode(data(raw))
            XCTAssertTrue(doc.strokes[0].recolored.isEmpty)
            XCTAssertEqual(doc.strokes[0].points[0].normal, SIMD3(0, 0, 1))
            let saved = try DrawingCodec.encode(doc)
            let root = try XCTUnwrap(JSONSerialization.jsonObject(with: saved) as? [String: Any])
            XCTAssertEqual(root["version"] as? Int, 3)
            let points = ((root["strokes"] as! [[String: Any]])[0]["points"] as! [[Any]])
            XCTAssertTrue(points.allSatisfy { $0.count == 8 })
            XCTAssertEqual(try DrawingCodec.decode(saved), doc)
        }
    }
    func testEditUndoRedoSaveReopenPreservesOriginalData() throws {
        let original = try fixture(), next = edited(original)
        let history = DrawingHistory(original)
        history.commit(next)
        XCTAssertNotEqual(next, original)
        XCTAssertEqual(next.strokes[0].id, original.strokes[0].id)
        XCTAssertEqual(next.strokes[0].points, original.strokes[0].points)
        XCTAssertEqual(next.strokes[0].erased, original.strokes[0].erased)
        XCTAssertEqual(next.strokes[0].color, original.strokes[0].color)
        XCTAssertTrue(history.undo()); XCTAssertEqual(history.document, original)
        XCTAssertTrue(history.redo()); XCTAssertEqual(history.document, next)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".json")
        defer { try? FileManager.default.removeItem(at: url) }
        try DrawingCodec.save(history.document, to: url)
        XCTAssertEqual(try DrawingCodec.read(url), next)
        XCTAssertTrue(StrokeAppearance.parts(next.strokes[0]).allSatisfy { $0.span.end <= 0.12 || $0.span.start >= 0.16 })
        XCTAssertTrue(next.strokes[0].recolored.allSatisfy { $0.span.end <= 0.12 || $0.span.start >= 0.16 })
    }
    func testErasedOnlyEditIsNoOpAndPreservesRedo() throws {
        let original = try fixture(), history = DrawingHistory(original)
        history.commit(edited(original)); history.undo()
        let s = original.strokes[0]
        let result = StrokeAppearance.recolor(s, in: .init(start: 0.125, end: 0.155), color: orange)
        history.commit(.init(strokes: [result]))
        XCTAssertEqual(result, s); XCTAssertTrue(history.canRedo)
    }
    func testHiddenColorAndAlphaRemainUnchanged() throws {
        var s = try fixture().strokes[0]
        s.color.a = 0.3
        s.recolored = [ColorSpan(span: .init(start: 0.10, end: 0.18), color: .init(r: 1, g: 0, b: 0, a: 0.3))]
        let changed = StrokeAppearance.recolor(s, in: .init(start: 0, end: 0.32), color: orange)
        XCTAssertEqual(changed.erased, s.erased)
        XCTAssertTrue(changed.recolored.allSatisfy { $0.color.a == 0.3 })
        XCTAssertEqual(changed.recolored.first { $0.span.start == 0.12 && $0.span.end == 0.16 }?.color,
                       s.recolored[0].color)
        s.color.a = 0
        XCTAssertEqual(StrokeAppearance.recolor(s, in: .init(start: 0, end: 0.32), color: orange), s)
        XCTAssertTrue(StrokeAppearance.parts(s).isEmpty)
    }
    func testRepeatedContactAndBaseColorReset() throws {
        let s = try fixture().strokes[0], selection = ArcSpan(start: 0.10, end: 0.18)
        let once = StrokeAppearance.recolor(s, in: selection, color: orange)
        XCTAssertEqual(StrokeAppearance.recolor(once, in: selection, color: orange), once)
        let reset = StrokeAppearance.recolor(once, in: .init(start: 0, end: 0.32), color: s.color)
        XCTAssertTrue(reset.recolored.isEmpty)
        XCTAssertEqual(reset.erased, s.erased)
    }
    func testMeshesForAllBrushesNeverCrossGap() throws {
        for brush in ["spatial_tube", "spatial_flat", "spatial_fan"] {
            var s = try fixture().strokes[0]; s.brush = brush
            for part in StrokeAppearance.parts(s) {
                let mesh = StrokeMesh.build(stroke: s, part: part)
                XCTAssertFalse(mesh.indices.isEmpty)
                XCTAssertEqual(mesh.positions.count, mesh.normals.count)
                XCTAssertTrue(mesh.indices.allSatisfy { Int($0) < mesh.positions.count })
                for p in mesh.positions {
                    XCTAssertTrue(p.x.isFinite && p.y.isFinite && p.z.isFinite)
                    XCTAssertTrue(p.x <= 0.120001 || p.x >= 0.159999)
                }
                for i in stride(from: 0, to: mesh.indices.count, by: 3) {
                    let xs = (0..<3).map { mesh.positions[Int(mesh.indices[i + $0])].x }
                    XCTAssertLessThanOrEqual(xs.max()! - xs.min()!, part.span.end - part.span.start + 1e-6)
                }
            }
        }
    }
    func testFullyErasedStrokeRetainsIdentityAndHasNoGeometry() throws {
        var doc = try fixture(); doc.strokes[0].erased = [.init(start: 0, end: 0.32)]
        let decoded = try DrawingCodec.decode(DrawingCodec.encode(doc))
        XCTAssertEqual(decoded, doc)
        XCTAssertTrue(StrokeAppearance.parts(decoded.strokes[0]).isEmpty)
        XCTAssertEqual(edited(doc), doc)
    }
    func testRejectsInvalidDocuments() throws {
        for change in ["version", "units", "duplicate", "arity", "alpha", "overlap", "outside", "missing", "pressure", "zero", "width"] {
            var raw = try rawFixture(), rows = raw["strokes"] as! [[String: Any]]
            switch change {
            case "version": raw["version"] = 4
            case "units": raw["units"] = "centimeters"
            case "duplicate": rows.append(rows[0])
            case "arity": rows[0]["points"] = [[0, 0, 0, 1, 0], [0.32, 0, 0, 1, 320]]
            case "alpha": rows[0]["recolored"] = [[0.08, 0.12, 1, 0, 0, 0.5]]
            case "overlap": rows[0]["recolored"] = [[0.08, 0.12, 1, 0, 0, 1], [0.10, 0.14, 1, 0, 0, 1]]
            case "outside": rows[0]["erased"] = [[0.12, 1]]
            case "missing": rows[0].removeValue(forKey: "recolored")
            case "pressure": rows[0]["points"] = [[0, 0, 0, 2, 0, 0, 0, 1], [0.32, 0, 0, 1, 320, 0, 0, 1]]
            case "zero": rows[0]["points"] = [[0, 0, 0, 1, 0, 0, 0, 1], [0, 0, 0, 1, 320, 0, 0, 1]]
            default: rows[0]["width"] = 0
            }
            raw["strokes"] = rows
            XCTAssertThrowsError(try DrawingCodec.decode(data(raw)), change)
        }
    }
    func testExactLargeTimestampAndUnnormalizedErasurePreservation() throws {
        var doc = try fixture()
        doc.strokes[0].points[1].timeMs = 9_007_199_254_740_993
        doc.strokes[0].erased = [.init(start: 0.14, end: 0.16), .init(start: 0.12, end: 0.145)]
        XCTAssertEqual(try DrawingCodec.decode(DrawingCodec.encode(doc)), doc)
        XCTAssertEqual(doc.strokes[0].visibleSpans, [.init(start: 0, end: 0.12), .init(start: 0.16, end: 0.32)])
        doc.strokes[0].points[1].timeMs = Int64.max
        XCTAssertEqual(doc.strokes[0].point(at: 0.32).timeMs, Int64.max)
        XCTAssertGreaterThan(doc.strokes[0].point(at: 0.20).timeMs, 0)
    }
    func testHistoryLimitAndNewEditClearsRedo() throws {
        let original = try fixture(), history = DrawingHistory(original)
        for i in 0..<110 {
            var next = original; next.strokes[0].width = 0.01 + Float(i) * 0.0001
            history.commit(next)
        }
        var count = 0
        while history.undo() { count += 1 }
        XCTAssertEqual(count, 100)
        history.commit(edited(original)); XCTAssertFalse(history.canRedo)
    }
    func testCurvedStrokeAndDuplicatePositionsProduceFiniteGeometry() throws {
        var s = try fixture().strokes[0]
        s.erased = []; s.recolored = []
        s.points.insert(s.points[0], at: 1)
        var middle = s.points[0]; middle.position = SIMD3(0.10, 0.1, 0.02)
        s.points.insert(middle, at: 2)
        for part in StrokeAppearance.parts(s) {
            let mesh = StrokeMesh.build(stroke: s, part: part)
            XCTAssertTrue(mesh.normals.allSatisfy { $0.x.isFinite && $0.y.isFinite && $0.z.isFinite })
        }
    }
}
