# Compatibility and verification scope

The platform implementations share a documented workspace contract and reference parameters. Each selects its own runtime source, toolchain, input adapters and renderer. [Workspace format](WORKSPACE-FORMAT.md), [review guide](REVIEW-GUIDE.md)

| Area | PICO source review | visionOS source review |
| --- | --- | --- |
| Language and native integration | Kotlin with Android/PICO integration files. The desktop Gradle build explicitly selects 44 portable production files. | Swift with SwiftUI, ARKit and RealityKit integration. The Swift package selects DrawingCore. The Xcode project selects core plus app sources. |
| Portable test prerequisites | JDK 17 and Gradle 8.13. First resolution needs Gradle/Maven access. | macOS, Xcode and Apple frameworks. The review uses Xcode 27.0. |
| Native build boundary | SDK-facing code is included for inspection. This review build produces desktop JVM tests. SDK entry points, platform build packaging and cleared assets are needed for a headset APK. | Xcode 27.0 with the visionOS SDK builds the unsigned app using included text resources. Device installation needs the owner's signing setup and a compatible device. |
| Editable workspace | Reads v1–v3, writes v3, preserves stroke order, identity, control points and stored interval arrays. UTF-8 input is bounded to 32 MiB. | Reads v1–v3, writes v3. The editable document limits match the shared contract. No extra file byte cap is applied. |
| Brush lookup and rendering | Kotlin profile and geometry calculations with SDK-specific materials in the integration code. | Native Swift geometry profiles and RealityKit material approximations. Original Unity visual fidelity is unverified per brush. |
| Menu descriptors | The reviewed PICO menu includes its own extended order. | The committed visionOS menu preserves its order. Geometry, rendering and texture metadata match the PICO files. Separate menu copies prevent a cross-platform runtime dependency. |
| GLB and images | GLB resource callbacks resolve safe local companions, override brush fallbacks and append supported image data while retaining geometry. Synthetic tests exercise these paths. | Native GLB/glTF import is unsupported. USDZ uses RealityKit loading. |
| TILT | Toolkit-derived parsing and brush mapping. Included tests use synthetic data. | Container v1 and sketch v5/v6 support exists. The review's generated fixture tests stored ZIP/sketch-v5 decoding and damage rejection. Real artwork is excluded. |
| Gaussian data | Parser and integration sources are included, with portable tests. | Selected binary Gaussian PLY and 32-byte SPLAT/SPLT records are parsed. Native rendering depends on visionOS 27 APIs and remains device-unverified. |
| Input | Joint decoding, gesture gates, swept edits and whole-gesture history have portable replay tests. | Pinch capture, tracked-joint math, swept edits and palm/back menu pose have Swift tests. Native hand and stylus adapters need device acceptance. |

## Evidence by round

| Evidence | Recorded M04/M05 baseline | M06 Mac run |
| --- | --- | --- |
| PICO portable JVM | Windows recorded 119 tests in 19 classes, with 44 production files compiled. | Java and Gradle are unavailable. Source and build preservation are checked. No new JVM execution result is claimed. |
| Swift portable tests | M05 passed 39 XCTest cases, including an isolated export run. | Results are recorded in the [M06 structure report](STRUCTURE-REVIEW.md#validation-and-publication-status). |
| Native compilation and simulator | M05 passed unsigned arm64 Release, simulator Debug, installation and document acceptance. | Rebuilt targets and any execution evidence are recorded separately in the structure report. |
| Physical headsets and exchange | Source editions retain separate historical application evidence in their platform handoffs. | PICO/Vision Pro hardware, fresh device-to-device v3 exchange, system-picker interaction and wearer acceptance remain unverified. |

A Unity port must adapt vector types, tracking validity, coordinate transforms, gesture thresholds, source-stroke storage and command history. Native meshes, clipping boundaries, materials, transparency and animation must be attached to Unity's brush lifecycle. SwiftUI/RealityKit and Android/PICO window code use platform APIs. The pure interval, swept-contact and data-validation logic provides a starting point for evaluating a C# implementation. Its tests and invariants can be translated with that implementation.
