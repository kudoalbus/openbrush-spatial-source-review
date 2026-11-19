// OpenBrush Spatial Kotlin adaptation of Open Brush / Tilt Brush behavior. See pico/NOTICE.
/* Format based on Open Brush Toolkit tilt.py, Copyright 2016 Google Inc., Apache-2.0. */
package com.openbrush.picoviewer.data

import com.openbrush.picoviewer.domain.model.*
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

object TiltReader {
    const val MAX_SKETCH_BYTES = 64 * 1024 * 1024
    const val MAX_POINTS = 200_000

    fun read(file: File,preset:(String)->ArtworkLighting?={null}): TiltSketch =
        file.inputStream().use { read(it, preset) }

    fun read(input: InputStream,preset:(String)->ArtworkLighting?={null}): TiltSketch {
        val entries=input.buffered().use { stream ->
            val header = stream.readNBytes(16)
            require(header.size == 16 && header.copyOfRange(0,4).contentEquals(byteArrayOf(0x74,0x69,0x6c,0x54))) { "不是有效的 .tilt 文件" }
            val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val headerSize=b.getShort(4).toInt() and 65535
            require(headerSize in 16..4096 && b.getShort(6).toInt() == 1) { "不支持的 .tilt 容器版本" }
            require(stream.readNBytes(headerSize-16).size==headerSize-16){".tilt 文件头被截断"}
            // Android ZipFile validates offsets differently from desktop Java. Start at
            // the ZIP local header and stream entries; central-directory offsets are irrelevant.
            ZipInputStream(stream).use { zip ->
                val found=mutableMapOf<String,ByteArray>();var count=0;var total=0L
                while(true) {
                    val e=zip.nextEntry ?: break
                    require(++count<=128){".tilt 归档条目过多"}
                    val limit=when(e.name){"data.sketch"->MAX_SKETCH_BYTES;"metadata.json"->2*1024*1024;else->8*1024*1024}
                    val bytes=zip.readNBytes(limit+1)
                    require(bytes.size<=limit){"${e.name} 超出读取上限"}
                    total+=bytes.size;require(total<=96L*1024*1024){".tilt 解压数据过大"}
                    if(e.name in setOf("data.sketch","metadata.json")) {
                        require(!found.containsKey(e.name)){".tilt 含有重复条目"}
                        found[e.name]=bytes
                    }
                    zip.closeEntry()
                }
                found
            }
        }
        fun entry(name:String)=entries[name] ?: error(".tilt 缺少 $name")
        return run {
            val metadata = JSONObject(String(entry("metadata.json"), Charsets.UTF_8))
            val index = metadata.optJSONArray("BrushIndex") ?: error("缺少 BrushIndex")
            val brushes = (0 until index.length()).map { index.getString(it) }
            val strokes = parseSketch(entry("data.sketch"))
            require(strokes.all { it.brush in brushes.indices }) { "笔刷索引越界" }
            TiltSketch(strokes, brushes, (metadata.optJSONArray("ModelIndex")?.length() ?: 0) > 0,ArtworkMetadata.fromTilt(metadata,preset))
        }
    }

    fun parseSketch(bytes: ByteArray): List<BrushStroke> {
        require(bytes.size <= MAX_SKETCH_BYTES)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun i(): Int { require(b.remaining() >= 4) { "笔触文件被截断" }; return b.int }
        fun f(): Float = Float.fromBits(i()).also { require(it.isFinite()) { "笔触包含无效数值" } }
        fun skip(n: Int) { require(n >= 0 && n <= b.remaining()) { "无效扩展长度" }; b.position(b.position()+n) }
        require(i() == 0xc576a5cd.toInt()) { "不支持的 sketch 标记" }
        require(i() in 5..6) { "仅支持 sketch v5/v6" }
        require(i() == 0) { "未知 sketch 标志" }
        skip(i())
        val count = i(); require(count in 0..100_000) { "笔触数量超出限制" }
        var total = 0
        return List(count) {
            val brush = i(); val color = FloatArray(4) { f().coerceIn(0f,1f) }
            val size = f(); require(size in 0f..10000f) { "无效笔宽" }
            val sm = i(); val cm = i(); var scale = 1f
            for (bit in 0..31) if (sm and (1 shl bit) != 0) {
                if (bit >= 16) skip(i()) else {
                    val value = i()
                    if (bit == 1) scale = Float.fromBits(value).also { require(it.isFinite() && it >= 0) }
                }
            }
            val n = i(); require(n in 0..MAX_POINTS && total <= MAX_POINTS - n) { "笔触控制点超过 $MAX_POINTS，建议导出 GLB" }; total += n
            val stride = 28L + 4L * Integer.bitCount(cm)
            require(n * stride <= b.remaining()) { "控制点数据被截断" }
            val points = List(n) {
                val position = FloatArray(3) { f() }
                val rotation = FloatArray(4) { f() }
                var pressure = 1f
                var timeMs = 0L
                for (bit in 0..31) if (cm and (1 shl bit) != 0) {
                    val value = i()
                        when (bit) {
                            0 -> pressure = Float.fromBits(value).also { require(it.isFinite()) }.coerceIn(0f,1f)
                            1 -> timeMs = Integer.toUnsignedLong(value)
                        }
                }
                StrokePoint(position,rotation,pressure,timeMs)
            }
            BrushStroke(brush,color,size,scale,points)
        }
    }
}
