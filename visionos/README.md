# OpenBrush Spatial for visionOS source review

This directory contains the native SwiftUI, RealityKit and ARKit source from development commit `cdc7c4a724d284eddb6e64b577a63d0f80c37c98`, plus review packaging and tests. All 36 runtime Swift files match the committed development source byte for byte. The Xcode resource list and test fixture paths were adapted for this text-only snapshot. Read the [technical handoff](../docs/VISIONOS.md) for architecture, the five suggested review targets, v3 rules and Unity porting boundaries.

## Build and test

Use macOS with Xcode 27.0 and its visionOS SDK. The source uses SDK 27 APIs with runtime availability checks. The target deployment version is visionOS 2.0. The package uses Apple Foundation, simd and Compression. There are no third-party Swift package dependencies. Xcode, its SDKs and simulator runtimes are installed separately.

From the repository root, run the portable suite and the unsigned device-target compile.

```sh
swift test --package-path visionos --scratch-path visionos/.scratch/swift-build

xcodebuild -project visionos/OpenBrushSpatial.xcodeproj -scheme OpenBrushSpatial \
  -configuration Release -sdk xros -destination 'generic/platform=visionOS' \
  -derivedDataPath visionos/.scratch/DeviceBuild CODE_SIGNING_ALLOWED=NO build

python3 visionos/scripts/verify-review.py
```

The package suite passed 39 tests in this snapshot. The arm64 Release build passed with Xcode 27.0 (27A266a). A fresh visionOS 27.0 simulator also passed the document edit/save/reopen replay and the independent v3 result check. For a simulator with visionOS 27 installed, open `OpenBrushSpatial.xcodeproj` and select the shared **OpenBrushSpatial** scheme. For automated document editing, persistence and reopen checks, pass the UDID of a dedicated simulator.

```sh
xcrun simctl list devices available
bash visionos/scripts/verify-simulator.sh <simulator-UDID>
```

Evidence is written to the ignored `visionos/.scratch/` directory. This replay uses synthetic data and simulated app actions. Physical hand tracking, spatial stylus input, microphone interaction, Gaussian rendering and wearer acceptance require device testing. For device installation, configure an authorized signing team in Xcode and use a bundle identifier available to that team. Optional local overrides belong in the ignored `Signing.local.xcconfig`.

## Resources and reproducibility

The app compiles using the included JSON metadata, localization catalogs and a synthetic two-point drawing. Models, icon atlases, brush images, genuine TILT artwork and screenshots are excluded. Existing code provides a plain palette fallback and handles absent model and texture URLs. The resource-free rendering and menu presentation differ from the full private application. The bundled TILT example button reports a missing fixture. Importing a legally obtained external file remains possible through the system picker.

The original five studio models, Open Brush brush icons/atlases and any desired textures can be supplied separately after their redistribution terms are confirmed. A developer can also provide newly authored geometry and generated icons under independently documented terms. Update `scripts/generate-project.py` with the approved resources and regenerate the project. Keep the additions outside this review snapshot until clearance. The shared text descriptors and procedural geometry let reviewers evaluate the algorithms before making that choice.

To reproduce the checked-in project, run `python3 visionos/scripts/generate-project.py`. The [file manifest](REVIEW-MANIFEST.json) and its verifier record the selected files, origins and hashes. [Notices](NOTICE) and the [asset inventory](../docs/ASSETS.md) explain licensing scope.

## Dual-platform review navigation

The [developer guide](../docs/REVIEW-GUIDE.md) links the Kotlin and Swift algorithm/test entries. Shared semantics are documented in the [workspace format](../docs/WORKSPACE-FORMAT.md) and [compatibility matrix](../docs/COMPATIBILITY.md). The [M06 report](../docs/STRUCTURE-REVIEW.md#validation-and-publication-status) keeps this round's checks separate from the M05 results above.
