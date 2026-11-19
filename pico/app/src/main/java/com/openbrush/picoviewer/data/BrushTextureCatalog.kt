package com.openbrush.picoviewer.data

import org.json.JSONObject
import java.net.URLDecoder

data class BrushTexture(val brush:String,val guid:String,val slot:String,val asset:String,val size:Long,val sha256:String,val aliases:List<String>)

/** Uses the exported image's brush identity and texture slot, never the artwork filename. */
class BrushTextureCatalog(document:JSONObject) {
    private val entries = document.getJSONArray("entries").let { a -> (0 until a.length()).map { i ->
        val j=a.getJSONObject(i)
        val names=j.getJSONArray("aliases")
        BrushTexture(j.getString("brush"),j.getString("guid"),j.getString("slot"),j.getString("asset"),
            j.getLong("size"),j.getString("sha256"),(0 until names.length()).map{names.getString(it)})
    } }
    fun forBrush(guid:String,slot:String="MainTex"):BrushTexture?=entries.firstOrNull{it.guid.equals(guid,true) && it.slot.equals(slot,true)}
    fun match(uri:String):BrushTexture? {
        val filename=relativeResourcePath(uri).substringAfterLast('/')
        val match=EXPORTED.matchEntire(filename) ?: return null
        val (name,guid,slot)=match.destructured
        val candidates=entries.filter { it.slot.equals(slot,true) }
        candidates.firstOrNull{it.guid.equals(guid,true)}?.let{return it}
        val named=candidates.filter { e->e.aliases.any{it.equals(name,true)} }
        // A name shared by versions is usable only when it resolves to one texture.
        return named.takeIf{it.map{e->e.sha256}.distinct().size==1}?.firstOrNull()
    }
    companion object {
        private val EXPORTED=Regex("^Brush_(.+?)-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(?:-v[0-9.]+)?-([A-Za-z0-9_]+)\\.(?:png|jpe?g)$",RegexOption.IGNORE_CASE)
    }
}

/** Resource reads stay within the folder explicitly selected by the user. */
fun relativeResourcePath(uri:String):String {
    require(uri.length<=2048 && !uri.contains('\\') && !uri.contains('?') && !uri.contains('#')){"贴图路径无效：$uri"}
    val decoded=try{URLDecoder.decode(uri.replace("+","%2B"),"UTF-8")}catch(e:IllegalArgumentException){throw IllegalArgumentException("贴图路径编码无效",e)}
    require(!decoded.startsWith('/') && !decoded.contains(':') && !decoded.contains('\\') && decoded.none{it.code<32}){"仅支持模型文件夹内的相对资源：$uri"}
    val segments=decoded.split('/').filter{it!="."}
    require(segments.isNotEmpty() && segments.all{it.isNotEmpty() && it!=".."}){"贴图路径不能离开模型文件夹：$uri"}
    return segments.joinToString("/")
}
