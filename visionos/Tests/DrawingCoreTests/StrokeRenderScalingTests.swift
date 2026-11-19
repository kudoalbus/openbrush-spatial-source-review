import XCTest
@testable import DrawingCore

final class StrokeRenderScalingTests: XCTestCase {
    func testLongStrokeWithInterleavedColorsAndGapsKeepsVisibleArcCoverage() {
        let points = (0..<DrawingCodec.maximumStrokePoints).map { index in
            PaintPoint(position: [Float(index) * 0.001, 0, 0], pressure: 1,
                       timeMs: Int64(index), normal: [0, 0, 1])
        }
        let color = InkColor(r: 1, g: 0, b: 0)
        let edited = InkColor(r: 0, g: 1, b: 0)
        let erased = (0..<100).map { index in
            let base = Float(index) * 0.03
            return ArcSpan(start: base + 0.01, end: base + 0.014)
        }
        let tints = (0..<100).map { index in
            let base = Float(index) * 0.03
            return ColorSpan(span: .init(start: base + 0.006, end: base + 0.018), color: edited)
        }
        let stroke = PaintStroke(id: "long", brush: "spatial_tube", width: 0.004, color: color,
                                 points: points, erased: erased, recolored: tints)
        let parts = StrokeAppearance.parts(stroke)
        XCTAssertEqual(parts.filter { $0.color == edited }.count, 200)
        XCTAssertTrue(parts.allSatisfy { part in erased.allSatisfy { $0.intersection(part.span) == nil } })
        XCTAssertEqual(parts.reduce(0) { $0 + $1.span.end - $1.span.start },
                       stroke.length - erased.reduce(0) { $0 + $1.end - $1.start }, accuracy: 0.001)
        let arcs = stroke.distances
        for part in parts {
            let points = stroke.fragment(part.span, arcs: arcs)
            XCTAssertEqual(points.first?.position.x ?? -1, part.span.start, accuracy: 0.0001)
            XCTAssertEqual(points.last?.position.x ?? -1, part.span.end, accuracy: 0.0001)
        }
    }
}
