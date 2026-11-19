package com.openbrush.picoviewer.data

import com.openbrush.picoviewer.domain.drawing.*
import org.json.JSONArray
import org.json.JSONObject

object PaletteCatalog {
    fun decode(text:String):PaletteLayout {
        val j=JSONObject(text);require(j.getInt("version")==1 && j.getString("units")=="meters")
        val items=j.getJSONArray("targets")
        return PaletteLayout(vector(j.getJSONArray("grip")),(0 until items.length()).map { i ->
            val target=items.getJSONObject(i)
            PaletteTarget(target.getInt("id"),PaletteKind.valueOf(target.getString("kind")),target.getString("name"),
                vector(target.getJSONArray("position")),target.getDouble("radius").toFloat(),
                target.optString("color").takeIf { it.startsWith("#") }?.let(::color),
                target.optString("brush").takeIf { it.isNotEmpty() })
        })
    }
    private fun vector(a:JSONArray):V3 { require(a.length()==3);return V3(a.getDouble(0).toFloat(),a.getDouble(1).toFloat(),a.getDouble(2).toFloat()) }
    private fun color(hex:String):InkColor {
        require(Regex("#[0-9a-fA-F]{6}").matches(hex))
        return InkColor(hex.substring(1,3).toInt(16)/255f,hex.substring(3,5).toInt(16)/255f,hex.substring(5,7).toInt(16)/255f)
    }
}
