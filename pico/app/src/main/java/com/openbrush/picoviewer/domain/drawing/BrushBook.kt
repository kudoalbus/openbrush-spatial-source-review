package com.openbrush.picoviewer.domain.drawing

import kotlin.math.abs

enum class BrushBookMode { BASIC, CLASSIC, EXTENDED }
data class BrushBookEntry(val id:String,val name:String,val icon:String?,val renderer:String?,val model:String?) {
    val implemented get()=renderer!=null && id==renderer
}
class BrushBookCatalog(
    val entries:Map<String,BrushBookEntry>,val basic:List<String>,val classic:List<String>,val extended:List<String>,
    val classicAtlases:List<String>,val extendedAtlases:List<String>,val sourceCommit:String,
    val cell:Int,val gutter:Int,val atlasWidth:Int,val atlasHeight:Int
) {
    init {
        require(classic.size==48 && extended.size==102 && basic.size==3)
        require(classic.distinct().size==classic.size && extended.distinct().size==extended.size)
        require(extended.take(classic.size)==classic)
        require((basic+extended).all { it in entries })
        require(basic.all { entries.getValue(it).implemented })
        require(classicAtlases.size==4 && extendedAtlases.size==9 && extendedAtlases.take(4)==classicAtlases)
        require(cell>0 && gutter>=0 && atlasWidth==(cell+gutter*2)*4 && atlasHeight==(cell+gutter*2)*3)
    }
    fun withStudioBrushes(enabledIds: Set<String>) = BrushBookCatalog(
        entries.mapValues { (id, entry) ->
            if (id.lowercase() in enabledIds) entry.copy(renderer = id) else entry
        }, basic, classic, extended, classicAtlases, extendedAtlases, sourceCommit, cell, gutter, atlasWidth, atlasHeight
    )
    fun brushes(mode:BrushBookMode)=when(mode) { BrushBookMode.BASIC->basic;BrushBookMode.CLASSIC->classic;BrushBookMode.EXTENDED->extended }.map(entries::getValue)
    fun atlas(mode:BrushBookMode,page:Int):String?=when(mode) {
        BrushBookMode.BASIC->null
        BrushBookMode.CLASSIC->classicAtlases[page]
        BrushBookMode.EXTENDED->extendedAtlases[page]
    }
}

object BrushBookCommands {
    const val TOGGLE=200
    const val CLASSIC=201
    const val EXTENDED=202
    const val BASIC=203
    const val PREVIOUS=204
    const val NEXT=205
    const val CLOSE=206
    const val SLOT=220
    const val PAGE=240
    fun owns(command:Int)=command in 200..206 || command in SLOT until SLOT+12 || command in PAGE until PAGE+9
}

/** Each mode keeps its own page. Native basics never change the upstream classic/extended slots. */
class BrushBookState(val catalog:BrushBookCatalog,initialMode:BrushBookMode=BrushBookMode.CLASSIC,
    classicPage:Int=1,extendedPage:Int=1) {
    private val pages=mutableMapOf(BrushBookMode.BASIC to 0,BrushBookMode.CLASSIC to classicPage.coerceIn(0,3),BrushBookMode.EXTENDED to extendedPage.coerceIn(0,8))
    var mode=initialMode;private set
    val page get()=pages.getValue(mode)
    val pageCount get()=(catalog.brushes(mode).size+11)/12
    val slots get()=catalog.brushes(mode).drop(page*12).take(12)
    fun savedPage(mode:BrushBookMode)=pages.getValue(mode)
    fun entry(command:Int)=slots.getOrNull(command-BrushBookCommands.SLOT)
    fun enabled(command:Int)=when(command) {
        BrushBookCommands.PREVIOUS->page>0
        BrushBookCommands.NEXT->page<pageCount-1
        in BrushBookCommands.SLOT until BrushBookCommands.SLOT+12->entry(command)!=null
        in BrushBookCommands.PAGE until BrushBookCommands.PAGE+9->command-BrushBookCommands.PAGE<pageCount
        else->true
    }
    fun navigate(command:Int):Boolean {
        val old=mode to page
        when(command) {
            BrushBookCommands.BASIC->mode=BrushBookMode.BASIC
            BrushBookCommands.CLASSIC->mode=BrushBookMode.CLASSIC
            BrushBookCommands.EXTENDED->mode=BrushBookMode.EXTENDED
            BrushBookCommands.PREVIOUS->pages[mode]=(page-1).coerceAtLeast(0)
            BrushBookCommands.NEXT->pages[mode]=(page+1).coerceAtMost(pageCount-1)
            in BrushBookCommands.PAGE until BrushBookCommands.PAGE+9->pages[mode]=(command-BrushBookCommands.PAGE).coerceIn(0,pageCount-1)
        }
        return old!=(mode to page)
    }
    fun renderer(command:Int):String?=entry(command)?.takeIf { it.implemented }?.renderer
}

data class BrushBookTarget(val command:Int,val center:V3,val width:Float,val height:Float)
data class BrushBookRayHit(val command:Int,val distance:Float)

/** The glTF face is +Y; image top is -Z, just like the color palette. */
object BrushBookLayout {
    const val FACE=.016f
    const val SURFACE_WIDTH=.224f
    const val SURFACE_HEIGHT=.201f
    const val SURFACE_CENTER_Z=.0135f
    /** Transparent native panel covers the slots and footer, below the separate header. */
    fun surfaceCommand(u:Float,v:Float,state:BrushBookState):Int? {
        if(u !in 0f..1f || v !in 0f..1f)return null
        return touch(V3((u-.5f)*SURFACE_WIDTH,FACE,(v-.5f)*SURFACE_HEIGHT+SURFACE_CENTER_Z),state)
    }
    fun slotCenter(slot:Int)=V3((slot%4-1.5f)*.048f,FACE,-.061f+(slot/4)*.052f)
    fun targets(state:BrushBookState):List<BrushBookTarget> = buildList {
        repeat(12) { add(BrushBookTarget(BrushBookCommands.SLOT+it,slotCenter(it),.043f,.043f)) }
        for((command,x) in listOf(BrushBookCommands.BASIC to -.024f,BrushBookCommands.CLASSIC to .018f,
            BrushBookCommands.EXTENDED to .060f,BrushBookCommands.CLOSE to .102f)) {
            add(BrushBookTarget(command,V3(x,.010f,-.104f),if(command==BrushBookCommands.CLOSE).020f else .036f,.019f))
        }
        add(BrushBookTarget(BrushBookCommands.PREVIOUS,V3(-.084f,.009f,.098f),.025f,.025f))
        add(BrushBookTarget(BrushBookCommands.NEXT,V3(.084f,.009f,.098f),.025f,.025f))
        repeat(state.pageCount) { add(BrushBookTarget(BrushBookCommands.PAGE+it,V3((it-(state.pageCount-1)*.5f)*.010f,.009f,.096f),.008f,.014f)) }
    }
    fun touch(point:V3,state:BrushBookState,hover:Boolean=false):Int? {
        if(!point.finite() || point.y<.008f)return null
        return targets(state).asSequence().filter { state.enabled(it.command) }.filter {
            abs(point.x-it.center.x)<=it.width*.5f+.004f && abs(point.z-it.center.z)<=it.height*.5f+.004f &&
                abs(point.y-it.center.y)<=if(hover).04f else .010f
        }.minByOrNull { (point-it.center).length() }?.command
    }
    fun guards(point:V3)=point.finite() && abs(point.x)<.134f && abs(point.z)<.14f && point.y in -.04f.. .075f
    fun ray(origin:V3,direction:V3,state:BrushBookState):BrushBookRayHit? {
        if(!origin.finite() || !direction.finite() || direction.length()<1e-7f || origin.y<.008f)return null
        val unit=direction.unit()
        if(unit.y>=-1e-6f)return null
        return targets(state).asSequence().filter { state.enabled(it.command) }.mapNotNull {
            val distance=(it.center.y-origin.y)/unit.y
            val point=origin+unit*distance
            if(distance in 0f..2f && abs(point.x-it.center.x)<=it.width*.5f && abs(point.z-it.center.z)<=it.height*.5f)
                BrushBookRayHit(it.command,distance) else null
        }.minByOrNull { it.distance }
    }
    fun guardsRay(origin:V3,direction:V3):Boolean {
        return blockingDistance(origin,direction)!=null
    }
    fun blockingDistance(origin:V3,direction:V3):Float? {
        if(!origin.finite() || !direction.finite() || direction.length()<1e-7f)return null
        val ray=direction.unit();if(abs(ray.y)<1e-6f)return null
        val distance=(FACE-origin.y)/ray.y
        return distance.takeIf { it in 0f..2f && guards(origin+ray*it) }
    }
    fun headerCommand(u:Float):Int? {
        if(u !in 0f..1f)return null
        val x=(u-.5f)*.224f
        return listOf(BrushBookCommands.BASIC to -.024f,BrushBookCommands.CLASSIC to .018f,
            BrushBookCommands.EXTENDED to .060f,BrushBookCommands.CLOSE to .102f)
            .firstOrNull { abs(x-it.second)<=if(it.first==BrushBookCommands.CLOSE).010f else .018f }?.first
    }
}
