import RealityKit
import UIKit

@MainActor
final class WorkspaceRenderer {
    let root = Entity()
    private static var textures: [String: TextureResource] = [:]
    private var displayed: DrawingDocument?
    private(set) var triangleCount = 0

    static func entity(for stroke: PaintStroke, parts: [DisplayPart], sides: Int = 12, countTriangles: ((Int) -> Void)? = nil) throws -> ModelEntity? {
        guard !parts.isEmpty else { return nil }
        let arcs = stroke.distances
        let profile = OpenBrushCatalog.bundled.profile(for: stroke.brush)
        let pressures: [Float] = profile.map {
            if $0.geometry == .hull || $0.geometry == .membrane { return [] }
            return StrokeMesh.smoothPressures(stroke: stroke, arcs: arcs, profile: $0)
        } ?? []
        let stamps: [OpenBrushStamp]
        if let profile, let spray = profile.spray {
            stamps = OpenBrushStamp.place(on: stroke, arcs: arcs, pressures: pressures, profile: profile, spray: spray,
                                         capacity: profile.particle == nil ? 4096 : 512)
        } else if let profile, profile.name == "Slice" {
            stamps = OpenBrushStamp.slices(on: stroke, arcs: arcs, pressures: pressures, profile: profile)
        } else {
            stamps = []
        }
        let hulls = profile.flatMap { profile in
            profile.geometry == .hull ? OpenBrushHull.meshes(stroke: stroke, parts: parts, arcs: arcs, profile: profile) : nil
        }
        let membranes = profile.flatMap { profile in
            profile.geometry == .membrane ? OpenBrushMembrane.meshes(stroke: stroke, parts: parts, arcs: arcs, profile: profile) : nil
        }
        let tileDistances = profile.map {
            StrokeMesh.tileDistances(stroke: stroke, arcs: arcs, pressures: pressures, profile: $0)
        } ?? []
        var texture: MaterialParameters.Texture?
        if let pattern = profile?.materialStyle?.pattern {
            texture = .init(try OpenBrushPatternTexture.make(pattern))
        } else {
            texture = try Self.texture(for: profile?.textureAsset)
        }
        if !tileDistances.isEmpty || (profile?.materialStyle?.scroll ?? 0) != 0 {
            texture?.sampler.modify { $0.sAddressMode = .repeat }
        }
        if let profile, let style = profile.particle {
            return try particleEntity(stroke: stroke, parts: parts, stamps: stamps, profile: profile,
                                      style: style, texture: texture, countTriangles: countTriangles)
        }
        var descriptors: [MeshDescriptor] = []
        var waveParts: [(StrokeMesh, Int)] = []
        var materials: [any Material] = []
        var materialIndices: [InkColor: Int] = [:]
        func append(_ mesh: StrokeMesh, color: InkColor, name: String) {
            guard !mesh.indices.isEmpty else { return }
            countTriangles?(mesh.indices.count / 3)
            let materialIndex = materialIndices[color] ?? materials.count
            if materialIndex == materials.count {
                materialIndices[color] = materialIndex
                materials.append(material(for: color, profile: profile, texture: texture))
            }
            if profile?.wave != nil {
                waveParts.append((mesh, materialIndex))
                return
            }
            var descriptor = MeshDescriptor(name: name)
            descriptor.positions = MeshBuffers.Positions(mesh.positions)
            descriptor.normals = MeshBuffers.Normals(mesh.normals)
            if !mesh.uv.isEmpty { descriptor.textureCoordinates = .init(mesh.uv) }
            descriptor.primitives = .triangles(mesh.indices)
            descriptor.materials = .allFaces(UInt32(materialIndex))
            descriptors.append(descriptor)
        }
        for (index, part) in parts.enumerated() {
            let name = "\(stroke.id):\(part.span.start)-\(part.span.end)"
            if let layers = membranes?[index] {
                append(layers.fill, color: part.color, name: name)
                let tint = part.color
                let value = max(tint.r, tint.g, tint.b)
                let scale = value >= 0.5 ? 0.5 : (value + 1) / (2 * max(value, 0.001))
                let border = value <= 0.001 ? InkColor(r: 0.5, g: 0.5, b: 0.5, a: tint.a) :
                    InkColor(r: tint.r * scale, g: tint.g * scale, b: tint.b * scale, a: tint.a)
                append(layers.outline, color: border, name: name + "-outline")
                continue
            }
            let mesh: StrokeMesh
            if let hulls {
                mesh = hulls[index]
            } else if profile?.geometry == .stamp {
                mesh = OpenBrushStamp.mesh(stamps, in: part.span)
            } else if let profile {
                mesh = StrokeMesh.openBrush(stroke: stroke, part: part, profile: profile, arcs: arcs,
                                            pressures: pressures, tileDistances: tileDistances)
            } else {
                mesh = StrokeMesh.build(stroke: stroke, part: part, sides: sides, arcs: arcs)
            }
            append(mesh, color: part.color, name: name)
        }
        let materialAnimation = profile?.materialStyle.flatMap { style in
            style.isAnimated ? OpenBrushMaterialAnimation(style: style, materials: materials) : nil
        }
        let entity: ModelEntity
        if let wave = profile?.wave, !waveParts.isEmpty {
            let animation = try OpenBrushWaveMesh(parts: waveParts, wave: wave, width: stroke.width)
            entity = ModelEntity(mesh: try MeshResource(from: animation.mesh), materials: materials)
            entity.components.set(OpenBrushAnimationComponent(animation: .wave(animation, materialAnimation)))
        } else {
            guard !descriptors.isEmpty else { return nil }
            entity = ModelEntity(mesh: try MeshResource.generate(from: descriptors), materials: materials)
            if let materialAnimation {
                entity.components.set(OpenBrushAnimationComponent(animation: .material(materialAnimation)))
            }
        }
        entity.name = stroke.id
        return entity
    }

    private static func particleEntity(stroke: PaintStroke, parts: [DisplayPart], stamps: [OpenBrushStamp],
                                       profile: OpenBrushProfile, style: OpenBrushProfile.Particle,
                                       texture: MaterialParameters.Texture?,
                                       countTriangles: ((Int) -> Void)?) throws -> ModelEntity? {
        var particles: [OpenBrushParticle] = []
        var materials: [any Material] = []
        var materialIndices: [InkColor: Int] = [:]
        var materialRanges: [(Range<Int>, Int)] = []
        let strokeLength = stroke.length
        for part in parts {
            let marks = OpenBrushStamp.marks(stamps, in: part.span)
            guard !marks.isEmpty else { continue }
            let materialIndex = materialIndices[part.color] ?? materials.count
            if materialIndex == materials.count {
                materials.append(material(for: part.color, profile: profile, texture: texture))
                materialIndices[part.color] = materialIndex
            }
            let start = particles.count
            particles.append(contentsOf: marks.map { OpenBrushParticle(stamp: $0, strokeLength: strokeLength) })
            materialRanges.append((start..<particles.count, materialIndex))
        }
        guard !particles.isEmpty else { return nil }
        let animation = try OpenBrushParticleMesh(particles: particles, materialRanges: materialRanges,
                                                   style: style, width: stroke.width)
        let entity = ModelEntity(mesh: try MeshResource(from: animation.mesh), materials: materials)
        entity.components.set(OpenBrushAnimationComponent(animation: .particles(animation)))
        entity.name = stroke.id
        countTriangles?(particles.count * (style.spray.crossedCards ? 4 : 2))
        return entity
    }

    private static func texture(for asset: String?) throws -> MaterialParameters.Texture? {
        guard let asset else { return nil }
        if let cached = textures[asset] { return .init(cached) }
        let filename = URL(fileURLWithPath: asset).lastPathComponent
        guard let url = Bundle.main.url(forResource: filename, withExtension: nil, subdirectory: "brush-textures") else {
            throw WorkspaceError.invalid("Missing Open Brush texture: \(filename)")
        }
        let resource = try TextureResource.load(contentsOf: url)
        textures[asset] = resource
        return .init(resource)
    }

    private static func material(for color: InkColor, profile: OpenBrushProfile?,
                                 texture: MaterialParameters.Texture?) -> any Material {
        if profile?.name == "Passthrough Hull" {
            var mask = OcclusionMaterial()
            mask.faceCulling = .none
            return mask
        }
        let tintScale: CGFloat = profile?.name == "Dark" ? 0.055 : 1
        let keepsSpectrum = profile?.materialStyle?.pattern == .rainbow || profile?.materialStyle?.pattern == .disco
        let base: CGFloat = keepsSpectrum ? 0.64 : 0
        let range = (1 - base) * tintScale
        let tint = UIColor(red: base + CGFloat(color.r) * range, green: base + CGFloat(color.g) * range,
                           blue: base + CGFloat(color.b) * range, alpha: 1)
        var opacityTexture = texture
        if profile?.materialStyle?.luminanceMask == true {
            opacityTexture?.swizzle = .init(red: .red, green: .red, blue: .red, alpha: .one)
        } else {
            opacityTexture?.swizzle = .init(red: .alpha, green: .alpha, blue: .alpha, alpha: .one)
        }
        let scale = color.a * (profile?.effect?.opacity ?? 1)
        let usesTextureOpacity = profile?.cutoff != nil || (scale < 1 && profile?.effect != nil)
        let opacity = PhysicallyBasedMaterial.Opacity(scale: scale, texture: usesTextureOpacity ? opacityTexture : nil)
        let transparent = scale < 1 || (usesTextureOpacity && opacityTexture != nil)
        let cutoff = opacityTexture == nil ? nil : profile?.cutoff
        let emission = profile?.effect?.emission ?? 0
        if let profile, !profile.selfLit || profile.faceted || emission > 0 {
            var material = PhysicallyBasedMaterial()
            material.baseColor = .init(tint: tint, texture: texture)
            if profile.name == "Mylar Tube" {
                material.metallic = 0.85
                material.roughness = 0.25
            } else if profile.name == "Diamond Hull" || profile.name == "Diamond Membrane" {
                material.metallic = 0.20
                material.roughness = 0.14
            } else if ["Shiny Hull", "Smooth Hull", "Shiny Membrane"].contains(profile.name) {
                material.roughness = 0.24
            } else {
                material.roughness = 0.72
            }
            material.faceCulling = profile.singleSided ? .back : .none
            if profile.materialStyle?.wireframe == true { material.triangleFillMode = .lines }
            if emission > 0 {
                material.emissiveColor = .init(color: tint, texture: texture)
                material.emissiveIntensity = emission
            }
            material.opacityThreshold = cutoff
            if transparent { material.blending = .transparent(opacity: opacity) }
            return material
        }
        var material = UnlitMaterial(color: tint)
        if let profile {
            material.color = .init(tint: tint, texture: texture)
            if #available(visionOS 2, *) { material.faceCulling = profile.singleSided ? .back : .none }
            material.opacityThreshold = cutoff
        }
        if transparent { material.blending = .transparent(opacity: opacity) }
        return material
    }

    @discardableResult
    func update(_ document: DrawingDocument, model: WorkspaceModel? = nil) -> Bool {
        guard displayed != document else { return true }
        do {
            // Build off-scene first; a failed mesh never leaves a half-updated artwork.
            let artwork = Entity()
            var partCount = 0
            var triangles = 0
            for stroke in document.strokes {
                let parts = StrokeAppearance.parts(stroke)
                if let entity = try Self.entity(for: stroke, parts: parts, countTriangles: { triangles += $0 }) {
                    artwork.addChild(entity)
                }
                partCount += parts.count
            }
            // Display-only centering and scale; never mutate stored coordinates or arc lengths.
            let positions = document.strokes.flatMap { $0.points.map(\.position) }
            if let first = positions.first {
                let low = positions.reduce(first) { simd_min($0, $1) }
                let high = positions.reduce(first) { simd_max($0, $1) }
                let extent = max(0.32, (high - low).max())
                let scale = min(1.5, 0.48 / extent)
                artwork.scale = SIMD3(repeating: scale)
                artwork.position = -(low + high) * 0.5 * scale
            }
            root.children.removeAll()
            root.addChild(artwork)
            displayed = document
            triangleCount = triangles
            model?.renderedDocument = document
            model?.renderedPartCount = partCount
            model?.renderFailure = nil
            return true
        } catch {
            model?.renderFailure = String(localized: "RealityKit mesh error: \(error.localizedDescription)")
            return false
        }
    }
}
