import Foundation
import XCTest
@testable import DrawingCore

final class GaussianArtworkTests: XCTestCase {
    func testGaussianPLYReordersSphericalHarmonicsAndRejectsTruncation() throws {
        let names = ["x", "y", "z", "scale_0", "scale_1", "scale_2",
                     "rot_0", "rot_1", "rot_2", "rot_3", "opacity",
                     "f_dc_0", "f_dc_1", "f_dc_2"] + (0..<9).map { "f_rest_\($0)" }
        let header = ["ply", "format binary_little_endian 1.0", "element vertex 1"]
            + names.map { "property float \($0)" } + ["end_header", ""]
        var file = Data(header.joined(separator: "\n").utf8)
        let row: [Float] = [0, 1, 2, -3, -3, -3, 1, 0, 0, 0, 0, 0.1, 0.2, 0.3]
            + (1...9).map { Float($0) }
        for value in row { append(value, to: &file) }

        let result = try GaussianArtwork.readPLY(file)
        XCTAssertEqual(result.count, 1)
        XCTAssertEqual(result.degree, 1)
        XCTAssertFalse(result.usesLinearScaleAndOpacity)
        let packed = result.bytes.withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
        XCTAssertEqual(Array(packed.suffix(9)), [1, 4, 7, 2, 5, 8, 3, 6, 9])
        XCTAssertThrowsError(try GaussianArtwork.readPLY(Data(file.dropLast())))
    }

    func testPackedSplatUsesLinearOpacityAndNormalizesWXYZ() throws {
        var file = Data()
        for value: Float in [1, 2, 3, 0.1, 0.2, 0.3] { append(value, to: &file) }
        file.append(contentsOf: [255, 128, 0, 128, 255, 128, 128, 128])
        let result = try GaussianArtwork.readPacked(file)
        let packed = result.bytes.withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
        XCTAssertEqual(result.count, 1)
        XCTAssertTrue(result.usesLinearScaleAndOpacity)
        XCTAssertEqual(Array(packed[6...9]), [1, 0, 0, 0])
        XCTAssertEqual(packed[10], 128.0 / 255, accuracy: 0.00001)
        XCTAssertEqual(packed[11], 0.5 / 0.2820948, accuracy: 0.00001)
        XCTAssertThrowsError(try GaussianArtwork.readPacked(Data(file.dropLast())))
    }

    private func append(_ value: Float, to data: inout Data) {
        var bits = value.bitPattern.littleEndian
        withUnsafeBytes(of: &bits) { data.append(contentsOf: $0) }
    }
}
