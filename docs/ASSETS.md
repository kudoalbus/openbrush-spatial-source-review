# Asset and license inventory

This source edition contains text files. The project owner approved Apache License 2.0 for project-authored source code. Artwork is managed separately. The rows below identify the actual contents and the decisions for material considered during the review.

| Class | Contents of this edition | Source, credit and release decision |
| --- | --- | --- |
| Project-authored Kotlin, review build and tests | Source files, desktop build definition, tests and documentation | Project-specific work by the owner with AI assistance. Apply the [root license](../LICENSE) to the project-authored source. |
| Open Brush brush behavior and descriptors | Adapted Kotlin frame, pressure, geometry and lighting logic; geometry/rendering JSON, brush IDs, display names, menu layout and references to texture slots | Based on the Open Brush and Tilt Brush work. Preserve the applicable upstream license and attribution. The associated art is excluded. [Pinned Open Brush source](https://github.com/icosa-foundation/open-brush/tree/6996c28dabda6ce7ff2b4728767ff99484445162) · [Upstream license copy](../pico/app/src/main/assets/brush-library/OPENBRUSH-LICENSE) |
| Toolkit parser and texture records | TILT reader adaptation and a JSON catalog with relative source paths, hashes and byte sizes for texture lookup | Retain the Google and Tilt Brush credits and Apache text. No Unity package, real TILT painting or source image is distributed here. [Toolkit source](https://github.com/icosa-foundation/open-brush-toolkit) · [Toolkit license copy](../pico/OPENBRUSH-LICENSE) |
| Palette, book carrier and three basic brush models | No model or baked image | The owner confirmed these five models were made in this project. Separate art terms can be selected if they are included in a future edition. Keep the source of the book carrier distinct from the upstream brush icons that it displays. |
| Open Brush icons, atlases and Toolkit brush images | No PNG, JPEG, atlas or embedded texture payload | These assets and the 17 texture PNGs found with machine paths in metadata are excluded. If cleared for a future art distribution, remove incidental metadata from candidate copies and revalidate all hashes and lookup behavior. Preserve attribution metadata. |
| PICO SDK, scaffold, reference model, launcher, shader output, screenshots | None of these artifacts are distributed | The box USDZ contains a PICO rights reservation. Template and generated-shader redistribution require separate clearance. The source-only selection makes those materials unnecessary for desktop tests. Android and PICO API calls in the reference code show integration points. |
| Build dependencies | Maven coordinates only | Kotlin JVM 2.1.20, JSON-java 20240303 and JUnit 4.13.2 are resolved by the build. Gradle 8.13 and JDK 17 are installed separately. No wrapper JAR, SDK or dependency binaries are included. |

## visionOS M05 addition

The M05 snapshot adds project-authored Swift, SwiftUI and RealityKit integration source and review tools under the root Apache License 2.0, consistent with the owner's approval for project-authored contributions. Upstream-derived algorithms and metadata retain their existing rights and credits. This statement gives no new permission for third-party images or artwork. [visionOS notice](../visionos/NOTICE)

| Addition | Provenance and scope |
| --- | --- |
| Runtime Swift sources and localization catalogs | Exported from committed development source at `cdc7c4a`. Local uncommitted translation edits are excluded. The manifest records hashes for all 36 runtime Swift files. |
| Brush menu, geometry, rendering and texture records | Text-only references to the pinned Open Brush and Toolkit material already described above. Geometry, rendering and texture catalogs match the existing PICO review files. The visionOS menu retains its original development ordering. No referenced image payload is included. |
| Palette target coordinates | Project-authored JSON layout data from the same committed source, included to keep the native controls inspectable. Models and baked images remain excluded. |
| JSON and TILT test input | The included JSON fixture is a synthetic two-point edit case. The Swift test helper generates five mathematical TILT paths and ZIP records in memory. It contains no external painting, thumbnail or texture. |
| Xcode build definitions and review inventory | A generated, reproducible project, a shared scheme, SHA-256 file inventory and verification scripts. Build output, signing overrides, account data, caches and device identifiers are excluded. |

The included Apple-framework calls require Xcode and the relevant SDK to compile. The app's resource list has been reduced to the included text files. The model, icon and texture dependencies and possible separately authored replacements are documented in the [visionOS README](../visionos/README.md#resources-and-reproducibility).
