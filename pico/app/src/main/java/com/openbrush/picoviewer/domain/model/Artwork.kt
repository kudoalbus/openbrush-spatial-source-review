package com.openbrush.picoviewer.domain.model

import com.openbrush.picoviewer.domain.drawing.V3

data class Artwork(
    val path: String,
    val name: String,
    val format: String,
    val colorInfo: String,
    val notice: String = "",
    val lighting: ArtworkLighting? = null,
    val gaussian: GaussianArtworkInfo? = null,
    val triangleCount: Long? = null,
)

data class GaussianArtworkInfo(val count: Int, val lower: V3, val upper: V3) {
    init {
        val extent = upper - lower
        require(count > 0 && lower.finite() && upper.finite() && extent.finite() &&
            extent.x >= 0f && extent.y >= 0f && extent.z >= 0f &&
            maxOf(extent.x, extent.y, extent.z) > 0f) { "高斯数据的空间范围无效" }
    }
}

data class StrokePoint(val position: FloatArray, val rotation: FloatArray, val pressure: Float, val timeMs: Long = 0L)
data class BrushStroke(val brush: Int, val color: FloatArray, val size: Float, val scale: Float, val points: List<StrokePoint>)
data class TiltSketch(val strokes: List<BrushStroke>, val brushes: List<String>, val hasExternalModels: Boolean,val lighting:ArtworkLighting?=null)

/** Open Brush scene-space lights. Color includes the original HDR intensity. */
data class ArtworkLight(val color:List<Float>,val orientation:List<Float>,val shadow:Boolean)
data class ArtworkLighting(val ambient:List<Float>,val lights:List<ArtworkLight>,val source:String)
