package com.openbrush.picoviewer.domain.drawing

import kotlin.math.*

data class HsvInk(val h:Float,val s:Float,val v:Float,val alpha:Float=1f) {
    init { require(listOf(h,s,v,alpha).all { it.isFinite() && it in 0f..1f }) }
    fun color():InkColor {
        val sector=(h%1f)*6;val f=sector-floor(sector);val p=v*(1-s);val q=v*(1-s*f);val t=v*(1-s*(1-f))
        val c=when(sector.toInt()%6) {
            0->listOf(v,t,p);1->listOf(q,v,p);2->listOf(p,v,t);3->listOf(p,q,v);4->listOf(t,p,v);else->listOf(v,p,q)
        }
        return InkColor(c[0],c[1],c[2],alpha)
    }
    companion object {
        fun from(color:InkColor,previous:HsvInk?=null):HsvInk {
            val max=max(color.r,max(color.g,color.b));val min=min(color.r,min(color.g,color.b));val d=max-min
            val hue=if(d<1e-7f)previous?.h ?: 0f else {
                val raw=when(max) { color.r->(color.g-color.b)/d; color.g->(color.b-color.r)/d+2;else->(color.r-color.g)/d+4 }
                ((raw/6)%1f+1f)%1f
            }
            return HsvInk(hue,if(max<1e-7f)previous?.s ?: 0f else d/max,max,color.a)
        }
    }
}

data class PaletteColorUiState(val picker:Boolean,val selected:HsvInk,val favorites:List<HsvInk?>) {
    init { require(favorites.size==5) }
    val color get()=selected.color()
}

/** HSV retains the latent hue/saturation at black, so the brightness control is reversible. */
class PaletteColorState(initial:PaletteColorUiState) {
    var ui=initial;private set
    fun toggle() { ui=ui.copy(picker=!ui.picker) }
    fun brushColor(color:InkColor):Boolean {
        if(ui.color==color)return false
        ui=ui.copy(selected=HsvInk.from(color,ui.selected));return true
    }
    fun select(hsv:HsvInk) { ui=ui.copy(selected=hsv) }
    fun favorite(index:Int) {
        require(index in 0..4)
        val saved=ui.favorites[index]
        ui=if(saved!=null)ui.copy(selected=saved) else ui.copy(favorites=ui.favorites.mapIndexed { i,c -> if(i==index)ui.selected else c })
    }
}

object PaletteColorCommands {
    const val MODE=300
    const val WHEEL=320
    const val VALUE=321
    const val FAVORITE=310
    fun owns(id:Int)=id==MODE || id==WHEEL || id==VALUE || id in FAVORITE..FAVORITE+4
    fun continuous(id:Int)=id==WHEEL || id==VALUE
}

object PalettePickerLayout {
    const val FACE=.010f
    const val PANEL_WIDTH=.42f
    // End below the floating brush-book footer so blank UI space cannot cover its page buttons.
    const val PANEL_HEIGHT=.29f
    const val PANEL_CENTER_X=-.025f
    val CENTER=V3(.005f,FACE,-.027f)
    const val RADIUS=.0715f
    const val ARC_IN=.081f
    const val ARC_OUT=.099f
    val CURRENT=V3(-.070f,FACE,-.092f)
    const val CURRENT_RADIUS=.0232f
    val actions=listOf(8 to -.032f,9 to 0f,10 to .032f,BrushBookCommands.TOGGLE to .064f,PaletteColorCommands.MODE to .096f)
    fun favoriteCenter(index:Int)=V3(.005f+(index-2)*.023f,FACE,-.119f)
    fun layout(legacy:PaletteLayout)=PaletteLayout(legacy.grip,
        legacy.targets.filter { it.kind==PaletteKind.BRUSH }+
        actions.map { (id,x)->PaletteTarget(id,PaletteKind.ACTION,"PickerAction_$id",V3(x,FACE,.079f),.0118f) }+
        (0..4).map { PaletteTarget(PaletteColorCommands.FAVORITE+it,PaletteKind.ACTION,"Favorite_$it",favoriteCenter(it),.0078f) })
    fun surface(u:Float,v:Float):V3?=if(u in 0f..1f && v in 0f..1f)
        V3((u-.5f)*PANEL_WIDTH+PANEL_CENTER_X,FACE,(v-.5f)*PANEL_HEIGHT) else null
    fun plane(origin:V3,direction:V3):Pair<V3,Float>? {
        if(!origin.finite() || !direction.finite() || direction.length()<1e-7f || origin.y<.001f)return null
        val ray=direction.unit();if(ray.y>=-1e-6f)return null
        val distance=(FACE-origin.y)/ray.y
        return if(distance in 0f..2f)(origin+ray*distance) to distance else null
    }
    fun widget(point:V3):Int? {
        if(!contact(point,.009f))return null
        val d=point-CENTER;val r=sqrt(d.x*d.x+d.z*d.z)
        if(r<=RADIUS+.0015f)return PaletteColorCommands.WHEEL
        val angle=atan2(-d.z,d.x)*180f/PI.toFloat()
        return if(r in ARC_IN-.002f..ARC_OUT+.002f && angle in -60f..60f)PaletteColorCommands.VALUE else null
    }
    fun sample(id:Int,point:V3,selected:HsvInk,drag:Boolean):HsvInk? {
        if(!contact(point,if(drag).025f else .009f))return null
        val d=point-CENTER;val r=sqrt(d.x*d.x+d.z*d.z)
        return when(id) {
            PaletteColorCommands.WHEEL-> {
                if(r>RADIUS+if(drag).018f else .0015f)return null
                val h=if(r<1e-6f)selected.h else ((atan2(-d.z,d.x)/(2*PI.toFloat()))%1f+1f)%1f
                selected.copy(h=h,s=(r/RADIUS).coerceIn(0f,1f))
            }
            PaletteColorCommands.VALUE-> {
                if(r !in (ARC_IN-if(drag).018f else .002f)..(ARC_OUT+if(drag).018f else .002f))return null
                val angle=atan2(-d.z,d.x)*180f/PI.toFloat()
                if(abs(angle)>if(drag)85f else 60f)return null
                selected.copy(v=((angle+60)/120).coerceIn(0f,1f))
            }
            else->null
        }
    }
    private fun contact(point:V3,depth:Float)=point.finite() && point.y>=.001f && abs(point.y-FACE)<=depth
    fun target(id:Int)=PaletteTarget(id,PaletteKind.ACTION,"ColorWidget_$id",CENTER,.04f)
}
