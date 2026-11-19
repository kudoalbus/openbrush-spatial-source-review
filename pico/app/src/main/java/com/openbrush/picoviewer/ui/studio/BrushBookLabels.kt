package com.openbrush.picoviewer.ui.studio

import androidx.compose.ui.res.stringResource
import com.openbrush.picoviewer.R

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import com.openbrush.picoviewer.domain.drawing.*

/** OS-emulated taps use the same layout as raw hand/controller input. No replacement icons. */
@Composable
fun BrushBookSurface(onTap:(Float,Float)->Unit) {
    val latest by rememberUpdatedState(onTap)
    Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
        detectTapGestures { p -> latest(p.x/size.width,p.y/size.height) }
    }) {}
}

@Composable
fun BrushBookHeader(state:BrushBookUiState,onCommand:(Int)->Unit) {
    val latest by rememberUpdatedState(onCommand)
    val brushLabel=stringResource(R.string.brush)
    val tabs=listOf(
        Triple(-.024f,stringResource(R.string.basic_brushes),BrushBookMode.BASIC),
        Triple(.018f,stringResource(R.string.classic_brushes),BrushBookMode.CLASSIC),
        Triple(.060f,stringResource(R.string.experimental_brushes),BrushBookMode.EXTENDED),
    )
    val paint=remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign=Paint.Align.CENTER } }
    Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
        detectTapGestures { p -> BrushBookLayout.headerCommand(p.x/size.width)?.let(latest) }
    }) {
        val w=size.width;val h=size.height
        fun drawLabel(text:String,x:Float,selected:Boolean=false) {
            paint.textSize=h*.46f;paint.color=if(selected)0xFFFFDEA0.toInt() else 0xFFF1EBD9.toInt()
            drawIntoCanvas { it.nativeCanvas.drawText(text,x,h*.68f,paint) }
        }
        paint.color=0xFF40362A.toInt();paint.textSize=h*.62f
        drawIntoCanvas { it.nativeCanvas.drawText(brushLabel,w*.115f,h*.70f,paint) }
        for((x,title,mode) in tabs) {
            val center=(x/.224f+.5f)*w;val width=.036f/.224f*w
            drawRect(Color(0xFF293F42),Offset(center-width*.5f,h*.10f),Size(width,h*.83f))
            drawLabel(title,center,state.mode==mode)
        }
        val close=(.102f/.224f+.5f)*w
        drawRect(Color(0xFF293F42),Offset(close-.010f/.224f*w,h*.10f),Size(.020f/.224f*w,h*.83f))
        drawLabel("×",close)
    }
}

@Composable
fun BrushBookCaption(state:BrushBookUiState) {
    val paint=remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign=Paint.Align.CENTER } }
    val caption=state.caption.ifEmpty { stringResource(R.string.choose_brush) }
    Canvas(Modifier.fillMaxSize()) {
        val message="$caption  ·  ${state.page+1}/${state.pages}"
        paint.color=0xFF55442D.toInt();paint.textSize=size.height*.65f
        val measured=paint.measureText(message)
        if(measured>size.width*.98f)paint.textSize*=size.width*.98f/measured
        drawIntoCanvas { it.nativeCanvas.drawText(message,size.width*.5f,size.height*.76f,paint) }
    }
}
