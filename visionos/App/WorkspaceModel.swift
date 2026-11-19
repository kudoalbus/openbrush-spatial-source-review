import SwiftUI
import Observation

@MainActor @Observable
final class WorkspaceModel {
    private(set) var document = DrawingDocument(strokes: [])
    private(set) var canUndo = false
    private(set) var canRedo = false
    var selectedStrokeID = ""
    private(set) var isFileBusy = false
    private var savedDocument = DrawingDocument(strokes: [])
    var hasUnsavedChanges: Bool { document != savedDocument }
    var status = String(localized: "New drawing")
    var isRunningAcceptance = false
    @ObservationIgnored private var pointCount = 0
    @ObservationIgnored private var didLaunchLibrary = false
    @ObservationIgnored private var history = DrawingHistory(DrawingDocument(strokes: []))
    @ObservationIgnored var renderedDocument: DrawingDocument?
    @ObservationIgnored var renderedPartCount = 0
    @ObservationIgnored var renderFailure: String?

    var saveURL: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("workspace-mac-v3.json")
    }
    func claimLibraryLaunch() -> Bool {
        guard !didLaunchLibrary else { return false }
        didLaunchLibrary = true
        return true
    }

    func restoreSavedDrawingIfNeeded(from source: URL? = nil) async {
        let url = source ?? saveURL
        guard !hasUnsavedChanges, document.strokes.isEmpty, FileManager.default.fileExists(atPath: url.path) else { return }
        await openUserDocument(url, securityScoped: false)
    }

    func loadFixture() {
        perform(String(localized: "Shared v3 fixture loaded")) {
            guard let url = Bundle.main.url(forResource: "workspace-v3", withExtension: "json") else {
                throw WorkspaceError.invalid("Bundled fixture is missing")
            }
            replace(try DrawingCodec.read(url))
        }
    }
    var availableDrawingPoints: Int {
        guard document.strokes.count < DrawingCodec.maximumStrokes else { return 0 }
        return DrawingCodec.maximumPoints - pointCount
    }
    func append(_ stroke: PaintStroke) {
        guard stroke.points.count <= availableDrawingPoints else {
            status = String(localized: "Drawing is at the v3 point or stroke limit")
            return
        }
        history.commit(DrawingDocument(strokes: document.strokes + [stroke]))
        synchronize()
        status = String(localized: "\(document.strokes.count) strokes")
    }
    func newDrawing() {
        replace(DrawingDocument(strokes: []))
        status = String(localized: "New drawing")
    }
    func replaceEditedStroke(_ edited: PaintStroke) {
        replaceEditedStrokes([edited])
    }

    func replaceEditedStrokes(_ edits: [PaintStroke]) {
        guard !edits.isEmpty else { return }
        let replacements = Dictionary(uniqueKeysWithValues: edits.map { ($0.id, $0) })
        let next = DrawingDocument(strokes: document.strokes.map { replacements[$0.id] ?? $0 })
        guard next != document else { return }
        history.commit(next)
        synchronize()
        status = String(localized: "Spatial edit applied")
    }
    func delete(strokeID: String) {
        guard document.strokes.contains(where: { $0.id == strokeID }) else { return }
        history.commit(DrawingDocument(strokes: document.strokes.filter { $0.id != strokeID }))
        synchronize()
        status = String(localized: "Stroke deleted")
    }
    @discardableResult
    func openUserDocument(_ url: URL, securityScoped: Bool) async -> Bool {
        guard !isFileBusy else { return false }
        isFileBusy = true
        defer { isFileBusy = false }
        do {
            let (drawing, imported) = try await Task.detached(priority: .userInitiated) { () -> (DrawingDocument, TiltImport?) in
                let scoped = securityScoped && url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                if url.pathExtension.lowercased() == "tilt" {
                    let imported = try TiltImporter.read(url)
                    return (imported.document, imported)
                }
                return (try DrawingCodec.read(url), nil)
            }.value
            replace(drawing)
            if let imported {
                savedDocument = DrawingDocument(strokes: [])
                status = String(localized: "Imported \(drawing.strokes.count) editable TILT strokes · approximated brushes, \(imported.unmappedBrushes) unmapped, \(imported.skipped) skipped, \(imported.clampedWidths) width limits")
            } else {
                status = String(localized: "Opened \(url.lastPathComponent)")
            }
            return true
        } catch {
            status = String(localized: "Open failed: \(error.localizedDescription)")
            return false
        }
    }

    @discardableResult
    func saveUserDocument(to destination: URL? = nil) async -> Bool {
        guard !isFileBusy else { return false }
        isFileBusy = true
        defer { isFileBusy = false }
        let snapshot = document, url = destination ?? saveURL
        do {
            try await Task.detached(priority: .userInitiated) { try DrawingCodec.save(snapshot, to: url) }.value
            savedDocument = snapshot
            status = String(localized: "Saved drawing on this device")
            return true
        } catch {
            status = String(localized: "Save failed: \(error.localizedDescription)")
            return false
        }
    }

    func encodeForExport() async throws -> Data {
        guard !isFileBusy else { throw WorkspaceError.invalid("A file operation is already running") }
        isFileBusy = true
        defer { isFileBusy = false }
        let snapshot = document
        return try await Task.detached(priority: .userInitiated) { try DrawingCodec.encode(snapshot) }.value
    }

    func recolor(strokeID: String, start: Float, end: Float, color: InkColor) {
        var next = document
        guard let index = next.strokes.firstIndex(where: { $0.id == strokeID }) else { return }
        next.strokes[index] = StrokeAppearance.recolor(next.strokes[index], in: .init(start: start, end: end), color: color)
        let changed = next != document
        history.commit(next); synchronize()
        status = changed ? String(localized: "Local color applied · erased gap preserved") : String(localized: "No visible ink changed")
    }
    func undo() { let changed = history.undo(); synchronize(); status = changed ? String(localized: "Undo complete") : String(localized: "Nothing to undo") }
    func redo() { let changed = history.redo(); synchronize(); status = changed ? String(localized: "Redo complete") : String(localized: "Nothing to redo") }
    var acceptanceSaveURL: URL {
        saveURL.deletingLastPathComponent().appendingPathComponent("acceptance-workspace-v3.json")
    }
    private func replace(_ next: DrawingDocument) {
        history = DrawingHistory(next)
        savedDocument = next
        synchronize()
    }
    private func synchronize() {
        document = history.document
        pointCount = document.strokes.reduce(0) { $0 + $1.points.count }
        canUndo = history.canUndo
        canRedo = history.canRedo
        if !document.strokes.contains(where: { $0.id == selectedStrokeID }) {
            selectedStrokeID = document.strokes.first?.id ?? ""
        }
    }
    private func perform(_ message: String, operation: () throws -> Void) {
        do { try operation(); status = message } catch { status = String(localized: "Error: \(error.localizedDescription)") }
    }

    // Simulator replay uses exactly the same actions as the visible controls. No mock persistence.
    func runAcceptance() async {
        guard !isRunningAcceptance else { return }
        isRunningAcceptance = true
        defer { isRunningAcceptance = false }
        let reportURL = saveURL.deletingLastPathComponent().appendingPathComponent("acceptance-report.json")
        var checks: [String] = []
        do {
            loadFixture()
            let original = document
            try ensure(original.strokes.count == 1, "Shared fixture loaded")
            try await rendered(original, parts: 4)
            checks.append("RealityKit created four fixture parts: blue/red, erased 0.12–0.16 m, green/blue")
            recolor(strokeID: "interop-stroke-1", start: 0.10, end: 0.18, color: .init(r: 1, g: 0.55, b: 0.05))
            let changed = document
            try ensure(changed != original, "Recolor changed the document")
            try ensure(changed.strokes[0].id == original.strokes[0].id &&
                       changed.strokes[0].points == original.strokes[0].points &&
                       changed.strokes[0].erased == original.strokes[0].erased &&
                       changed.strokes[0].color == original.strokes[0].color, "Original stroke data preserved")
            try ensure(changed.strokes[0].recolored.allSatisfy { $0.span.end <= 0.12 || $0.span.start >= 0.16 },
                       "Recolor wrote no interval in erased gap")
            try await rendered(changed, parts: 6)
            checks.append("Recolored 0.10–0.18 m orange; original ID, points, base RGBA and erased intervals unchanged")
            undo(); try ensure(document == original, "Undo restores exact original")
            try await rendered(original, parts: 4)
            checks.append("Undo restored original state and four rendered parts")
            redo(); try ensure(document == changed, "Redo restores exact edit")
            try await rendered(changed, parts: 6)
            checks.append("Redo restored edited state and six rendered parts")
            // Call the same save action, then read disk independently so a swallowed UI error cannot pass.
            await saveUserDocument(to: acceptanceSaveURL)
            try ensure(try DrawingCodec.read(acceptanceSaveURL) == changed, "Saved v3 matches edited document")
            loadFixture(); try await rendered(original, parts: 4)
            await openUserDocument(acceptanceSaveURL, securityScoped: false)
            try ensure(document == changed && !canUndo && !canRedo, "Reopen restores disk state with fresh history")
            try await rendered(changed, parts: 6)
            checks.append("Atomic save, replace in-memory state, reopen from app Documents, exact equality verified")
            try writeReport(to: reportURL, passed: true, checks: checks, error: nil)
            status = String(localized: "PASS · recolor → undo → redo → save → reopen")
        } catch {
            try? writeReport(to: reportURL, passed: false, checks: checks, error: error.localizedDescription)
            status = String(localized: "FAIL · \(error.localizedDescription)")
        }
    }
    private func rendered(_ expected: DrawingDocument, parts: Int) async throws {
        for _ in 0..<100 {
            if let renderFailure { throw WorkspaceError.invalid(renderFailure) }
            if renderedDocument == expected && renderedPartCount == parts {
                // Leave each state on screen long enough for simulator inspection/replay.
                try await Task.sleep(for: .milliseconds(450))
                return
            }
            try await Task.sleep(for: .milliseconds(100))
        }
        throw WorkspaceError.invalid("RealityKit did not display the expected \(parts) parts")
    }
    private func ensure(_ condition: Bool, _ message: String) throws {
        if !condition { throw WorkspaceError.invalid(message) }
    }
    private func writeReport(to url: URL, passed: Bool, checks: [String], error: String?) throws {
        var report: [String: Any] = [
            "passed": passed, "timestamp": ISO8601DateFormatter().string(from: Date()),
            "runtime": ProcessInfo.processInfo.operatingSystemVersionString,
            "checks": checks, "renderedParts": renderedPartCount,
            "savedFile": acceptanceSaveURL.lastPathComponent, "referenceCommit": "0af0960",
            "unverified": ["Physical Vision Pro", "Real hand tracking", "Windows/PICO read-back", "UI file importer/exporter"]
        ]
        if let error { report["error"] = error }
        try JSONSerialization.data(withJSONObject: report, options: [.prettyPrinted, .sortedKeys]).write(to: url, options: .atomic)
    }
}
