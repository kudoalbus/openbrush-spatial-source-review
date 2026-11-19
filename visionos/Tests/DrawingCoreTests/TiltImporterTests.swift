import Foundation
import XCTest
@testable import DrawingCore

final class TiltImporterTests: XCTestCase {
    func testSyntheticTiltImportsAsEditableStrokes() throws {
        let imported = try TiltImporter.decode(SyntheticTilt.data(), catalog: OpenBrushTests.catalog())
        let strokes = imported.document.strokes
        XCTAssertEqual(strokes.count, 5)
        XCTAssertEqual(Set(strokes.map(\.brush)), ["d90c6ad8-af0f-4b54-b422-e0f92abe1b3c"])
        XCTAssertEqual(Set(strokes.map(\.id)).count, strokes.count)
        XCTAssertEqual(imported.skipped, 0)
        XCTAssertEqual(imported.unmappedBrushes, 0)
        XCTAssertGreaterThan(strokes.flatMap(\.points).count, 500)
        XCTAssertTrue(strokes.flatMap(\.points).contains { $0.timeMs > 0 })
        XCTAssertEqual(try DrawingCodec.decode(DrawingCodec.encode(imported.document)), imported.document)
    }

    func testTruncatedAndDamagedZIPDataAreRejected() throws {
        let data = try SyntheticTilt.data()
        XCTAssertThrowsError(try TiltImporter.decode(Data(data.dropLast())))
        var damaged = data
        let pattern = Data("BrushIndex".utf8)
        let range = try XCTUnwrap(damaged.range(of: pattern))
        damaged[range.lowerBound] ^= 1
        XCTAssertThrowsError(try TiltImporter.decode(damaged))
    }
}
