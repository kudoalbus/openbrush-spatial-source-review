package com.openbrush.picoviewer.data

import org.json.JSONObject

data class BrushGeometry(
    val name:String="Approximate",val kind:String="RIBBON",val pressureMin:Float=.1f,
    val pressureOpacityMin:Float=1f,val pressureOpacityMax:Float=1f,val opacity:Float=1f,
    val pressureWindowMeters:Float=.2f,val zeroInitialPressure:Boolean=false,val singleSided:Boolean=false,
    val sides:Int=8,val radiusMultiplier:Float=1f,val crossSectionAngle:Float=0f,
    val sizeRatioX:Float=1f,val sizeRatioY:Float=1f,val approximate:Boolean=false,
)

class BrushGeometryCatalog(document:JSONObject) {
    private val entries=document.getJSONArray("entries").let { a->(0 until a.length()).associate { i ->
        val j=a.getJSONObject(i)
        j.getString("guid").lowercase() to BrushGeometry(j.getString("name"),j.getString("geometry"),
            j.getDouble("pressureMin").toFloat(),j.getDouble("pressureOpacityMin").toFloat(),j.getDouble("pressureOpacityMax").toFloat(),
            j.getDouble("opacity").toFloat(),j.getDouble("pressureWindowMeters").toFloat(),j.getBoolean("zeroInitialPressure"),
            j.getBoolean("singleSided"),j.getInt("sides").coerceIn(3,12),j.getDouble("radiusMultiplier").toFloat(),
            j.getDouble("crossSectionAngle").toFloat(),j.getDouble("sizeRatioX").toFloat(),j.getDouble("sizeRatioY").toFloat(),j.getBoolean("approximate"))
    } }
    fun get(guid:String):BrushGeometry?=entries[guid.lowercase()]
}
