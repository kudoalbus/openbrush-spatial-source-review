package com.openbrush.picoviewer.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.openbrush.picoviewer.R
import com.openbrush.picoviewer.data.*
import com.openbrush.picoviewer.domain.model.Artwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID

class ArtworkRepository(private val context: Context) {
    companion object {
        internal val modelExtensions=setOf("glb","usdz","tilt")+GaussianImport.extensions
    }
    private val folder = File(context.cacheDir,"artworks").apply { mkdirs() }
    private val styles by lazy {
        JSONObject(context.assets.open("brushes/registry.json").bufferedReader().use { it.readText() })
    }
    private val textureCatalog by lazy {
        BrushTextureCatalog(JSONObject(context.assets.open("brush-textures/catalog.json").bufferedReader().use{it.readText()}))
    }
    private val renderCatalog by lazy {
        BrushRenderCatalog(JSONObject(context.assets.open("brush-rendering.json").bufferedReader().use{it.readText()}))
    }
    private val geometryCatalog by lazy {
        BrushGeometryCatalog(JSONObject(context.assets.open("brush-geometry.json").bufferedReader().use{it.readText()}))
    }
    private val studioBrushes by lazy {
        fun readAsset(path: String) = context.assets.open(path).bufferedReader().use { it.readText() }
        StudioBrushCatalog.load(BrushBookCatalogCodec.decode(readAsset("brush-library/catalog.json")), ::readAsset)
    }
    private val lightingPresets by lazy {
        JSONObject(context.assets.open("lighting-presets.json").bufferedReader().use{it.readText()})
    }
    private fun style(guid:String):BrushStyle? {
        val key=guid.lowercase();val j=styles.optJSONObject(key)
        val geometry=geometryCatalog.get(key);val rendering=renderCatalog.forBrush(key)
        val studio = studioBrushes[key]
        val surface = studio?.takeIf { it.isArcSurface }
        if (surface != null) return BrushStyle(surface.name, cutoff=surface.material.cutoff.toFloat(),
            selfLit=surface.material.selfLit, studioProfile=surface)
        val main=textureCatalog.forBrush(key)
        if(j==null && geometry==null && rendering==null && main==null)return null
        val tex=main?.let{context.assets.open(it.asset).use{it.readBytes()}} ?: j?.optString("texture")?.takeIf{it.isNotEmpty()}?.let {
            context.assets.open("brushes/$it").use{stream->stream.readBytes()}
        }
        return BrushStyle(geometry?.name ?: rendering?.name ?: j?.getString("name") ?: main!!.brush,tex,
            (rendering?.cutoff ?: j?.optDouble("cutoff",.5) ?: .5).toFloat(),geometry?.kind=="TUBE",
            rendering?.selfLit==true,if(main?.asset?.endsWith(".jpg")==true || main?.asset?.endsWith(".jpeg")==true)"image/jpeg" else "image/png",
            studioProfile=studio?.takeIf { it.section != null })
    }
    private fun copy(stream:InputStream,destination:File) {
        stream.use { input->destination.outputStream().buffered().use { output->
            val buffer=ByteArray(64*1024);var total=0L
            while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=256L*1024*1024){"文件超过 256 MB，建议先精简作品"};output.write(buffer,0,n)}
        } }
    }
    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { c->
            if(c.moveToFirst())c.getString(0) else null
        } ?: uri.lastPathSegment ?: "Artwork"

    internal suspend fun stageUsdReference(uri: Uri): Pair<File,String> = withContext(Dispatchers.IO) {
        val name=displayName(uri)
        require(name.endsWith(".usdz", ignoreCase=true)) { context.getString(R.string.reference_usdz_only) }
        val file=File.createTempFile("studio-reference-", ".usdz", folder)
        try {
            copy(context.contentResolver.openInputStream(uri) ?: error(context.getString(R.string.file_load_failed)),file)
            file to name
        } catch(e:Exception) {
            file.delete()
            throw e
        }
    }

    suspend fun import(request:ImportRequest):Artwork = withContext(Dispatchers.IO) {
        val uri=request.uri
        val name=displayName(uri)
        val ext=name.substringAfterLast('.',"").lowercase()
        require(ext in modelExtensions) { "请选择 GLB、USDZ、TILT、Gaussian PLY、SPLAT 或 SPLT 文件" }
        val file=File(folder,"${UUID.randomUUID()}.$ext")
        try {
            copy(context.contentResolver.openInputStream(uri) ?: error("无法读取文件"),file)
            val companions=request.treeUri?.let{DocumentTree(context.contentResolver,it).scan()}
            val modelDirectory=request.modelPath?.substringBeforeLast('/',"")?.takeIf{it.isNotEmpty()}?.plus('/') ?: ""
            val opener:((String)->InputStream?)?=companions?.let { docs -> { path:String ->
                val full=modelDirectory+path
                val document=docs.firstOrNull{it.path==full} ?: docs.filter{it.path.equals(full,true)}.let {
                    require(it.size<=1){"贴图文件名大小写不明确：$path"};it.firstOrNull()
                }
                document?.let {context.contentResolver.openInputStream(it.uri) ?: error("无法读取贴图：$path")}
            } }
            prepare(file,name,opener)
        } catch(e:Exception){file.delete();throw e}
    }
    suspend fun demo():Artwork = withContext(Dispatchers.IO) {
        val file=File(folder,"${UUID.randomUUID()}.tilt");copy(context.assets.open("sample.tilt"),file);prepare(file,"Open Brush 官方测试笔触.tilt")
    }
    private fun prepare(file:File,name:String,companion:((String)->InputStream?)?=null):Artwork = when(file.extension) {
        in GaussianImport.extensions -> {
            val normalized=File(folder,"${file.nameWithoutExtension}-gaussian.ply")
            val result=GaussianImport.prepare(file,normalized)
            if(result.file!=file)file.delete()
            val format=file.extension.uppercase()
            Artwork(result.file.absolutePath,name,format,"${result.info.count} 个高斯点",gaussian=result.info)
        }
        "tilt" -> {
            val sketch=TiltReader.read(file){guid->lightingPresets.optJSONObject(guid)?.let(ArtworkMetadata::parseLighting)};val output=File(folder,"${file.nameWithoutExtension}.glb")
            val report=TiltMesher.write(sketch,output,::style,geometryCatalog::get)
            val rendered=File(folder,"${file.nameWithoutExtension}-materials.glb")
            val materials=GlbBrushMaterials.convert(output,rendered,renderCatalog)
            Artwork(if(materials.selfLit>0)rendered.absolutePath else output.absolutePath,name,"TILT → GLB","${report.renderedStrokes} 笔 · ${report.vertices} 顶点 · 自发光 ${materials.selfLit} 材质／加法 ${materials.additive}",
                "特殊／未知几何近似 ${report.fallbackStrokes} 笔；凸包和膜使用画室网格；喷溅、粒子、动态和音频仍有近似。" + (if(sketch.hasExternalModels) " 原文件包含外部模型，尚未载入这些引用。" else ""),
                sketch.lighting,triangleCount=report.triangleCount)
        }
        "glb" -> {
            val resources=File(folder,"${file.nameWithoutExtension}-textures.glb")
            val report=GlbResources.embed(file,resources,textureCatalog,{context.assets.open(it)},companion)
            val source=if(report.total>0)resources else file
            val info=GlbInspector.inspect(source)
            require(!info.externalResources) { "GLB 仍有未解析的外部资源，请检查模型文件夹" }
            val normalized=File(folder,"${file.nameWithoutExtension}-linear.glb")
            val converted=LegacyVertexColors.convert(source,normalized)
            val colored=if(converted>0)normalized else source
            val rendered=File(folder,"${file.nameWithoutExtension}-materials.glb")
            val materials=GlbBrushMaterials.convert(colored,rendered,renderCatalog)
            Artwork(if(materials.selfLit>0)rendered.absolutePath else colored.absolutePath,name,"GLB",
                "${info.colorPrimitives}/${info.primitives} 网格含 COLOR_0；自发光 ${materials.selfLit}／加法 ${materials.additive}",
                "内置贴图 ${report.bundled} 张，文件夹贴图 ${report.local} 张。${if(converted>0)"已修正 sRGB 顶点色。" else ""}Self-lit 笔刷使用无光照／源混合模式；动态和 Bloom 光晕尚未还原。",
                triangleCount=info.triangleCount)
        }
        else -> Artwork(file.absolutePath,name,"USDZ","原生 USD 模型加载 · 保留文件中的材质与颜色","保留 USDZ 自带材质；displayColor / displayOpacity 的呈现由原生导入器处理。")
    }
}
