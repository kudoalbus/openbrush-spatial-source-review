package com.openbrush.picoviewer.domain.drawing

import kotlin.math.sqrt

enum class PaletteKind { COLOR, BRUSH, ACTION }
data class PaletteTarget(val id:Int,val kind:PaletteKind,val name:String,val position:V3,val radius:Float,
    val color:InkColor?=null,val brush:String?=null) {
    val depth:Float get()=if(kind==PaletteKind.ACTION).0025f else .003f
}
data class PaletteRayHit(val target:PaletteTarget,val distance:Float)

/** All coordinates come from the exported GLB. Positive Y is the painted front face. */
class PaletteLayout(val grip:V3,val targets:List<PaletteTarget>) {
    init {
        require(grip.finite() && targets.isNotEmpty() && targets.map { it.id }.distinct().size==targets.size)
        require(targets.all { it.position.finite() && it.radius in .005f.. .04f })
    }
    fun touch(point:V3,brushesOnly:Boolean=false):PaletteTarget? {
        if(!point.finite())return null
        return targets.asSequence().filter { (!brushesOnly || it.kind==PaletteKind.BRUSH) &&
            (it.kind==PaletteKind.BRUSH || point.y>=.001f) }
            .map { target ->
                val d=point-target.position;val radius=target.radius+.006f;val depth=target.depth+.006f
                target to sqrt((d.x*d.x+d.z*d.z)/(radius*radius)+d.y*d.y/(depth*depth))
            }
            .filter { it.second<=1f }.minByOrNull { it.second }?.first
    }
    fun guards(point:V3):Boolean {
        if(!point.finite())return false
        val plate=point.x*point.x/(.162f*.162f)+point.z*point.z/(.151f*.151f)<=1.15f && point.y in -.04f.. .07f
        val brushes=point.x in -.205f..-.025f && point.z in -.055f.. .145f && point.y in -.045f.. .075f
        return plate || brushes || targets.any { (point-it.position).length()<it.radius+.03f }
    }
    fun ray(origin:V3,direction:V3,maxDistance:Float=2f):PaletteRayHit? {
        if(!origin.finite() || !direction.finite() || direction.length()<1e-7f)return null
        val ray=direction.unit()
        return targets.asSequence().filter { it.kind==PaletteKind.BRUSH || origin.y>=.001f }.mapNotNull { target ->
            val delta=origin-target.position
            val offset=V3(delta.x/target.radius,delta.y/target.depth,delta.z/target.radius)
            val scaled=V3(ray.x/target.radius,ray.y/target.depth,ray.z/target.radius)
            val a=scaled.dot(scaled);val b=offset.dot(scaled)
            val discriminant=b*b-a*(offset.dot(offset)-1f)
            if(discriminant<0f)null else {
                val near=(-b-sqrt(discriminant))/a;val far=(-b+sqrt(discriminant))/a
                val distance=if(near>=0f)near else far
                if(distance in 0f..maxDistance)PaletteRayHit(target,distance) else null
            }
        }.minByOrNull { it.distance }
    }
    fun guardsRay(origin:V3,direction:V3):Boolean {
        if(ray(origin,direction)!=null)return true
        if(!origin.finite() || !direction.finite() || kotlin.math.abs(direction.y)<1e-7f)return false
        val unit=direction.unit();val distance=-origin.y/unit.y
        return distance in 0f..2f && guards(origin+unit*distance)
    }
}
