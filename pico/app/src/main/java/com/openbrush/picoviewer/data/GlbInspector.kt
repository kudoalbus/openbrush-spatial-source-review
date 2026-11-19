package com.openbrush.picoviewer.data

import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class GlbInfo(
    val colorPrimitives: Int,
    val primitives: Int,
    val brushMaterials: Int,
    val externalResources: Boolean,
    val triangleCount: Long?,
)

object GlbInspector {
    fun json(file: File): JSONObject = file.inputStream().use { stream ->
        val header = stream.readNBytes(20)
        require(header.size == 20) { "GLB 文件被截断" }
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        require(b.int == 0x46546c67 && b.int == 2) { "仅支持 glTF 2.0 GLB" }
        require(b.int.toLong() == file.length()) { "GLB 总长度不匹配" }
        val length = b.int
        require(length in 2..(16*1024*1024) && length <= file.length()-20 && b.int == 0x4e4f534a) { "GLB JSON 块无效" }
        val bytes = stream.readNBytes(length); require(bytes.size == length)
        JSONObject(String(bytes,Charsets.UTF_8).trimEnd('\u0000',' '))
    }
    fun inspect(file: File): GlbInfo {
        val j = json(file); var color = 0; var total = 0; var brushes = 0; var external = false
        j.optJSONArray("meshes")?.let { meshes -> for (m in 0 until meshes.length()) {
            val p = meshes.getJSONObject(m).getJSONArray("primitives")
            for (n in 0 until p.length()) { total++; if (p.getJSONObject(n).getJSONObject("attributes").has("COLOR_0")) color++ }
        } }
        j.optJSONArray("materials")?.let { a -> for (i in 0 until a.length()) if (Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-").containsMatchIn(a.getJSONObject(i).optString("name"))) brushes++ }
        for (key in listOf("buffers","images")) j.optJSONArray(key)?.let { a -> for (i in 0 until a.length()) {
            val uri = a.getJSONObject(i).optString("uri"); if (uri.isNotEmpty() && !uri.startsWith("data:")) external = true
        } }
        require(total > 0) { "GLB 不含可显示网格" }
        return GlbInfo(color,total,brushes,external,triangleCount(j))
    }

    internal fun triangleCount(json: JSONObject): Long? {
        val scenes = json.optJSONArray("scenes") ?: return null
        val scene = scenes.optJSONObject(json.optInt("scene", 0)) ?: return null
        val roots = scene.optJSONArray("nodes") ?: return 0
        val nodes = json.optJSONArray("nodes") ?: return null
        val meshes = json.optJSONArray("meshes") ?: return null
        val accessors = json.optJSONArray("accessors") ?: return null
        val visited = BooleanArray(nodes.length())
        val meshCounts = mutableMapOf<Int, Long>()
        val pending = ArrayDeque<Int>()
        for (index in 0 until roots.length()) pending.addLast(roots.optInt(index, -1))
        var total = 0L
        // A mesh contributes once per node that uses it in the selected scene.
        while (pending.isNotEmpty()) {
            val index = pending.removeLast()
            val node = nodes.optJSONObject(index) ?: return null
            if (visited[index] || node.optJSONObject("extensions")?.has("EXT_mesh_gpu_instancing") == true) return null
            visited[index] = true
            if (node.has("mesh")) {
                val meshIndex = node.optInt("mesh", -1)
                total += meshCounts.getOrPut(meshIndex) {
                    val primitives = meshes.optJSONObject(meshIndex)?.optJSONArray("primitives") ?: return null
                    var count = 0L
                    for (p in 0 until primitives.length()) {
                        val primitive = primitives.optJSONObject(p) ?: return null
                        val mode = primitive.optInt("mode", 4)
                        if (mode !in 4..6) continue
                        val accessor = if (primitive.has("indices")) primitive.optInt("indices", -1)
                            else primitive.optJSONObject("attributes")?.optInt("POSITION", -1) ?: return null
                        val elements = accessors.optJSONObject(accessor)?.optInt("count", 0) ?: return null
                        if (elements < 3 || (mode == 4 && elements % 3 != 0)) return null
                        count += if (mode == 4) elements / 3L else elements.toLong() - 2
                    }
                    count
                }
            }
            node.optJSONArray("children")?.let { children ->
                for (child in 0 until children.length()) pending.addLast(children.optInt(child, -1))
            }
        }
        return total
    }
}
