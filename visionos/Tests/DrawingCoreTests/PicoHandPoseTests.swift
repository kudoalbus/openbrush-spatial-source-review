import XCTest
import simd
@testable import DrawingCore

final class PicoHandPoseTests: XCTestCase {
    func testPicoPoseUsesIndexThumbAndTwoFingers() throws {
        func finger(_ x: Float, _ straight: Bool) -> PicoHandPose.Finger {
            .init(root: [x, 0.055, 0], middle: [x, 0.077, 0],
                  distal: [x, straight ? 0.095 : 0.077, straight ? 0 : 0.019],
                  tip: [x, straight ? 0.111 : 0.059, straight ? 0 : 0.019])
        }
        func pose(_ flags: [Bool], tuck: Bool = true) throws -> PicoHandPose {
            try XCTUnwrap(PicoHandPose(wrist: .zero,
                fingers: zip([Float(0.025), 0.005, -0.014, -0.035], flags).map(finger),
                thumbBase: [0.039, 0.015, 0], thumbDistal: [0.049, 0.030, 0],
                thumbTip: tuck ? [0.031, 0.054, 0] : [0.066, 0.0555, 0]))
        }
        XCTAssertTrue(try pose([true, true, true, true]).open)
        XCTAssertEqual(try pose([true, false, false, false]).requested, .draw)
        let eraser = try pose([false, false, false, false], tuck: false)
        XCTAssertEqual(eraser.requested, .erase)
        XCTAssertEqual(eraser.tip(for: .erase), eraser.thumb)
        let tint = try pose([true, true, false, false])
        XCTAssertEqual(tint.requested, .recolor)
        XCTAssertEqual(tint.tip(for: .recolor).x, 0.015, accuracy: 0.0001)
    }

    func testGateDwellAndFreshOpenAfterUIAndToolSwitch() {
        var gate = PicoHandGate()
        XCTAssertNil(gate.update(open: false, requested: .draw, held: [], at: 0))
        XCTAssertNil(gate.update(open: true, requested: nil, held: [], at: 0.02))
        XCTAssertNil(gate.update(open: false, requested: .draw, held: [], at: 0.04))
        XCTAssertNil(gate.update(open: false, requested: .draw, held: [], at: 0.12))
        XCTAssertEqual(gate.update(open: false, requested: .draw, held: [], at: 0.17), .draw)
        XCTAssertEqual(gate.update(open: false, requested: nil, held: [.draw], at: 0.19), .draw)
        XCTAssertNil(gate.update(open: false, requested: .erase, held: [], at: 0.21))
        XCTAssertNil(gate.update(open: false, requested: .erase, held: [], at: 0.34))
        XCTAssertNil(gate.update(open: true, requested: nil, held: [], at: 0.36, blocked: true))
        XCTAssertFalse(gate.ready)
        XCTAssertNil(gate.update(open: true, requested: nil, held: [], at: 0.40))
        XCTAssertNil(gate.update(open: false, requested: .erase, held: [], at: 0.41))
        XCTAssertNil(gate.update(open: false, requested: .erase, held: [], at: 0.70))
        XCTAssertFalse(gate.ready)
    }
}
