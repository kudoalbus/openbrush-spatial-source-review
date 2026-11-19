package com.openbrush.picoviewer.domain.drawing

/** OpenXR's 26-joint order, also used by Spatial SDK 6.1.9. No native calls in classification. */
enum class HandJointId {
    PALM,WRIST,THUMB_METACARPAL,THUMB_PROXIMAL,THUMB_DISTAL,THUMB_TIP,
    INDEX_METACARPAL,INDEX_PROXIMAL,INDEX_INTERMEDIATE,INDEX_DISTAL,INDEX_TIP,
    MIDDLE_METACARPAL,MIDDLE_PROXIMAL,MIDDLE_INTERMEDIATE,MIDDLE_DISTAL,MIDDLE_TIP,
    RING_METACARPAL,RING_PROXIMAL,RING_INTERMEDIATE,RING_DISTAL,RING_TIP,
    LITTLE_METACARPAL,LITTLE_PROXIMAL,LITTLE_INTERMEDIATE,LITTLE_DISTAL,LITTLE_TIP
}
data class DecodedHand(val shape:HandShape,val index:V3,val thumb:V3,val normal:V3,val heldTools:Set<PaintTool>,
    val recolorTip:V3=index,val palmWidth:Float=.08f) {
    val pinchTip: V3 get() = (index+thumb)*.5f
    // Select by the gated tool: a held eraser still uses the thumb during classification noise.
    fun tipFor(tool:PaintTool)=when(tool) { PaintTool.ERASE -> thumb; PaintTool.RECOLOR -> recolorTip; else -> index }
}

object HandPoseDecoder {
    fun decode(joints:List<V3>):DecodedHand? {
        if(joints.size!=26 || joints.any { !it.finite() })return null
        fun p(i:HandJointId)=joints[i.ordinal]
        val width=(p(HandJointId.INDEX_PROXIMAL)-p(HandJointId.LITTLE_PROXIMAL)).length()
        if(width !in .025f.. .15f)return null
        val groups=listOf(listOf(7,8,9,10),listOf(12,13,14,15),listOf(17,18,19,20),listOf(22,23,24,25))
        val bones=groups.map { (a,b,c,d) -> FingerBones(joints[a],joints[b],joints[c],joints[d]) }
        if(bones.any { listOf(it.root-it.middle,it.middle-it.distal,it.distal-it.tip).any { v -> v.length() !in .002f.. .1f } })return null
        val angles=bones.map { it.minAngleDegrees() ?: return null }
        val before=p(HandJointId.THUMB_DISTAL)-p(HandJointId.THUMB_PROXIMAL)
        val after=p(HandJointId.THUMB_TIP)-p(HandJointId.THUMB_DISTAL)
        if(before.length() !in .003f.. .08f || after.length() !in .003f.. .08f)return null
        val alignment=before.unit().dot(after.unit())
        val thumbDistance=(p(HandJointId.THUMB_TIP)-p(HandJointId.INDEX_PROXIMAL)).length()
        val tucked=thumbDistance<width*.65f
        val thumb=when {
            tucked -> FingerShape.CURLED
            alignment>.82f -> FingerShape.STRAIGHT
            alignment<.42f -> FingerShape.CURLED
            else -> FingerShape.UNCERTAIN
        }
        val indexDirection=(bones[0].tip-bones[0].root).unit()
        val middleDirection=(bones[1].tip-bones[1].root).unit()
        val fingerAlignment=indexDirection.dot(middleDirection)
        val forward=(indexDirection+middleDirection).unit()
        val tipOffset=bones[1].tip-bones[0].tip
        // Compare lateral gap, allowing the natural difference in finger lengths.
        val sideGap=(tipOffset-forward*tipOffset.dot(forward)).length()/width
        val together=fingerAlignment>.94f && sideGap<.34f && tipOffset.length()<width*.70f
        val shape=HandShape(thumb,bones[0].shape(),bones[1].shape(),bones[2].shape(),bones[3].shape(),together)
        val rawNormal=(p(HandJointId.INDEX_PROXIMAL)-p(HandJointId.WRIST))
            .cross(p(HandJointId.LITTLE_PROXIMAL)-p(HandJointId.WRIST))
        if(rawNormal.length()<.0001f)return null
        // Broader hold thresholds prevent a small curl fluctuation from splitting one gesture.
        // They never initiate a gesture: starting still requires the strict shape plus dwell.
        val othersCurled=angles.drop(1).all { it<145f }
        val held=buildSet {
            if(othersCurled && angles[0]>=140f && (thumbDistance<width*.75f || alignment<.60f))add(PaintTool.DRAW)
            if(othersCurled && angles[0]<145f && thumbDistance>width*.55f && alignment>.72f)add(PaintTool.ERASE)
            if(angles[0]>=140f && angles[1]>=140f && angles[2]<145f && angles[3]<145f &&
                (thumbDistance<width*.75f || alignment<.60f) && fingerAlignment>.90f &&
                sideGap<.42f && tipOffset.length()<width*.80f) add(PaintTool.RECOLOR)
        }
        return DecodedHand(shape,p(HandJointId.INDEX_TIP),p(HandJointId.THUMB_TIP),rawNormal.unit(),held,
            (bones[0].tip+bones[1].tip)*.5f,width)
    }
}
