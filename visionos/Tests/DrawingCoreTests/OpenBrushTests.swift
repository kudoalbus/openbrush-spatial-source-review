import simd
import XCTest
@testable import DrawingCore

final class OpenBrushTests: XCTestCase {
    private let marker = "d90c6ad8-af0f-4b54-b422-e0f92abe1b3c"
    private let wire = "4391385a-cf83-4396-9e33-31e4e4930b27"

    static func catalog() throws -> OpenBrushCatalog {
        let assets = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("ReviewResources")
        return try .load(menu: assets.appendingPathComponent("brush-library/catalog.json"),
                         geometry: assets.appendingPathComponent("brush-geometry.json"),
                         materials: assets.appendingPathComponent("brush-rendering.json"),
                         textures: assets.appendingPathComponent("brush-textures/catalog.json"))
    }

    func testOriginalBrushSelectionSurvivesCaptureAndV3RoundTrip() throws {
        let catalog = try Self.catalog()
        XCTAssertEqual(catalog.enabledIDs.count, 102)
        XCTAssertEqual(catalog.profile(for: marker)?.name, "Tapered Marker")
        XCTAssertNotNil(catalog.profile(for: marker)?.textureAsset)
        XCTAssertEqual(catalog.profile(for: wire)?.geometry, .tube)

        let brush = try XCTUnwrap(SpatialBrush(rawValue: marker.uppercased()))
        XCTAssertEqual(brush, .openBrush(marker))
        var capture = SpatialStrokeCapture()
        let style = SpatialBrushStyle(brush: brush)
        func sample(_ time: Double, _ x: Float, _ contact: SpatialContact) -> PaintStroke? {
            capture.update(.init(position: [x, 0, 0], timestamp: time),
                           contact: contact, style: style, availablePoints: 10)
        }
        _ = sample(0, 0, .released)
        _ = sample(0.01, 0, .engaged)
        _ = sample(0.06, 0.03, .engaged)
        let stroke = try XCTUnwrap(sample(0.07, 0.03, .released))
        XCTAssertEqual(stroke.brush, marker)
        let doc = DrawingDocument(strokes: [stroke])
        XCTAssertEqual(try DrawingCodec.decode(DrawingCodec.encode(doc)), doc)
    }

    func testEveryOriginalBrushSurvivesTheDrawingCapturePathAndV3() throws {
        let catalog = try Self.catalog()
        var strokes: [PaintStroke] = []
        for id in catalog.enabledIDs.sorted() {
            let brush = try XCTUnwrap(SpatialBrush(rawValue: id))
            let style = SpatialBrushStyle(brush: brush)
            var capture = SpatialStrokeCapture()
            func sample(_ time: Double, _ x: Float, _ contact: SpatialContact) -> PaintStroke? {
                capture.update(.init(position: [x, x * 0.4, 0], timestamp: time),
                               contact: contact, style: style, availablePoints: 10)
            }
            _ = sample(0, 0, .released)
            _ = sample(0.01, 0, .engaged)
            _ = sample(0.06, 0.035, .engaged)
            let stroke = try XCTUnwrap(sample(0.08, 0.035, .released), id)
            XCTAssertEqual(stroke.brush, id)
            strokes.append(stroke)
        }
        let doc = DrawingDocument(strokes: strokes)
        XCTAssertEqual(Set(strokes.map(\.brush)), catalog.enabledIDs)
        XCTAssertEqual(try DrawingCodec.decode(DrawingCodec.encode(doc)), doc)
    }

    func testEraseAndRecolorKeepTextureCoordinatesAndFrontFacingGeometry() throws {
        let catalog = try Self.catalog()
        for guid in [marker, wire] {
            let profile = try XCTUnwrap(catalog.profile(for: guid))
            let red = InkColor(r: 1, g: 0, b: 0)
            let blue = InkColor(r: 0, g: 0, b: 1)
            let stroke = PaintStroke(id: "stroke", brush: guid, width: 0.02, color: red, points: [
                .init(position: .zero, pressure: 0.3, timeMs: 0, normal: [0, 0, 1]),
                .init(position: [0.1, 0, 0], pressure: 1, timeMs: 1, normal: [0, 0, 1])
            ], erased: [.init(start: 0.04, end: 0.06)],
               recolored: [.init(span: .init(start: 0.06, end: 0.08), color: blue)])
            let arcs = stroke.distances
            let pressures = StrokeMesh.smoothPressures(stroke: stroke, arcs: arcs, profile: profile)
            let parts = StrokeAppearance.parts(stroke)
            XCTAssertEqual(parts.map(\.color), [red, blue, red])
            let mesh = StrokeMesh.openBrush(stroke: stroke, part: parts[1], profile: profile, arcs: arcs, pressures: pressures)
            XCTAssertEqual(mesh.uv.count, mesh.positions.count)
            XCTAssertEqual(mesh.uv.map(\.x).min() ?? -1, 0.6, accuracy: 1e-6)
            XCTAssertEqual(mesh.uv.map(\.x).max() ?? -1, 0.8, accuracy: 1e-6)
            let firstFace = mesh.indices.prefix(3).map(Int.init)
            let p = firstFace.map { mesh.positions[$0] }
            XCTAssertGreaterThan(simd_dot(simd_cross(p[1] - p[0], p[2] - p[0]), mesh.normals[firstFace[0]]), 0)
        }
    }

    func testSprayLayoutUsesAtlasAndPreservesMarksOutsideEdits() throws {
        let catalog = try Self.catalog()
        let guid = "8dc4a70c-d558-4efd-a5ed-d4e860f40dc3"
        let profile = try XCTUnwrap(catalog.profile(for: guid))
        let spray = try XCTUnwrap(profile.spray)
        let red = InkColor(r: 1, g: 0, b: 0)
        var stroke = PaintStroke(id: "spray", brush: guid, width: 0.02, color: red, points: [
            .init(position: .zero, pressure: 1, timeMs: 0, normal: [0, 0, 1]),
            .init(position: [0.2, 0, 0], pressure: 1, timeMs: 1, normal: [0, 0, 1])
        ], erased: [], recolored: [])
        let arcs = stroke.distances
        let pressures = StrokeMesh.smoothPressures(stroke: stroke, arcs: arcs, profile: profile)
        let stamps = OpenBrushStamp.place(on: stroke, arcs: arcs, pressures: pressures, profile: profile, spray: spray)
        XCTAssertEqual(stamps[1].arc, 0.02 / 3, accuracy: 1e-6)
        XCTAssertGreaterThan(Set(stamps.map(\.uvOrigin)).count, 1)
        let full = OpenBrushStamp.mesh(stamps, in: .init(start: 0, end: stroke.length))
        XCTAssertEqual(full.positions.count, stamps.count * 4)

        let cut = ArcSpan(start: 0.03, end: 0.09)
        stroke.erased = [cut]
        stroke.recolored = [.init(span: .init(start: 0.09, end: 0.13), color: .init(r: 0, g: 0, b: 1))]
        let stored = try DrawingCodec.decode(DrawingCodec.encode(.init(strokes: [stroke])))
        let reopened = try XCTUnwrap(stored.strokes.first)
        let reopenedStamps = OpenBrushStamp.place(on: reopened, arcs: arcs, pressures: pressures, profile: profile, spray: spray)
        let visible = StrokeAppearance.parts(reopened).flatMap { OpenBrushStamp.mesh(reopenedStamps, in: $0.span).positions }
        let originalOutsideCut = stamps.enumerated().filter { $0.element.arc < cut.start || $0.element.arc >= cut.end }
            .flatMap { full.positions[$0.offset * 4..<$0.offset * 4 + 4] }
        XCTAssertEqual(visible, originalOutsideCut)
    }

    func testSpikesHaveThreeFacetsAndRetainTaperAcrossEdits() throws {
        let guid = "cf7f0059-7aeb-53a4-2b67-c83d863a9ffa"
        let profile = try XCTUnwrap(Self.catalog().profile(for: guid))
        let color = InkColor(r: 1, g: 0, b: 0)
        let stroke = PaintStroke(id: "spikes", brush: guid, width: 0.02, color: color, points: [
            .init(position: .zero, pressure: 1, timeMs: 0, normal: [0, 0, 1]),
            .init(position: [0.2, 0, 0], pressure: 1, timeMs: 1, normal: [0, 0, 1])
        ], erased: [.init(start: 0.04, end: 0.10)], recolored: [])
        XCTAssertEqual(profile.sides, 3)
        let arcs = stroke.distances
        let pressures = StrokeMesh.smoothPressures(stroke: stroke, arcs: arcs, profile: profile)
        let mesh = StrokeMesh.openBrush(stroke: stroke, part: StrokeAppearance.parts(stroke)[1],
                                        profile: profile, arcs: arcs, pressures: pressures)
        XCTAssertNotEqual(mesh.normals[1], mesh.normals[2])
        XCTAssertEqual(simd_length(mesh.positions[1] - mesh.positions[2]), 0, accuracy: 1e-6)
        XCTAssertGreaterThan(simd_length(mesh.positions[0] - stroke.point(at: 0.10).position),
                             simd_length(mesh.positions[6] - stroke.point(at: 0.20).position))
    }

    func testHullKeepsSurfaceAfterRecolorAndSeparatesErasedSpans() throws {
        let guid = "79348357-432d-4746-8e29-0e25c112e3aa"
        let profile = try XCTUnwrap(Self.catalog().profile(for: guid))
        var stroke = PaintStroke(id: "hull", brush: guid, width: 0.03, color: .init(r: 1, g: 0, b: 0), points: [
            .init(position: .zero, pressure: 1, timeMs: 0, normal: [0, 0, 1]),
            .init(position: [0.08, 0, 0], pressure: 1, timeMs: 1, normal: [0, 0, 1]),
            .init(position: [0.16, 0, 0], pressure: 1, timeMs: 2, normal: [0, 0, 1]),
            .init(position: [0.24, 0, 0], pressure: 1, timeMs: 3, normal: [0, 0, 1])
        ], erased: [], recolored: [])
        func hulls(_ stroke: PaintStroke) -> [StrokeMesh] {
            OpenBrushHull.meshes(stroke: stroke, parts: StrokeAppearance.parts(stroke), arcs: stroke.distances, profile: profile)
        }
        func area(_ mesh: StrokeMesh) -> Float {
            stride(from: 0, to: mesh.indices.count, by: 3).reduce(0) { sum, index in
                let a = mesh.positions[Int(mesh.indices[index])]
                let b = mesh.positions[Int(mesh.indices[index + 1])]
                let c = mesh.positions[Int(mesh.indices[index + 2])]
                return sum + simd_length(simd_cross(b - a, c - a)) * 0.5
            }
        }
        let original = hulls(stroke).reduce(0) { $0 + area($1) }
        XCTAssertGreaterThan(original, 0)
        stroke.recolored = [.init(span: .init(start: 0.045, end: 0.185), color: .init(r: 0, g: 0, b: 1))]
        let recolored = hulls(stroke)
        XCTAssertEqual(recolored.count, 3)
        XCTAssertTrue(recolored.allSatisfy { area($0) > 0 })
        XCTAssertEqual(recolored.reduce(0) { $0 + area($1) }, original, accuracy: 1e-6)
        stroke.erased = [.init(start: 0.075, end: 0.165)]
        let cut = hulls(stroke)
        XCTAssertEqual(cut.count, 4)
        XCTAssertTrue(cut.flatMap(\.positions).allSatisfy { $0.x <= 0.0751 || $0.x >= 0.1649 })
    }

    func testRibbonVariantsKeepTextureRepeatAndAtlasRowAfterEdits() throws {
        let catalog = try Self.catalog()
        let color = InkColor(r: 1, g: 0, b: 0)
        var stroke = PaintStroke(id: "tiled-ribbon", brush: "84d5bbb2-6634-8434-f8a7-681b576b4664",
                                 width: 0.02, color: color, points: [
            .init(position: .zero, pressure: 1, timeMs: 0, normal: [0, 0, 1]),
            .init(position: [0.2, 0, 0], pressure: 1, timeMs: 1, normal: [0, 0, 1])
        ], erased: [.init(start: 0.04, end: 0.1)], recolored: [])
        let arcs = stroke.distances
        let part = StrokeAppearance.parts(stroke)[1]
        let tape = try XCTUnwrap(catalog.profile(for: stroke.brush))
        let pressures = StrokeMesh.smoothPressures(stroke: stroke, arcs: arcs, profile: tape)
        let repeats = StrokeMesh.tileDistances(stroke: stroke, arcs: arcs, pressures: pressures, profile: tape)
        let tapeMesh = StrokeMesh.openBrush(stroke: stroke, part: part, profile: tape, arcs: arcs,
                                            pressures: pressures, tileDistances: repeats)
        XCTAssertEqual(tapeMesh.uv[0].x, 3, accuracy: 1e-5)
        XCTAssertEqual(tapeMesh.uv.last?.x ?? 0, 6, accuracy: 1e-5)

        stroke.brush = "7c972c27-d3c2-8af4-7bf8-5d9db8f0b7bb"
        let ink = try XCTUnwrap(catalog.profile(for: stroke.brush))
        let inkMesh = StrokeMesh.openBrush(stroke: stroke, part: part, profile: ink, arcs: arcs, pressures: pressures)
        XCTAssertEqual(inkMesh.uv[1].y - inkMesh.uv[0].y, 0.25, accuracy: 1e-6)
    }

    func testParticlesMoveAndCycleWithoutReseedingEditedIntervals() throws {
        let catalog = try Self.catalog()
        let bubbles = "a8147ce1-005e-abe4-88e8-09a1eaadcc89"
        let snow = "d902ed8b-d0d1-476c-a8de-878a79e3a34c"
        for guid in [bubbles, snow] {
            let profile = try XCTUnwrap(catalog.profile(for: guid))
            let style = try XCTUnwrap(profile.particle)
            let spray = try XCTUnwrap(profile.spray)
            let width: Float = 0.03
            var stroke = PaintStroke(id: "particles", brush: guid, width: width, color: .init(r: 1, g: 1, b: 1), points: [
                .init(position: .zero, pressure: 1, timeMs: 0, normal: [0, 0, 1]),
                .init(position: [0.15, 0, 0], pressure: 1, timeMs: 100, normal: [0, 0, 1])
            ], erased: [], recolored: [])
            let arcs = stroke.distances
            let pressures = StrokeMesh.smoothPressures(stroke: stroke, arcs: arcs, profile: profile)
            let stamps = OpenBrushStamp.place(on: stroke, arcs: arcs, pressures: pressures, profile: profile, spray: spray)
            let stamp = try XCTUnwrap(stamps.first { $0.arc > 0.09 })
            let particle = OpenBrushParticle(stamp: stamp)
            let birth = Double((1 - particle.phase) * style.lifetime)
            let early = particle.pose(at: birth + Double(style.lifetime) * 0.25, style: style, width: width)
            let late = particle.pose(at: birth + Double(style.lifetime) * 0.60, style: style, width: width)
            XCTAssertEqual(particle.pose(at: birth, style: style, width: width).scale, 0, accuracy: 1e-5)
            let loop = particle.pose(at: birth + Double(style.lifetime) * 1.25, style: style, width: width)
            XCTAssertEqual(simd_distance(loop.position, early.position), 0, accuracy: 1e-6)
            if guid == bubbles {
                XCTAssertGreaterThan(late.position.y, early.position.y)
            } else {
                XCTAssertLessThan(late.position.y, early.position.y)
            }

            stroke.erased = [.init(start: 0, end: 0.08)]
            let reopened = try XCTUnwrap(DrawingCodec.decode(DrawingCodec.encode(.init(strokes: [stroke]))).strokes.first)
            let newMarks = OpenBrushStamp.place(on: reopened, arcs: reopened.distances, pressures: pressures, profile: profile, spray: spray)
            let visible = StrokeAppearance.parts(reopened).flatMap { OpenBrushStamp.marks(newMarks, in: $0.span) }
            XCTAssertTrue(visible.allSatisfy { $0.arc >= 0.08 })
            let sameStamp = try XCTUnwrap(visible.first { $0.arc == stamp.arc })
            let sameParticle = OpenBrushParticle(stamp: sameStamp)
            XCTAssertEqual(sameParticle.phase, particle.phase)
            XCTAssertEqual(sameParticle.pose(at: birth, style: style, width: width).position,
                           particle.pose(at: birth, style: style, width: width).position)
        }
    }

    func testSolidSectionsAndPerpendicularSliceCards() throws {
        let catalog = try Self.catalog()
        func profile(_ name: String) throws -> OpenBrushProfile {
            try XCTUnwrap(catalog.enabledIDs.compactMap(catalog.profile(for:)).first { $0.name == name })
        }
        let paper = try profile("Square Paper")
        let slice = try profile("Slice")
        let marker = try profile("Double Tapered Marker")
        let points = [Float(0), 0.05, 0.10].map {
            PaintPoint(position: [$0, 0, 0], pressure: 1, timeMs: 0, normal: [0, 0, 1])
        }
        var stroke = PaintStroke(id: "sections", brush: "", width: 0.02, color: .init(r: 1, g: 1, b: 1),
                                 points: points, erased: [], recolored: [])
        let arcs = stroke.distances
        let part = try XCTUnwrap(StrokeAppearance.parts(stroke).first)
        let paperMesh = StrokeMesh.openBrush(stroke: stroke, part: part, profile: paper, arcs: arcs, pressures: [1, 1, 1])
        XCTAssertEqual(paperMesh.positions.map(\.z).max() ?? 0, 0.00375, accuracy: 1e-6)
        XCTAssertEqual(paperMesh.positions.map(\.y).max() ?? 0, 0.01, accuracy: 1e-6)
        let pinched = StrokeMesh.openBrush(stroke: stroke, part: part, profile: marker, arcs: arcs, pressures: [1, 1, 1])
        XCTAssertLessThan(abs(pinched.positions[0].y), 0.0001)
        XCTAssertEqual(abs(pinched.positions[2].y), 0.01, accuracy: 1e-6)

        stroke.erased = [.init(start: 0.04, end: 0.06)]
        let cards = OpenBrushStamp.slices(on: stroke, arcs: arcs, pressures: [1, 1, 1], profile: slice)
        let meshes = StrokeAppearance.parts(stroke).map { OpenBrushStamp.mesh(cards, in: $0.span) }
        XCTAssertEqual(meshes.map { $0.positions.count }, [4, 4])
        XCTAssertTrue(meshes.flatMap(\.normals).allSatisfy { $0 == [1, 0, 0] })
        XCTAssertEqual(Set(meshes.flatMap(\.positions).map(\.x)), [0, 0.10])
    }

    func testConcaveMembraneClosesItsBoundaryWithoutFillingTheNotch() throws {
        let catalog = try Self.catalog()
        let profile = try XCTUnwrap(catalog.enabledIDs.compactMap(catalog.profile(for:)).first { $0.name == "Outlined Membrane" })
        let positions: [SIMD3<Float>] = [[0, 0, 0], [2, 0, 0], [2, 1, 0], [1, 1, 0], [1, 2, 0], [0, 2, 0], [0, 0, 0]]
        let points = positions.map { PaintPoint(position: $0, pressure: 1, timeMs: 0, normal: [0, 0, 1]) }
        var stroke = PaintStroke(id: "membrane", brush: "", width: 0.02, color: .init(r: 1, g: 1, b: 1),
                                 points: points, erased: [], recolored: [])
        func area(_ layers: [OpenBrushMembrane.Layers]) -> Float {
            layers.reduce(0) { sum, layer in
                let mesh = layer.fill
                return sum + stride(from: 0, to: mesh.indices.count, by: 3).reduce(0) { sum, i in
                    let a = mesh.positions[Int(mesh.indices[i])]
                    let b = mesh.positions[Int(mesh.indices[i + 1])]
                    let c = mesh.positions[Int(mesh.indices[i + 2])]
                    return sum + simd_length(simd_cross(b - a, c - a)) * 0.5
                }
            }
        }
        let arcs = stroke.distances
        let original = OpenBrushMembrane.meshes(stroke: stroke, parts: StrokeAppearance.parts(stroke), arcs: arcs, profile: profile)
        XCTAssertEqual(area(original), 3, accuracy: 1e-5)
        XCTAssertFalse(original[0].outline.indices.isEmpty)
        stroke = StrokeAppearance.recolor(stroke, in: .init(start: 1, end: 4), color: .init(r: 1, g: 0, b: 0))
        let recolored = OpenBrushMembrane.meshes(stroke: stroke, parts: StrokeAppearance.parts(stroke), arcs: arcs, profile: profile)
        XCTAssertEqual(recolored.count, 3)
        XCTAssertEqual(area(recolored), 3, accuracy: 1e-5)
        stroke.erased = [.init(start: 2.8, end: 4.2)]
        let cut = OpenBrushMembrane.meshes(stroke: stroke, parts: StrokeAppearance.parts(stroke), arcs: arcs, profile: profile)
        XCTAssertLessThan(area(cut), 3)
    }
}
