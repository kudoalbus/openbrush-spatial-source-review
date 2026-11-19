package com.openbrush.picoviewer.ui.viewer.components

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.*
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.constraint.Constraints3D

/** Two siblings share one window volume. The model reserves room above the controls;
 * the controls sit on the front face, immediately above the system drag dot. */
@Composable
fun ArtworkWindowLayout(instance:String,modifier:Modifier,model:@Composable ()->Unit,controls:@Composable ()->Unit) {
    val policy=remember(instance){object:MeasurePolicy {
        var lastGeometry:String?=null
        override fun MeasureScope.measure(measurables:List<Measurable>,constraints:Constraints3D):MeasureResult {
            val width=constraints.maxWidth
            val height=constraints.maxHeight
            val depth=if(constraints.maxDepth!=Constraints.Infinity)constraints.maxDepth else constraints.minDepth
            val margin=20.dp.roundToPx()
            val frontInset=12.dp.roundToPx()
            val availableWidth=(width-2*margin).coerceAtLeast(0)
            val panel=measurables[1].measure(Constraints3D(
                Constraints(maxWidth=minOf(760.dp.roundToPx(),availableWidth),maxHeight=(height-2*margin).coerceAtLeast(0)),
                minDepth=0,maxDepth=0))
            val modelHeight=(height-panel.height-3*margin).coerceAtLeast(0)
            val artwork=measurables[0].measure(Constraints3D(
                Constraints.fixed(availableWidth,modelHeight),minDepth=depth,maxDepth=depth))
            val panelX=(width-panel.width)/2
            val panelY=height-margin-panel.height
            val panelZ=(depth-frontInset-panel.depth).coerceAtLeast(0)
            val geometry="window=${width}x${height}x${depth} panel=${panel.width}x${panel.height} at=($panelX,$panelY,$panelZ) bottomGap=$margin frontGap=$frontInset"
            if(geometry!=lastGeometry){Log.i("OpenBrushToolbar","instance=$instance $geometry");lastGeometry=geometry}
            return layout(width,height,depth) {
                artwork.place3D(margin,margin,0)
                panel.place3D(panelX,panelY,panelZ)
            }
        }
        override fun MeasureScope.measure(measurables:List<Measurable>,constraints:Constraints):MeasureResult {
            val width=constraints.maxWidth;val height=constraints.maxHeight;val margin=20.dp.roundToPx()
            val availableWidth=(width-2*margin).coerceAtLeast(0)
            val panel=measurables[1].measure(Constraints(maxWidth=minOf(760.dp.roundToPx(),availableWidth),maxHeight=(height-2*margin).coerceAtLeast(0)))
            val artwork=measurables[0].measure(Constraints.fixed(availableWidth,(height-panel.height-3*margin).coerceAtLeast(0)))
            return layout(width,height){artwork.place(margin,margin);panel.place((width-panel.width)/2,height-margin-panel.height)}
        }
    }}
    Layout(content={model();controls()},modifier=modifier,measurePolicy=policy)
}
