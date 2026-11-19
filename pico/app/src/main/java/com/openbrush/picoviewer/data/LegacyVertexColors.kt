package com.openbrush.picoviewer.data

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Older Open/Tilt Brush exporters write sRGB COLOR_0 despite glTF's linear-color convention. */
object LegacyVertexColors {
    fun needsConversion(j:JSONObject):Boolean {
        val generator=j.optJSONObject("asset")?.optString("generator") ?: return false
        return Regex("^(Tilt Brush|Open Brush) ",RegexOption.IGNORE_CASE).containsMatchIn(generator)
    }
    fun convert(input:File,output:File):Int {
        val j=GlbInspector.json(input)
        if(!needsConversion(j))return 0
        val accessors=j.getJSONArray("accessors");val views=j.getJSONArray("bufferViews")
        val colors=linkedSetOf<Int>();val meshes=j.getJSONArray("meshes")
        for(m in 0 until meshes.length()) {
            val p=meshes.getJSONObject(m).getJSONArray("primitives")
            for(i in 0 until p.length())p.getJSONObject(i).getJSONObject("attributes").let { a->if(a.has("COLOR_0"))colors.add(a.getInt("COLOR_0")) }
        }
        if(colors.isEmpty())return 0
        val append=ByteArrayOutputStream();val replacements=mutableMapOf<Int,Int>()
        RandomAccessFile(input,"r").use { source->
            source.seek(12);val jsonLength=Integer.reverseBytes(source.readInt())
            val binHeader=20L+jsonLength;require(binHeader+8<=source.length()) { "GLB 缺少内嵌 BIN" }
            source.seek(binHeader);val binLength=Integer.reverseBytes(source.readInt());require(Integer.reverseBytes(source.readInt())==0x004e4942 && binLength>=0 && binHeader+8+binLength<=source.length())
            val binStart=binHeader+8
            val alignedBinLength=(binLength+3)/4*4
            var total=0
            for(old in colors) {
                val a=accessors.getJSONObject(old)
                require(!a.has("sparse")){"此 Open Brush GLB 使用 sparse 顶点色，请先重导出"}
                val v=views.getJSONObject(a.getInt("bufferView"));require(v.optInt("buffer",0)==0)
                val count=a.getInt("count");require(count>=0 && total<=4_000_000-count){"顶点色超过 400 万条，请精简后导出"};total+=count
                val channels=when(a.getString("type")){"VEC3"->3;"VEC4"->4;else->error("无效 COLOR_0 类型")}
                val type=a.getInt("componentType");val size=when(type){5121->1;5123->2;5126->4;else->error("不支持的颜色分量类型")}
                require(type==5126 || a.optBoolean("normalized")){"整数顶点色没有 normalized 标记"}
                val stride=v.optInt("byteStride",size*channels);require(stride in size*channels..252 && stride%size==0)
                val offset=a.optLong("byteOffset",0);val viewOffset=v.optLong("byteOffset",0);val viewLength=v.getLong("byteLength")
                require(offset>=0 && viewOffset>=0 && viewLength>=0 && viewOffset+viewLength<=binLength && (if(count==0)offset else offset+(count-1L)*stride+channels*size)<=viewLength){"颜色缓冲区越界"}
                val newOffset=alignedBinLength+append.size()
                val rowsPerBlock=maxOf(1,65536/stride)
                var row=0
                while(row<count) {
                    val rows=minOf(rowsPerBlock,count-row)
                    val bytes=ByteArray((rows-1)*stride+channels*size)
                    source.seek(binStart+viewOffset+offset+row.toLong()*stride);source.readFully(bytes)
                    val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    val out=ByteBuffer.allocate(rows*16).order(ByteOrder.LITTLE_ENDIAN)
                    for(i in 0 until rows) {
                        b.position(i*stride)
                        for(c in 0 until 4) {
                            val x=if(c>=channels)1f else when(type){5121->(b.get().toInt() and 255)/255f;5123->(b.short.toInt() and 65535)/65535f;else->b.float}
                            require(x.isFinite()){ "顶点色包含无效数值" }
                            out.putFloat(if(c<3)TiltMesher.srgbToLinear(x.coerceIn(0f,1f)) else x)
                        }
                    }
                    append.write(out.array());row+=rows
                }
                val vi=views.length();views.put(JSONObject().put("buffer",0).put("byteOffset",newOffset).put("byteLength",count*16).put("target",34962))
                val ai=accessors.length();accessors.put(JSONObject().put("bufferView",vi).put("componentType",5126).put("count",count).put("type","VEC4"));replacements[old]=ai
            }
            for(m in 0 until meshes.length()) {
                val p=meshes.getJSONObject(m).getJSONArray("primitives")
                for(i in 0 until p.length())p.getJSONObject(i).getJSONObject("attributes").let{a->if(a.has("COLOR_0"))a.put("COLOR_0",replacements.getValue(a.getInt("COLOR_0")))}
            }
            j.getJSONArray("buffers").getJSONObject(0).put("byteLength",alignedBinLength+append.size())
            j.getJSONObject("asset").put("generator","OpenBrushNativeViewer color-normalized (source: ${j.getJSONObject("asset").optString("generator")})")
            GlbContainer.write(input,output,j,GlbContainer.Bin(binStart,binLength),append.toByteArray())
        }
        return colors.size
    }
}
