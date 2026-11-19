package com.openbrush.picoviewer.ui.viewer.components

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import com.openbrush.picoviewer.data.BrushBookCatalogCodec
import com.openbrush.picoviewer.data.DrawingWorkspaceFiles
import com.openbrush.picoviewer.data.StudioBrushCatalog
import com.openbrush.picoviewer.domain.drawing.BrushAudioFrame
import com.openbrush.picoviewer.domain.model.Artwork
import com.openbrush.picoviewer.domain.model.GaussianArtworkInfo
import com.openbrush.picoviewer.ui.studio.StrokeRenderer
import com.pico.spatial.ui.foundation.content.*
import com.pico.spatial.ui.foundation.gesture.*
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.*
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.GaussianSplattingResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.math.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.ReceiveChannel
import java.io.File
import kotlin.math.*

private class ArtworkScene {
    var owner:Entity?=null
    var manipulationRoot:Entity?=null
    var fittedSceneRoot:Entity?=null
    var model:Entity?=null
    var content:SpatialViewContent?=null
    var safe:BoundingBox?=null
    var fittedFor:Pair<IntSize,IntSize>?=null
    var manipulated=false
    var gestureUpdates=0
    var lighting:ArtworkLightingRuntime?=null
    var grabShape:ShapeResource?=null
    var physicsMaterial:PhysicsMaterialResource?=null
    var strokeRenderer:StrokeRenderer?=null
    var gaussianResource:GaussianSplattingResource?=null
    var gaussian:GaussianArtworkInfo?=null
    val brushMaterials=mutableListOf<Material>()

    fun bounds(entity:Entity, relativeTo:Entity):BoundingBox {
        val info=gaussian ?: return entity.getVisualBounds(relativeTo=relativeTo,recursive=true)
        val min=info.lower
        val max=info.upper
        val corners=buildList {
            for(x in listOf(min.x,max.x))for(y in listOf(min.y,max.y))for(z in listOf(min.z,max.z)) {
                add(entity.convertPositionTo(Vector3(x,y,z),relativeTo))
            }
        }
        val low=Vector3(corners.minOf{it.x},corners.minOf{it.y},corners.minOf{it.z})
        val high=Vector3(corners.maxOf{it.x},corners.maxOf{it.y},corners.maxOf{it.z})
        return BoundingBox((low+high)*.5f,(high-low)*.5f)
    }
}

internal sealed interface ArtworkWindowSource {
    data class Model(val artwork: Artwork) : ArtworkWindowSource
    data class Drawing(val id: String, val title: String) : ArtworkWindowSource
}
private fun Entity.transform()=requireNotNull(components[TransformComponent::class.java]){"实体缺少 TransformComponent"}
private fun BoundingBox.contains(b:BoundingBox):Boolean {
    val epsilon=.001f // one millimeter, matching native collision precision
    return (0..2).all { k->
        fun Vector3.axis()=when(k){0->x;1->y;else->z}
        abs(b.center.axis()-center.axis())+b.halfExtent.axis()<=halfExtent.axis()+epsilon
    }
}

@Composable
internal fun ArtworkView(source:ArtworkWindowSource,instance:String,useFileLighting:Boolean,shown:Boolean,
    windowSize:IntSize,motions:ReceiveChannel<ArtworkMotion>,onReady:()->Unit,
    onDrawingInfo:(Int,Int)->Unit,onError:(String)->Unit) {
    val context=LocalContext.current
    val scene=remember(source){ArtworkScene()}
    var ready by remember{mutableStateOf(false)}
    var viewSize by remember{mutableStateOf(IntSize.Zero)}
    val reportError by rememberUpdatedState(onError)
    val reportReady by rememberUpdatedState(onReady)
    val reportDrawingInfo by rememberUpdatedState(onDrawingInfo)
    // Commands are per window and consumed once; light toggles/recompositions cannot replay them.
    LaunchedEffect(scene,ready) {
        if(!ready)return@LaunchedEffect
        for(motion in motions) {
            val root=scene.manipulationRoot ?: continue
            val owner=scene.owner ?: continue
            val model=scene.model ?: continue
            if(!root.valid || scene.fittedFor==null)continue
            val transform=root.transform()
            val before=scene.bounds(model,owner)
            when(motion) {
                is ArtworkMotion.Rotate -> {
                    val step=motion.step
                    val axis=when(step.axis){"X"->Vector3(1f,0f,0f);"Y"->Vector3(0f,1f,0f);else->Vector3(0f,0f,1f)}
                    val delta=Quat(rotAngle=step.direction*PI.toFloat()/2f,rotAxis=axis)
                    transform.setPosition(before.center+delta.rotateVector(transform.position-before.center))
                    val q=delta*transform.quaternion
                    val n=sqrt(q.x*q.x+q.y*q.y+q.z*q.z+q.w*q.w)
                    transform.setQuaternion(Quat(q.x/n,q.y/n,q.z/n,q.w/n))
                    Log.i("OpenBrushRotation","instance=$instance axis=${step.axis} degrees=${step.direction*90} quaternion=${transform.quaternion}")
                }
                ArtworkMotion.Center -> {
                    val center=scene.safe?.center ?: continue
                    transform.setPosition(transform.position+center-before.center)
                }
            }
            scene.manipulated=true
            val after=scene.bounds(model,owner)
            reportError(if(scene.safe?.contains(after)==false)"作品超出组件边界；请向内拖回，或用系统边界扩大组件。" else "")
            Log.i("OpenBrushMotion","instance=$instance motion=$motion position=${transform.position} scale=${transform.scaleVector} after=$after")
        }
    }
    DisposableEffect(scene){onDispose{
        scene.strokeRenderer?.close()
        scene.owner?.takeIf{it.valid}?.destroy()
        scene.gaussianResource?.close()
        scene.grabShape?.close();scene.physicsMaterial?.close()
        scene.lighting?.close()
        scene.brushMaterials.forEach { it.close() };scene.brushMaterials.clear()
        scene.owner=null;scene.content=null
        Log.i("OpenBrushWindows","Disposed instance=$instance")
    }}
    SpatialView(
        modifier=Modifier.fillMaxSize().onSizeChanged{viewSize=it}.pointerInput(scene,ready) {
            val root=scene.manipulationRoot ?: return@pointerInput
            if(!ready)return@pointerInput
            // SpatialView already routes hits to this window's colliders. Do not re-query
            // runtime entity IDs while a captured two-hand gesture crosses mesh children.
            detectSpatialTransformGesture(context,
                onTransformBegin={scene.gestureUpdates=0;Log.i("OpenBrushGesture","Begin instance=$instance")},
                onTransformEnd={
                    val owner=scene.owner
                    if(owner!=null)Log.i("OpenBrushGesture","End instance=$instance updates=${scene.gestureUpdates} position=${root.transform().position} scale=${root.transform().scaleVector} bounds=${root.getVisualBounds(relativeTo=owner,recursive=true)}")
                }
            ) { gesture ->
                val content=scene.content ?: return@detectSpatialTransformGesture
                if(!root.valid)return@detectSpatialTransformGesture
                val delta=gesture.dragAmount
                val zero=content.convertPosition(Vector3.ZERO,ViewCoordinateSpace.Local,content.localSpatialCoordinateSpace)
                val drag=content.convertPosition(Vector3(delta.x,delta.y,delta.z),ViewCoordinateSpace.Local,content.localSpatialCoordinateSpace)-zero
                val rotation=content.convertRotation(gesture.rotation.toQuaternion(),ViewCoordinateSpace.Local,content.localSpatialCoordinateSpace)
                val scale=gesture.scaleValue
                if(!scale.isFinite() || scale<=0f)return@detectSpatialTransformGesture
                val half=scene.safe?.halfExtent ?: return@detectSpatialTransformGesture
                val centroid=gesture.centroid
                val pivot=Vector3((2*centroid.x-1)*half.x,(1-2*centroid.y)*half.y,(2*centroid.z-1)*half.z)-drag
                val transform=root.transform()
                val proposedScale=transform.scaleVector*scale
                if(!proposedScale.x.isFinite() || proposedScale.x<1e-5f)return@detectSpatialTransformGesture
                transform.setPosition(pivot+rotation.rotateVector(transform.position-pivot)*scale+drag)
                transform.setQuaternion(rotation*transform.quaternion)
                transform.setScaleVector(proposedScale)
                scene.gestureUpdates++
                scene.manipulated=true
                val model=scene.model ?: return@detectSpatialTransformGesture
                val bounds=scene.bounds(model,requireNotNull(scene.owner))
                reportError(if(scene.safe?.contains(bounds)==false)"作品超出组件边界；请向内拖回，或用系统边界扩大组件。" else "")
            }
        },
        initial={content,_->
            val owner=Entity();scene.owner=owner;content.addEntity(owner);scene.content=content
            try {
                val manipulationRoot=Entity();owner.addChild(manipulationRoot);scene.manipulationRoot=manipulationRoot
                val fittedSceneRoot=Entity();manipulationRoot.addChild(fittedSceneRoot);scene.fittedSceneRoot=fittedSceneRoot
                scene.physicsMaterial=PhysicsMaterialResource()
                when(source) {
                    is ArtworkWindowSource.Model -> {
                        val art=source.artwork
                        val gaussian=art.gaussian
                        if(gaussian!=null) {
                            withContext(NonCancellable) {
                                val resource=withContext(Dispatchers.IO) {
                                    GaussianSplattingResource(art.path,LoadType.FROM_STORAGE).also{it.toGlobal()}
                                }
                                if(!owner.valid) resource.close() else {
                                    scene.gaussianResource=resource
                                    val loaded=Entity()
                                    fittedSceneRoot.addChild(loaded);scene.model=loaded;scene.gaussian=gaussian
                                    loaded.components.set(GaussianSplattingComponent().apply {
                                        gaussianSplattingResource=resource
                                    })
                                    ready=true
                                    Log.i("OpenBrushWindows","Gaussian instance=$instance file=${art.name} points=${gaussian.count}")
                                }
                            }
                        } else {
                            val loaded=Entity.loadFrom(Source.file(File(art.path)))
                            fittedSceneRoot.addChild(loaded);scene.model=loaded
                            BrushMaterialRuntime.apply(loaded,scene.brushMaterials)
                            scene.lighting=ArtworkLightingRuntime(instance).also{
                                it.initialize(owner,manipulationRoot,loaded,art.lighting,context.cacheDir)
                            }
                            ready=true
                            Log.i("OpenBrushWindows","Loaded instance=$instance file=${art.name} lightSource=${art.lighting?.source}")
                        }
                    }
                    is ArtworkWindowSource.Drawing -> {
                        val (strokes,brushes)=withContext(Dispatchers.IO) {
                            val strokes=DrawingWorkspaceFiles(context.applicationContext).read(source.id)
                            fun readAsset(path: String) = context.assets.open(path).bufferedReader().use { it.readText() }
                            val book=BrushBookCatalogCodec.decode(readAsset("brush-library/catalog.json"))
                            strokes to StudioBrushCatalog.load(book,::readAsset)
                        }
                        val drawing=Entity();fittedSceneRoot.addChild(drawing);scene.model=drawing
                        val renderer=StrokeRenderer(drawing,brushes,audioFrame={BrushAudioFrame.SILENT},onRendered={ triangles ->
                            if(owner.valid) {
                                reportDrawingInfo(strokes.size,triangles)
                                if(triangles>0) {
                                    scene.lighting=ArtworkLightingRuntime(instance).also{
                                        it.initialize(owner,manipulationRoot,drawing,null,context.cacheDir)
                                    }
                                    ready=true
                                }
                                Log.i("OpenBrushWindows","Preview instance=$instance workspace=${source.id} strokes=${strokes.size} triangles=$triangles")
                            }
                        },onError={reportError(it);Log.e("OpenBrushWindows","Preview failed $instance: $it")})
                        scene.strokeRenderer=renderer
                        renderer.pauseAnimations()
                        renderer.offer(strokes)
                    }
                }
            } catch(e:CancellationException){throw e}
            catch(e:Exception){reportError("模型渲染失败：${e.message}");Log.e("OpenBrushWindows","Load failed $instance",e)}
        },
        update=update@{content,_->
            if(!ready || viewSize==IntSize.Zero || windowSize==IntSize.Zero)return@update
            scene.lighting?.select(useFileLighting)
            scene.manipulationRoot?.enabled=shown
            val sizeKey=windowSize to viewSize
            if(scene.fittedFor==sizeKey)return@update
            val owner=scene.owner ?: return@update
            val root=scene.fittedSceneRoot ?: return@update
            val model=scene.model ?: return@update
            val local=content.localSpatialCoordinateSpace
            val corner=content.convertPosition(Vector3.ZERO,ViewCoordinateSpace.Local,local)
            val a=content.convertPosition(Vector3.ZERO,ViewCoordinateSpace.Global,local)
            val b=content.convertPosition(Vector3(windowSize.width.toFloat(),windowSize.height.toFloat(),0f),ViewCoordinateSpace.Global,local)
            val lower=Vector3(max(-abs(corner.x),min(a.x,b.x)),max(-abs(corner.y),min(a.y,b.y)),-abs(corner.z))
            val upper=Vector3(min(abs(corner.x),max(a.x,b.x)),min(abs(corner.y),max(a.y,b.y)),abs(corner.z))
            val safe=BoundingBox((lower+upper)*.5f,(upper-lower)*.5f)
            if(minOf(safe.halfExtent.x,safe.halfExtent.y,safe.halfExtent.z)<=0f) {
                reportError("无法测量组件的三维可用范围");return@update
            }
            scene.safe=safe
            if(!scene.manipulated) {
                root.transform().setPosition(Vector3.ZERO).setScaleVector(Vector3(1f))
                val base=scene.bounds(model,owner)
                val radius=base.boundingSphereRadius
                if(!radius.isFinite() || radius<=1e-7f){reportError("模型没有有效的可见几何");return@update}
                // One uniform fit, with room on all axes for direct manipulation.
                val fit=minOf(safe.halfExtent.x,safe.halfExtent.y,safe.halfExtent.z)*.6f/radius
                root.transform().setScaleVector(Vector3(fit)).setPosition(safe.center-base.center*fit)
                val final=scene.bounds(model,owner)
                // A flat brush can have zero depth. The proxy adds 1cm of selection
                // thickness without changing visible geometry or material normals.
                val grabRoot=requireNotNull(scene.manipulationRoot)
                val oldShape=scene.grabShape
                val shape=ShapeResource.createBox(Vector3(max(final.size.x,.01f),max(final.size.y,.01f),max(final.size.z,.01f)))
                val centered=shape.offsetByTranslation(final.center)
                grabRoot.components.set(CollisionComponent(listOf(centered),requireNotNull(scene.physicsMaterial)))
                grabRoot.components.set(InteractableComponent())
                scene.grabShape=centered
                if(centered!==shape)shape.close()
                oldShape?.close()
                Log.i("OpenBrushBounds","instance=$instance owner=placementOwner safe=$safe base=$base scale=$fit plannedCenter=${safe.center} actual=$final contained=${safe.contains(final)}")
                if(!safe.contains(final))reportError("模型初始适配超出组件边界，请调整组件大小")
            }
            scene.fittedFor=sizeKey
            reportReady()
        }
    )
}
