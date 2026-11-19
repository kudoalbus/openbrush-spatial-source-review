package com.openbrush.picoviewer.data

import com.openbrush.picoviewer.domain.drawing.*
import org.json.JSONArray
import org.json.JSONObject

object BrushBookCatalogCodec {
    fun decode(text:String):BrushBookCatalog {
        val j=JSONObject(text)
        require(j.getInt("version")==1 && j.getInt("columns")==4 && j.getInt("rows")==3)
        val list=j.getJSONArray("entries")
        val entries=(0 until list.length()).map { i ->
            val e=list.getJSONObject(i)
            fun optional(key:String)=if(e.has(key) && !e.isNull(key))e.getString(key) else null
            BrushBookEntry(e.getString("id"),e.getString("name"),optional("icon"),optional("renderer"),optional("model"))
        }
        require(entries.map { it.id }.distinct().size==entries.size)
        val atlases=j.getJSONObject("atlases")
        return BrushBookCatalog(entries.associateBy { it.id },strings(j.getJSONArray("basic")),
            strings(j.getJSONArray("classic")),strings(j.getJSONArray("extended")),
            strings(atlases.getJSONArray("classic")),strings(atlases.getJSONArray("extended")),
            j.getString("sourceCommit"),j.getInt("atlasCell"),j.getInt("atlasGutter"),j.getInt("atlasWidth"),j.getInt("atlasHeight"))
    }
    private fun strings(array:JSONArray)=(0 until array.length()).map(array::getString)
}
