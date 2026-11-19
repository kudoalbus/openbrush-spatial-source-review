package com.openbrush.picoviewer.data

import com.openbrush.picoviewer.domain.drawing.BrushBookCatalog
import com.openbrush.picoviewer.domain.drawing.PaintStyle
import com.openbrush.picoviewer.domain.drawing.BrushParticle
import com.openbrush.picoviewer.domain.drawing.BrushWave
import org.json.JSONObject

data class StudioBrushProfile(
    val name: String,
    val geometry: BrushGeometry,
    val material: BrushRenderRule,
    val texture: BrushTexture?,
    val atlasRows: Int,
    val tileRate: Float?,
    val modifier: Int,
    val faceted: Boolean,
    val spray: BrushSpray?,
    val opacity: Float = 1f,
    val emission: Float = 0f,
    val section: List<Pair<Float, Float>>? = null,
    val motion: BrushMaterialMotion? = null,
    val wave: BrushWave? = null,
    val particle: StudioParticle? = null,
    val depthOnly: Boolean = false,
) {
    val isArcSurface: Boolean get() = geometry.kind == "HULL" || geometry.kind == "MEMBRANE"
    val animatedGeometry: Boolean get() = wave != null || particle != null
}

data class StudioParticle(val motion: BrushParticle, val lifetime: Float, val rate: Float,
    val columns: Int, val opacity: Float, val emission: Float, val spacingUsesWidth: Boolean = false) {
    val spray = BrushSpray(if (spacingUsesWidth) rate else 1f, 0f, 0f, 0f, columns,
        if (spacingUsesWidth) null else .0025f / rate, crossed = !spacingUsesWidth)
}

data class BrushSpray(val rate: Float, val variation: Float, val rotation: Float,
    val scatter: Float, val columns: Int, val fixedInterval: Float? = null, val crossed: Boolean = false)

/** Uses the same Open Brush descriptors as TILT import and the visionOS brush catalog. */
class StudioBrushCatalog private constructor(private val profiles: Map<String, StudioBrushProfile>) {
    val enabledIds: Set<String> get() = profiles.keys

    operator fun get(id: String): StudioBrushProfile? = profiles[id.lowercase()]

    fun contactRadius(style: PaintStyle): Float? {
        val profile = this[style.brush] ?: return null
        val shape = profile.geometry
        // Use a conservative extent because smoothed pressure may exceed either raw sample.
        profile.spray?.let { return style.width * (.707107f * (1f + it.variation) + it.scatter) }
        profile.section?.let { return style.width * .707107f * shape.radiusMultiplier }
        if (profile.name == "Slice") return style.width * .707107f
        if (profile.modifier == 5) return style.width * (.5f * shape.radiusMultiplier + 1.5f)
        if (profile.name == "Spikes") return style.width * .55f * shape.radiusMultiplier
        return style.width * .5f * if (shape.kind == "TUBE") shape.radiusMultiplier else 1f
    }

    companion object {
        private val stableBrushes = setOf(
            "Oil Paint", "Ink", "Thick Paint", "Wet Paint", "Marker", "Tapered Marker",
            "Flat", "Tapered Flat", "Duct Tape", "Paper", "Wiggly Graphite", "Cel Vinyl",
            "Toon", "Wire", "Gouache", "Dry Brush", "Single Sided", "Double Flat", "Charcoal", "Wire (Lit)",
            "Icing", "Guts", "Mylar Tube", "Faceted Tube", "Petal", "Spikes", "Lofted", "Tapered Wire", "Muscle",
            "Ink Geometry", "Duct Tape Geometry", "Tapered Marker Flat", "Slice",
            "Double Tapered Flat", "Double Tapered Marker", "Dark",
            "Square Paper", "Thick Geometry", "3D Printing Brush"
        )
        private val sprays = mapOf(
            "Coarse Bristles" to BrushSpray(2f, .3f, 5f, .05f, 2),
            "Dot Marker" to BrushSpray(3f, 0f, 0f, 0f, 1),
            "Leaves2" to BrushSpray(2f, .3f, 20f, .15f, 2),
            "Splatter" to BrushSpray(3f, .5f, 180f, .05f, 2),
            "Dots" to BrushSpray(1f, 0f, 0f, 0f, 1, .025f, true),
            "Hyper Grid" to BrushSpray(1f, 0f, 0f, 0f, 1)
        )
        private val waves = mapOf(
            "Waveform" to BrushWave.WAVEFORM, "Waveform FFT" to BrushWave.FFT,
            "Waveform Tube" to BrushWave.TUBE, "Electricity" to BrushWave.ELECTRICITY,
            "Chromatic Wave" to BrushWave.CHROMATIC
        )
        private val particles = mapOf(
            "Bubbles" to StudioParticle(BrushParticle.BUBBLES, 3.2f, .15f, 1, .66f, .25f),
            "Rising Bubbles" to StudioParticle(BrushParticle.RISING_BUBBLES, 2.8f, .35f, 1, .66f, .25f),
            "Embers" to StudioParticle(BrushParticle.EMBERS, 1.8f, .35f, 1, .72f, 1.2f),
            "Smoke" to StudioParticle(BrushParticle.SMOKE, 4.8f, .05f, 2, .27f, 0f),
            "Snow" to StudioParticle(BrushParticle.SNOW, 4f, .35f, 1, .74f, .15f),
            "Stars" to StudioParticle(BrushParticle.STARS, 2.4f, .30f, 2, .85f, 1f),
            "Waveform Particles" to StudioParticle(BrushParticle.WAVEFORM, 2.2f, 4f, 1, .72f, .88f, true),
            "Dance Floor" to StudioParticle(BrushParticle.DANCE_FLOOR, 2.6f, 1f, 1, .86f, .58f, true)
        )
        private val sections = mapOf(
            "Square Paper" to listOf(1f to -.375f, 1f to .375f, -1f to .375f, -1f to -.375f),
            "Thick Geometry" to listOf(1f to 0f, 0f to .125f, -1f to 0f, 0f to -.125f),
            "3D Printing Brush" to listOf(1f to -.99f, 1f to .99f, .99f to 1f, -.99f to 1f,
                -1f to .99f, -1f to -.99f, -.99f to -1f, .99f to -1f)
        )
        private val pinched = setOf("Double Tapered Flat", "Double Tapered Marker", "Dark")
        private val membranes = setOf("Matte Membrane", "Shiny Membrane", "Diamond Membrane", "Outlined Membrane")
        private const val PASSTHROUGH_HULL = "Passthrough Hull"
        private val plainMaterials = setOf("Slice", "Dark", PASSTHROUGH_HULL) + membranes
        private val hulls = setOf("Matte Hull", "Shiny Hull", "Unlit Hull", "Smooth Hull", "Concave Hull", "Diamond Hull", PASSTHROUGH_HULL)
        private val effects = mapOf(
            "Highlighter" to (.48f to 0f), "Soft Highlighter" to (.58f to 0f),
            "Feather" to (.42f to 0f), "Velvet Ink" to (.80f to 0f),
            "Drafting" to (.68f to .45f), "Light" to (.80f to 1.5f), "Tube Additive" to (.52f to .90f),
            "Dots" to (.64f to .35f), "Hyper Grid" to (.72f to .45f), "Dark" to (.72f to 0f),
            "Slice" to (.56f to 0f), "Diamond Hull" to (.54f to .1f), "Diamond Membrane" to (.62f to .1f)
        )
        private val faceted = setOf("Petal", "Spikes", "Lofted", "Lofted (Hue Shift)", "Faceted Tube")
        private val fourRowAtlases = setOf("Oil Paint", "Ink", "Wet Paint", "Wiggly Graphite", "Gouache", "Dry Brush", "Guts", "Muscle", "Velvet Ink", "Ink Geometry")
        private val tileRates = mapOf(
            "Wiggly Graphite" to .1f, "Thick Paint" to .2f, "Marker" to .15f,
            "Duct Tape" to .6f, "Paper" to .15f, "Charcoal" to .6f, "Guts" to 1f, "Muscle" to 1f,
            "Highlighter" to 1f, "Duct Tape Geometry" to .6f, "Thick Geometry" to .6f
        )

        fun load(book: BrushBookCatalog, readAsset: (String) -> String): StudioBrushCatalog {
            val geometryDocument = JSONObject(readAsset("brush-geometry.json"))
            val geometry = BrushGeometryCatalog(geometryDocument)
            val descriptions = geometryDocument.getJSONArray("entries")
            val modifiers = (0 until descriptions.length()).associate { index ->
                val descriptor = descriptions.getJSONObject(index)
                descriptor.getString("guid").lowercase() to descriptor.getInt("shapeModifier")
            }
            val materials = BrushRenderCatalog(JSONObject(readAsset("brush-rendering.json")))
            val textures = BrushTextureCatalog(JSONObject(readAsset("brush-textures/catalog.json")))
            val motionStyles = BrushMaterialMotion.brushStyles
            val names = stableBrushes + sprays.keys + particles.keys + hulls + membranes + effects.keys + motionStyles.keys
            val profiles = book.entries.values.filter { it.name in names }.associate { entry ->
                val source = requireNotNull(geometry.get(entry.id)) { "Missing geometry for ${entry.name}" }
                val section = sections[entry.name]
                val shape = when {
                    section != null -> source.copy(kind = "TUBE")
                    entry.name in membranes -> source.copy(kind = "MEMBRANE")
                    entry.name in hulls -> source.copy(kind = "HULL")
                    else -> source
                }
                val material = if (entry.name.startsWith("Diamond ")) {
                    BrushRenderRule(entry.id, entry.name, emptyList(), false, "ALPHA", false, 0.0)
                } else materials.forBrush(entry.id) ?: run {
                    require(entry.name in plainMaterials) { "Missing material for ${entry.name}" }
                    val membrane = entry.name in membranes
                    BrushRenderRule(entry.id, entry.name, emptyList(), !membrane,
                        if (membrane || entry.name == PASSTHROUGH_HULL) "OPAQUE" else "ALPHA", false, 0.0)
                }
                val particle = particles[entry.name]
                val spray = sprays[entry.name] ?: particle?.spray
                val effect = effects[entry.name]
                val motion = motionStyles[entry.name]
                require(when (shape.kind) {
                    "STAMP" -> spray != null
                    "HULL" -> entry.name in hulls
                    "MEMBRANE" -> entry.name in membranes
                    "RIBBON", "TUBE" -> spray == null
                    else -> false
                })
                require(material.blend in setOf("MASK", "OPAQUE", "DEPTH") ||
                    (material.blend in setOf("ADD", "ALPHA") && (effect != null || motion != null || particle != null)))
                entry.id.lowercase() to StudioBrushProfile(
                    entry.name, shape, material, textures.forBrush(entry.id).takeUnless { shape.kind in setOf("HULL", "MEMBRANE") },
                    if (entry.name in fourRowAtlases) 4 else 1, tileRates[entry.name] ?: motion?.tileRate,
                    if (entry.name in pinched) -1 else modifiers.getValue(entry.id.lowercase()),
                    entry.name in faceted || section != null, spray,
                    effect?.first ?: motion?.opacity ?: particle?.opacity ?: 1f,
                    effect?.second ?: motion?.emission ?: particle?.emission ?: 0f, section, motion,
                    waves[entry.name], particle, depthOnly=entry.name == PASSTHROUGH_HULL,
                )
            }
            require(profiles.values.map { it.name }.toSet() == names)
            return StudioBrushCatalog(profiles)
        }
    }
}
