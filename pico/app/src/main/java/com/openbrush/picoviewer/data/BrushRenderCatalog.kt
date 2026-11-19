package com.openbrush.picoviewer.data

import org.json.JSONObject

data class BrushRenderRule(val guid:String,val name:String,val aliases:List<String>,val selfLit:Boolean,
                           val blend:String,val depthWrite:Boolean,val cutoff:Double)

class BrushRenderCatalog(document:JSONObject) {
    private val entries=document.getJSONArray("entries").let { a->(0 until a.length()).map { i ->
        val j=a.getJSONObject(i);val names=j.getJSONArray("aliases")
        BrushRenderRule(j.getString("guid"),j.getString("name"),(0 until names.length()).map{names.getString(it)},
            j.getBoolean("selfLit"),j.getString("blend"),j.getBoolean("depthWrite"),j.getDouble("cutoff"))
    } }
    private val byGuid=entries.filter{it.guid.isNotEmpty()}.associateBy{it.guid.lowercase()}
    fun forBrush(guid:String):BrushRenderRule?=if(guid.equals(PASSTHROUGH_HULL,ignoreCase=true)) depthHull else byGuid[guid.lowercase()]
    fun match(material:JSONObject):BrushRenderRule? {
        val guid=material.optJSONObject("extensions")?.optJSONObject("GOOGLE_tilt_brush_material")?.optString("guid")
        if(!guid.isNullOrEmpty())forBrush(guid)?.let{return it}
        val name=material.optString("name")
        GUID.find(name)?.value?.let{forBrush(it)?.let{return it}}
        val normalized=key(name.removePrefix("brush_").replace(Regex("_\\d+$"),""))
        val candidates=entries.filter{e->e.aliases.any{key(it)==normalized}}
        return candidates.firstOrNull()?.takeIf{candidates.map{it.selfLit to it.blend}.distinct().size==1}
    }
    companion object {
        const val PASSTHROUGH_HULL="cc131ff8-0d17-4677-93e0-d7cd19fea9ac"
        internal val depthHull=BrushRenderRule(PASSTHROUGH_HULL,"Passthrough Hull",emptyList(),true,"DEPTH",true,0.0)
        private fun key(s:String)=s.lowercase().filter{it.isLetterOrDigit()}
        private val GUID=Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",RegexOption.IGNORE_CASE)
    }
}
