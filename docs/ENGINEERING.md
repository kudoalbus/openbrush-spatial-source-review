# Engineering handoff

## Scope and provenance

This PICO source edition is drawn from review commit `00afd2c45c44f29d43ff3f76e1130a3108ba1bf7`, whose PICO application code is at `884f635`. The standalone Gradle project compiles 44 production Kotlin files and 19 test classes. Another 34 production files show the Android and PICO integration. The build selects its JVM source sets explicitly. This edition does not produce an installable headset application.

The production algorithms follow the reviewed Kotlin sources. The surface generators and TILT parser/frame code carry additional attribution comments. The desktop test selection uses portable source tests, public brush parameters, generated synthetic geometry and in-memory images. Tests requiring private drawings, actual brush textures, the SDK or packaged artwork are omitted. The GLB fallback test supplies its small test image through the same resource callback used by the application.

## What could transfer to Unity

| Review target | Relevant behavior | Integration work |
| --- | --- | --- |
| [Arc-length edits](../pico/app/src/main/java/com/openbrush/picoviewer/domain/drawing/PartialStrokeEraser.kt) and [appearance](../pico/app/src/main/java/com/openbrush/picoviewer/domain/drawing/StrokeAppearance.kt) | Keep the GUID, control points, base color and erasures on the same stroke. A single movement can edit a segment between input samples. | Port the interval math and tests to C#. Connect commands to Open Brush history and clip each brush family's geometry without bridging a gap. |
| [Pose decoder](../pico/app/src/main/java/com/openbrush/picoviewer/domain/drawing/HandPoseDecoder.kt) and [workspace movement](../pico/app/src/main/java/com/openbrush/picoviewer/domain/drawing/WorkspaceMove.kt) | Index draws, thumb erases and joined index/middle fingers recolor. Bimanual placement is separate from saved local coordinates. | Supply tracked joints and tracking validity from Unity XR. Calibrate tolerances and retain controller input on hardware. |
| [GLB companion textures](../pico/app/src/main/java/com/openbrush/picoviewer/data/GlbResources.kt) and [legacy vertex colors](../pico/app/src/main/java/com/openbrush/picoviewer/data/LegacyVertexColors.kt) | Resolve local images first, use a known brush image as fallback and preserve the embedded geometry buffer. Convert supported legacy sRGB vertex colors once. | Attach the host's file-access API. The [GLB tests](../pico/app/src/test/java/com/openbrush/picoviewer/GlbResourceTest.kt) exercise the callback using synthetic fixtures. |
| [TILT geometry](../pico/app/src/main/java/com/openbrush/picoviewer/data/TiltGeometry.kt) and [brush profiles](../pico/app/src/main/java/com/openbrush/picoviewer/data/StudioBrushCatalog.kt) | File coordinates, pressure smoothing, orientation and source widths map into a separate preview pipeline. The studio uses the original brush GUIDs. | Use the tests to compare import/export behavior against canonical Unity brushes. The mobile geometries and shaders include approximations. |
| [Document codec](../pico/app/src/main/java/com/openbrush/picoviewer/data/DrawingDocumentCodec.kt) | JSON v3 stores stroke IDs, local right-handed meters, samples with pressure and orientation normals, erasures and color spans. | Preserve the documented field meanings when adapting the codec. Runtime world placement belongs to the host. |

## PICO integration for source review

`ui/studio/` binds hand and controller input, the palm palette and brush book, microphone capture and the native stroke renderer. `ui/viewer/` owns independent model windows and native materials. `data/repository/` and `platform/ImportActivity.kt` bridge the Android system picker, workspace storage and import preparation. `capture/` includes the PICO-side mixed-reality protocol. The source edition builds only its portable protocol code. The SDK-specific window lifecycle, platform entry points, models and material bundles would be needed for device deployment. [Studio runtime](../pico/app/src/main/java/com/openbrush/picoviewer/ui/studio/StudioRuntime.kt) · [Artwork view](../pico/app/src/main/java/com/openbrush/picoviewer/ui/viewer/components/ArtworkView.kt)

## Evidence and limits

- **Source edition, Windows / JDK 17, 2026-11-19.** The desktop suite passed 119 tests in 19 classes, with zero errors, failures or skips. It covers gesture replay, partial edits, undo/redo, document round trips, brush geometry and parameters, lighting metadata, GLB handling, Gaussian data and capture protocol logic.
- **Private application baseline.** A separate Android Kotlin compile and 164 JVM tests passed at the review base. Earlier PICO emulator evidence includes two independent TILT windows, a 422-triangle hull/membrane model and a 96-triangle shaped-section model. Those results used the full private assets and SDK.
- **Hardware and platform scope.** Headset tracking, real bimanual movement, microphone-driven brush appearance, camera passthrough and large-scene performance still need device acceptance. The suite does not measure visual equivalence with Unity. visionOS and device-to-device editing are outside this PICO edition.

## visionOS M05 companion

The native Apple implementation and independent Swift suite are now included under [visionos/](../visionos/README.md). The [visionOS handoff](VISIONOS.md) covers the selected runtime source, interval editing, swept contact, v3 interchange, brush geometry, hand-menu pose and Unity porting boundaries. Its checks use the text-only review resources and synthetic test input. PICO source, test classes and the desktop Gradle selection retain the baseline content.

## Shared review entry

Use the [developer guide](REVIEW-GUIDE.md) to compare platform source and tests. The [workspace contract](WORKSPACE-FORMAT.md) describes the existing interchange schema. [Compatibility](COMPATIBILITY.md) and the [M06 report](STRUCTURE-REVIEW.md) distinguish the platform build scopes and this round's actual checks.
