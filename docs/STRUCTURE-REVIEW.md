# M06 structure and publication review

## Baseline and findings

The repository was confirmed private. Remote `main` was `1685da52912fd980d269b3d25eaeb7831ef2f193`, and remote `review/visionos-source` was `b053c06aa94a422b25862e6768407c53ca1a6eff`. M06 work is isolated on `review/m06-developer-guide`, based on the latter. No branch merge is part of this round.

| Check | Finding |
| --- | --- |
| Platform directories | `main` has the PICO edition. The visionOS review branch already contains both `pico/` and `visionos/`, with separate runtime source and test directories. |
| Source preservation | The visionOS branch's entire PICO tree, root Gradle/settings files and root license match `1685da5`. Its Swift and Kotlin source baselines are retained by the M06 documentation candidate. |
| Build inputs | Gradle explicitly selects Kotlin files under `pico/`. Swift Package selects its core and tests under `visionos/`. The Xcode resource references point within `visionos/`. Neither selects the other platform's business code. |
| Outputs and resources | JVM output is under root `build/`; documented Apple output is under ignored `visionos/.scratch/`. The three brush parameter/texture catalogs are identical copies. The menu order has a platform-specific difference and is kept local to each platform. Resource filenames are scoped to separate builds. |
| README links | Existing relative README targets resolve in their own baseline branches. A reader opening main has no visionOS section. This is a navigation gap caused by branch entry choice. M06 adds a branch table and one developer guide. |
| Shared documentation | The M05 visionOS handoff repeated v3 rules and described older PICO erase normalization. The reviewed PICO codec preserves stored arrays. M06 centralizes the current contract and corrects the handoff without changing the codecs. |
| Licensing | Root Apache text and all three bundled license copies are byte-identical. Platform notices provide distinct component credits. M06 adds a root notice that points to both and retains the asset boundaries. |

## Current candidate structure

```text
openbrush-spatial-source-review/
├── README.md                    # platform and branch entry
├── LICENSE
├── NOTICE                       # common attribution entry
├── build.gradle                 # PICO desktop JVM review selection
├── settings.gradle
├── docs/
│   ├── REVIEW-GUIDE.md          # suggested source/test reading order
│   ├── WORKSPACE-FORMAT.md      # existing v1–v3 contract
│   ├── COMPATIBILITY.md         # platform differences and evidence
│   ├── STRUCTURE-REVIEW.md      # M06 findings and options
│   ├── ENGINEERING.md           # existing PICO handoff
│   ├── VISIONOS.md              # existing native Apple handoff
│   ├── ASSETS.md
│   └── PROGRESS.md
├── pico/
│   ├── NOTICE / OPENBRUSH-LICENSE
│   └── app/src/                 # 78 production Kotlin files, 19 test classes
└── visionos/
    ├── README.md / NOTICE
    ├── Package.swift
    ├── OpenBrushSpatial.xcodeproj/
    ├── App/                    # native app and localization
    ├── Sources/DrawingCore/
    ├── Tests/DrawingCoreTests/
    ├── ReviewResources/         # text metadata and synthetic JSON
    └── scripts/
```

The existing PICO guide remains `docs/ENGINEERING.md`. Keeping that entry and the root JVM build avoids source relocation and leaves the entire protected PICO tree intact. Shared protocol and navigation documents introduce no runtime dependency.

## Organization options

| Option | Files and repository work | Build impact | Maintenance and review experience | Risk and rollback |
| --- | --- | --- | --- | --- |
| A. Keep platform branches with explicit entries | Update root README and add branch-aware guide/protocol/compatibility pages on a documentation branch. Backport selected entry text to main only if the owner chooses. | Existing build paths stay intact. Each platform can be evaluated from its documented branch. | Lowest immediate change. Reviewers need the branch table; shared documents can drift if separately maintained. | Documentation drift is the main risk. Revert or stop using the documentation branch. |
| B. Independent directories under a common default entry | The directory layout already exists on `review/visionos-source`. The owner can later move the main entry forward through an ordinary Git merge after review. M06 adds docs and a root notice; no file move is required. | Each platform retains the current build command and its own SDK prerequisites. | A single checkout exposes both implementations and shared protocol docs. Platform-specific code and tests continue independently. | An owner-approved main update may encounter future document conflicts. Resolve them through ordinary review, or revert the integration commit. Preserve the baseline platform refs. |
| C. Separate review repositories with a common index | Create a visionOS review repository, copy only audited source and license material, and maintain a branch/commit index plus synchronized protocol docs. Repository creation needs a separate owner decision. | Each repository needs its own preserved build paths and verification. | More hosting, protocol synchronization and license/inventory maintenance. Reviewers switch repositories to compare algorithms. | Duplicated docs and differing snapshot versions. Keep old repos private and restore the previous index if the split is abandoned. |

**Recommendation.** Retain the existing side-by-side layout from B and deliver the M06 guide on its own branch now. The owner can share that branch for review immediately. Updating the default main entry is optional and needs the owner's approval. No technical build dependency requires a Git merge. This gives reviewers one source/test index while preserving the platform snapshots and current build paths.

## Validation and publication status

| Check | M06 evidence |
| --- | --- |
| Swift XCTest | Rerun on Mac with Xcode 27.0 (27A266a). All 39 tests passed, zero failures and skips. |
| Native Release compile | Unsigned arm64 build with the visionOS SDK passed. |
| Simulator Debug compile | arm64 build for the visionOS simulator passed. No new simulator app execution or hardware acceptance was performed in M06. |
| PICO JVM | JDK and Gradle are unavailable on this Mac. The 119-test Windows result is retained as prior evidence. The current run checks unchanged source/build bytes. |
| Baseline and inventory | The PICO tree and root JVM build/license match `1685da5`. Swift/Kotlin production and test sources, build definitions and scripts match `b053c06`. The existing verifier passes for 72 visionOS files and 36 original runtime Swift files. |
| Documentation | All ten Markdown documents were checked for relative file targets and heading anchors. README build commands were compared with the build definitions and the commands rerun on this Mac. |
| Included files and history | The candidate has 194 UTF-8 text files. Selected and reachable historical blobs are checked for binary payloads, excluded media/SDK/signing extensions and literal personal workspace paths. |
| Credentials | Gitleaks 8.30.1 scans the candidate tree and its reachable review history. Results are zero detections. The source-review history contains the PICO root, two M05 commits and the M06 documentation addition. |

The prior Windows 119-test result and M05 runtime/simulator results remain dated evidence in their platform records. Device behavior, the system picker, a fresh cross-device exchange, Unity visual parity and performance need separate validation. No Swift/Kotlin business, test or build code changed in M06.

The intended publication unit is this documentation candidate and its reachable review history. The separate private development repository and its earlier asset-bearing history are outside that unit. A visibility change requires owner authorization after reviewing the final candidate, notices, repository metadata and any separately published artifacts.

The source-only candidate is technically ready for the owner's publication decision after these checks. The excluded art, SDK packaging and original private development history remain outside this candidate. Restoring any of those materials requires its own provenance and scan review. The default main still exposes the PICO baseline, so the owner should choose the intended review URL/default entry before public sharing. No included-file licensing blocker was identified by this review. The branch remains private, pending that decision.
