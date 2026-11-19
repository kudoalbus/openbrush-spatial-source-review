import SwiftUI
import RealityKit
import UniformTypeIdentifiers

@main
struct OpenBrushSpatialApp: App {
    @State private var model = WorkspaceModel()
    @State private var studio = SpatialStudio()
    @State private var artworks = ArtworkLibrary()

    init() {
        OpenBrushAnimationComponent.registerComponent()
        OpenBrushAnimationSystem.registerSystem()
    }

    var body: some SwiftUI.Scene {
        WindowGroup(id: "artwork-library") { WorkspaceView(model: model, studio: studio, artworks: artworks) }
            .defaultSize(width: 850, height: 560)
        WindowGroup(id: "artwork", for: UUID.self) { $id in
            if let id { ArtworkWindow(id: id, library: artworks) }
        }
        .windowStyle(.volumetric)
        .defaultSize(width: 0.58, height: 0.56, depth: 0.58, in: .meters)
        .defaultWindowPlacement { _, context in
            if let library = context.windows.first(where: { $0.id == "artwork-library" }) {
                return WindowPlacement(.trailing(library))
            }
            return WindowPlacement()
        }
        ImmersiveSpace(id: SpatialStudio.spaceID) {
            SpatialStudioView(studio: studio, model: model)
        }
        .immersionStyle(selection: .constant(.mixed), in: .mixed)
        .upperLimbVisibility(.hidden)
    }
}

private struct WorkspaceFile: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    var data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

struct WorkspaceView: View {
    @Bindable var model: WorkspaceModel
    @Bindable var studio: SpatialStudio
    let artworks: ArtworkLibrary
    @Environment(\.openWindow) private var openWindow
    @Environment(\.openImmersiveSpace) private var openImmersiveSpace
    @Environment(\.dismissWindow) private var dismissWindow
    @Environment(\.dismissImmersiveSpace) private var dismissImmersiveSpace
    @State private var openingStudio = false
    @State private var showStudioControls = false
    @State private var showDrawingOptions = false
    @State private var folderFiles: [URL] = []
    @State private var folderURL: URL?
    @State private var showingFolder = false
    @State private var readingFolder = false
    private enum Replacement { case new, fixture, saved, external(URL, folder: URL? = nil) }
    @State private var replacement: Replacement?
    @State private var confirmReplacement = false
    @State private var renderer = WorkspaceRenderer()
    @State private var confirmDelete = false
    @State private var start: Float = 0.10
    @State private var end: Float = 0.18
    @State private var ink = Color(red: 1, green: 0.55, blue: 0.05)
    private enum ImportKind { case artwork, folder, drawing, model, tilt }
    @State private var importKind = ImportKind.drawing
    @State private var importing = false
    @State private var exporting = false
    @State private var exportFile = WorkspaceFile(data: Data())
    @Environment(\.self) private var environment

    private nonisolated static let spatialFormats = ["usdz", "tilt", "ply", "splat", "splt"]

    private var importTypes: [UTType] {
        switch importKind {
        case .artwork: [.json, .usdz] + Self.spatialFormats.dropFirst().compactMap {
            UTType(filenameExtension: $0, conformingTo: .data)
        }
        case .folder: [.folder]
        case .drawing: [.json]
        case .model: [.usdz]
        case .tilt: [UTType(filenameExtension: "tilt", conformingTo: .data) ?? .data]
        }
    }

    private var selected: PaintStroke? { model.document.strokes.first { $0.id == model.selectedStrokeID } }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("Open Brush · Artwork Library")
                    .font(.largeTitle.bold())
                    .accessibilityAddTraits(.isHeader)
                Text("Open artwork in space, or start painting.").font(.title3)
                HStack(spacing: 12) {
                    Button("Open file", systemImage: "folder") { presentImporter(.artwork) }
                    Button("Import folder", systemImage: "folder.badge.plus") { presentImporter(.folder) }
                    Button("Example", systemImage: "sparkles") { openExample() }
                    Button(LocalizedStringKey(studio.isOpen ? "Return to painting" : "Start painting"), systemImage: "paintbrush.pointed") {
                        if studio.isOpen {
                            studio.libraryHidden = true
                            studio.isShowingSettings = false
                            dismissWindow(id: "artwork-library")
                        } else { Task { await openStudio() } }
                    }.buttonStyle(.borderedProminent).disabled(openingStudio)
                }
                Text("Open TILT, USDZ, and Gaussian artwork", tableName: "ArtworkControls")
                    .foregroundStyle(.secondary)
                if studio.isOpen { studioControls }
                DisclosureGroup("Drawing and files", isExpanded: $showDrawingOptions) {
                    VStack(alignment: .leading, spacing: 13) {
                        if !studio.isOpen && !model.document.strokes.isEmpty {
                            RealityView { content in
                                content.add(renderer.root)
                                renderer.update(model.document, model: model)
                            } update: { _ in
                                renderer.update(model.document, model: model)
                            }
                            .frame(height: 210)
                            .background(.black.opacity(0.28), in: RoundedRectangle(cornerRadius: 22))
                            if model.document.strokes.map(\.id) == ["interop-stroke-1"] {
                                HStack(spacing: 12) {
                                    Label("Blue 0–0.08", systemImage: "circle.fill").foregroundStyle(.blue)
                                    Label("Red 0.08–0.12", systemImage: "circle.fill").foregroundStyle(.red)
                                    Text("GAP 0.12–0.16").fontWeight(.bold)
                                    Label("Green 0.16–0.20", systemImage: "circle.fill").foregroundStyle(.green)
                                    Text("Blue → 0.32 m").foregroundStyle(.blue)
                                }.font(.caption)
                                Text("Reference fixture legend · edit selection defaults to 0.10–0.18 m")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        HStack {
                            Picker("Stroke", selection: $model.selectedStrokeID) {
                                Text("None").tag("")
                                ForEach(Array(model.document.strokes.enumerated()), id: \.element.id) { index, stroke in
                                    Text("Stroke \(index + 1) · \(SpatialBrush(rawValue: stroke.brush)?.localizedLabel ?? stroke.brush)").tag(stroke.id)
                                }
                            }
                            ColorPicker("Local RGB", selection: $ink, supportsOpacity: false)
                            Button("Delete stroke", role: .destructive) { confirmDelete = true }.disabled(selected == nil)
                                .confirmationDialog("Delete the selected stroke?", isPresented: $confirmDelete) {
                                    Button("Delete stroke", role: .destructive) {
                                        studio.arm(false, model: model)
                                        model.delete(strokeID: model.selectedStrokeID)
                                    }
                                }
                        }
                        HStack(spacing: 18) {
                            VStack(alignment: .leading) {
                                Text("Start \(start, specifier: "%.3f") m").monospacedDigit()
                                Slider(value: $start, in: 0...max(0.001, selected?.length ?? 0.32))
                                    .accessibilityIdentifier("rangeStart")
                            }
                            VStack(alignment: .leading) {
                                Text("End \(end, specifier: "%.3f") m").monospacedDigit()
                                Slider(value: $end, in: 0...max(0.001, selected?.length ?? 0.32))
                                    .accessibilityIdentifier("rangeEnd")
                            }
                            Button("Apply color") {
                                studio.arm(false, model: model)
                                let c = ink.resolve(in: environment)
                                model.recolor(strokeID: model.selectedStrokeID, start: min(start, end), end: max(start, end),
                                              color: InkColor(r: c.red, g: c.green, b: c.blue))
                            }.buttonStyle(.borderedProminent).disabled(selected == nil || start == end)
                                .accessibilityIdentifier("applyColor")
                        }
                        HStack {
                            Button("Undo", systemImage: "arrow.uturn.backward") { studio.arm(false, model: model); model.undo() }.disabled(!model.canUndo).accessibilityIdentifier("undo")
                            Button("Redo", systemImage: "arrow.uturn.forward") { studio.arm(false, model: model); model.redo() }.disabled(!model.canRedo).accessibilityIdentifier("redo")
                            Spacer()
                            Button("Save v3") {
                                studio.arm(false, model: model)
                                Task { await model.saveUserDocument() }
                            }.accessibilityIdentifier("save")
                            Button("Reopen saved") { requestReplacement(.saved) }.accessibilityIdentifier("reopen")
                        }
                        HStack {
                            Button("New drawing") { requestReplacement(.new) }.accessibilityIdentifier("newDrawing")
                            #if DEBUG
                            Button("Load fixture") { requestReplacement(.fixture) }.accessibilityIdentifier("loadFixture")
                            #endif
                            Button("Open JSON…") {
                                studio.arm(false, model: model)
                                importKind = .drawing
                                importing = true
                            }
                            Button("Export v3…") {
                                studio.arm(false, model: model)
                                Task {
                                    do {
                                        exportFile = WorkspaceFile(data: try await model.encodeForExport())
                                        exporting = true
                                    } catch { model.status = String(localized: "Export failed: \(error.localizedDescription)") }
                                }
                            }
                            Spacer()
                            #if DEBUG
                            if !studio.isOpen {
                                Button("Run acceptance") { Task { await model.runAcceptance() } }.accessibilityIdentifier("acceptance")
                            }
                            #endif
                        }.font(.callout)
                        HStack {
                            Button("Import USDZ…") {
                                studio.arm(false, model: model)
                                importKind = .model
                                importing = true
                            }
                            Button("Import TILT…") {
                                studio.arm(false, model: model)
                                importKind = .tilt
                                importing = true
                            }
                            if let name = studio.referenceName {
                                Text(name).font(.caption).lineLimit(1)
                                Button("Remove reference") { studio.removeReference() }
                            }
                        }.font(.callout)
                    }.padding(.top, 10)
                }
                if studio.isLoadingModel { ProgressView("Loading USDZ model…") }
                if artworks.isLoading { ProgressView("Loading artwork…") }
                if readingFolder { ProgressView("Reading folder…") }
                Text("\(artworks.items.count) artwork windows").font(.caption).foregroundStyle(.secondary)
                if model.isFileBusy { ProgressView("Working with drawing file…") }
                if model.hasUnsavedChanges { Label("Unsaved changes", systemImage: "circle.fill").font(.caption) }
                Text(model.status).font(.callout.monospaced()).accessibilityIdentifier("status")
                    .frame(maxWidth: .infinity, alignment: .leading)
                if model.hasUnsavedChanges || studio.isOpen {
                    Text("Save stores one drawing on this device. Export creates a separate copy.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .padding(28)
        }
        .disabled(model.isRunningAcceptance || model.isFileBusy || studio.isEditing || studio.isLoadingModel ||
                  artworks.isLoading || readingFolder)
        .onDisappear { studio.isShowingSettings = false }
        .confirmationDialog("Replace this drawing with unsaved changes?", isPresented: $confirmReplacement) {
            Button("Replace drawing", role: .destructive) {
                if let replacement { executeReplacement(replacement) }
                replacement = nil
            }
            Button("Cancel", role: .cancel) { replacement = nil }
        } message: {
            Text("Save or export first if you want to keep the current drawing.")
        }
        .onChange(of: model.selectedStrokeID) { _, _ in
            start = 0; end = selected?.length ?? 0
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: importTypes) { result in
            switch result {
            case .success(let url):
                switch importKind {
                case .folder: Task { await readFolder(url) }
                case .model: Task { await studio.loadReference(url, securityScoped: true, model: model) }
                case .tilt, .drawing: requestReplacement(.external(url))
                case .artwork: openArtwork(url)
                }
            case .failure(let error): model.status = String(localized: "Open failed: \(error.localizedDescription)")
            }
        }
        .fileExporter(isPresented: $exporting, document: exportFile, contentType: .json,
                      defaultFilename: String(localized: "OpenBrush Drawing")) { result in
            switch result {
            case .success: model.status = String(localized: "Exported v3 workspace")
            case .failure(let error): model.status = String(localized: "Export failed: \(error.localizedDescription)")
            }
        }
        .sheet(isPresented: $showingFolder) {
            VStack(alignment: .leading) {
                HStack {
                    Text("Choose artwork").font(.title2)
                    Spacer()
                    Button("Cancel") { showingFolder = false }
                }.padding()
                List(folderFiles, id: \.self) { url in
                    Button(url.lastPathComponent) {
                        showingFolder = false
                        openArtwork(url, folder: folderURL)
                    }
                }
            }.frame(minWidth: 540, minHeight: 420)
        }
        .task {
            guard model.claimLibraryLaunch() else { return }
            let args = ProcessInfo.processInfo.arguments
            if ["--acceptance", "--fixture", "--reopen-saved", "--reopen-acceptance"].contains(where: args.contains) {
                showDrawingOptions = true
            }
            if args.contains("--spatial-replay") { await openStudio(hideLibrary: false) }
            else if args.contains("--studio-preview") { await openStudio() }
            else if args.contains("--artwork-preview") { openExample() }
            else if args.contains("--acceptance") { await model.runAcceptance() }
            else if args.contains("--reopen-saved") {
                await model.openUserDocument(model.saveURL, securityScoped: false)
            } else if args.contains("--reopen-acceptance") {
                await model.openUserDocument(model.acceptanceSaveURL, securityScoped: false)
            }
            else if args.contains("--fixture") { model.loadFixture() }
            else { await model.restoreSavedDrawingIfNeeded() }
        }
    }

    private var studioControls: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Button("Save & return", systemImage: "square.and.arrow.down") {
                    Task { if await studio.saveAndLeave(model) { await dismissImmersiveSpace() } }
                }.disabled(studio.isSavingAndLeaving)
                if !studio.poseMode {
                    Toggle("Enable \(studio.tool.localizedLabel)", isOn: Binding(get: { studio.armed },
                           set: { studio.arm($0, model: model) })).toggleStyle(.button).disabled(!studio.canArm)
                }
                Button("New drawing", systemImage: "doc.badge.plus") { requestReplacement(.new) }
                Button(LocalizedStringKey(studio.paletteVisible ? "Hide palette" : "Show palette")) {
                    studio.arm(false, model: model)
                    studio.paletteVisible.toggle()
                }.disabled(!studio.isOpen)
                Spacer()
            }
            Text(studio.inputStatus).font(.callout).foregroundStyle(.secondary)
            if studio.isOpen && !studio.poseMode {
                Picker("Tool", selection: Binding(get: { studio.tool },
                                                 set: { studio.chooseTool($0, model: model) })) {
                    ForEach(SpatialTool.allCases) { Text($0.localizedLabel).tag($0) }
                }.pickerStyle(.segmented)
                if studio.isEditing { ProgressView("Applying spatial edit…") }
            }
            DisclosureGroup("Brush and placement", isExpanded: $showStudioControls) {
                Toggle("PICO hand gestures", isOn: Binding(get: { studio.poseMode },
                       set: { studio.changeInputMode($0, model: model) }))
                let audio = OpenBrushAudioInput.shared
                Toggle(String(localized: "Sound-reactive brushes", table: "BrushAudio"),
                       isOn: Binding(get: { audio.isEnabled }, set: { audio.setEnabled($0) }))
                    .disabled(!studio.isOpen || audio.state == .requesting)
                if audio.state == .denied {
                    Text(String(localized: "Microphone access is off", table: "BrushAudio")).font(.caption).foregroundStyle(.secondary)
                } else if audio.state == .unavailable {
                    Text(String(localized: "Microphone unavailable", table: "BrushAudio")).font(.caption).foregroundStyle(.secondary)
                }
                HStack {
                    Picker("Brush", selection: $studio.style.brush) {
                        ForEach(SpatialBrush.allCases + OpenBrushCatalog.bundled.enabledIDs.compactMap(SpatialBrush.init(rawValue:))
                            .sorted { $0.localizedLabel.localizedStandardCompare($1.localizedLabel) == .orderedAscending }) {
                            Text($0.localizedLabel).tag($0)
                        }
                    }
                    ColorPicker("Brush color", selection: Binding(
                        get: { Color(red: Double(studio.style.color.r), green: Double(studio.style.color.g),
                                     blue: Double(studio.style.color.b)) },
                        set: {
                            let c = $0.resolve(in: environment)
                            studio.setColor(InkColor(r: c.red, g: c.green, b: c.blue))
                        }), supportsOpacity: false)
                    Toggle("Left hand", isOn: $studio.leftHand)
                }
                HStack {
                    Text("Width \(studio.style.width * 1000, specifier: "%.0f") mm")
                    Slider(value: $studio.style.width, in: 0.001...0.150)
                }
                HStack {
                    Button("Left") { studio.move(by: [-0.10, 0, 0], model: model) }
                    Button("Right") { studio.move(by: [0.10, 0, 0], model: model) }
                    Button("Up") { studio.move(by: [0, 0.10, 0], model: model) }
                    Button("Down") { studio.move(by: [0, -0.10, 0], model: model) }
                    Button("Nearer") { studio.move(by: [0, 0, 0.10], model: model) }
                    Button("Farther") { studio.move(by: [0, 0, -0.10], model: model) }
                    Button("Rotate") { studio.move(by: .zero, yaw: .pi / 12, model: model) }
                }.font(.caption).disabled(!studio.isOpen)
            }
        }
    }

    private func requestReplacement(_ action: Replacement) {
        studio.arm(false, model: model)
        if model.hasUnsavedChanges {
            replacement = action
            confirmReplacement = true
        } else {
            executeReplacement(action)
        }
    }

    private func executeReplacement(_ action: Replacement) {
        switch action {
        case .new: model.newDrawing(); studio.removeReference()
        case .fixture: model.loadFixture(); showDrawingOptions = true
        case .saved: Task { await model.openUserDocument(model.saveURL, securityScoped: false) }
        case .external(let url, let folder):
            Task {
                let scoped = folder?.startAccessingSecurityScopedResource() == true
                defer { if scoped { folder?.stopAccessingSecurityScopedResource() } }
                await model.openUserDocument(url, securityScoped: folder == nil)
            }
        }
    }

    private func presentImporter(_ kind: ImportKind) {
        studio.arm(false, model: model)
        importKind = kind
        importing = true
    }

    private func openExample() {
        guard let url = Bundle.main.url(forResource: "sample", withExtension: "tilt") else {
            model.status = String(localized: "Bundled fixture is missing")
            return
        }
        openArtwork(url, title: String(localized: "Open Brush example"))
    }

    private func openArtwork(_ url: URL, folder: URL? = nil, title: String? = nil) {
        if Self.spatialFormats.contains(url.pathExtension.lowercased()) {
            Task {
                do {
                    let id = try await artworks.open(url, from: folder, title: title)
                    openWindow(id: "artwork", value: id)
                    model.status = String(localized: "Opened \(title ?? url.lastPathComponent)")
                } catch {
                    model.status = String(localized: "Open failed: \(error.localizedDescription)")
                }
            }
        } else { requestReplacement(.external(url, folder: folder)) }
    }

    private func readFolder(_ url: URL) async {
        readingFolder = true
        defer { readingFolder = false }
        do {
            let files = try await Task.detached(priority: .userInitiated) {
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                let candidates = try FileManager.default.contentsOfDirectory(at: url, includingPropertiesForKeys: [.isRegularFileKey],
                                                                             options: [.skipsHiddenFiles])
                return candidates.filter { candidate in
                    (["json"] + Self.spatialFormats).contains(candidate.pathExtension.lowercased()) &&
                        (try? candidate.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true
                }.sorted { $0.lastPathComponent.localizedStandardCompare($1.lastPathComponent) == .orderedAscending }
            }.value
            guard !files.isEmpty else {
                model.status = String(localized: "No supported artwork in this folder")
                return
            }
            folderURL = url
            folderFiles = files
            showingFolder = true
        } catch {
            model.status = String(localized: "Could not read folder: \(error.localizedDescription)")
        }
    }

    private func openStudio(hideLibrary: Bool = true) async {
        guard !openingStudio else { return }
        openingStudio = true
        defer { openingStudio = false }
        switch await openImmersiveSpace(id: SpatialStudio.spaceID) {
        case .opened:
            if hideLibrary {
                studio.libraryHidden = true
                dismissWindow(id: "artwork-library")
            }
        case .userCancelled: model.status = String(localized: "Opening studio cancelled")
        case .error: model.status = String(localized: "Could not open studio")
        @unknown default: model.status = String(localized: "Unexpected studio result")
        }
    }
}
