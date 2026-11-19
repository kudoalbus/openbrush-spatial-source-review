# OpenBrush Spatial source review

OpenBrush Spatial explores local editing and native interaction for spatial drawings on PICO and Apple Vision Pro. Open Brush maintainers can inspect the algorithms, run each platform's portable tests and evaluate integration work. The project draws on Open Brush, Tilt Brush and the Open Brush Toolkit, with thanks to their authors and contributors.

## Choose a platform

| Platform | Start here | Build and validation scope |
| --- | --- | --- |
| PICO | [Kotlin engineering handoff](docs/ENGINEERING.md), [production source](pico/app/src/main/java/com/openbrush/picoviewer), [tests](pico/app/src/test/java/com/openbrush/picoviewer) | JDK 17 and Gradle 8.13 run the explicitly selected JVM sources. Windows recorded 119 tests in 19 classes. Android/PICO integration is available for inspection. The review build produces JVM test results. |
| visionOS | [Build instructions](visionos/README.md), [Swift technical handoff](docs/VISIONOS.md), [core algorithms](visionos/Sources/DrawingCore), [tests](visionos/Tests/DrawingCoreTests) | macOS and Xcode 27.0 run Swift tests and build the native app with the visionOS SDK. See [current-round evidence](docs/STRUCTURE-REVIEW.md#validation-and-publication-status) separately from M05 results. |

For a short review of the highest-value algorithms, read the [developer review guide](docs/REVIEW-GUIDE.md). It links both implementations of partial erase, local recolor, gesture history and input replay, as well as PICO GLB texture handling and native Apple integration.

## Run the selected suite

Run commands from the repository root. Install the toolchain for the platform being evaluated.

```sh
# PICO portable JVM selection: JDK 17 and Gradle 8.13.
gradle clean test

# visionOS portable core: macOS and Xcode 27.0.
swift test --package-path visionos --scratch-path visionos/.scratch/swift-build
```

PICO's desktop build resolves Kotlin 2.1.20, JSON-java and JUnit through the Gradle plugin portal and Maven Central. Results go to `build/reports/tests/test/index.html`. The Swift package uses Apple's Foundation, simd and Compression frameworks. Native Apple build and simulator commands are in the [visionOS README](visionos/README.md#build-and-test). PICO's desktop source selection is in [build.gradle](build.gradle). The two build definitions select their own platform's files and introduce no shared runtime dependency.

## Branches and shared documentation

| Reference | Contents |
| --- | --- |
| [`main` at `1685da5`](https://github.com/kudoalbus/openbrush-spatial-source-review/tree/1685da52912fd980d269b3d25eaeb7831ef2f193) | The PICO source-review baseline. |
| [`review/visionos-source` at `b053c06`](https://github.com/kudoalbus/openbrush-spatial-source-review/tree/b053c06aa94a422b25862e6768407c53ca1a6eff) | Both platform directories and the M05 visionOS addition. PICO source and JVM build retain the main baseline. |
| [`review/m06-developer-guide`](https://github.com/kudoalbus/openbrush-spatial-source-review/tree/review/m06-developer-guide) | The M06 documentation candidate, with the same platform source and independent build entries. |

The [workspace format](docs/WORKSPACE-FORMAT.md) describes the existing v1–v3 exchange contract. [Compatibility and evidence](docs/COMPATIBILITY.md) records platform differences and verification limits. The [structure review](docs/STRUCTURE-REVIEW.md) compares organization options and explains why no source relocation is needed. Changes to the default branch or visibility remain the owner's decision.

## Source, attribution and resources

Project-authored source and review documentation are offered under [Apache License 2.0](LICENSE). Keep the [root notice](NOTICE), platform notices and relevant upstream credits when redistributing portions. The [asset inventory](docs/ASSETS.md) describes the license boundary and withheld content. Brush metadata may name images that are outside this text-only review snapshot.

The reference catalog is pinned to Open Brush 2.32.0. The upstream implementations informed brush identifiers, geometry, input and format handling. Reviewers should evaluate the portable ideas and account for Unity input, renderer, history and material integration. [Open Brush](https://github.com/icosa-foundation/open-brush), [Open Brush Toolkit](https://github.com/icosa-foundation/open-brush-toolkit)
