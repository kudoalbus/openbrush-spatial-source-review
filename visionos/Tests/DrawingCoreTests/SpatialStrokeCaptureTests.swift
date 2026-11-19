import XCTest
@testable import DrawingCore

final class SpatialStrokeCaptureTests: XCTestCase {
    private let style = SpatialBrushStyle()

    private func sample(_ t: Double, _ x: Float = 0) -> SpatialInputSample {
        .init(position: [x, x * 2, -x * 0.5], timestamp: t)
    }

    func testReleaseArmingDebounceAndFrozenStyle() throws {
        var capture = SpatialStrokeCapture()
        func update(_ t: Double, _ contact: SpatialContact, _ x: Float = 0,
                    brush: SpatialBrush = .tube) -> PaintStroke? {
            var settings = style
            settings.brush = brush
            return capture.update(sample(t, x), contact: contact, style: settings, availablePoints: 10)
        }
        XCTAssertNil(update(0, .engaged))
        XCTAssertNil(update(0.05, .engaged, 0.02))
        XCTAssertNil(update(0.06, .released))
        XCTAssertNil(update(0.07, .engaged))
        XCTAssertNil(update(0.08, .released))
        XCTAssertNil(update(0.09, .engaged))
        XCTAssertNil(update(0.13, .engaged, 0.02, brush: .flat))
        XCTAssertNil(update(0.15, .engaged, 0.04, brush: .fan))
        let stroke = try XCTUnwrap(update(0.16, .released))
        XCTAssertEqual(stroke.brush, "spatial_flat")
        XCTAssertEqual(stroke.points.count, 3)
        XCTAssertEqual(stroke.points.map(\.pressure), [1, 1, 1])
        XCTAssertNotEqual(stroke.points.first?.position.z, stroke.points.last?.position.z)
        let document = DrawingDocument(strokes: [stroke])
        XCTAssertEqual(try DrawingCodec.decode(DrawingCodec.encode(document)), document)
    }

    func testTrackingJumpRequiresFreshRelease() throws {
        var capture = SpatialStrokeCapture()
        func update(_ t: Double, _ contact: SpatialContact, _ x: Float = 0) -> PaintStroke? {
            capture.update(sample(t, x), contact: contact, style: style, availablePoints: 10)
        }
        _ = update(0, .released)
        _ = update(0.01, .engaged)
        _ = update(0.05, .engaged, 0.02)
        let first = try XCTUnwrap(update(0.07, .engaged, 1))
        XCTAssertEqual(first.points.count, 2)
        XCTAssertNil(update(0.10, .engaged, 1.01))
        XCTAssertNil(update(0.15, .engaged, 1.02))
        XCTAssertNil(update(0.16, .released, 1.02))
        _ = update(0.17, .engaged, 1.02)
        _ = update(0.22, .engaged, 1.04)
        _ = update(0.25, .engaged, 1.06)
        let second = try XCTUnwrap(capture.interrupt())
        XCTAssertNotEqual(second.id, first.id)
        XCTAssertGreaterThan(second.points[0].position.x, 1)
    }

    func testTimeGapAndPointLimitEndTransactions() throws {
        var capture = SpatialStrokeCapture()
        func update(_ t: Double, _ contact: SpatialContact, _ x: Float = 0, limit: Int = 3) -> PaintStroke? {
            capture.update(sample(t, x), contact: contact, style: style, availablePoints: limit)
        }
        _ = update(0, .released)
        _ = update(0.01, .engaged)
        _ = update(0.05, .engaged, 0.02)
        XCTAssertEqual(update(0.50, .engaged, 0.03)?.points.count, 2)
        _ = update(0.51, .released)
        _ = update(0.52, .engaged)
        _ = update(0.56, .engaged, 0.02)
        XCTAssertEqual(update(0.60, .engaged, 0.03)?.points.count, 3)
        XCTAssertNil(update(0.64, .engaged, 0.04))
        XCTAssertNil(capture.interrupt())
    }

    func testPinchUsesSeparateOpenAndCloseThresholds() {
        var pinch = HandPinch()
        XCTAssertEqual(pinch.update(distance: 0.03), .indeterminate)
        XCTAssertEqual(pinch.update(distance: 0.04), .released)
        XCTAssertEqual(pinch.update(distance: 0.02), .engaged)
        XCTAssertEqual(pinch.update(distance: 0.03), .engaged)
        XCTAssertEqual(pinch.update(distance: 0.04), .released)
    }
}
