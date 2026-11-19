import simd
import XCTest
@testable import DrawingCore

final class PalmMenuPoseTests: XCTestCase {
    func testEitherSupportingHandShowsTheCorrectSide() throws {
        let wrist = SIMD3<Float>(0, 1, 0)
        let middle = SIMD3<Float>(0, 1, -0.08)
        let thumbSide = SIMD3<Float>(-0.03, 1, -0.08)
        let pinkieSide = SIMD3<Float>(0.03, 1, -0.07)
        let left = try XCTUnwrap(PalmMenuPose(wrist: wrist, middle: middle, index: thumbSide, little: pinkieSide,
                                              isLeft: true, previousFace: nil))
        let right = try XCTUnwrap(PalmMenuPose(wrist: wrist, middle: middle, index: pinkieSide, little: thumbSide,
                                               isLeft: false, previousFace: nil))
        XCTAssertEqual(left.position, (wrist + middle) * 0.5)
        XCTAssertEqual(left.orientation.act([0, 1, 0]).y, 1, accuracy: 0.001)
        XCTAssertEqual(right.orientation.act([0, 1, 0]).y, 1, accuracy: 0.001)
        XCTAssertEqual(left.face, .palm)
        XCTAssertEqual(right.face, .palm)
        let back = try XCTUnwrap(PalmMenuPose(wrist: wrist, middle: middle, index: pinkieSide, little: thumbSide,
                                              isLeft: true, previousFace: .palm))
        XCTAssertEqual(back.face, .back)
        XCTAssertEqual(back.orientation.act([0, -1, 0]).y, 1, accuracy: 0.001)
    }

    func testHandOnEdgeKeepsItsPreviousMenuFace() throws {
        let wrist = SIMD3<Float>(0, 1, 0)
        let middle = SIMD3<Float>(0, 1, -0.08)
        let index = SIMD3<Float>(0, 1.03, -0.08)
        let little = SIMD3<Float>(0, 0.97, -0.08)
        XCTAssertNil(PalmMenuPose(wrist: wrist, middle: middle, index: index, little: little,
                                  isLeft: true, previousFace: nil))
        let tilted = try XCTUnwrap(PalmMenuPose(wrist: wrist, middle: middle, index: index, little: little,
                                                isLeft: true, previousFace: .back))
        XCTAssertEqual(tilted.face, .back)
    }
}
