import Foundation

struct OpenBrushProfile: Sendable {
    enum Geometry: String, Decodable, Sendable {
        case ribbon = "RIBBON", tube = "TUBE", stamp = "STAMP", hull = "HULL", membrane
    }

    enum Shape: Int, Sendable {
        case constant = 0, lofted = 1, sine = 2, comet = 3, tapered = 4, petal = 5, pinched = -1
    }

    enum CrossSection: Equatable, Sendable {
        case round, paper, beveledSquare, lens

        var corners: [SIMD2<Float>] {
            switch self {
            case .round: []
            case .paper: [[1, -0.375], [1, 0.375], [-1, 0.375], [-1, -0.375]]
            case .beveledSquare:
                [[1, -0.99], [1, 0.99], [0.99, 1], [-0.99, 1],
                 [-1, 0.99], [-1, -0.99], [-0.99, -1], [0.99, -1]]
            case .lens: [[1, 0], [0, 0.125], [-1, 0], [0, -0.125]]
            }
        }
    }

    struct TextureLayout: Sendable {
        var atlasRows = 1
        var tileRate: Float? = nil
    }

    struct Effect: Sendable {
        let opacity: Float
        var emission: Float = 0
    }

    struct Particle: Sendable {
        enum Motion: Sendable { case bubbles, risingBubbles, embers, smoke, snow, stars, waveform, danceFloor }

        let motion: Motion
        let lifetime: Float
        let rate: Float
        let atlasColumns: Int
        let effect: Effect
        var spacingUsesWidth = false

        var spray: Spray {
            // GeniusParticlesBrush uses a 2.5 mm base spawn interval divided by the descriptor rate.
            .init(rate: spacingUsesWidth ? rate : 1, sizeVariation: 0, rotationDegrees: 0, positionVariation: 0,
                  atlasColumns: atlasColumns, fixedInterval: spacingUsesWidth ? nil : 0.0025 / rate,
                  crossedCards: !spacingUsesWidth)
        }
    }

    struct Spray: Sendable {
        let rate: Float
        let sizeVariation: Float
        let rotationDegrees: Float
        let positionVariation: Float
        let atlasColumns: Int
        var fixedInterval: Float? = nil
        var crossedCards = false
    }

    let name: String
    let geometry: Geometry
    let section: CrossSection
    let sides: Int
    let shape: Shape
    let faceted: Bool
    let capped: Bool
    let crossSectionAngle: Float
    let radiusMultiplier: Float
    let pressureMin: Float
    let pressureWindowMeters: Float
    let zeroInitialPressure: Bool
    let singleSided: Bool
    let selfLit: Bool
    let cutoff: Float?
    let textureAsset: String?
    let spray: Spray?
    let effect: Effect?
    let textureLayout: TextureLayout?
    let particle: Particle?
    let materialStyle: OpenBrushMaterialStyle?
    let wave: OpenBrushWave?
}

struct OpenBrushCatalog: Sendable {
    private struct Entries<Entry: Decodable>: Decodable { let entries: [Entry] }
    private struct Brush: Decodable { let id, name: String }
    private struct Geometry: Decodable {
        let guid, geometry: String
        let sides, shapeModifier: Int
        let crossSectionAngle, radiusMultiplier, pressureMin, pressureWindowMeters: Float
        let zeroInitialPressure, singleSided, approximate: Bool
    }
    private struct Material: Decodable {
        let guid, blend: String
        let selfLit: Bool
        let cutoff: Float
    }
    private struct Texture: Decodable {
        let guid, slot, asset: String
    }

    // Only expose brushes whose current geometry, pressure and material paths are implemented.
    private static let staticBrushes: Set<String> = [
        "Oil Paint", "Ink", "Thick Paint", "Wet Paint", "Marker", "Tapered Marker",
        "Flat", "Tapered Flat", "Duct Tape", "Paper", "Wiggly Graphite", "Cel Vinyl",
        "Toon", "Wire", "Gouache", "Dry Brush", "Single Sided", "Double Flat", "Charcoal", "Wire (Lit)",
        "Icing", "Guts", "Mylar Tube", "Faceted Tube",
        "Ink Geometry", "Duct Tape Geometry", "Tapered Marker Flat"
    ]
    // Source descriptor m_TextureAtlasV and m_TileRate, matched by brush GUID.
    private static let textureLayouts: [String: OpenBrushProfile.TextureLayout] = [
        "Oil Paint": .init(atlasRows: 4),
        "Ink": .init(atlasRows: 4),
        "Wet Paint": .init(atlasRows: 4),
        "Velvet Ink": .init(atlasRows: 4),
        "Wiggly Graphite": .init(atlasRows: 4, tileRate: 0.1),
        "Gouache": .init(atlasRows: 4),
        "Dry Brush": .init(atlasRows: 4),
        "Guts": .init(atlasRows: 4, tileRate: 1),
        "Muscle": .init(atlasRows: 4, tileRate: 1),
        "Ink Geometry": .init(atlasRows: 4),
        "Thick Paint": .init(tileRate: 0.2),
        "Marker": .init(tileRate: 0.15),
        "Highlighter": .init(tileRate: 1),
        "Duct Tape": .init(tileRate: 0.6),
        "Duct Tape Geometry": .init(tileRate: 0.6),
        "Paper": .init(tileRate: 0.15),
        "Thick Geometry": .init(tileRate: 0.6),
        "Charcoal": .init(tileRate: 0.6)
    ]
    private static let solidSections: [String: OpenBrushProfile.CrossSection] = [
        "Square Paper": .paper, "Thick Geometry": .lens, "3D Printing Brush": .beveledSquare
    ]
    private static let pinched: Set<String> = ["Double Tapered Marker", "Double Tapered Flat", "Dark"]

    private static let shapedTubes: Set<String> = ["Petal", "Spikes", "Lofted", "Tapered Wire", "Muscle"]

    private static let hulls: Set<String> = [
        "Matte Hull", "Shiny Hull", "Unlit Hull", "Smooth Hull", "Concave Hull", "Passthrough Hull", "Diamond Hull"
    ]
    private static let membranes: Set<String> = ["Matte Membrane", "Shiny Membrane", "Diamond Membrane", "Outlined Membrane"]

    // RealityKit alpha blending and emission approximate these Open Brush additive materials.
    private static let effects: [String: OpenBrushProfile.Effect] = [
        "Highlighter": .init(opacity: 0.48),
        "Soft Highlighter": .init(opacity: 0.58),
        "Feather": .init(opacity: 0.42),
        "Velvet Ink": .init(opacity: 0.80),
        "Drafting": .init(opacity: 0.68, emission: 0.45),
        "Light": .init(opacity: 0.80, emission: 1.5),
        "Tube Additive": .init(opacity: 0.52, emission: 0.90),
        "Dots": .init(opacity: 0.64, emission: 0.35),
        "Hyper Grid": .init(opacity: 0.72, emission: 0.45),
        "Dark": .init(opacity: 0.72),
        "Slice": .init(opacity: 0.56),
        "Diamond Hull": .init(opacity: 0.54, emission: 0.1),
        "Diamond Membrane": .init(opacity: 0.62, emission: 0.1)
    ]

    // Descriptor values from the pinned Open Brush SprayBrush assets.
    private static let sprays: [String: OpenBrushProfile.Spray] = [
        "Coarse Bristles": .init(rate: 2, sizeVariation: 0.3, rotationDegrees: 5, positionVariation: 0.05, atlasColumns: 2),
        "Dot Marker": .init(rate: 3, sizeVariation: 0, rotationDegrees: 0, positionVariation: 0, atlasColumns: 1),
        "Leaves2": .init(rate: 2, sizeVariation: 0.3, rotationDegrees: 20, positionVariation: 0.15, atlasColumns: 2),
        "Splatter": .init(rate: 3, sizeVariation: 0.5, rotationDegrees: 180, positionVariation: 0.05, atlasColumns: 2),
        "Dots": .init(rate: 1, sizeVariation: 0, rotationDegrees: 0, positionVariation: 0, atlasColumns: 1,
                      fixedInterval: 0.025, crossedCards: true),
        "Hyper Grid": .init(rate: 1, sizeVariation: 0, rotationDegrees: 0, positionVariation: 0, atlasColumns: 1)
    ]
    private static let particles: [String: OpenBrushProfile.Particle] = [
        "Bubbles": .init(motion: .bubbles, lifetime: 3.2, rate: 0.15, atlasColumns: 1,
                         effect: .init(opacity: 0.66, emission: 0.25)),
        "Rising Bubbles": .init(motion: .risingBubbles, lifetime: 2.8, rate: 0.35, atlasColumns: 1,
                                effect: .init(opacity: 0.66, emission: 0.25)),
        "Embers": .init(motion: .embers, lifetime: 1.8, rate: 0.35, atlasColumns: 1,
                         effect: .init(opacity: 0.72, emission: 1.2)),
        "Smoke": .init(motion: .smoke, lifetime: 4.8, rate: 0.05, atlasColumns: 2,
                        effect: .init(opacity: 0.27)),
        "Snow": .init(motion: .snow, lifetime: 4.0, rate: 0.35, atlasColumns: 1,
                       effect: .init(opacity: 0.74, emission: 0.15)),
        "Stars": .init(motion: .stars, lifetime: 2.4, rate: 0.30, atlasColumns: 2,
                        effect: .init(opacity: 0.85, emission: 1)),
        "Waveform Particles": .init(motion: .waveform, lifetime: 2.2, rate: 4, atlasColumns: 1,
                                   effect: .init(opacity: 0.72, emission: 0.88), spacingUsesWidth: true),
        "Dance Floor": .init(motion: .danceFloor, lifetime: 2.6, rate: 1, atlasColumns: 1,
                             effect: .init(opacity: 0.86, emission: 0.58), spacingUsesWidth: true)
    ]
    private let profiles: [String: OpenBrushProfile]

    var enabledIDs: Set<String> { Set(profiles.keys) }

    func profile(for id: String) -> OpenBrushProfile? { profiles[id.lowercased()] }

    static let bundled: Self = {
        let bundle = Bundle.main
        guard let menu = bundle.url(forResource: "catalog", withExtension: "json"),
              let geometry = bundle.url(forResource: "brush-geometry", withExtension: "json"),
              let material = bundle.url(forResource: "brush-rendering", withExtension: "json"),
              let textures = bundle.url(forResource: "catalog", withExtension: "json", subdirectory: "brush-textures"),
              let catalog = try? load(menu: menu, geometry: geometry, materials: material, textures: textures) else {
            return Self(profiles: [:])
        }
        return catalog
    }()

    static func load(menu: URL, geometry: URL, materials: URL, textures: URL) throws -> Self {
        let decoder = JSONDecoder()
        let brushes = try decoder.decode(Entries<Brush>.self, from: Data(contentsOf: menu)).entries
        let supportedNames = staticBrushes.union(shapedTubes).union(hulls).union(effects.keys).union(sprays.keys).union(particles.keys)
            .union(solidSections.keys).union(pinched).union(membranes).union(OpenBrushMaterialStyle.brushes.keys)
        let namesByID = Dictionary(uniqueKeysWithValues: brushes.filter { supportedNames.contains($0.name) }
            .map { ($0.id.lowercased(), $0.name) })
        let shapes = try decoder.decode(Entries<Geometry>.self, from: Data(contentsOf: geometry)).entries
        let materialEntries = try decoder.decode(Entries<Material>.self, from: Data(contentsOf: materials)).entries
        let textureEntries = try decoder.decode(Entries<Texture>.self, from: Data(contentsOf: textures)).entries
        let materialByID = Dictionary(materialEntries.map { ($0.guid.lowercased(), $0) }, uniquingKeysWith: { first, _ in first })
        let textureByID = Dictionary(textureEntries.filter { $0.slot == "MainTex" }.map { ($0.guid.lowercased(), $0.asset) },
                                     uniquingKeysWith: { first, _ in first })
        var profiles: [String: OpenBrushProfile] = [:]
        for shape in shapes {
            let guid = shape.guid.lowercased()
            guard let name = namesByID[guid], let sourceGeometry = OpenBrushProfile.Geometry(rawValue: shape.geometry),
                  let modifier = OpenBrushProfile.Shape(rawValue: shape.shapeModifier) else { continue }
            let material = materialByID[guid]
            let isMembrane = membranes.contains(name)
            let nativeMaterial = name == "Slice" || name == "Dark" || name == "Passthrough Hull" || isMembrane
            guard material != nil || nativeMaterial else { continue }
            let materialStyle = OpenBrushMaterialStyle.brushes[name]
            guard material?.blend != "ADD" || effects[name] != nil || particles[name] != nil ||
                    materialStyle?.effect != nil else { continue }
            let particle = particles[name]
            let spray = sprays[name] ?? particle?.spray
            let solidSection = solidSections[name]
            guard !shape.approximate || shapedTubes.contains(name) || hulls.contains(name) ||
                    spray != nil || solidSection != nil || nativeMaterial || materialStyle != nil else { continue }
            if sourceGeometry == .stamp && (spray == nil || textureByID[guid] == nil) { continue }
            let geometry: OpenBrushProfile.Geometry
            if solidSection != nil { geometry = .tube }
            else if name == "Slice" { geometry = .stamp }
            else if hulls.contains(name) { geometry = .hull }
            else if isMembrane { geometry = .membrane }
            else { geometry = sourceGeometry }
            let textureLayout = textureLayouts[name] ?? materialStyle?.tileRate.map {
                OpenBrushProfile.TextureLayout(tileRate: $0)
            }
            profiles[guid] = OpenBrushProfile(
                name: name, geometry: geometry, section: solidSection ?? .round, sides: max(3, min(32, shape.sides)),
                shape: pinched.contains(name) ? .pinched : modifier,
                faceted: solidSection != nil || ["Petal", "Spikes", "Lofted", "Faceted Tube", "Lofted (Hue Shift)"].contains(name),
                capped: modifier != .petal && modifier != .lofted,
                crossSectionAngle: shape.crossSectionAngle, radiusMultiplier: shape.radiusMultiplier,
                pressureMin: shape.pressureMin, pressureWindowMeters: shape.pressureWindowMeters,
                zeroInitialPressure: shape.zeroInitialPressure, singleSided: shape.singleSided,
                selfLit: material?.selfLit ?? !isMembrane, cutoff: material?.blend == "MASK" ? material?.cutoff : nil,
                textureAsset: geometry == .hull ? nil : textureByID[guid], spray: spray, effect: effects[name] ?? particle?.effect ?? materialStyle?.effect,
                textureLayout: textureLayout, particle: particle, materialStyle: materialStyle, wave: OpenBrushWave.brushes[name])
        }
        return Self(profiles: profiles)
    }
}
