package com.openbrush.picoviewer.data

import com.openbrush.picoviewer.domain.model.*
import com.openbrush.picoviewer.domain.drawing.V3
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/** Small, durable window payload; never put mesh buffers into an Android Bundle. */
object ArtworkMetadata {
    fun encode(a:Artwork):String=JSONObject().apply {
        put("path",a.path);put("name",a.name);put("format",a.format)
        put("colorInfo",a.colorInfo);put("notice",a.notice)
        a.triangleCount?.let { put("triangleCount", it) }
        a.lighting?.let { put("lighting",lightingJson(it)) }
        a.gaussian?.let { info ->
            fun coordinates(v: V3)=JSONArray(listOf(v.x,v.y,v.z))
            put("gaussian",JSONObject().put("count",info.count)
                .put("lower",coordinates(info.lower)).put("upper",coordinates(info.upper)))
        }
    }.toString()
    fun decode(s:String):Artwork=JSONObject(s).let {
        val gaussian=it.optJSONObject("gaussian")?.let { info ->
            fun point(key: String): V3 {
                val a=info.getJSONArray(key)
                return V3(a.getDouble(0).toFloat(),a.getDouble(1).toFloat(),a.getDouble(2).toFloat())
            }
            GaussianArtworkInfo(info.getInt("count"),point("lower"),point("upper"))
        }
        Artwork(it.getString("path"),it.getString("name"),it.getString("format"),it.getString("colorInfo"),
            it.optString("notice"),it.optJSONObject("lighting")?.let(::parseLighting),gaussian,
            it.optLong("triangleCount", -1L).takeIf { count -> count >= 0L })
    }
    fun lightingJson(l:ArtworkLighting)=JSONObject().apply {
        put("ambient",JSONArray(l.ambient));put("source",l.source)
        put("lights",JSONArray(l.lights.map { light->JSONObject().apply {
            put("color",JSONArray(light.color));put("orientation",JSONArray(light.orientation));put("shadow",light.shadow)
        } }))
    }
    private fun vector(j:JSONArray?,n:Int,max:Float,negative:Boolean=false):List<Float>? {
        if(j==null || j.length()<n)return null
        val v=(0 until n).map { j.optDouble(it,Double.NaN).toFloat() }
        return v.takeIf { it.all { x->x.isFinite() && x>=(if(negative)-max else 0f) && x<=max } }
    }
    private fun quaternion(j:JSONArray?):List<Float>? {
        val q=vector(j,4,1e6f,true) ?: return null
        val length=sqrt(q.sumOf { (it*it).toDouble() }).toFloat()
        return q.takeIf{length>1e-7f}?.map { it/length }
    }
    fun parseLighting(j:JSONObject):ArtworkLighting? {
        val ambient=vector(j.optJSONArray("ambient"),3,1f) ?: return null
        val a=j.optJSONArray("lights") ?: return null
        if(a.length()>2)return null
        val lights=(0 until a.length()).map { i ->
            val l=a.optJSONObject(i) ?: return null
            ArtworkLight(vector(l.optJSONArray("color"),3,65504f) ?: return null,
                quaternion(l.optJSONArray("orientation")) ?: return null,l.optBoolean("shadow"))
        }
        return ArtworkLighting(ambient,lights,j.optString("source","tilt"))
    }
    /** Invalid optional lighting must not make otherwise readable strokes unloadable. */
    fun fromTilt(metadata:JSONObject,preset:(String)->ArtworkLighting?={null}):ArtworkLighting? {
        val custom=metadata.optJSONObject("Lights")
        if(custom!=null) {
            val ambient=vector(custom.optJSONArray("Ambient"),3,255f) ?: return null
            val lights=listOf("Shadow","NoShadow").map { key ->
                val l=custom.optJSONObject(key) ?: return null
                ArtworkLight(vector(l.optJSONArray("Color"),3,65504f) ?: return null,
                    quaternion(l.optJSONArray("Orientation")) ?: return null,key=="Shadow")
            }
            return ArtworkLighting(ambient.map{it/255f},lights,"tilt:Lights")
        }
        return preset(metadata.optString("EnvironmentPreset").lowercase())
    }
}
