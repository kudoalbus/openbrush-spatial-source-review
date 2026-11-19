// Source-review edition: exercises cases that run without a headset or the bundled artwork.
package com.openbrush.picoviewer

import com.openbrush.picoviewer.data.*
import com.openbrush.picoviewer.domain.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class TiltGeometryTest {
    private val identity=floatArrayOf(0f,0f,0f,1f)
    private fun point(x:Float,y:Float=0f,z:Float=0f,pressure:Float=1f,q:FloatArray=identity)=StrokePoint(floatArrayOf(x,y,z),q,pressure)
    private fun stroke(points:List<StrokePoint>,size:Float=2f,scale:Float=1f)=BrushStroke(0,floatArrayOf(.8f,.4f,.2f,1f),size,scale,points)
    private fun mesh(s:BrushStroke,profile:BrushGeometry):File {
        val file=File.createTempFile("tilt-geometry", ".glb").apply{deleteOnExit()}
        TiltMesher.write(TiltSketch(listOf(s),listOf("test"),false),file,{BrushStyle("Test")},{profile})
        return file
    }
    private fun vectors(file:File,attribute:String):List<FloatArray> {
        val j=GlbInspector.json(file);val p=j.getJSONArray("meshes").getJSONObject(0).getJSONArray("primitives").getJSONObject(0)
        val a=j.getJSONArray("accessors").getJSONObject(p.getJSONObject("attributes").getInt(attribute))
        val v=j.getJSONArray("bufferViews").getJSONObject(a.getInt("bufferView"));val data=file.readBytes()
        val bin=GlbContainer.bin(file);val offset=bin.start.toInt()+v.optInt("byteOffset")+a.optInt("byteOffset")
        return (0 until a.getInt("count")).map{i->
            val b=ByteBuffer.wrap(data,offset+i*v.optInt("byteStride",12),12).order(ByteOrder.LITTLE_ENDIAN)
            floatArrayOf(b.float,b.float,b.float)
        }
    }
    private fun assertUnitFrame(frame:TiltGeometry.Frame) {
        for(v in listOf(frame.right,frame.normal,frame.tangent)){
            assertTrue(v.all{it.isFinite()});assertEquals(1f,TiltGeometry.length(v),1e-5f)
        }
        assertEquals(0f,TiltGeometry.dot(frame.right,frame.tangent),1e-5f)
        assertEquals(0f,TiltGeometry.dot(frame.normal,frame.tangent),1e-5f)
        assertEquals(0f,TiltGeometry.dot(frame.normal,frame.right),1e-5f)
    }
    @Test fun movingAlongControllerRightBuildsPerpendicularRibbonInsteadOfCollapsingWidth() {
        val s=stroke(listOf(point(0f,pressure=.5f),point(10f,pressure=.5f)),scale=3f)
        val p=BrushGeometry(pressureMin=.2f)
        val positions=vectors(mesh(s,p),"POSITION")
        val width=TiltGeometry.sub(positions[1],positions[0])
        assertEquals(0f,width[0],1e-6f);assertEquals(0f,width[2],1e-6f)
        assertEquals(.36f,TiltGeometry.length(width),1e-5f)
        assertEquals(1f,(positions[2][0]+positions[3][0])*.5f,1e-6f)
    }
    @Test fun reconstructsFrameInSourceSpaceAndReflectsOrientationOnlyOnce() {
        val q=floatArrayOf(0f,sqrt(.5f),0f,sqrt(.5f))
        val s=stroke(listOf(point(0f,q=q),point(0f,10f,q=q)))
        val file=mesh(s,BrushGeometry())
        val p=vectors(file,"POSITION");val width=TiltGeometry.sub(p[1],p[0])
        assertEquals(0f,width[0],1e-5f);assertEquals(0f,width[1],1e-5f);assertEquals(-.2f,width[2],1e-5f)
        val normal=vectors(file,"NORMAL")[0]
        assertEquals(1f,normal[0],1e-5f)
    }
    @Test fun usesPerBrushPressureFloorAndAppliesStrokeScaleExactlyOnce() {
        val assets=File("src/main/assets")
        val profiles=BrushGeometryCatalog(JSONObject(File(assets,"brush-geometry.json").readText()))
        val s=stroke(listOf(point(0f)),size=2f,scale=3f)
        val coarse=profiles.get("79168f10-6961-464a-8be1-57ed364c5600")!!
        val oil=profiles.get("c515dad7-4393-4681-81ad-162ef052241b")!!
        assertEquals(.5f,coarse.pressureMin,0f);assertEquals(.2f,oil.pressureMin,0f)
        assertEquals(.3f,TiltGeometry.widthMeters(s,0f,coarse),1e-6f)
        assertEquals(.12f,TiltGeometry.widthMeters(s,0f,oil),1e-6f)
        assertEquals(.6f,TiltGeometry.widthMeters(s,1f,oil),1e-6f)
        val constant=BrushGeometry(pressureMin=1f)
        assertEquals(TiltGeometry.widthMeters(s,0f,constant),TiltGeometry.widthMeters(s,1f,constant),0f)
    }
    @Test fun pressureSmoothingUsesDistanceAndPointerScaleInsteadOfControlPointCount() {
        val s=stroke(listOf(point(0f,pressure=0f),point(.1f,pressure=1f)),scale=2f)
        val p=BrushGeometry(pressureWindowMeters=.2f)
        val smoothed=TiltGeometry.pressures(s,p)
        assertEquals(1f-.1f.pow(.01f/.4f),smoothed[1],1e-6f)
        val withDuplicate=s.copy(points=listOf(s.points[0],s.points[0],s.points[1]))
        assertEquals(smoothed[1],TiltGeometry.pressures(withDuplicate,p)[2],1e-6f)
    }
    @Test fun degenerateAndRolledFramesStayFiniteOrthogonalAndContinuous() {
        val s=stroke(listOf(point(0f),point(0f),point(0f,0f,10f),point(0f,0f,10f)))
        var previous:TiltGeometry.Frame?=null
        for((i,t) in TiltGeometry.tangents(s).withIndex()) {
            val q=if(i%2==0)identity else identity.map{-it}.toFloatArray()
            val frame=TiltGeometry.ribbonFrame(t,q,previous?.right)
            assertUnitFrame(frame);previous?.let{assertTrue(TiltGeometry.dot(it.right,frame.right)>.999f)}
            previous=frame
        }
        assertUnitFrame(TiltGeometry.ribbonFrame(floatArrayOf(0f,0f,1f),FloatArray(4)))
    }
    @Test fun tubeFrameTransportsWithoutFollowingControllerRoll() {
        val first=TiltGeometry.tubeFrame(floatArrayOf(0f,0f,1f),identity)
        val rolled=floatArrayOf(0f,0f,sqrt(.5f),sqrt(.5f))
        val second=TiltGeometry.tubeFrame(floatArrayOf(0f,0f,1f),rolled,first)
        assertUnitFrame(second);assertEquals(1f,TiltGeometry.dot(first.right,second.right),1e-5f)
        assertUnitFrame(TiltGeometry.tubeFrame(floatArrayOf(0f,0f,-1f),identity,second))
    }
    @Test fun singlePointStampUsesControllerPlaneInsteadOfFixedWorldXY() {
        val q=floatArrayOf(sqrt(.5f),0f,0f,sqrt(.5f))
        val p=vectors(mesh(stroke(listOf(point(0f,q=q))),BrushGeometry()),"POSITION")
        assertTrue(p.all{abs(it[1])<1e-6f})
        assertEquals(.2f,p.maxOf{it[2]}-p.minOf{it[2]},1e-5f)
    }
    @Test fun tubePreservesRadiusAndVertexNormalsAndRebuildsDeterministically() {
        val s=stroke(listOf(point(0f),point(0f,10f)))
        val profile=BrushGeometry(kind="TUBE",sides=4,radiusMultiplier=.25f,pressureMin=1f)
        val file=mesh(s,profile);val positions=vectors(file,"POSITION");val normals=vectors(file,"NORMAL")
        assertEquals(12,positions.size)
        for(i in 0 until 10){
            assertEquals(.025f,sqrt(positions[i][0].pow(2)+positions[i][2].pow(2)),1e-5f)
            assertEquals(1f,TiltGeometry.length(normals[i]),1e-5f)
            assertTrue(positions[i][0]*normals[i][0]+positions[i][2]*normals[i][2]>0f)
        }
        assertArrayEquals(file.readBytes(),mesh(s,profile).readBytes())
    }
}
