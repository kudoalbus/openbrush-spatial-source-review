# Workspace interchange format

This document describes the existing PICO and visionOS review codecs. It makes no schema change. JSON v3 is the current editable interchange format. Canonical implementations are the [PICO codec](../pico/app/src/main/java/com/openbrush/picoviewer/data/DrawingDocumentCodec.kt) and [Swift codec](../visionos/Sources/DrawingCore/DrawingCodec.swift), with their corresponding document types.

## Data representation

| Field | Contract |
| --- | --- |
| Root | `version` is 1, 2 or 3 on read. Both writers emit 3. `units` is `meters_rh`. `strokes` is ordered. |
| Stroke identity | `id` is nonblank and unique within the document. `brush` retains the original brush identifier, including an Open Brush GUID where used. The codec can retain an identifier whose renderer is unavailable. |
| Style | `width` is finite and in 0.001–0.15 m. `color` is finite RGBA in 0–1. Base style survives local edits. |
| Control points | V1 stores `[x,y,z,pressure,timeMs]`. V2/v3 stores `[x,y,z,pressure,timeMs,nx,ny,nz]`. Positions are finite, document-local, right-handed meters. Pressure is 0–1. Time is a nonnegative signed 64-bit integer. V1 gets normal `(0,0,1)`. Other normals are finite and have length greater than 0.01. |
| Erasure | `erased` stores `[start,end]` intervals in cumulative meters along the original centerline. End may exceed computed length by at most 0.0001 m. Intervals have finite nonnegative bounds and end ≥ start. Both reviewed codecs preserve the stored arrays. Rendering derives normalized visible spans. |
| Local color | V3 requires `recolored`, which can be empty. Each entry is `[start,end,r,g,b,a]`, sorted by start, nonoverlapping, longer than 1e-6 m and bounded by stroke length. Its alpha equals original base alpha. V1/v2 supply no local-color overrides. |

Each stroke has 2–4,096 points and finite arc length greater than 1e-6 m. Shared editable limits are 2,000 strokes, 200,000 total points and at most 20,000 intervals of each kind per stroke. PICO's byte-oriented IO and decoder bound UTF-8 input to 32 MiB. Swift adds no separate byte cap. The exchange must satisfy the receiving implementation's limits.

Keep integer timestamps as integers throughout a round trip. Swift decodes directly into Int64 and PICO uses JSON-java's long access. Encoding an integer through a floating-point intermediary can lose values above 2^53. Coordinate precision follows the existing float-based models. Empty drawings are allowed, while each present stroke must satisfy the point and length constraints.

## Editing and geometry

Local erase and recolor retain the original stroke ID, points, width, brush and base color. Recolor intersects the requested interval with visible spans and preserves original alpha. Existing hidden color under erasure can remain stored, while the erased section remains invisible. A visible/color fragment is derived geometry with the source stroke identity.

Both history implementations group an operation by a completed gesture, retain up to 100 history entries and preserve redo for a no-op. History state belongs to the application session. Save/open creates a document boundary. Display placement, centering, scale and room transforms stay outside the local-point schema.

The [synthetic v3 fixture](../visionos/ReviewResources/workspace-v3.json) spans 0–0.32 m with a 0.12–0.16 m erased gap. Recoloring 0.10–0.18 m orange affects only 0.10–0.12 and 0.16–0.18 m. The [Swift tests](../visionos/Tests/DrawingCoreTests/DrawingCoreTests.swift) and [PICO local recolor tests](../pico/app/src/test/java/com/openbrush/picoviewer/LocalRecolorTest.kt) exercise these invariants independently.

## Exchange acceptance

1. Export a synthetic or user-owned v3 document on the sending platform. Keep the original file and source version.
2. Open it with the receiving codec, inspect retained identities, control points, order, visible colors and gaps, then save it as v3.
3. Compare the returned document using `python3 visionos/scripts/compare-v3.py original.json returned.json`. This compares IDs and structure exactly, integer timestamps exactly and float values with relative/absolute tolerance 1e-6.
4. Record the actual receiving platform, build and result. A file comparison and a local unit test establish their own evidence. A headset exchange needs its own run record.

Brush identity interchange does not guarantee appearance equivalence between the native renderers. Format-specific model and TILT imports have additional conversion behavior, described in [compatibility](COMPATIBILITY.md).
