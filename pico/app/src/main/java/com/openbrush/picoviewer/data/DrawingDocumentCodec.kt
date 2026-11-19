package com.openbrush.picoviewer.data

import com.openbrush.picoviewer.domain.drawing.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Native editable workspace. This is intentionally not advertised as a TILT file. */
object DrawingDocumentCodec {
    private const val MAX_BYTES = 32 * 1024 * 1024
    internal const val MAX_STROKES = 2000
    internal const val MAX_STROKE_POINTS = 4096
    internal const val MAX_POINTS = 200_000

    fun encodeBytes(strokes: List<PaintStroke>): ByteArray =
        encode(strokes).toByteArray(Charsets.UTF_8).also {
            require(it.size <= MAX_BYTES) { "绘画文件过大" }
        }

    /** The caller owns the stream. Bound allocation even when the provider reports no size. */
    fun read(input: InputStream): List<PaintStroke> {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(count <= MAX_BYTES - output.size()) { "绘画文件过大" }
            output.write(buffer, 0, count)
        }
        return decode(output.toString(Charsets.UTF_8.name()))
    }

    fun encode(strokes: List<PaintStroke>): String = JSONObject().put("version",3).put("units","meters_rh")
        .put("strokes",JSONArray().apply { strokes.forEach { s ->
            put(JSONObject().put("id",s.id).put("brush",s.style.brush).put("width",s.style.width)
                .put("color",JSONArray(listOf(s.style.color.r,s.style.color.g,s.style.color.b,s.style.color.a)))
                .put("points",JSONArray().apply { s.points.forEach { p -> put(JSONArray(listOf(p.position.x,p.position.y,p.position.z,p.pressure,p.timeMs,p.normal.x,p.normal.y,p.normal.z))) } })
                .put("erased",JSONArray().apply { s.erased.forEach { put(JSONArray(listOf(it.start,it.end))) } })
                .put("recolored",JSONArray().apply { s.recolored.forEach {
                    put(JSONArray(listOf(it.span.start,it.span.end,it.color.r,it.color.g,it.color.b,it.color.a)))
                } }))
        } }).toString()

    fun decode(text: String): List<PaintStroke> {
        require(text.length <= MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "绘画文件过大" }
        val root=JSONObject(text)
        val version=root.getInt("version")
        require(version in 1..3 && root.getString("units")=="meters_rh") { "不支持的绘画文档版本" }
        val strokes=root.getJSONArray("strokes");require(strokes.length()<=MAX_STROKES)
        var count=0
        val result=(0 until strokes.length()).map { i ->
            val s=strokes.getJSONObject(i);val c=s.getJSONArray("color");require(c.length()==4)
            val color=InkColor(c.number(0),c.number(1),c.number(2),c.number(3))
            val p=s.getJSONArray("points");require(p.length() in 2..MAX_STROKE_POINTS)
            count+=p.length();require(count<=MAX_POINTS)
            val points=(0 until p.length()).map { k ->
                val a=p.getJSONArray(k);require(a.length()==if(version==1)5 else 8)
                PaintPoint(V3(a.number(0),a.number(1),a.number(2)),a.number(3),a.getLong(4),
                    if(version==1)V3(0f,0f,1f) else V3(a.number(5),a.number(6),a.number(7)))
            }
            val e=s.getJSONArray("erased");require(e.length()<=20_000)
            val erased=(0 until e.length()).map { k -> val a=e.getJSONArray(k);require(a.length()==2);ArcSpan(a.number(0),a.number(1)) }
            val stroke=PaintStroke(s.getString("id"),PaintStyle(s.getDouble("width").toFloat(),color,s.getString("brush")),points)
            require(erased.all { it.end<=stroke.length+.0001f }) { "擦除区间超出原笔触" }
            val recolored=if(version<3) emptyList() else {
                val entries=s.getJSONArray("recolored");require(entries.length()<=20_000)
                var end=0f
                (0 until entries.length()).map { k ->
                    val a=entries.getJSONArray(k);require(a.length()==6)
                    val span=ArcSpan(a.number(0),a.number(1))
                    require(span.start>=end && span.end<=stroke.length && span.end-span.start>1e-6f) { "上色区间重叠或超出原笔触" }
                    end=span.end
                    val tint=InkColor(a.number(2),a.number(3),a.number(4),a.number(5))
                    require(tint.a==color.a) { "局部上色不能改变笔触透明度" }
                    ColorSpan(span,tint)
                }
            }
            // Preserve authored intervals on an unchanged round trip. Geometry clips and
            // merges them through visibleSpans without altering the stored data.
            stroke.copy(erased=erased,recolored=recolored)
        }
        require(result.map { it.id }.distinct().size==result.size) { "笔触身份重复" }
        return result
    }
    private fun JSONArray.number(i: Int) = getDouble(i).toFloat().also { require(it.isFinite()) }
}
