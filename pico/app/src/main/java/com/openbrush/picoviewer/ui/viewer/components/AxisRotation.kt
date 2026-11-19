package com.openbrush.picoviewer.ui.viewer.components

internal sealed interface ArtworkMotion {
    data class Rotate(val step: AxisRotation) : ArtworkMotion
    data object Center : ArtworkMotion
}

/** Component axes: +X right, +Y up, +Z toward the user; right-hand positive angles. */
enum class AxisRotation(val axis:String,val direction:Int,val label:String) {
    X_MINUS("X",-1,"↶ X−"),X_PLUS("X",1,"↷ X+"),
    Y_MINUS("Y",-1,"↶ Y−"),Y_PLUS("Y",1,"↷ Y+"),
    Z_MINUS("Z",-1,"↶ Z−"),Z_PLUS("Z",1,"↷ Z+")
}
