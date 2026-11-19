package com.openbrush.picoviewer.domain.drawing

import kotlin.math.*

data class StrokeMeshData(val positions: List<V3>,val normals: List<V3>,val triangles: List<Int>,
    val uv: List<Pair<Float, Float>>? = null)

/** Each visible interval is capped separately: erased gaps never reconnect. */
object StrokeTubeMesh {
    fun build(stroke: PaintStroke,sides: Int = 8): StrokeMeshData {
        require(sides>=3)
        if(stroke.style.brush=="spatial_flat" || stroke.style.brush=="spatial_fan")return bands(stroke)
        val vertices=mutableListOf<V3>();val normals=mutableListOf<V3>();val indices=mutableListOf<Int>()
        for(points in stroke.fragments()) {
            val offset=vertices.size
            var normal=V3(0f,1f,0f)
            val tangents=points.indices.map { i ->
                (points[min(i+1,points.lastIndex)].position-points[max(0,i-1)].position).unit()
            }
            for(i in points.indices) {
                val tangent=tangents[i]
                val projected=normal-tangent*normal.dot(tangent)
                normal=if(projected.length()>.01f) projected.unit() else tangent.cross(V3(0f,0f,1f)).unit()
                val binormal=tangent.cross(normal).unit()
                for(side in 0 until sides) {
                    val angle=2f*PI.toFloat()*side/sides
                    val radial=normal*cos(angle)+binormal*sin(angle)
                    vertices.add(points[i].position+radial*stroke.style.radius(points[i].pressure));normals.add(radial)
                }
                if(i>0) for(side in 0 until sides) {
                    val a=offset+(i-1)*sides+side;val b=offset+(i-1)*sides+(side+1)%sides
                    val c=offset+i*sides+side;val d=offset+i*sides+(side+1)%sides
                    indices.addAll(listOf(a,b,c,b,d,c))
                }
            }
            // Duplicate cap vertices so cut faces have axial normals rather than side normals.
            for(end in listOf(false,true)) {
                val i=if(end) points.lastIndex else 0;val n=tangents[i]*(if(end) 1f else -1f)
                val center=vertices.size;vertices.add(points[i].position);normals.add(n)
                val ring=vertices.size
                for(side in 0 until sides) { vertices.add(vertices[offset+i*sides+side]);normals.add(n) }
                for(side in 0 until sides) {
                    val a=ring+side;val b=ring+(side+1)%sides
                    indices.addAll(if(end) listOf(center,a,b) else listOf(center,b,a))
                }
            }
        }
        return StrokeMeshData(vertices,normals,indices)
    }
    private fun bands(stroke:PaintStroke):StrokeMeshData {
        val vertices=mutableListOf<V3>();val normals=mutableListOf<V3>();val indices=mutableListOf<Int>()
        val strands=if(stroke.style.brush=="spatial_fan")7 else 1
        for(points in stroke.fragments()) for(strand in 0 until strands) {
            val offset=vertices.size
            var previous=V3.ZERO
            val centers=mutableListOf<V3>();val tangents=mutableListOf<V3>()
            for(i in points.indices) {
                val tangent=(points[min(i+1,points.lastIndex)].position-points[max(0,i-1)].position).unit()
                var right=tangent.cross(points[i].normal)
                if(right.length()<.01f)right=previous-tangent*previous.dot(tangent)
                if(right.length()<.01f)right=tangent.cross(if(abs(tangent.y)<.9f)V3(0f,1f,0f) else V3(1f,0f,0f))
                right=right.unit()
                if(previous.length()>.5f && right.dot(previous)<0f)right=right*-1f
                previous=right
                val normal=right.cross(tangent).unit()
                val radius=stroke.style.radius(points[i].pressure)
                val lateral=if(strands==1)0f else (strand/(strands-1f)*2f-1f)*radius*.86f
                val center=points[i].position+right*lateral
                centers.add(center);tangents.add(tangent)
                val width=radius*(if(strands==1)1f else .075f)
                val depth=radius*(if(strands==1).08f else .035f)
                for((x,y) in listOf(1f to 1f,-1f to 1f,-1f to -1f,1f to -1f)) {
                    vertices.add(center+right*(width*x)+normal*(depth*y))
                    normals.add((right*x+normal*y).unit())
                }
                if(i>0) for(side in 0..3) {
                    val a=offset+(i-1)*4+side;val b=offset+(i-1)*4+(side+1)%4
                    val c=offset+i*4+side;val d=offset+i*4+(side+1)%4
                    indices.addAll(listOf(a,b,c,b,d,c))
                }
            }
            for(end in listOf(false,true)) {
                val i=if(end)points.lastIndex else 0;val n=tangents[i]*(if(end)1f else -1f)
                val center=vertices.size;vertices.add(centers[i]);normals.add(n)
                val ring=vertices.size
                repeat(4) { side -> vertices.add(vertices[offset+i*4+side]);normals.add(n) }
                repeat(4) { side ->
                    val a=ring+side;val b=ring+(side+1)%4
                    indices.addAll(if(end)listOf(center,a,b) else listOf(center,b,a))
                }
            }
        }
        val outward=indices.chunked(3).flatMap { listOf(it[0],it[2],it[1]) }
        return StrokeMeshData(vertices,normals,outward)
    }
}
