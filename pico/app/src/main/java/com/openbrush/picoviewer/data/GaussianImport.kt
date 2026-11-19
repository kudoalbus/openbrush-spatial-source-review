package com.openbrush.picoviewer.data

import com.openbrush.picoviewer.domain.drawing.V3
import com.openbrush.picoviewer.domain.model.GaussianArtworkInfo
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** Inspect binary Gaussian PLY and normalize other supported inputs for the PICO native loader. */
internal object GaussianImport {
    private const val HEADER_LIMIT = 64 * 1024
    private const val DC = .2820948f
    private const val PACKED_STRIDE = 32
    val extensions = setOf("ply", "splat", "splt")
    private val positionNames = listOf("x", "y", "z")
    private val scaleNames = (0..2).map { "scale_$it" }
    private val rotationNames = (0..3).map { "rot_$it" }
    private val dcNames = (0..2).map { "f_dc_$it" }
    private val attributeNames = positionNames + scaleNames + rotationNames + "opacity" + dcNames

    data class Prepared(val file: File, val info: GaussianArtworkInfo)

    fun prepare(file: File, normalized: File): Prepared = try {
        if (file.extension.equals("ply", true)) readPly(file, normalized)
        else Prepared(normalized, convertPacked(file, normalized))
    } catch (e: Exception) {
        normalized.delete()
        throw e
    }

    private class Bounds {
        private var low = V3(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        private var high = V3(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        fun include(x: Float, y: Float, z: Float, radius: Float) {
            require(radius.isFinite() && radius > 0f) { "高斯尺寸无效" }
            low = V3(minOf(low.x,x-radius),minOf(low.y,y-radius),minOf(low.z,z-radius))
            high = V3(maxOf(high.x,x+radius),maxOf(high.y,y+radius),maxOf(high.z,z+radius))
        }
        fun info(count: Int) = GaussianArtworkInfo(count,low,high)
    }

    private fun convertPacked(file: File, target: File): GaussianArtworkInfo {
        require(file.length() > 0L && file.length() % PACKED_STRIDE == 0L) { "SPLAT／SPLT 必须由 32 字节记录组成" }
        val count = Math.toIntExact(file.length() / PACKED_STRIDE)
        val raw = ByteBuffer.allocate(PACKED_STRIDE).order(ByteOrder.LITTLE_ENDIAN)
        val bounds = Bounds()
        val rotation = FloatArray(4)
        file.inputStream().buffered().use { input ->
            target.outputStream().buffered().use { output ->
                val row = startPly(output, count, emptyList())
                repeat(count) {
                    require(input.readNBytes(raw.array(), 0, PACKED_STRIDE) == PACKED_STRIDE) { "SPLAT 数据被截断" }
                    val x=raw.getFloat(0)
                    val y=raw.getFloat(4)
                    val z=raw.getFloat(8)
                    val sx=raw.getFloat(12)
                    val sy=raw.getFloat(16)
                    val sz=raw.getFloat(20)
                    require(x.isFinite() && y.isFinite() && z.isFinite()) { "SPLAT 坐标无效" }
                    require(minOf(sx,sy,sz)>0f) { "SPLAT 缩放值必须大于零" }
                    bounds.include(x,y,z,maxOf(sx,sy,sz)*2f)
                    fun unsigned(offset: Int) = raw.get(offset).toInt() and 255
                    for (i in rotation.indices) rotation[i] = (unsigned(28+i)-128)/128f
                    val length = rotationLength(rotation)
                    val opacity = (unsigned(27)/255f).coerceIn(1e-6f,1f-1e-6f)
                    row.clear()
                    row.putFloat(x).putFloat(y).putFloat(z)
                    repeat(3) { row.putFloat(0f) }
                    for (i in 24..26) row.putFloat((unsigned(i)/255f-.5f)/DC)
                    row.putFloat(ln(opacity/(1f-opacity)))
                    row.putFloat(ln(sx)).putFloat(ln(sy)).putFloat(ln(sz))
                    for (value in rotation) row.putFloat(value/length)
                    output.write(row.array())
                }
            }
        }
        return bounds.info(count)
    }

    private data class PlyHeader(val count: Int, val rowBytes: Int, val headerBytes: Int,
        val order: ByteOrder, val floats: Map<String,Int>)

    private fun readPly(file: File, target: File): Prepared = file.inputStream().buffered().use { input ->
        val header=readHeader(input)
        require(header.count.toLong()*header.rowBytes <= file.length()-header.headerBytes) { "PLY 顶点数据被截断" }
        val offset=attributeNames.map { requireNotNull(header.floats[it]) { "缺少高斯 PLY 属性 $it" } }
        val restCount=header.floats.keys.count { it.startsWith("f_rest_") }
        require(restCount in setOf(0,9,24,45)) { "不支持的高斯球谐阶数" }
        val restNames=(0 until restCount).map { "f_rest_$it" }
        val rest=restNames.map { requireNotNull(header.floats[it]) { "PLY 的球谐属性编号不连续" } }
        val row=ByteBuffer.allocate(header.rowBytes).order(header.order)
        val bounds=Bounds()
        val values=FloatArray(offset.size)
        val rotation=FloatArray(4)
        val outFile=if(header.order == ByteOrder.LITTLE_ENDIAN) file else target
        val out=if(outFile==target)target.outputStream().buffered() else null
        out.use { output ->
            val packed=output?.let { startPly(it,header.count,restNames) }
            repeat(header.count) {
                require(input.readNBytes(row.array(),0,row.capacity())==row.capacity()) { "PLY 顶点数据被截断" }
                for(i in values.indices) {
                    values[i]=row.getFloat(offset[i])
                    require(values[i].isFinite()) { "PLY 顶点属性包含无效值" }
                }
                for(i in rotation.indices) rotation[i]=values[6+i]
                val length=rotationLength(rotation)
                val radius=exp(maxOf(values[3],values[4],values[5]))*2f
                bounds.include(values[0],values[1],values[2],radius)
                packed?.clear()
                if(packed!=null) {
                    for(i in 0..2)packed.putFloat(values[i])
                    repeat(3){packed.putFloat(0f)}
                    for(i in 11..13)packed.putFloat(values[i])
                }
                for(index in rest) {
                    val coefficient=row.getFloat(index)
                    require(coefficient.isFinite()) { "PLY 球谐系数无效" }
                    packed?.putFloat(coefficient)
                }
                if(packed!=null) {
                    packed.putFloat(values[10])
                    for(i in 3..5)packed.putFloat(values[i])
                    for(q in rotation)packed.putFloat(q/length)
                    requireNotNull(output).write(packed.array())
                }
            }
        }
        Prepared(outFile,bounds.info(header.count))
    }

    private fun readHeader(input: BufferedInputStream): PlyHeader {
        var consumed=0
        fun readLine(): String {
            val line=StringBuilder()
            while(true) {
                val byte=input.read()
                require(byte>=0 && ++consumed<=HEADER_LIMIT) { "高斯 PLY 文件头不完整或过长" }
                if(byte==10)return line.toString().trim()
                line.append(byte.toChar())
            }
        }
        require(readLine()=="ply") { "无效的 PLY 文件头" }
        var order: ByteOrder?=null
        var count=0
        var vertex=false
        var sawData=false
        var bytes=0
        val offsets=mutableMapOf<String,Int>()
        val names=mutableSetOf<String>()
        while(true) {
            val fields=readLine().split(Regex("\\s+"))
            when(fields.first()) {
                "format" -> {
                    require(order==null && fields.size==3 && fields[2]=="1.0") { "无效的 PLY 格式行" }
                    order=when(fields[1]) {
                        "binary_little_endian" -> ByteOrder.LITTLE_ENDIAN
                        "binary_big_endian" -> ByteOrder.BIG_ENDIAN
                        else -> error("请选择二进制 Gaussian PLY")
                    }
                }
                "element" -> {
                    require(fields.size==3) { "无效的 PLY 元素" }
                    val rows=fields[2].toIntOrNull()
                    require(rows!=null && rows>=0) { "无效的 PLY 元素数量" }
                    vertex=fields[1]=="vertex"
                    if(vertex) {
                        require(!sawData && rows>0) { "Gaussian PLY 需以顶点数据开头" }
                        count=rows
                    }
                    sawData=sawData || rows>0
                }
                "property" -> if(vertex) {
                    require(fields.size==3 && names.add(fields[2])) { "不支持列表或重复的 PLY 顶点属性" }
                    val width=when(fields[1]) {
                        "char","uchar","int8","uint8" -> 1
                        "short","ushort","int16","uint16" -> 2
                        "int","uint","int32","uint32" -> 4
                        "float","float32" -> 4.also { offsets[fields[2]]=bytes }
                        "double","float64" -> 8
                        else -> error("未知 PLY 属性类型")
                    }
                    bytes+=width
                }
                "end_header" -> {
                    require(order!=null && count>0 && bytes>0) { "PLY 缺少顶点或二进制格式" }
                    return PlyHeader(count,bytes,consumed,order,offsets)
                }
            }
        }
    }

    private fun startPly(output: BufferedOutputStream,count: Int,rest: List<String>): ByteBuffer {
        val fields=positionNames+listOf("nx","ny","nz")+dcNames+rest+"opacity"+scaleNames+rotationNames
        val header=buildString {
            append("ply\nformat binary_little_endian 1.0\nelement vertex $count\n")
            for(field in fields)append("property float $field\n")
            append("end_header\n")
        }
        output.write(header.toByteArray(Charsets.US_ASCII))
        return ByteBuffer.allocate(fields.size*4).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun rotationLength(values: FloatArray): Float {
        val length=sqrt(values.sumOf { it.toDouble()*it }).toFloat()
        require(length.isFinite() && length>1e-6f) { "高斯旋转四元数无效" }
        return length
    }
}
