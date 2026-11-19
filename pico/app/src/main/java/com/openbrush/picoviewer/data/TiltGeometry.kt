// OpenBrush Spatial Kotlin adaptation of Open Brush / Tilt Brush behavior. See pico/NOTICE.
/* Frame/pressure behavior based on Open Brush BaseBrushScript/MathUtils, Apache-2.0. */
package com.openbrush.picoviewer.data

import com.openbrush.picoviewer.domain.model.BrushStroke
import kotlin.math.*

/** Frames are reconstructed in the original Unity coordinate system, then reflected once for glTF. */
object TiltGeometry {
    data class Frame(val right:FloatArray,val normal:FloatArray,val tangent:FloatArray)
    fun add(a:FloatArray,b:FloatArray)=FloatArray(3){a[it]+b[it]}
    fun sub(a:FloatArray,b:FloatArray)=FloatArray(3){a[it]-b[it]}
    fun scale(a:FloatArray,s:Float)=FloatArray(3){a[it]*s}
    fun dot(a:FloatArray,b:FloatArray)=a.indices.sumOf{(a[it]*b[it]).toDouble()}.toFloat()
    fun cross(a:FloatArray,b:FloatArray)=floatArrayOf(a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0])
    fun length(a:FloatArray)=sqrt(dot(a,a))
    fun unit(a:FloatArray,fallback:FloatArray=floatArrayOf(1f,0f,0f)):FloatArray {
        val n=length(a);return if(n>1e-8f)scale(a,1f/n) else fallback.copyOf()
    }
    fun reflect(a:FloatArray)=floatArrayOf(a[0],a[1],-a[2])
    fun position(a:FloatArray)=scale(reflect(a),.1f)
    fun rotate(q:FloatArray,v:FloatArray):FloatArray {
        val len=sqrt(q.sumOf{it.toDouble()*it})
        if(len<1e-8)return v.copyOf()
        val axis=FloatArray(3){(q[it]/len).toFloat()};val w=(q[3]/len).toFloat()
        val twice=scale(cross(axis,v),2f)
        return add(v,add(scale(twice,w),cross(axis,twice)))
    }
    private fun perpendicular(t:FloatArray):FloatArray {
        val axis=when {abs(t[0])<=abs(t[1]) && abs(t[0])<=abs(t[2])->floatArrayOf(1f,0f,0f)
            abs(t[1])<=abs(t[2])->floatArrayOf(0f,1f,0f);else->floatArrayOf(0f,0f,1f)}
        return unit(cross(axis,t))
    }
    private fun along(desired:FloatArray,v:FloatArray)=if(dot(desired,v)>=0f)v else scale(v,-1f)
    fun ribbonFrame(tangent:FloatArray,q:FloatArray,previousRight:FloatArray?=null,singleSided:Boolean=false):Frame {
        val t=unit(tangent,rotate(q,floatArrayOf(0f,0f,1f)))
        val forward=rotate(q,floatArrayOf(0f,0f,1f));val up=rotate(q,floatArrayOf(0f,1f,0f))
        val preferred=if(singleSided)cross(forward,t) else previousRight ?: FloatArray(3)
        val r1=along(preferred,cross(forward,t))
        val r2=scale(along(preferred,cross(up,t)),abs(dot(forward,t)))
        val candidate=add(r1,r2)
        val backup=previousRight?.let{sub(it,scale(t,dot(it,t)))}?.takeIf{length(it)>1e-7f} ?: perpendicular(t)
        val right=unit(candidate,unit(backup))
        return Frame(right,unit(cross(t,right)),t)
    }
    fun tubeFrame(tangent:FloatArray,q:FloatArray,previous:Frame?=null):Frame {
        val t=unit(tangent,rotate(q,floatArrayOf(0f,0f,1f)))
        var right:FloatArray
        if(previous==null) {
            var up=rotate(q,floatArrayOf(0f,1f,0f))
            // Match the official minimal-rotation bootstrap, guarding its collinear case.
            if(dot(up,t)<.01f)up=rotate(q,floatArrayOf(1f,0f,0f))
            right=unit(cross(up,t),perpendicular(t))
        } else {
            val v=cross(previous.tangent,t);val cosine=dot(previous.tangent,t).coerceIn(-1f,1f)
            right=if(cosine<-.99999f)previous.right.copyOf() else add(previous.right,
                add(cross(v,previous.right),scale(cross(v,cross(v,previous.right)),1f/(1f+cosine))))
            right=unit(sub(right,scale(t,dot(right,t))),perpendicular(t))
        }
        return Frame(right,unit(cross(t,right)),t)
    }
    fun tangents(stroke:BrushStroke):List<FloatArray> {
        val points=stroke.points;val before=IntArray(points.size){-1};val after=IntArray(points.size){points.size}
        for(i in 1 until points.size)before[i]=if(length(sub(points[i].position,points[i-1].position))>1e-7f)i-1 else before[i-1]
        for(i in points.lastIndex-1 downTo 0)after[i]=if(length(sub(points[i+1].position,points[i].position))>1e-7f)i+1 else after[i+1]
        return points.indices.map { i ->
            val direction=when {before[i]>=0 && after[i]<points.size->sub(points[after[i]].position,points[before[i]].position)
                after[i]<points.size->sub(points[after[i]].position,points[i].position)
                before[i]>=0->sub(points[i].position,points[before[i]].position)
                else->rotate(points[i].rotation,floatArrayOf(0f,0f,1f))}
            unit(direction,rotate(points[i].rotation,floatArrayOf(0f,0f,1f)))
        }
    }
    fun pressures(stroke:BrushStroke,profile:BrushGeometry):FloatArray {
        val points=stroke.points;val result=FloatArray(points.size)
        if(points.isEmpty())return result
        result[0]=if(profile.zeroInitialPressure)0f else points[0].pressure
        val window=profile.pressureWindowMeters*stroke.scale
        for(i in 1 until points.size) {
            val distance=length(sub(points[i].position,points[i-1].position))*.1f
            val k=if(window>1e-8f) .1f.pow(distance/window) else 0f
            result[i]=k*result[i-1]+(1f-k)*points[i].pressure
        }
        return result
    }
    fun widthMeters(stroke:BrushStroke,pressure:Float,profile:BrushGeometry):Float =
        stroke.size*stroke.scale*.1f*(profile.pressureMin+(1f-profile.pressureMin)*pressure.coerceIn(0f,1f))
}
