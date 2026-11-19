package com.openbrush.picoviewer.capture

import com.openbrush.picoviewer.domain.drawing.PaintStroke
import com.openbrush.picoviewer.domain.drawing.StrokeTubeMesh
import com.openbrush.picoviewer.domain.drawing.OriginalBrushMesh
import com.openbrush.picoviewer.data.StudioBrushCatalog
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs

/** No SDK entities cross the network/background-thread boundary. All arrays are owned snapshots. */
data class CapturePose(val position: List<Float>, val rotation: List<Float>) {
    init {
        require(position.size == 3 && rotation.size == 4)
        require((position + rotation).all { it.isFinite() })
        require(abs(rotation.sumOf { (it * it).toDouble() } - 1.0) < .02)
    }
    fun json() = JSONObject().put("position", JSONArray(position)).put("rotation", JSONArray(rotation))
    fun matrix(): FloatArray {
        val (x, y, z, w) = rotation
        return floatArrayOf(
            1-2*y*y-2*z*z, 2*x*y+2*z*w, 2*x*z-2*y*w, 0f,
            2*x*y-2*z*w, 1-2*x*x-2*z*z, 2*y*z+2*x*w, 0f,
            2*x*z+2*y*w, 2*y*z-2*x*w, 1-2*x*x-2*y*y, 0f,
            position[0], position[1], position[2], 1f)
    }
}

data class CaptureFrame(
    val stageId: String, val receivedAtNs: Long, val generation: Long, val revision: Long,
    val head: CapturePose?, val leftController: CapturePose?, val rightController: CapturePose?,
    val documentToWorld: FloatArray, val paletteToWorld: FloatArray?, val strokes: List<PaintStroke>,
    val headReceivedAtNs: Long? = null, val controllersReceivedAtNs: Long? = null,
    val brushes: StudioBrushCatalog? = null
)

/** Column-major rigid transforms, meters, +Y up. Camera convention is -Z forward. */
object CaptureTransform {
    fun read(json: JSONArray): FloatArray {
        require(json.length() == 16) { "变换必须是16个数" }
        val m = FloatArray(16) { json.getDouble(it).toFloat() }
        require(m.all { it.isFinite() })
        require(abs(m[3]) < .001f && abs(m[7]) < .001f && abs(m[11]) < .001f && abs(m[15]-1) < .001f)
        require((12..14).all { abs(m[it]) <= 100f })
        for(a in 0..2) for(b in 0..2) {
            val dot = (0..2).sumOf { (m[a*4+it] * m[b*4+it]).toDouble() }
            require(abs(dot - if(a == b) 1.0 else 0.0) < .02) { "变换含缩放或剪切" }
        }
        val determinant = m[0]*(m[5]*m[10]-m[9]*m[6]) - m[4]*(m[1]*m[10]-m[9]*m[2]) + m[8]*(m[1]*m[6]-m[5]*m[2])
        require(abs(determinant-1f) < .03f) { "变换坐标系不匹配" }
        return m
    }
    fun multiply(a: FloatArray, b: FloatArray) = FloatArray(16) { i ->
        val row = i % 4; val col = i / 4
        (0..3).sumOf { (a[it*4+row] * b[col*4+it]).toDouble() }.toFloat()
    }
    fun inverse(m: FloatArray): FloatArray {
        val out = FloatArray(16)
        for(col in 0..2) for(row in 0..2) out[col*4+row] = m[row*4+col]
        for(row in 0..2) out[12+row] = -(0..2).sumOf { (out[it*4+row]*m[12+it]).toDouble() }.toFloat()
        out[15] = 1f
        return out
    }
    fun json(matrix: FloatArray) = JSONArray(matrix.toList())
}

/** Mesh packets share the exact local-erasure mesher used by the drawing renderer. */
object CaptureProtocol {
    const val VERSION = 1
    const val SERVICE_TYPE = "_openbrush-mrc._tcp."
    const val PALETTE_ASSET = "left-hand-palette.glb"
    const val CHUNK_BYTES = 48 * 1024
    fun message(type: String) = JSONObject().put("type", type).put("protocolVersion", VERSION)
    fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun resource(stroke: PaintStroke, brushes: StudioBrushCatalog? = null, original: PaintStroke = stroke): Pair<String, ByteArray> {
        val mesh = brushes?.get(stroke.style.brush)?.let { OriginalBrushMesh.build(stroke,it,original) }
            ?: StrokeTubeMesh.build(stroke)
        val data = ByteBuffer.allocate(16 + mesh.positions.size * 24 + mesh.triangles.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        data.putInt(0x314d424f) // bytes OBM1
        data.putInt(mesh.positions.size).putInt(mesh.triangles.size).putInt(0)
        mesh.positions.forEach { data.putFloat(it.x).putFloat(it.y).putFloat(it.z) }
        mesh.normals.forEach { data.putFloat(it.x).putFloat(it.y).putFloat(it.z) }
        mesh.triangles.forEach { data.putInt(it) }
        val bytes = data.array()
        return hash(bytes) to bytes
    }
    fun stroke(stroke: PaintStroke, resource: String) = JSONObject()
        .put("id", stroke.id).put("resource", resource).put("brush", stroke.style.brush)
        .put("color", JSONArray(listOf(stroke.style.color.r, stroke.style.color.g, stroke.style.color.b, stroke.style.color.a)))
        .put("colorSpace", "srgb").put("unlit", true).put("doubleSided", true)
        .put("erased", JSONArray().apply { stroke.erased.forEach { put(JSONArray(listOf(it.start, it.end))) } })
}
