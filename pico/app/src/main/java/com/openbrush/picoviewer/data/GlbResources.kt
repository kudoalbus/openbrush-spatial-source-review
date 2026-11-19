package com.openbrush.picoviewer.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

data class ResourceReport(val bundled:Int=0,val local:Int=0) { val total:Int get()=bundled+local }

/** Local companion images override the packaged brush fallback, including modified normals. */
object GlbResources {
    fun embed(input:File,output:File,catalog:BrushTextureCatalog,
              openAsset:(String)->InputStream,openCompanion:((String)->InputStream?)?=null):ResourceReport {
        val j=GlbInspector.json(input)
        val buffers=j.optJSONArray("buffers") ?: error("GLB 缺少几何缓冲区")
        require(buffers.length()==1 && buffers.getJSONObject(0).optString("uri").isEmpty()){"目前仅支持几何内嵌的 GLB；请将外部 BIN 合并后重导出"}
        val images=j.optJSONArray("images") ?: return ResourceReport()
        val external=(0 until images.length()).filter { i ->
            val uri=images.getJSONObject(i).optString("uri")
            uri.isNotEmpty() && !uri.startsWith("data:")
        }
        if(external.isEmpty())return ResourceReport()
        require(external.size<=256){"外部贴图超过 256 张，请精简模型"}
        val bin=GlbContainer.bin(input)
        val views=j.optJSONArray("bufferViews") ?: JSONArray().also{j.put("bufferViews",it)}
        val append=ByteArrayOutputStream();var bundled=0;var local=0
        val embedded=mutableMapOf<String,Pair<Int,String>>()
        for(i in external) {
            val image=images.getJSONObject(i);val uri=image.getString("uri")
            val path=relativeResourcePath(uri)
            val result=embedded[path] ?: run {
                val companion=openCompanion?.invoke(path)
                val entry=if(companion==null)catalog.match(uri) else null
                val stream=companion ?: entry?.let{openAsset(it.asset)} ?: error(
                    "找不到贴图：$path。请把模型与额外贴图放在同一文件夹，保留子目录，再用「导入文件夹」选择该文件夹。")
                val data=stream.use { s ->
                    val bytes=ByteArrayOutputStream();val chunk=ByteArray(65536)
                    while(true){val n=s.read(chunk);if(n<0)break
                        require(bytes.size().toLong()+n<=32L*1024*1024 && append.size().toLong()+bytes.size()+n<=96L*1024*1024){"贴图过大：单张上限 32 MB，总计上限 96 MB"}
                        bytes.write(chunk,0,n)
                    };bytes.toByteArray()
                }
                val mime=imageMime(data)
                val index=views.length()
                views.put(JSONObject().put("buffer",0).put("byteOffset",bin.alignedLength+append.size()).put("byteLength",data.size))
                append.write(data);while(append.size()%4!=0)append.write(0)
                if(companion!=null)local++ else bundled++
                (index to mime).also{embedded[path]=it}
            }
            image.remove("uri");image.put("bufferView",result.first);image.put("mimeType",result.second)
        }
        buffers.getJSONObject(0).put("byteLength",bin.alignedLength+append.size())
        GlbContainer.write(input,output,j,bin,append.toByteArray())
        return ResourceReport(bundled,local)
    }
    private fun imageMime(bytes:ByteArray):String = when {
        bytes.size>=8 && bytes.copyOfRange(0,8).contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))->"image/png"
        bytes.size>=3 && bytes[0]==255.toByte() && bytes[1]==216.toByte() && bytes[2]==255.toByte()->"image/jpeg"
        else->error("外部贴图需要 PNG 或 JPEG 格式，请转换后再导入")
    }
}
