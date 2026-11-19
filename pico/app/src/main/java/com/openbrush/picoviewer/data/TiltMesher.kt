package com.openbrush.picoviewer.data

import com.openbrush.picoviewer.domain.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

data class BrushStyle(val name: String, val texture: ByteArray? = null, val cutoff: Float = .5f, val tube: Boolean = false,val selfLit:Boolean=true,val textureMimeType:String="image/png",val studioProfile:StudioBrushProfile?=null)
data class MeshReport(val renderedStrokes: Int, val fallbackStrokes: Int, val vertices: Int,
    val tubeStrokes: Int, val stampStrokes: Int, val triangleCount: Long)

/** Baseline ribbons/tubes, not the complete Open Brush brush geometry engine. */
object TiltMesher {
    private fun array(vararg v: Number) = JSONArray(v.toList())
    private fun obj(vararg v: Pair<String,Any>): JSONObject = JSONObject().apply { v.forEach { put(it.first,it.second) } }
    fun srgbToLinear(x: Float): Float = if(x <= .04045f) x/12.92f else ((x+.055f)/1.055f).pow(2.4f)
    fun write(sketch:TiltSketch,file:File,styleFor:(String)->BrushStyle?):MeshReport=write(sketch,file,styleFor){null}
    fun write(sketch: TiltSketch, file: File, styleFor: (String)->BrushStyle?,geometryFor:(String)->BrushGeometry?): MeshReport {
        val binary = ByteArrayOutputStream(); val views=JSONArray();val accessors=JSONArray();val primitives=JSONArray();val materials=JSONArray();val images=JSONArray();val textures=JSONArray()
        var rendered=0;var fallback=0;var vertexCount=0;var tubes=0;var stamps=0
        var triangleCount=0L
        fun view(bytes:ByteArray,target:Int?=null):Int {
            while(binary.size()%4!=0)binary.write(0)
            val index=views.length();val v=obj("buffer" to 0,"byteOffset" to binary.size(),"byteLength" to bytes.size)
            if(target!=null)v.put("target",target)
            views.put(v);binary.write(bytes);return index
        }
        for ((brushIndex,strokes) in sketch.strokes.filter { it.points.isNotEmpty() }.groupBy { it.brush }) {
            val guid=sketch.brushes[brushIndex];val style=styleFor(guid)
            val known=geometryFor(guid)
            val profile=known ?: BrushGeometry(kind=if(style?.tube==true)"TUBE" else "RIBBON")
            val studio = style?.studioProfile ?: if (guid.equals(BrushRenderCatalog.PASSTHROUGH_HULL, true)) {
                val rule = BrushRenderCatalog.depthHull
                StudioBrushProfile(rule.name, profile.copy(kind="HULL"), rule, null, 1, null, 0, false, null, depthOnly=true)
            } else null
            val surface = studio?.takeIf { it.isArcSurface }
            if((style==null && known==null) || profile.approximate)fallback+=strokes.size
            val verts=ByteArrayOutputStream();val indices=ByteArrayOutputStream();var count=0;var indexCount=0
            val min=FloatArray(3){Float.POSITIVE_INFINITY};val max=FloatArray(3){Float.NEGATIVE_INFINITY}
            fun vertex(p:FloatArray,n:FloatArray,u:Float,v:Float,c:FloatArray) {
                val b=ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
                for(k in 0..2){b.putFloat(p[k]);min[k]=min(min[k],p[k]);max[k]=max(max[k],p[k])}
                n.forEach { b.putFloat(it) };b.putFloat(u);b.putFloat(v);c.forEach { b.putFloat(it) }
                verts.write(b.array());count++
            }
            fun triangle(a:Int,b:Int,c:Int){val data=ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(a).putInt(b).putInt(c);indices.write(data.array());indexCount+=3}
            for(s in strokes) {
                rendered++
                val color=floatArrayOf(srgbToLinear(s.color[0]),srgbToLinear(s.color[1]),srgbToLinear(s.color[2]),s.color[3])
                if (surface != null) color[3] *= surface.opacity
                // The legacy preview draws a card for a tap or stationary controller. Preserve it
                // for visible brushes when a 3D path cannot be constructed.
                val hasPath = studio != null && s.points.any { !it.position.contentEquals(s.points.first().position) }
                if (studio != null && (hasPath || studio.depthOnly)) {
                    val usedBytes = binary.size().toLong() + verts.size() + indices.size()
                    studio.section?.let { section ->
                        // Two face vertices per side, six indices between rings, plus the two caps.
                        val rings = s.points.size.toLong() + 1
                        val bytes = rings * section.size * (2 * 48 + 6 * 4) + (section.size + 2) * 2 * 48 + section.size * 24
                        require(usedBytes + bytes <= 128L * 1024 * 1024) { "生成模型超过内存预算，请改用 GLB 导出" }
                        tubes++
                    }
                    val mesh = TiltStudioMesh.build(s, studio)
                    val offset=count
                    require(usedBytes+mesh.positions.size*48L+mesh.triangles.size*4L<=128L*1024*1024) {
                        "生成模型超过内存预算，请改用 GLB 导出"
                    }
                    for(i in mesh.positions.indices) {
                        val p=mesh.positions[i];val n=mesh.normals[i]
                        val uv = mesh.uv?.get(i)
                        vertex(floatArrayOf(p.x,p.y,p.z),floatArrayOf(n.x,n.y,n.z),uv?.first ?: 0f,uv?.second ?: 0f,color)
                    }
                    for(i in mesh.triangles.indices step 3)triangle(offset+mesh.triangles[i],offset+mesh.triangles[i+1],offset+mesh.triangles[i+2])
                    continue
                }
                val points=s.points;val tube=profile.kind=="TUBE";val sides=if(tube)profile.sides.coerceIn(3,12) else 2
                val ringSize=if(tube)sides+1 else 2;val start=count
                if(tube)tubes++;if(profile.kind=="STAMP")stamps++
                val estimate=points.size.toLong()*(if(profile.kind=="STAMP" || points.size==1)216 else ringSize*48+(if(tube)sides else 1)*24)+4096
                require(binary.size().toLong()+verts.size()+indices.size()+estimate<=128L*1024*1024){"生成模型超过内存预算，请改用 GLB 导出"}
                val positions=points.map{TiltGeometry.position(it.position)};val lengths=FloatArray(points.size)
                for(i in 1 until points.size)lengths[i]=lengths[i-1]+sqrt((0..2).sumOf { k-> ((positions[i][k]-positions[i-1][k]).pow(2)).toDouble() }).toFloat()
                val pressures=TiltGeometry.pressures(s,profile)
                fun pointColor(i:Int)=color.copyOf().also { c ->
                    c[3]*=(profile.opacity*(profile.pressureOpacityMin+(profile.pressureOpacityMax-profile.pressureOpacityMin)*pressures[i])).coerceIn(0f,1f)
                }
                if(points.size==1 || lengths.last()<1e-8f || profile.kind=="STAMP") {
                    for(i in points.indices) {
                        val p=positions[i];val r=TiltGeometry.widthMeters(s,pressures[i],profile)*.5f
                        val right=TiltGeometry.reflect(TiltGeometry.rotate(points[i].rotation,floatArrayOf(1f,0f,0f)))
                        val up=TiltGeometry.reflect(TiltGeometry.rotate(points[i].rotation,floatArrayOf(0f,1f,0f)))
                        val normal=TiltGeometry.reflect(TiltGeometry.rotate(points[i].rotation,floatArrayOf(0f,0f,1f)))
                        val first=count
                        for((x,y) in listOf(-1f to -1f,1f to -1f,1f to 1f,-1f to 1f))
                            vertex(FloatArray(3){k->p[k]+r*(right[k]*x*profile.sizeRatioX+up[k]*y*profile.sizeRatioY)},normal,(x+1)/2,(y+1)/2,pointColor(i))
                        triangle(first,first+2,first+1);triangle(first,first+3,first+2)
                    }
                    continue
                }
                val tangents=TiltGeometry.tangents(s);var previous:TiltGeometry.Frame?=null
                val frames=ArrayList<TiltGeometry.Frame>(points.size)
                for((i,point) in points.withIndex()) {
                    val frame=if(tube)TiltGeometry.tubeFrame(tangents[i],point.rotation,previous)
                        else TiltGeometry.ribbonFrame(tangents[i],point.rotation,previous?.right,profile.singleSided)
                    frames.add(frame);previous=frame
                    val right=TiltGeometry.reflect(frame.right);val normal=TiltGeometry.reflect(frame.normal)
                    val half=TiltGeometry.widthMeters(s,pressures[i],profile)*.5f*(if(tube)profile.radiusMultiplier else 1f)
                    val u=lengths[i]/lengths.last()
                    for(side in 0 until ringSize) {
                        val angle=side*2*PI/sides+profile.crossSectionAngle
                        val a=if(!tube) (if(side==0)-1f else 1f) else cos(angle).toFloat()
                        val b=if(!tube)0f else sin(angle).toFloat()
                        val radial=FloatArray(3){k->right[k]*a+normal[k]*b}
                        val p=FloatArray(3){k->positions[i][k]+half*radial[k]}
                        vertex(p,if(tube)TiltGeometry.unit(radial) else normal,u,if(tube)side.toFloat()/sides else side.toFloat(),pointColor(i))
                    }
                    if(i>0)for(side in 0 until (if(!tube)1 else sides)) {
                        val next=side+1;val a0=start+(i-1)*ringSize+side;val b0=start+(i-1)*ringSize+next;val c0=start+i*ringSize+side;val d0=start+i*ringSize+next
                        if(tube){triangle(a0,c0,b0);triangle(b0,c0,d0)}
                        else{triangle(a0,b0,c0);triangle(b0,d0,c0)}
                    }
                }
                if(tube)for(end in listOf(0,points.lastIndex)) {
                    val center=count;val sign=if(end==0)-1f else 1f
                    vertex(positions[end],TiltGeometry.reflect(TiltGeometry.scale(frames[end].tangent,sign)),if(end==0)0f else 1f,.5f,pointColor(end))
                    val ring=start+end*ringSize
                    for(side in 0 until sides)if(end==0)triangle(center,ring+side,ring+side+1) else triangle(center,ring+side+1,ring+side)
                }
            }
            if(count==0)continue
            require(binary.size().toLong()+verts.size()+indices.size() <= 128*1024*1024) { "生成模型超过内存预算，请改用 GLB 导出" }
            vertexCount+=count
            triangleCount+=indexCount/3L
            val vv=view(verts.toByteArray(),34962);views.getJSONObject(vv).put("byteStride",48)
            fun attr(offset:Int,type:String):Int { val n=accessors.length();val a=obj("bufferView" to vv,"byteOffset" to offset,"componentType" to 5126,"count" to count,"type" to type);if(offset==0){a.put("min",JSONArray(min.toList()));a.put("max",JSONArray(max.toList()))};accessors.put(a);return n }
            val attributes=obj("POSITION" to attr(0,"VEC3"),"NORMAL" to attr(12,"VEC3"),"TEXCOORD_0" to attr(24,"VEC2"),"COLOR_0" to attr(32,"VEC4"))
            val iv=view(indices.toByteArray(),34963);val ia=accessors.length();accessors.put(obj("bufferView" to iv,"componentType" to 5125,"count" to indexCount,"type" to "SCALAR"))
            val pbr=obj("baseColorFactor" to array(1,1,1,1),"metallicFactor" to 0,"roughnessFactor" to 1)
            style?.texture?.let { data -> val texture=textures.length();images.put(obj("bufferView" to view(data),"mimeType" to style.textureMimeType));textures.put(obj("source" to images.length()-1));pbr.put("baseColorTexture",obj("index" to texture)) }
            val surfaceBlend = surface?.material?.blend
            val alphaMode = when {
                surface == null -> "MASK"
                surfaceBlend == "DEPTH" -> "OPAQUE"
                surfaceBlend == "ALPHA" || surface.opacity < 1f || strokes.any { it.color[3] < 1f } -> "BLEND"
                surfaceBlend == "MASK" -> "MASK"
                else -> "OPAQUE"
            }
            val extensions=obj("GOOGLE_tilt_brush_material" to obj("guid" to guid))
            if((surface?.material?.selfLit ?: style?.selfLit)==true)extensions.put("KHR_materials_unlit",JSONObject())
            val mi=materials.length();materials.put(obj("name" to "$guid:${style?.name ?: "Approximate"}","pbrMetallicRoughness" to pbr,"doubleSided" to true,"alphaMode" to alphaMode,"extensions" to extensions).apply {
                if(alphaMode=="MASK")put("alphaCutoff",style?.cutoff ?: .01f)
            })
            primitives.put(obj("attributes" to attributes,"indices" to ia,"material" to mi,"mode" to 4))
        }
        require(primitives.length()>0) { ".tilt 没有可显示笔触" }
        while(binary.size()%4!=0)binary.write(0)
        val document=obj("asset" to obj("version" to "2.0","generator" to "OpenBrushNativeViewer baseline tilt mesher"),"extensionsUsed" to JSONArray(listOf("KHR_materials_unlit")),"scene" to 0,"scenes" to JSONArray(listOf(obj("nodes" to array(0)))),"nodes" to JSONArray(listOf(obj("mesh" to 0))),"meshes" to JSONArray(listOf(obj("primitives" to primitives))),"buffers" to JSONArray(listOf(obj("byteLength" to binary.size()))),"bufferViews" to views,"accessors" to accessors,"materials" to materials)
        val used=JSONArray(listOf("GOOGLE_tilt_brush_material"))
        if((0 until materials.length()).any{materials.getJSONObject(it).getJSONObject("extensions").has("KHR_materials_unlit")})used.put("KHR_materials_unlit")
        document.put("extensionsUsed",used)
        if(images.length()>0){document.put("images",images);document.put("textures",textures)}
        val json=document.toString().toByteArray(Charsets.UTF_8);val padded=(json.size+3)/4*4
        val header=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).putInt(0x46546c67).putInt(2).putInt(12+8+padded+8+binary.size()).putInt(padded).putInt(0x4e4f534a)
        file.outputStream().buffered().use { output ->output.write(header.array());output.write(json);repeat(padded-json.size){output.write(32)};output.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(binary.size()).putInt(0x004e4942).array());binary.writeTo(output) }
        return MeshReport(rendered,fallback,vertexCount,tubes,stamps,triangleCount)
    }
}
