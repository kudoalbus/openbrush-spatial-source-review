import XCTest
@testable import DrawingCore

final class SpatialStrokeEditTests: XCTestCase {
    private func stroke(id: String = "paint", _ positions: [SIMD3<Float>]) -> PaintStroke {
        PaintStroke(id: id, brush: "spatial_tube", width: 0.02, color: .init(r: 1, g: 0, b: 0),
                    points: positions.map { PaintPoint(position: $0, pressure: 1, timeMs: 0, normal: [0, 0, 1]) },
                    erased: [], recolored: [])
    }

    func testCapsuleErasesBetweenSparseSamples() throws {
        let original = stroke([[-0.1, 0, 0], [0.1, 0, 0]])
        var path = stroke(id: "tool", [[0, 0.08, 0], [0, -0.08, 0]])
        path.width = 0.06
        let erased = SpatialStrokeEdit.apply(.erase, to: original, along: path)
        let cut = try XCTUnwrap(erased.erased.first)
        XCTAssertEqual(cut.start, 0.06, accuracy: 0.0001)
        XCTAssertEqual(cut.end, 0.14, accuracy: 0.0001)
        XCTAssertEqual(erased.id, original.id)
        XCTAssertEqual(erased.points, original.points)
        XCTAssertNil(SpatialStrokeEdit.select(in: .init(strokes: [erased]), at: .zero))
        XCTAssertEqual(SpatialStrokeEdit.select(in: .init(strokes: [erased]), at: [0.08, 0, 0]), original.id)
    }

    func testRecolorDoesNotModifyOriginalPointsOrErasedIntervals() throws {
        var original = stroke([[0, 0, 0], [0.1, 0, 0]])
        original.color.a = 0.5
        original.erased = [ArcSpan(start: 0.04, end: 0.06)]
        var path = stroke([[0.035, 0, 0], [0.065, 0, 0]])
        path.color = InkColor(r: 0, g: 1, b: 0)
        let edited = SpatialStrokeEdit.apply(.recolor, to: original, along: path)
        XCTAssertEqual(edited.erased, original.erased)
        XCTAssertEqual(edited.points, original.points)
        XCTAssertEqual(edited.recolored.count, 2)
        XCTAssertTrue(edited.recolored.allSatisfy { $0.color.a == 0.5 &&
            $0.span.intersection(original.erased[0]) == nil })
        let document = DrawingDocument(strokes: [edited, stroke(id: "next", [[0, 0, 0], [0, 0.1, 0]])])
        XCTAssertEqual(try DrawingCodec.decode(DrawingCodec.encode(document)), document)
    }

    func testPicoSweepTouchesMultipleStrokesWithoutSelecting() throws {
        let first = stroke(id: "first", [[-0.1, -0.018, 0], [0.1, -0.018, 0]])
        let second = stroke(id: "second", [[-0.1, 0.018, 0], [0.1, 0.018, 0]])
        let far = stroke(id: "far", [[-0.1, 0.3, 0], [0.1, 0.3, 0]])
        let path = stroke(id: "finger", [[0, -0.05, 0], [0, 0.05, 0]])
        let edits = SpatialStrokeEdit.edits(.erase, in: [first, far, second], along: path)
        XCTAssertEqual(edits.map(\.id), ["first", "second"])
        XCTAssertEqual(edits.map(\.points), [first.points, second.points])
        XCTAssertTrue(edits.allSatisfy { !$0.erased.isEmpty })

        let before = DrawingDocument(strokes: [first, far, second])
        let edited = DrawingDocument(strokes: [edits[0], far, edits[1]])
        let history = DrawingHistory(before)
        history.commit(edited)
        XCTAssertTrue(history.undo())
        XCTAssertEqual(history.document, before)
        XCTAssertTrue(history.redo())
        XCTAssertEqual(try DrawingCodec.decode(DrawingCodec.encode(history.document)), edited)
    }

    func testStationaryPinchCanEditButCannotCreatePaintStroke() throws {
        let sample = SpatialInputSample(position: [0, 0, 0], timestamp: 0)
        var capture = SpatialStrokeCapture()
        func update(_ contact: SpatialContact, time: Double, edit: Bool) -> PaintStroke? {
            var timed = sample
            timed.timestamp = time
            return capture.update(timed, contact: contact, style: .init(), availablePoints: 2, acceptsTap: edit)
        }
        for edit in [false, true] {
            _ = update(.released, time: 0, edit: edit)
            _ = update(.engaged, time: 0.01, edit: edit)
            _ = update(.engaged, time: 0.06, edit: edit)
            let path = update(.released, time: 0.10, edit: edit)
            XCTAssertEqual(path?.points.count, edit ? 1 : nil)
        }
    }
}
