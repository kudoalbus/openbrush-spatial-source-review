# OpenBrush Spatial source review

OpenBrush Spatial explores editing part of a spatial brush stroke while preserving the original stroke, its brush identity and whole-gesture history. This independent project draws on Open Brush and the original Tilt Brush work. The PICO implementation uses Kotlin and the PICO Spatial SDK. This source edition lets Open Brush maintainers run the portable algorithms on a desktop JVM and inspect their integration with the headset.

## Start with one stroke

1. [PartialStrokeEraser](pico/app/src/main/java/com/openbrush/picoviewer/domain/drawing/PartialStrokeEraser.kt) intersects the swept fingertip with each stroke segment and expresses contact as distances along the original stroke. It handles contact between sparse samples.
2. [StrokeAppearance](pico/app/src/main/java/com/openbrush/picoviewer/domain/drawing/StrokeAppearance.kt) applies color to visible spans. Erased sections stay invisible and recoloring preserves original alpha.
3. [DrawingSession](pico/app/src/main/java/com/openbrush/picoviewer/domain/drawing/DrawingSession.kt) commits the whole gesture to history, including gestures across multiple strokes. [LocalRecolorTest](pico/app/src/test/java/com/openbrush/picoviewer/LocalRecolorTest.kt) exercises editing, erasure precedence, persistence and undo.
4. [HandPoseDecoder](pico/app/src/main/java/com/openbrush/picoviewer/domain/drawing/HandPoseDecoder.kt) converts joint samples into drawing and editing inputs. The [hand replay tests](pico/app/src/test/java/com/openbrush/picoviewer/HandPaintingFlowTest.kt) are a starting point for a Unity XR input adapter.

## Run the review suite

Use JDK 17 and Gradle 8.13. From this directory, run:

```sh
gradle clean test
```

For the editing and hand-replay examples alone:

```sh
gradle test --tests '*LocalRecolorTest' --tests '*HandPaintingFlowTest'
```

The first build resolves Kotlin 2.1.20, JSON-java and JUnit from the Gradle plugin portal and Maven Central. Results are written to `build/reports/tests/test/index.html`. No private fixture directory, PICO SDK, Android SDK or headset is required for the JVM suite. The source selection, platform dependencies and hardware evidence are described in [the engineering handoff](docs/ENGINEERING.md).

## visionOS native source review

The [visionOS directory](visionos/README.md) adds a SwiftUI, RealityKit and ARKit snapshot. The native drawing algorithms, v1–v3 codec, brush geometry and palm-menu math can be reviewed through the [English visionOS handoff](docs/VISIONOS.md). It identifies the transferable interval and contact algorithms and the Apple-specific integration. This snapshot excludes external artwork and uses generated TILT paths for its tests.

On macOS with Xcode 27.0, run `swift test --package-path visionos --scratch-path visionos/.scratch/swift-build`. The review snapshot passed 39 Swift tests and an unsigned visionOS arm64 Release build. The [visionOS README](visionos/README.md) includes the full build, simulator and resource-dependency instructions. Windows' existing 119-test JVM result remains documented in the PICO engineering handoff. See the [M05 ledger](docs/PROGRESS.md) for this review's evidence and limits.

## Source and attribution

The upstream Open Brush 2.32.0 catalog supplies the original brush GUIDs, menu order and behavior references. The Toolkit supplies TILT parser references and brush texture metadata. We thank the Open Brush community, the Icosa Foundation and the Tilt Brush authors. The included catalog records can refer to images that are deliberately outside this source edition. [Open Brush](https://github.com/icosa-foundation/open-brush) · [Open Brush Toolkit](https://github.com/icosa-foundation/open-brush-toolkit)

Project-authored source code is offered under [Apache License 2.0](LICENSE). Retain applicable upstream copyright, modification and attribution notices when redistributing. The asset inventory documents the separately managed artwork and withheld content. [Notices](pico/NOTICE) · [Asset and license inventory](docs/ASSETS.md)
