package com.openbrush.picoviewer.ui.studio

import androidx.compose.ui.res.stringResource
import com.openbrush.picoviewer.R

import android.graphics.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import com.openbrush.picoviewer.domain.drawing.*
import kotlin.math.*

/** The transparent native attachment shares the analytical hand/controller coordinates. */
@Composable
fun PaletteColorSurface(ui:PaletteColorUiState,onPointer:(Int,Float,Float)->Unit) {
    val latest by rememberUpdatedState(onPointer)
    val paint=remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val filter=remember { Paint() }
    val labels=listOf(stringResource(R.string.undo),stringResource(R.string.redo),stringResource(R.string.save),
        stringResource(R.string.brush),stringResource(R.string.switch_colors))
    Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
        awaitEachGesture {
            val down=awaitFirstDown(requireUnconsumed=false)
            var last=down.position;down.consume()
            latest(0,last.x/size.width,last.y/size.height)
            try {
                while(true) {
                    val change=awaitPointerEvent().changes.firstOrNull { it.id==down.id } ?: break
                    last=change.position
                    if(!change.pressed) { change.consume();break }
                    latest(1,last.x/size.width,last.y/size.height);change.consume()
                }
            } finally { latest(2,last.x/size.width,last.y/size.height) }
        }
    }) {
        val ppm=size.width/PalettePickerLayout.PANEL_WIDTH
        fun x(m:Float)=((m-PalettePickerLayout.PANEL_CENTER_X)/PalettePickerLayout.PANEL_WIDTH+.5f)*size.width
        fun y(z:Float)=(z/PalettePickerLayout.PANEL_HEIGHT+.5f)*size.height
        fun argb(c:InkColor)=Color.argb(255,(c.r*255).roundToInt(),(c.g*255).roundToInt(),(c.b*255).roundToInt())
        drawIntoCanvas { canvas ->
            val c=canvas.nativeCanvas
            fun circle(point:V3,r:Float,color:Int) {
                paint.reset();paint.isAntiAlias=true;paint.style=Paint.Style.FILL;paint.color=color
                c.drawCircle(x(point.x),y(point.z),r*ppm,paint)
            }
            fun wheel(point:V3,r:Float,value:Float) {
                val cx=x(point.x);val cy=y(point.z);val rp=r*ppm
                filter.colorFilter=ColorMatrixColorFilter(ColorMatrix(floatArrayOf(value,0f,0f,0f,0f,0f,value,0f,0f,0f,0f,0f,value,0f,0f,0f,0f,0f,1f,0f)))
                val layer=c.saveLayer(RectF(cx-rp-1,cy-rp-1,cx+rp+1,cy+rp+1),filter)
                paint.reset();paint.isAntiAlias=true
                paint.shader=SweepGradient(cx,cy,intArrayOf(Color.RED,Color.MAGENTA,Color.BLUE,Color.CYAN,Color.GREEN,Color.YELLOW,Color.RED),null)
                c.drawCircle(cx,cy,rp,paint)
                paint.shader=RadialGradient(cx,cy,rp,intArrayOf(Color.WHITE,0x00FFFFFF),null,Shader.TileMode.CLAMP)
                c.drawCircle(cx,cy,rp,paint);c.restoreToCount(layer);paint.shader=null
            }
            val modePoint=if(ui.picker)V3(.096f,.01f,.079f) else V3(.028f,.01f,.045f)
            wheel(modePoint,.0061f,1f)
            if(ui.picker) {
                wheel(PalettePickerLayout.CENTER,PalettePickerLayout.RADIUS,ui.selected.v)
                val center=PalettePickerLayout.CENTER;val cx=x(center.x);val cy=y(center.z)
                val radius=(PalettePickerLayout.ARC_IN+PalettePickerLayout.ARC_OUT)*.5f*ppm
                val hue=ui.selected.copy(v=1f).color();val half=ui.selected.copy(v=.5f).color()
                paint.reset();paint.isAntiAlias=true;paint.style=Paint.Style.STROKE
                paint.strokeWidth=(PalettePickerLayout.ARC_OUT-PalettePickerLayout.ARC_IN)*ppm
                paint.shader=SweepGradient(cx,cy,intArrayOf(argb(half),Color.BLACK,argb(hue),argb(half)),floatArrayOf(0f,1f/6f,5f/6f,1f))
                c.drawArc(RectF(cx-radius,cy-radius,cx+radius,cy+radius),-60f,120f,false,paint);paint.shader=null
                val a=(60f-120f*ui.selected.v)*PI.toFloat()/180f
                val r1=PalettePickerLayout.ARC_IN*ppm;val r2=PalettePickerLayout.ARC_OUT*ppm
                paint.color=Color.BLACK;paint.strokeWidth=.0032f*ppm
                c.drawLine(cx+cos(a)*r1,cy+sin(a)*r1,cx+cos(a)*r2,cy+sin(a)*r2,paint)
                paint.color=Color.WHITE;paint.strokeWidth=.0017f*ppm
                c.drawLine(cx+cos(a)*r1,cy+sin(a)*r1,cx+cos(a)*r2,cy+sin(a)*r2,paint)
                val h=ui.selected.h*2*PI.toFloat();val sr=ui.selected.s*PalettePickerLayout.RADIUS
                val selected=V3(center.x+cos(h)*sr,.01f,center.z-sin(h)*sr)
                paint.style=Paint.Style.STROKE;paint.color=Color.BLACK;paint.strokeWidth=.0015f*ppm
                c.drawCircle(x(selected.x),y(selected.z),.0034f*ppm,paint)
                paint.color=Color.WHITE;paint.strokeWidth=.0008f*ppm
                c.drawCircle(x(selected.x),y(selected.z),.0029f*ppm,paint)
                circle(PalettePickerLayout.CURRENT,PalettePickerLayout.CURRENT_RADIUS,argb(ui.color))
                repeat(5) { i ->
                    val p=PalettePickerLayout.favoriteCenter(i);val saved=ui.favorites[i]
                    circle(p,.0078f,if(saved==null)0xFFBFC9C9.toInt() else argb(saved.color()))
                    if(saved==null) {
                        paint.color=Color.WHITE;paint.style=Paint.Style.STROKE;paint.strokeWidth=.0012f*ppm
                        val d=.0034f*ppm
                        c.drawLine(x(p.x)-d,y(p.z),x(p.x)+d,y(p.z),paint)
                        c.drawLine(x(p.x),y(p.z)-d,x(p.x),y(p.z)+d,paint)
                    }
                }
                paint.reset();paint.isAntiAlias=true;paint.style=Paint.Style.STROKE;paint.color=0xFFE7BB60.toInt();paint.strokeWidth=.0008f*ppm
                c.drawCircle(x(modePoint.x),y(modePoint.z),.0125f*ppm,paint)
                paint.style=Paint.Style.FILL;paint.color=0xFF39433F.toInt();paint.textSize=.005f*ppm;paint.textAlign=Paint.Align.CENTER
                PalettePickerLayout.actions.forEachIndexed { i,(_,px)->c.drawText(labels[i],x(px),y(.100f),paint) }
            }
        }
    }
}
