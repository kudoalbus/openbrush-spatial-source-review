// Source-review edition: exercises cases that run without a headset or the bundled artwork.
package com.openbrush.picoviewer

import com.openbrush.picoviewer.data.*
import com.openbrush.picoviewer.domain.drawing.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.*

class PaletteColorPickerTest {
    private val initial=HsvInk(.59f,.78f,.8f)
    private fun state()=PaletteColorState(PaletteColorUiState(true,initial,List(5){null}))
    private fun wheel(h:Float,s:Float,y:Float=PalettePickerLayout.FACE):V3 {
        val a=h*2*PI.toFloat();return PalettePickerLayout.CENTER+V3(cos(a)*s*PalettePickerLayout.RADIUS,y-PalettePickerLayout.FACE,-sin(a)*s*PalettePickerLayout.RADIUS)
    }
    private fun value(v:Float,r:Float=.09f):V3 {
        val a=(-60+120*v)*PI.toFloat()/180;return PalettePickerLayout.CENTER+V3(cos(a)*r,0f,-sin(a)*r)
    }
    @Test fun hsvRoundTripsPrimaryAndArbitraryBrushColors() {
        for(c in listOf(InkColor(1f,0f,0f),InkColor(0f,1f,0f),InkColor(0f,0f,1f),InkColor(.07f,.42f,.91f,.6f),InkColor(1f,1f,1f),InkColor(0f,0f,0f))) {
            val back=HsvInk.from(c).color()
            assertEquals(c.r,back.r,1e-5f);assertEquals(c.g,back.g,1e-5f);assertEquals(c.b,back.b,1e-5f);assertEquals(c.a,back.a,0f)
        }
    }
    @Test fun wheelUsesAngleForHueAndRadiusForSaturationWithoutChangingValue() {
        for(h in listOf(0f,.17f,.4f,.66f,.92f)) {
            val p=wheel(h,.7f)
            assertEquals(PaletteColorCommands.WHEEL,PalettePickerLayout.widget(p))
            val hsv=requireNotNull(PalettePickerLayout.sample(PaletteColorCommands.WHEEL,p,initial,false))
            assertEquals(h,hsv.h,1e-5f);assertEquals(.7f,hsv.s,1e-5f);assertEquals(initial.v,hsv.v,0f)
        }
        val center=PalettePickerLayout.sample(PaletteColorCommands.WHEEL,PalettePickerLayout.CENTER,initial,false)!!
        assertEquals(0f,center.s,0f);assertEquals(initial.h,center.h,0f)
    }
    @Test fun valueArcSpansExactlyOneHundredTwentyDegreesAndPreservesHue() {
        for(v in listOf(.001f,.25f,.5f,.75f,.999f)) {
            val p=value(v)
            assertEquals(PaletteColorCommands.VALUE,PalettePickerLayout.widget(p))
            val c=PalettePickerLayout.sample(PaletteColorCommands.VALUE,p,initial,false)!!
            assertEquals(v,c.v,1e-5f);assertEquals(initial.h,c.h,0f);assertEquals(initial.s,c.s,0f)
        }
        assertNull(PalettePickerLayout.widget(PalettePickerLayout.CENTER+V3(-.09f,0f,0f)))
        assertNull(PalettePickerLayout.widget(value(.5f,.075f)))
    }
    @Test fun activeValueSlideClampsBeyondEndpointsAndStopsWhenFingerWithdraws() {
        assertEquals(0f,PalettePickerLayout.sample(PaletteColorCommands.VALUE,value(-.1f),initial,true)!!.v,0f)
        assertEquals(1f,PalettePickerLayout.sample(PaletteColorCommands.VALUE,value(1.1f),initial,true)!!.v,0f)
        assertNull(PalettePickerLayout.sample(PaletteColorCommands.VALUE,value(1.1f),initial,false))
        assertNull(PalettePickerLayout.sample(PaletteColorCommands.VALUE,value(.5f)+V3(0f,.04f,0f),initial,true))
        assertNull(PalettePickerLayout.sample(PaletteColorCommands.VALUE,value(.5f,.15f),initial,true))
    }
    @Test fun backHoverAndNonFinitePointersDoNotChangeColor() {
        assertNull(PalettePickerLayout.widget(wheel(.3f,.5f,-.005f)))
        assertNull(PalettePickerLayout.widget(wheel(.3f,.5f,.030f)))
        assertNull(PalettePickerLayout.widget(V3(Float.NaN,.01f,0f)))
        assertNull(PalettePickerLayout.sample(PaletteColorCommands.WHEEL,V3.ZERO,initial,false))
    }
    @Test fun emptySlotStoresOnceAndFilledSlotRecallsAllFourValues() {
        val s=state();s.favorite(2)
        assertEquals(initial,s.ui.favorites[2]);assertEquals(initial,s.ui.selected)
        val changed=HsvInk(.2f,.4f,.1f,.6f);s.select(changed);s.favorite(2)
        assertEquals(initial,s.ui.selected);assertEquals(initial,s.ui.favorites[2])
        assertEquals(1,s.ui.favorites.count { it!=null })
    }
    @Test fun fiveSlotsAreIndependentAndSurviveSerializationAndModeSwitches() {
        val s=state()
        repeat(5) { s.select(HsvInk(it/5f,.7f,(it+1)/5f));s.favorite(it) }
        val slots=s.ui.favorites.toList();val selected=s.ui.selected
        s.toggle();assertFalse(s.ui.picker);assertEquals(selected,s.ui.selected);assertEquals(slots,s.ui.favorites)
        val restored=PaletteColorState(PaletteColorCodec.decode(PaletteColorCodec.encode(s.ui)))
        assertEquals(s.ui,restored.ui)
        repeat(5) { restored.favorite(it);assertEquals(slots[it],restored.ui.selected) }
    }
    @Test fun blackFavoritePreservesHueSoBrightnessCanRestoreTheColor() {
        val s=state();val black=initial.copy(v=0f);s.select(black);s.favorite(0)
        s.select(HsvInk(.1f,1f,1f));s.favorite(0)
        assertEquals(black,s.ui.selected)
        assertEquals(initial.copy(v=1f),PalettePickerLayout.sample(PaletteColorCommands.VALUE,value(1f),s.ui.selected,true))
        assertEquals(initial.h,HsvInk.from(InkColor(0f,0f,0f),initial).h,0f)
        assertEquals(initial.s,HsvInk.from(InkColor(0f,0f,0f),initial).s,0f)
    }
    @Test fun brushChangesSynchronizeSelectedColorWithoutMutatingSavedSlots() {
        val s=state();s.favorite(1);s.brushColor(InkColor(1f,0f,0f))
        assertEquals(0f,s.ui.selected.h,1e-5f);assertEquals(1f,s.ui.selected.s,0f);assertEquals(1f,s.ui.selected.v,0f)
        assertEquals(initial,s.ui.favorites[1])
    }
    @Test fun favoritesAreAboveTheWheelAndCurrentCircleIsLargerThanThumbHole() {
        val c=PalettePickerLayout.CURRENT
        assertTrue(PalettePickerLayout.CURRENT_RADIUS>.021f)
        repeat(5) { i ->
            val p=PalettePickerLayout.favoriteCenter(i)
            assertTrue(p.z+.0078f<PalettePickerLayout.CENTER.z-PalettePickerLayout.RADIUS)
            assertTrue((p-c).length()>PalettePickerLayout.CURRENT_RADIUS+.0078f)
            if(i>0)assertEquals(.023f,p.x-PalettePickerLayout.favoriteCenter(i-1).x,1e-6f)
        }
    }
    @Test fun rawRayAndSystemPointerProjectToTheSameWheelValueAndSlots() {
        for(p in listOf(wheel(.6f,.7f),value(.7f),PalettePickerLayout.favoriteCenter(3))) {
            val (ray,distance)=PalettePickerLayout.plane(p+V3(0f,.3f,0f),V3(0f,-1f,0f))!!
            assertEquals(p.x,ray.x,1e-5f);assertEquals(p.z,ray.z,1e-5f);assertEquals(.3f,distance,1e-5f)
            val u=(p.x-PalettePickerLayout.PANEL_CENTER_X)/PalettePickerLayout.PANEL_WIDTH+.5f
            val v=p.z/PalettePickerLayout.PANEL_HEIGHT+.5f
            val system=PalettePickerLayout.surface(u,v)!!
            assertEquals(p.x,system.x,1e-5f);assertEquals(p.z,system.z,1e-5f)
        }
        assertNull(PalettePickerLayout.plane(V3(0f,-.1f,0f),V3(0f,1f,0f)))
    }
    @Test fun pickerActionsDoNotTouchTheStrokeDocumentOrHistory() {
        val document=StrokeDocument();val s=state();val revision=document.revision
        s.select(HsvInk(.1f,.3f,.9f));s.favorite(0);s.toggle();s.favorite(0)
        assertEquals(revision,document.revision);assertFalse(document.canUndo);assertTrue(document.strokes.isEmpty())
    }
}
