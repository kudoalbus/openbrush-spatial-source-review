// Source-review edition: exercises cases that run without a headset or the bundled artwork.
package com.openbrush.picoviewer

import com.openbrush.picoviewer.data.BrushBookCatalogCodec
import com.openbrush.picoviewer.domain.drawing.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import org.json.JSONObject

class BrushBookTest {
    private val assets=File("src/main/assets")
    private fun catalog()=BrushBookCatalogCodec.decode(File(assets,"brush-library/catalog.json").readText())

    @Test fun originalSlotsStayStableAcrossClassicAndExperimental() {
        val c=catalog();val state=BrushBookState(c)
        assertEquals(1,state.page)
        assertEquals("Light",state.slots.first().name)
        assertEquals("f72ec0e7-a844-4e38-82e3-140c44772699",c.classic.first())
        assertEquals(c.classic,c.extended.take(48))
        assertEquals(4,state.pageCount)
        state.navigate(BrushBookCommands.EXTENDED)
        assertEquals(9,state.pageCount)
        assertEquals(c.classic.drop(12).take(12),state.slots.map { it.id })
        assertEquals(c.classicAtlases,c.extendedAtlases.take(4))
    }
    @Test fun modesKeepSeparatePagesAndNavigationStopsAtEnds() {
        val state=BrushBookState(catalog())
        state.navigate(BrushBookCommands.PAGE+3)
        assertFalse(state.enabled(BrushBookCommands.NEXT))
        assertFalse(state.navigate(BrushBookCommands.NEXT))
        state.navigate(BrushBookCommands.EXTENDED)
        assertEquals(1,state.page)
        state.navigate(BrushBookCommands.PAGE+8)
        assertEquals(6,state.slots.size)
        assertEquals("Slice",state.entry(BrushBookCommands.SLOT+5)?.name)
        assertFalse(state.enabled(BrushBookCommands.SLOT+6))
        assertNull(state.entry(BrushBookCommands.SLOT+6))
        state.navigate(BrushBookCommands.BASIC)
        assertEquals(0,state.page);assertEquals(1,state.pageCount)
        assertFalse(state.enabled(BrushBookCommands.PREVIOUS))
        state.navigate(BrushBookCommands.CLASSIC);assertEquals(3,state.page)
        state.navigate(BrushBookCommands.EXTENDED);assertEquals(8,state.page)
    }
    @Test fun onlyNativeBrushesCanReturnADrawingRenderer() {
        val state=BrushBookState(catalog())
        for(mode in listOf(BrushBookMode.CLASSIC,BrushBookMode.EXTENDED)) {
            state.navigate(if(mode==BrushBookMode.CLASSIC)BrushBookCommands.CLASSIC else BrushBookCommands.EXTENDED)
            repeat(state.pageCount) { page ->
                state.navigate(BrushBookCommands.PAGE+page)
                repeat(12) { assertNull(state.renderer(BrushBookCommands.SLOT+it)) }
            }
        }
        state.navigate(BrushBookCommands.BASIC)
        assertEquals(listOf("spatial_tube","spatial_flat","spatial_fan"),
            (0..2).map { state.renderer(BrushBookCommands.SLOT+it) })
        assertNull(state.renderer(BrushBookCommands.SLOT+3))
        assertFalse(BrushBookEntry("official","Official",null,"spatial_tube",null).implemented)
    }
    @Test fun fingersAndControllerRaysSelectIdenticalSlotsFromTheFront() {
        val state=BrushBookState(catalog())
        repeat(12) { i ->
            val p=BrushBookLayout.slotCenter(i)
            assertEquals(BrushBookCommands.SLOT+i,BrushBookLayout.touch(p,state))
            assertEquals(BrushBookCommands.SLOT+i,BrushBookLayout.ray(p+V3(0f,.3f,0f),V3(0f,-1f,0f),state)?.command)
            assertNull(BrushBookLayout.touch(p-V3(0f,.04f,0f),state))
            assertNull(BrushBookLayout.ray(p-V3(0f,.3f,0f),V3(0f,1f,0f),state))
            assertNull(BrushBookLayout.touch(p+V3(0f,.025f,0f),state))
            assertEquals(BrushBookCommands.SLOT+i,BrushBookLayout.touch(p+V3(0f,.025f,0f),state,hover=true))
        }
    }
    @Test fun blankAndEmptyTilesBlockToolsWithoutSelectingAnything() {
        val state=BrushBookState(catalog(),BrushBookMode.EXTENDED,extendedPage=8)
        val empty=BrushBookLayout.slotCenter(11)
        assertNull(BrushBookLayout.touch(empty,state))
        assertNull(BrushBookLayout.ray(empty+V3(0f,.3f,0f),V3(0f,-1f,0f),state))
        assertTrue(BrushBookLayout.guards(empty))
        assertTrue(BrushBookLayout.guardsRay(empty+V3(0f,.3f,0f),V3(0f,-1f,0f)))
        assertTrue(BrushBookLayout.guardsRay(empty-V3(0f,.3f,0f),V3(0f,1f,0f)))
        assertNull(BrushBookLayout.blockingDistance(V3(1f,.3f,0f),V3(0f,-1f,0f)))
        assertNull(BrushBookLayout.blockingDistance(V3(0f,3f,0f),V3(0f,-1f,0f)))
        assertNull(BrushBookLayout.touch(V3(Float.NaN,0f,0f),state))
    }
    @Test fun enteringBookRequiresOpenHandBeforeDrawingOrErasingAgain() {
        val s=FingerShape.STRAIGHT;val c=FingerShape.CURLED
        val open=HandShape(s,s,s,s,s)
        for(pose in listOf(HandShape(c,s,c,c,c),HandShape(s,c,c,c,c))) {
            val gate=HandToolGate(0)
            gate.update(open,0);gate.update(pose,1)
            assertEquals(pose.requested,gate.update(pose,2))
            assertEquals(PaintTool.NONE,gate.update(pose,3,overUi=BrushBookLayout.guards(BrushBookLayout.slotCenter(0))))
            assertEquals(PaintTool.NONE,gate.update(pose,4))
            gate.update(open,5);gate.update(pose,6)
            assertEquals(pose.requested,gate.update(pose,7))
        }
    }
    @Test fun systemTapSurfaceKeepsSlotOrderAndFooterNavigationAcrossModes() {
        val state=BrushBookState(catalog())
        fun tap(p:V3)=BrushBookLayout.surfaceCommand(p.x/BrushBookLayout.SURFACE_WIDTH+.5f,
            (p.z-BrushBookLayout.SURFACE_CENTER_Z)/BrushBookLayout.SURFACE_HEIGHT+.5f,state)
        repeat(12) { assertEquals(BrushBookCommands.SLOT+it,tap(BrushBookLayout.slotCenter(it))) }
        assertEquals(BrushBookCommands.NEXT,tap(V3(.084f,.016f,.098f)))
        assertEquals(BrushBookCommands.PAGE+2,tap(V3(.005f,.016f,.096f)))
        state.navigate(BrushBookCommands.EXTENDED);state.navigate(BrushBookCommands.PAGE+8)
        assertNull(tap(BrushBookLayout.slotCenter(11)))
        assertNull(tap(V3(.084f,.016f,.098f)))
        assertNull(BrushBookLayout.surfaceCommand(Float.NaN,.5f,state))
    }
}
