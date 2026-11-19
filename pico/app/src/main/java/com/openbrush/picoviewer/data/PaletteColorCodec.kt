package com.openbrush.picoviewer.data

import com.openbrush.picoviewer.domain.drawing.*
import org.json.JSONArray
import org.json.JSONObject

object PaletteColorCodec {
    fun encode(state:PaletteColorUiState):String=JSONObject().put("version",1).put("picker",state.picker)
        .put("selected",array(state.selected)).put("favorites",JSONArray().apply {
            state.favorites.forEach { put(it?.let(::array) ?: JSONObject.NULL) }
        }).toString()
    fun decode(text:String):PaletteColorUiState {
        val j=JSONObject(text);require(j.getInt("version")==1)
        val favorites=j.getJSONArray("favorites");require(favorites.length()==5)
        return PaletteColorUiState(j.getBoolean("picker"),read(j.getJSONArray("selected")),
            (0..4).map { if(favorites.isNull(it))null else read(favorites.getJSONArray(it)) })
    }
    private fun array(c:HsvInk)=JSONArray(listOf(c.h,c.s,c.v,c.alpha))
    private fun read(a:JSONArray):HsvInk { require(a.length()==4);return HsvInk(a.getDouble(0).toFloat(),a.getDouble(1).toFloat(),a.getDouble(2).toFloat(),a.getDouble(3).toFloat()) }
}
