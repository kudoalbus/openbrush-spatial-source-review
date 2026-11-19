package com.openbrush.picoviewer.domain.drawing

/** Contact is relative to the moving left hand, not to a world-space UI rectangle. */
class PokeLatch {
    private var press: V3?=null
    private var clearSince: Long?=null
    fun reset() { press=null;clearSince=null }
    fun update(cell: Int?,fingerInLeftHand: V3?,timeMs: Long): Int? {
        if(fingerInLeftHand==null) { clearSince=null;return null }
        val captured=press
        if(captured!=null) {
            if(cell==null && (fingerInLeftHand-captured).length()>=.025f) {
                if(clearSince==null) clearSince=timeMs
                if(timeMs-requireNotNull(clearSince)>=100L) reset()
            } else clearSince=null
            return null
        }
        if(cell!=null) { press=fingerInLeftHand;return cell }
        return null
    }
}
