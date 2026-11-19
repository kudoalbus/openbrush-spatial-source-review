import ARKit
import Observation
import QuartzCore
import RealityKit
import SwiftUI

@MainActor @Observable
final class SpatialStudio {
    static let spaceID = "spatial-painting"
    let renderer = SpatialStudioRenderer()
    let palette = SpatialPalette()
    let book = PicoBrushBook()
    let hud = Entity()
    let menuRoot = Entity()
    private let palmLayer = Entity()
    private let backLayer = Entity()
    private(set) var poseMode = false
    private(set) var pickerMode = true
    private(set) var pickerColor = PicoColor(.init(r: 8 / 255, g: 121 / 255, b: 203 / 255))
    private(set) var favorites: [PicoColor?] = Array(repeating: nil, count: 5)
    private(set) var isSavingAndLeaving = false
    private(set) var saveFeedback: String?
    var hudRequest: PicoHUDAction?
    @ObservationIgnored private var poseGate = PicoHandGate()
    @ObservationIgnored private var touchLatched = false
    @ObservationIgnored private var heldColor: PicoPickerLayout.Input?
    @ObservationIgnored private var lastMenuPose: TimeInterval?
    @ObservationIgnored private var lastMenuApproach: TimeInterval?
    @ObservationIgnored private var menuFace: PalmMenuPose.Face?
    @ObservationIgnored private var stylusCapture = SpatialStrokeCapture()
    @ObservationIgnored private var stylusTool: SpatialTool?
    var stylusStatus: String?
    @ObservationIgnored private var headOrientation = simd_quatf()
    @ObservationIgnored private var headPosition: SIMD3<Float> = .zero
    @ObservationIgnored private var hasHeadPose = false
    @ObservationIgnored private var workspacePlaced = false
    @ObservationIgnored private var saveTask: Task<Void, Never>?

    init() {
        let defaults = UserDefaults.standard
        poseMode = defaults.bool(forKey: "studioLegacyPoseMode")
        pickerMode = defaults.object(forKey: "picoPickerMode") as? Bool ?? true
        if let data = defaults.data(forKey: "picoPickerColor"),
           let value = try? JSONDecoder().decode(PicoColor.self, from: data) { pickerColor = value }
        if let data = defaults.data(forKey: "picoFavoriteColors"),
           let values = try? JSONDecoder().decode([PicoColor?].self, from: data), values.count == 5 { favorites = values }
        style.color = pickerColor.ink
        menuRoot.addChild(palmLayer)
        menuRoot.addChild(backLayer)
        palmLayer.addChild(palette.root)
        palmLayer.addChild(hud)
        backLayer.addChild(book.root)
        palette.root.position = [0, 0.043, -0.025]
        hud.position = [0, 0.055, -0.202]
        hud.orientation = simd_quatf(angle: -.pi / 4, axis: [1, 0, 0])
        book.root.position = [0, -0.040, -0.025]
        book.root.orientation = simd_quatf(angle: .pi, axis: [0, 0, 1])
        menuRoot.isEnabled = false
    }
    var paletteVisible = true
    var libraryHidden = false
    var isShowingSettings = false
    var isSceneActive = true
    private(set) var tool: SpatialTool = .draw
    private(set) var isEditing = false
    private(set) var isLoadingModel = false
    private(set) var referenceName: String?
    var style = SpatialBrushStyle()
    var leftHand = false
    private(set) var armed = false
    private(set) var canArm = false
    private(set) var isOpen = false
    private(set) var inputStatus = String(localized: "Open the studio to start hand tracking")
    @ObservationIgnored private var capture = SpatialStrokeCapture()
    @ObservationIgnored private var pinch = HandPinch()
    @ObservationIgnored private var lastHandUpdate: TimeInterval?
    @ObservationIgnored private var pendingStroke: PaintStroke?
    @ObservationIgnored private var pendingAcrossStrokes = false
    @ObservationIgnored private var activeAcrossStrokes = false
    @ObservationIgnored private var pendingCommit: Task<Void, Never>?
    @ObservationIgnored private var editTask: Task<Void, Never>?

    func enter() {
        isOpen = true
        canArm = false
        renderer.root.position = [0, 1.15, -0.75]
        renderer.root.orientation = simd_quatf()
        workspacePlaced = false
        hasHeadPose = false
        headPosition = .zero
        headOrientation = simd_quatf()
        hideHandMenus()
        touchLatched = false
        heldColor = nil
        book.visible = true
        saveFeedback = nil
        hudRequest = nil
        #if targetEnvironment(simulator)
        // Fixed room-space pose for screenshots and input replay.
        menuRoot.position = [-0.30, 1.31, -0.70]
        menuRoot.orientation = simd_quatf(angle: .pi / 3, axis: [1, 0, 0])
        menuRoot.isEnabled = true
        let showBack = ProcessInfo.processInfo.arguments.contains("--brush-book-preview")
        palmLayer.isEnabled = !showBack
        backLayer.isEnabled = showBack
        if showBack { menuRoot.orientation *= simd_quatf(angle: .pi, axis: [0, 0, 1]) }
        #endif
    }

    func leave(_ model: WorkspaceModel) {
        OpenBrushAudioInput.shared.stop()
        hideHandMenus()
        arm(false, model: model)
        saveTask?.cancel()
        isOpen = false
        if model.hasUnsavedChanges || isEditing {
            Task {
                await editTask?.value
                while model.isFileBusy {
                    do { try await Task.sleep(for: .milliseconds(80)) } catch { return }
                }
                if model.hasUnsavedChanges { await model.saveUserDocument() }
            }
        }
        canArm = false
        inputStatus = String(localized: "Studio closed")
    }

    func arm(_ enabled: Bool, model: WorkspaceModel) {
        if !enabled { endStylusStroke(model) }
        interrupt(model)
        armed = enabled && isOpen && canArm && !isEditing
    }

    func move(by offset: SIMD3<Float>, yaw: Float = 0, model: WorkspaceModel) {
        arm(false, model: model)
        renderer.root.position += offset
        renderer.root.orientation *= simd_quatf(angle: yaw, axis: [0, 1, 0])
    }

    @discardableResult
    func loadReference(_ url: URL, securityScoped: Bool, model: WorkspaceModel) async -> Bool {
        guard !isLoadingModel else { return false }
        arm(false, model: model)
        isLoadingModel = true
        defer { isLoadingModel = false }
        do {
            let reference = try await StudioReferenceModel.load(url, securityScoped: securityScoped)
            renderer.referenceRoot.children.removeAll()
            renderer.referenceRoot.addChild(reference)
            referenceName = url.lastPathComponent
            model.status = String(localized: "Loaded USDZ reference · visible in the studio")
            return true
        } catch {
            model.status = String(localized: "USDZ import failed: \(error.localizedDescription)")
            return false
        }
    }

    func removeReference() {
        renderer.referenceRoot.children.removeAll()
        referenceName = nil
    }

    func chooseTool(_ next: SpatialTool, model: WorkspaceModel) {
        interrupt(model)
        tool = next
        if !poseMode { armed = canArm && isOpen }
        updateHandHint()
    }

    func changeInputMode(_ pose: Bool, model: WorkspaceModel) {
        arm(false, model: model)
        poseMode = pose
        tool = .draw
        if !pose { armed = canArm && isOpen }
        UserDefaults.standard.set(pose, forKey: "studioLegacyPoseMode")
        updateHandHint()
    }

    private func updateHandHint() {
        guard canArm else { return }
        let hint = poseMode ? String(localized: "Open your hand · index draws · thumb erases · two fingers recolor") :
            String(localized: "Select a tool · pinch to begin · release to finish", table: "StudioInput")
        if inputStatus != hint { inputStatus = hint }
    }

    private func stopForMenu(_ model: WorkspaceModel) {
        endStylusStroke(model)
        let resume = armed
        pendingCommit?.cancel()
        pendingCommit = nil
        pendingStroke = nil
        _ = capture.interrupt()
        arm(resume, model: model)
    }

    func usePalette(_ action: PaletteAction, model: WorkspaceModel) {
        let wasArmed = armed
        stopForMenu(model)
        if let color = action.swatch { setColor(color) }
        switch action {
        case .arm: arm(!wasArmed, model: model)
        case .tube: style.brush = .tube
        case .flat: style.brush = .flat
        case .fan: style.brush = .fan
        case .smaller: style.width = max(0.001, style.width / 1.3)
        case .larger: style.width = min(0.15, style.width * 1.3)
        case .undo: model.undo()
        case .redo: model.redo()
        case .hide: paletteVisible = false
        case .draw: tool = .draw
        case .select: tool = .select
        case .erase: tool = .erase
        case .recolor: tool = .recolor
        case .save:
            Task {
                await editTask?.value
                if await model.saveUserDocument() { saveFeedback = nil }
                else { saveFeedback = model.status }
            }
        case .brushBook:
            book.visible = true
            inputStatus = String(localized: "Turn your supporting hand over for brushes", table: "StudioInput")
        case .colorMode:
            pickerMode.toggle()
            UserDefaults.standard.set(pickerMode, forKey: "picoPickerMode")
        case .cyan, .red, .amber, .green: break
        }
    }

    func usePaletteCommand(_ command: Int, model: WorkspaceModel) {
        if let color = palette.swatch(for: command) { stopForMenu(model); setColor(color); return }
        let actions: [Int: PaletteAction] = [8: .undo, 9: .redo, 10: .save, 20: .tube,
                                             21: .flat, 22: .fan, 200: .brushBook, 300: .colorMode]
        if let action = actions[command] { usePalette(action, model: model) }
    }

    func useBook(_ command: PicoBrushBook.Command, model: WorkspaceModel) {
        stopForMenu(model)
        if let brush = book.command(command) { style.brush = brush }
    }

    func setColor(_ color: InkColor) {
        style.color = color
        pickerColor = PicoColor(color, previous: pickerColor)
        persistColor()
    }

    func usePicker(_ input: PicoPickerLayout.Input, point: CGPoint, model: WorkspaceModel) {
        if let next = PicoPickerLayout.sample(point, input: input, selected: pickerColor) {
            guard pickerColor != next else { return }
            stopForMenu(model)
            pickerColor = next
        } else if case .favorite(let i) = input, favorites.indices.contains(i) {
            stopForMenu(model)
            if let value = favorites[i] { pickerColor = value }
            else {
                favorites[i] = pickerColor
                if let data = try? JSONEncoder().encode(favorites) {
                    UserDefaults.standard.set(data, forKey: "picoFavoriteColors")
                }
            }
        } else { return }
        style.color = pickerColor.ink
        persistColor()
    }

    private func persistColor() {
        if let data = try? JSONEncoder().encode(pickerColor) {
            UserDefaults.standard.set(data, forKey: "picoPickerColor")
        }
    }

    func scheduleSave(_ model: WorkspaceModel) {
        guard isOpen, !isSavingAndLeaving, model.hasUnsavedChanges,
              !ProcessInfo.processInfo.arguments.contains("--spatial-replay") else { return }
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            do { try await Task.sleep(for: .milliseconds(650)) } catch { return }
            guard self?.isOpen == true, model.hasUnsavedChanges else { return }
            while model.isFileBusy {
                do { try await Task.sleep(for: .milliseconds(80)) } catch { return }
            }
            await model.saveUserDocument()
        }
    }

    func saveAndLeave(_ model: WorkspaceModel) async -> Bool {
        guard !isSavingAndLeaving else { return false }
        isSavingAndLeaving = true
        defer { isSavingAndLeaving = false }
        saveTask?.cancel()
        arm(false, model: model)
        await editTask?.value
        while model.isFileBusy {
            do { try await Task.sleep(for: .milliseconds(80)) } catch { return false }
        }
        let saved = await model.saveUserDocument()
        if !saved { saveFeedback = model.status }
        return saved
    }

    func useHUD(_ action: PicoHUDAction, model: WorkspaceModel) {
        switch action {
        case .mode: changeInputMode(!poseMode, model: model)
        case .thinner: usePalette(.smaller, model: model)
        case .thicker: usePalette(.larger, model: model)
        case .draw, .erase, .recolor:
            if poseMode { changeInputMode(false, model: model) }
            let next: SpatialTool = action == .draw ? .draw : action == .erase ? .erase : .recolor
            chooseTool(next, model: model)
        case .book: usePalette(.brushBook, model: model)
        case .example: appendExample(model)
        case .settings, .leave: hudRequest = action
        }
    }

    private func hudHit(_ point: SIMD3<Float>) -> PicoHUDAction? {
        guard menuRoot.isEnabled, palmLayer.isEnabled, hud.isEnabled else { return nil }
        let local = hud.convert(position: point, from: nil)
        guard (-0.24...0.24).contains(local.x), (-0.028...0.006).contains(local.y),
              (-0.004...0.012).contains(local.z) else { return nil }
        let actions = PicoHUDAction.allCases
        let index = min(actions.count - 1, Int((local.x + 0.24) / 0.48 * Float(actions.count)))
        return actions[index]
    }

    func appendExample(_ model: WorkspaceModel) {
        stopForMenu(model)
        let points = (0...80).map { i in
            let t = Float(i) / 80
            let local: SIMD3<Float> = [(t - 0.5) * 0.48, sin(t * 2 * .pi) * 0.10, 0]
            let world = headPosition + headOrientation.act(local + [0, -0.06, -0.65])
            return PaintPoint(position: hasHeadPose ? renderer.root.convert(position: world, from: nil) : local,
                              pressure: 1, timeMs: Int64(i) * 10, normal: [0, 0, 1])
        }
        model.append(PaintStroke(id: UUID().uuidString, brush: style.brush.rawValue, width: style.width,
                                 color: style.color, points: points, erased: [], recolored: []))
    }

    private func flushPending(_ model: WorkspaceModel) {
        pendingCommit?.cancel()
        pendingCommit = nil
        if let stroke = pendingStroke {
            pendingStroke = nil
            apply(stroke, model: model, acrossStrokes: pendingAcrossStrokes)
            if capture.preview == nil { renderer.preview(nil, at: 0) }
        }
    }

    func interrupt(_ model: WorkspaceModel) {
        flushPending(model)
        if let stroke = capture.interrupt() { apply(stroke, model: model, acrossStrokes: activeAcrossStrokes) }
        pinch = HandPinch()
        poseGate = PicoHandGate()
        lastHandUpdate = nil
        renderer.preview(nil, at: 0)
        renderer.hideCursor()
    }

    private func ingest(_ sample: SpatialInputSample, contact: SpatialContact, model: WorkspaceModel,
                        deferForMenu: Bool = false, usesPicoPose: Bool = false, acrossStrokes: Bool = false) {
        guard (armed || usesPicoPose), !isEditing, tool == .draw || pendingStroke == nil else { return }
        activeAcrossStrokes = usesPicoPose || acrossStrokes
        if let stroke = capture.update(sample, contact: contact, style: style,
                                       availablePoints: tool == .draw ? model.availableDrawingPoints : DrawingCodec.maximumStrokePoints,
                                       acceptsTap: tool != .draw) {
            flushPending(model)
            pendingStroke = stroke
            pendingAcrossStrokes = activeAcrossStrokes
            if deferForMenu {
                pendingCommit = Task { [weak self] in
                    do { try await Task.sleep(for: .milliseconds(140)) } catch { return }
                    self?.flushPending(model)
                }
            } else { flushPending(model) }
        }
        if tool == .draw { renderer.preview(capture.preview ?? pendingStroke, at: sample.timestamp) }
    }

    private func apply(_ path: PaintStroke, model: WorkspaceModel, acrossStrokes: Bool, mode: SpatialTool? = nil) {
        let mode = mode ?? tool
        if mode == .draw {
            model.append(path)
            renderer.update(model.document)
            return
        }
        let original = model.document
        let target = original.strokes.first { $0.id == model.selectedStrokeID }
        if mode != .select && target == nil && !acrossStrokes {
            model.status = String(localized: "Select a stroke before using the edit tool")
            return
        }
        isEditing = true
        editTask = Task {
            let (selection, edit) = await Task.detached(priority: .userInitiated) { () -> (String?, [PaintStroke]) in
                if mode == .select {
                    return (SpatialStrokeEdit.select(in: original, at: path.points.last!.position), [])
                }
                return (nil, SpatialStrokeEdit.edits(mode, in: acrossStrokes ? original.strokes : [target].compactMap { $0 },
                                                    along: path))
            }.value
            defer { isEditing = false; editTask = nil }
            guard model.document == original else { return }
            if mode == .select {
                model.selectedStrokeID = selection ?? ""
                model.status = selection == nil ? String(localized: "No visible stroke at the tip") : String(localized: "Stroke selected")
            } else if edit.isEmpty { model.status = String(localized: "No visible stroke changed") }
            else { model.replaceEditedStrokes(edit) }
            renderer.update(model.document)
        }
    }

    func watchForTrackingLoss(_ model: WorkspaceModel) async {
        while !Task.isCancelled {
            do { try await Task.sleep(for: .milliseconds(80)) } catch { return }
            let now = CACurrentMediaTime()
            if let lastMenuPose {
                let interacting = lastMenuApproach.map { now - $0 < 0.4 } ?? false
                if now - lastMenuPose > (interacting ? 1.15 : 0.60) { hideHandMenus() }
            }
            if let lastHandUpdate, now - lastHandUpdate > SpatialStrokeCapture.maxGap {
                interrupt(model)
                inputStatus = String(localized: "Hand lost · open fingers before the next stroke")
            }
        }
    }

    func trackHands(_ model: WorkspaceModel) async {
        guard HandTrackingProvider.isSupported else {
            inputStatus = String(localized: "Hand tracking unavailable · window tools remain available")
            return
        }
        let session = ARKitSession()
        let provider = HandTrackingProvider()
        defer {
            session.stop()
            canArm = false
            hideHandMenus()
        }
        let permissions = await session.requestAuthorization(for: [.handTracking])
        guard !Task.isCancelled else { return }
        guard permissions[.handTracking] == .allowed else {
            inputStatus = String(localized: "Hand access required · enable Hands in Settings")
            return
        }
        do {
            try await session.run([provider])
            guard !Task.isCancelled else { return }
            canArm = true
            if !poseMode { armed = true }
            updateHandHint()
            for await update in provider.anchorUpdates {
                guard !Task.isCancelled else { return }
                let anchor = update.anchor
                let time = CACurrentMediaTime()
                if anchor.chirality != (leftHand ? .left : .right) {
                    if isSceneActive, update.event != .removed, let pose = palmPose(anchor) {
                        placeHandMenus(pose, at: time)
                    }
                    continue
                }
                guard isSceneActive, !isShowingSettings, !isSavingAndLeaving, !model.isFileBusy, !isEditing,
                      stylusTool == nil else { continue }
                guard update.event != .removed, anchor.isTracked, let skeleton = anchor.handSkeleton else {
                    interrupt(model)
                    inputStatus = String(localized: "Hand lost · open fingers before the next stroke")
                    continue
                }
                if poseMode {
                    handlePicoPose(decodePose(anchor, skeleton: skeleton), timestamp: time, model: model)
                    continue
                }
                let index = skeleton.joint(.indexFingerTip)
                let thumb = skeleton.joint(.thumbTip)
                guard index.isTracked, thumb.isTracked else {
                    interrupt(model)
                    inputStatus = String(localized: "Fingertips lost · open fingers before the next stroke")
                    continue
                }
                let indexTransform = anchor.originFromAnchorTransform * index.anchorFromJointTransform
                let thumbTransform = anchor.originFromAnchorTransform * thumb.anchorFromJointTransform
                let tip = indexTransform.columns.3.xyz
                let thumbTip = thumbTransform.columns.3.xyz
                let distance = simd_distance(tip, thumbTip)
                noteMenuApproach(tip, at: time)
                if handleHandMenu(index: tip, probes: [tip, thumbTip, (tip + thumbTip) * 0.5],
                                  allowsTouch: distance >= 0.027, model: model) {
                    continue
                }
                guard armed else { continue }
                let timestamp = time
                if let lastHandUpdate, timestamp - lastHandUpdate > SpatialStrokeCapture.maxGap {
                    interrupt(model)
                }
                lastHandUpdate = timestamp
                let position = renderer.root.convert(position: (tip + thumbTip) * 0.5, from: nil)
                // A small tip marker locates the brush in depth without hiding the artwork.
                renderer.moveCursor(to: position, radius: tool == .draw ? style.width * 0.18 : 0.005)
                ingest(SpatialInputSample(position: position,
                                          normal: renderer.root.convert(direction: indexTransform.columns.2.xyz, from: nil),
                                          timestamp: timestamp),
                       contact: pinch.update(distance: distance),
                       model: model, deferForMenu: true, acrossStrokes: true)
            }
            if !Task.isCancelled {
                arm(false, model: model)
                inputStatus = String(localized: "Hand tracking ended · reopen the studio to reconnect")
            }
        } catch {
            if !Task.isCancelled {
                arm(false, model: model)
                inputStatus = String(localized: "Hand tracking failed · \(error.localizedDescription)")
            }
        }
    }

    func trackWorkspacePlacement(_ model: WorkspaceModel) async {
        guard WorldTrackingProvider.isSupported else { return }
        let world = WorldTrackingProvider()
        let session = ARKitSession()
        defer { session.stop() }
        do { try await session.run([world]) } catch { return }
        while !Task.isCancelled {
            let now = CACurrentMediaTime()
            if let device = world.queryDeviceAnchor(atTimestamp: now), device.isTracked {
                headOrientation = simd_quatf(device.originFromAnchorTransform)
                headPosition = device.originFromAnchorTransform.columns.3.xyz
                hasHeadPose = true
                if !workspacePlaced { placeWorkspace(model.document, device: device.originFromAnchorTransform) }
            }
            do { try await Task.sleep(for: .milliseconds(33)) } catch { return }
        }
    }

    private func placeWorkspace(_ document: DrawingDocument, device: simd_float4x4) {
        var low = SIMD3<Float>(repeating: .infinity)
        var high = SIMD3<Float>(repeating: -.infinity)
        for stroke in document.strokes {
            for point in stroke.points {
                low = simd_min(low, point.position)
                high = simd_max(high, point.position)
            }
        }
        let center = low.x.isFinite ? (low + high) * 0.5 : .zero
        let head = simd_quatf(device)
        let forward = head.act([0, 0, -1])
        let yaw = atan2(-forward.x, -forward.z)
        let rotation = simd_quatf(angle: yaw, axis: [0, 1, 0])
        renderer.root.position = device.columns.3.xyz + rotation.act([0, -0.06, -0.65] - center)
        renderer.root.orientation = rotation
        workspacePlaced = true
    }

    func hideHandMenus() {
        lastMenuPose = nil
        lastMenuApproach = nil
        menuFace = nil
        menuRoot.isEnabled = false
        touchLatched = false
        heldColor = nil
    }

    private func placeHandMenus(_ pose: PalmMenuPose, at time: TimeInterval) {
        let alpha = lastMenuPose.map { 1 - exp(-Float(max(0, time - $0)) / 0.045) } ?? 1
        menuRoot.position += (pose.position - menuRoot.position) * alpha
        menuRoot.orientation = simd_slerp(menuRoot.orientation, pose.orientation, alpha)
        if menuFace != pose.face {
            menuFace = pose.face
            palmLayer.isEnabled = pose.face == .palm
            backLayer.isEnabled = pose.face == .back
            if pose.face == .back { book.visible = true }
            touchLatched = false
            heldColor = nil
        }
        lastMenuPose = time
        menuRoot.isEnabled = true
    }

    private func palmPose(_ anchor: HandAnchor) -> PalmMenuPose? {
        guard anchor.isTracked, let skeleton = anchor.handSkeleton else { return nil }
        func point(_ name: HandSkeleton.JointName) -> SIMD3<Float>? {
            joint(name, in: anchor, skeleton: skeleton)
        }
        guard let wrist = point(.wrist), let middle = point(.middleFingerKnuckle),
              let index = point(.indexFingerKnuckle), let little = point(.littleFingerKnuckle)
        else { return nil }
        return PalmMenuPose(wrist: wrist, middle: middle, index: index, little: little,
                            isLeft: anchor.chirality == .left, previousFace: menuFace)
    }

    private func joint(_ name: HandSkeleton.JointName, in anchor: HandAnchor,
                       skeleton: HandSkeleton) -> SIMD3<Float>? {
        let joint = skeleton.joint(name)
        guard joint.isTracked else { return nil }
        return (anchor.originFromAnchorTransform * joint.anchorFromJointTransform).columns.3.xyz
    }

    private func decodePose(_ anchor: HandAnchor, skeleton: HandSkeleton) -> PicoHandPose? {
        func p(_ name: HandSkeleton.JointName) -> SIMD3<Float>? { joint(name, in: anchor, skeleton: skeleton) }
        let names: [[HandSkeleton.JointName]] = [
            [.indexFingerKnuckle, .indexFingerIntermediateBase, .indexFingerIntermediateTip, .indexFingerTip],
            [.middleFingerKnuckle, .middleFingerIntermediateBase, .middleFingerIntermediateTip, .middleFingerTip],
            [.ringFingerKnuckle, .ringFingerIntermediateBase, .ringFingerIntermediateTip, .ringFingerTip],
            [.littleFingerKnuckle, .littleFingerIntermediateBase, .littleFingerIntermediateTip, .littleFingerTip]
        ]
        var fingers: [PicoHandPose.Finger] = []
        for group in names {
            let points = group.compactMap(p)
            guard points.count == 4 else { return nil }
            fingers.append(.init(root: points[0], middle: points[1], distal: points[2], tip: points[3]))
        }
        guard let wrist = p(.wrist), let thumbBase = p(.thumbIntermediateBase),
              let thumbDistal = p(.thumbIntermediateTip), let thumbTip = p(.thumbTip) else { return nil }
        return PicoHandPose(wrist: wrist, fingers: fingers, thumbBase: thumbBase, thumbDistal: thumbDistal, thumbTip: thumbTip)
    }

    func acceptsStylusInput(_ model: WorkspaceModel) -> Bool {
        isOpen && isSceneActive && !isShowingSettings && !isSavingAndLeaving && !model.isFileBusy && !isEditing
    }

    func useStylus(_ sample: SpatialInputSample, tool: SpatialTool?, model: WorkspaceModel) {
        let world = renderer.root.convert(position: sample.position, to: nil)
        guard let tool, !blocksDrawing(at: world) else {
            endStylusStroke(model)
            return
        }
        if stylusTool != tool {
            endStylusStroke(model)
            interrupt(model)
            guard !isEditing else { return }
            stylusTool = tool
            _ = stylusCapture.update(sample, contact: .released, style: style, availablePoints: model.availableDrawingPoints)
        }
        if let completed = stylusCapture.update(sample, contact: .engaged, style: style,
            availablePoints: tool == .draw ? model.availableDrawingPoints : DrawingCodec.maximumStrokePoints,
            acceptsTap: tool == .erase) {
            apply(completed, model: model, acrossStrokes: true, mode: tool)
        }
        renderer.moveCursor(to: sample.position, radius: tool == .draw ? style.width * 0.18 : 0.005)
        if tool == .draw { renderer.preview(stylusCapture.preview, at: sample.timestamp) }
    }

    func endStylusStroke(_ model: WorkspaceModel) {
        guard let mode = stylusTool else { return }
        stylusTool = nil
        if let stroke = stylusCapture.interrupt() { apply(stroke, model: model, acrossStrokes: true, mode: mode) }
        renderer.preview(nil, at: 0)
        renderer.hideCursor()
    }

    private func blocksDrawing(at world: SIMD3<Float>) -> Bool {
        guard menuRoot.isEnabled else { return false }
        let local = hud.convert(position: world, from: nil)
        let overHUD = hud.isEnabled && abs(local.x) < 0.25 && abs(local.y) < 0.040 && abs(local.z) < 0.018
        return (palmLayer.isEnabled && (overHUD || palette.protects(world))) ||
            (backLayer.isEnabled && book.protects(world))
    }

    private func noteMenuApproach(_ tip: SIMD3<Float>, at timestamp: TimeInterval) {
        guard menuRoot.isEnabled else { return }
        let local = menuRoot.convert(position: tip, from: nil)
        if abs(local.x) < 0.24 && abs(local.y) < 0.18 && abs(local.z) < 0.24 {
            lastMenuApproach = timestamp
        }
    }

    private func handleHandMenu(index: SIMD3<Float>, probes: [SIMD3<Float>], allowsTouch: Bool,
                                model: WorkspaceModel) -> Bool {
        if probes.contains(where: { blocksDrawing(at: $0) }) {
            // Finalize an intentional stroke on entering UI, then latch until the finger leaves.
            interrupt(model)
            if allowsTouch && !isEditing {
                if let action = hudHit(index), !touchLatched {
                    useHUD(action, model: model)
                    touchLatched = true
                } else if backLayer.isEnabled, let command = book.hit(index), !touchLatched {
                    useBook(command, model: model)
                    touchLatched = true
                } else if palmLayer.isEnabled, let id = palette.hit(index), !touchLatched {
                    usePaletteCommand(id, model: model)
                    touchLatched = true
                } else if palmLayer.isEnabled, let point = palette.pickerPoint(index, dragging: heldColor != nil) {
                    if !touchLatched, let input = PicoPickerLayout.hit(point) {
                        touchLatched = true
                        if case .favorite = input { usePicker(input, point: point, model: model) }
                        else { heldColor = input }
                    }
                    if let heldColor { usePicker(heldColor, point: point, model: model) }
                } else { heldColor = nil }
            }
            return true
        }
        touchLatched = false
        heldColor = nil
        return false
    }

    private func handlePicoPose(_ pose: PicoHandPose?, timestamp: TimeInterval, model: WorkspaceModel) {
        guard let pose else {
            interrupt(model)
            touchLatched = false
            heldColor = nil
            inputStatus = String(localized: "Fingertips lost · open fingers before the next stroke")
            return
        }
        noteMenuApproach(pose.index, at: timestamp)
        if handleHandMenu(index: pose.index, probes: [pose.index, pose.thumb, pose.recolor],
                          allowsTouch: pose.indexStraight, model: model) { return }
        lastHandUpdate = timestamp
        updateHandHint()
        let active = poseGate.update(open: pose.open, requested: pose.requested, held: pose.held, at: timestamp)
        let worldTip = pose.tip(for: active ?? pose.requested)
        let position = renderer.root.convert(position: worldTip, from: nil)
        let normal = renderer.root.convert(direction: pose.normal, from: nil)
        if let active {
            if active != tool {
                // A preceding gesture is always committed using its original tool.
                flushPending(model)
                guard !isEditing else { return }
                tool = active
            }
            renderer.moveCursor(to: position, radius: active == .draw ? style.width * 0.18 : 0.005)
        } else if let requested = pose.requested {
            renderer.moveCursor(to: position, radius: requested == .draw ? style.width * 0.18 : 0.005)
        } else { renderer.hideCursor() }
        ingest(.init(position: position, normal: normal, timestamp: timestamp),
               contact: active == nil ? (poseGate.ready ? .released : .indeterminate) : .engaged,
               model: model, deferForMenu: true, usesPicoPose: true)
        if active == nil && !poseGate.ready, let stroke = capture.interrupt() {
            pendingStroke = stroke
            pendingAcrossStrokes = true
            flushPending(model)
        }
    }

    #if targetEnvironment(simulator)
    func replay(_ model: WorkspaceModel) async {
        inputStatus = String(localized: "Simulator replay · synthetic spatial coordinates")
        canArm = true
        defer { canArm = false }
        arm(true, model: model)
        let url = model.saveURL.deletingLastPathComponent().appendingPathComponent("spatial-replay-v3.json")
        let reportURL = url.deletingLastPathComponent().appendingPathComponent("spatial-replay-report.json")
        do {
            model.newDrawing()
            renderer.root.position = [0.55, 1.28, -1.15]
            for line in 0..<2 {
                usePalette(line == 0 ? .tube : .flat, model: model)
                usePalette(line == 0 ? .cyan : .amber, model: model)
                style.width = line == 0 ? 0.019 : 0.035 / 1.3
                usePalette(.larger, model: model)
                arm(true, model: model)
                let start = Double(line)
                let origin: SIMD3<Float> = [-0.25, Float(line) * 0.16, 0]
                ingest(.init(position: origin, timestamp: start), contact: .released, model: model)
                for i in 0..<25 {
                    let t = Float(i) / 24
                    let position = origin + SIMD3(t * 0.50, sin(t * .pi) * 0.18, t * 0.22)
                    ingest(.init(position: position, timestamp: start + 0.02 * Double(i + 1)),
                           contact: .engaged, model: model)
                    try await Task.sleep(for: .milliseconds(20))
                }
                ingest(.init(position: origin, timestamp: start + 0.55), contact: .released, model: model)
            }
            arm(false, model: model)
            let drawn = model.document
            guard drawn.strokes.count == 2, Set(drawn.strokes.map(\.id)).count == 2,
                  drawn.strokes.allSatisfy({ stroke in
                      Set(stroke.points.map(\.position.x)).count > 2 &&
                      Set(stroke.points.map(\.position.y)).count > 2 &&
                      Set(stroke.points.map(\.position.z)).count > 2
                  }) else { throw WorkspaceError.invalid("Replay did not produce two distinct 3D strokes") }
            guard drawn.strokes.map(\.brush) == ["spatial_tube", "spatial_flat"],
                  drawn.strokes[0].color == PaletteAction.cyan.swatch,
                  drawn.strokes[1].color == PaletteAction.amber.swatch,
                  abs(drawn.strokes[1].width - 0.035) < 0.0001 else {
                throw WorkspaceError.invalid("Palette brush, swatch or width command was not applied")
            }
            usePalette(.undo, model: model)
            guard model.document.strokes == Array(drawn.strokes.prefix(1)) else {
                throw WorkspaceError.invalid("Undo was not a complete stroke transaction")
            }
            usePalette(.redo, model: model)
            guard model.document == drawn else { throw WorkspaceError.invalid("Redo did not restore the drawing") }
            let original = drawn.strokes[0]
            let cutPoint = original.points[8].position
            let tintPoint = original.points[15].position
            await replayTool(.select, at: cutPoint, model: model)
            guard model.selectedStrokeID == original.id else { throw WorkspaceError.invalid("Spatial selection failed") }
            await replayTool(.erase, at: cutPoint, model: model)
            let erased = model.document.strokes[0]
            guard !erased.erased.isEmpty, erased.points == original.points, erased.id == original.id else {
                throw WorkspaceError.invalid("Spatial eraser did not preserve original stroke identity")
            }
            usePalette(.green, model: model)
            await replayTool(.recolor, at: tintPoint, model: model)
            let edited = model.document
            let tinted = edited.strokes[0]
            guard !tinted.recolored.isEmpty, tinted.erased == erased.erased,
                  tinted.points == erased.points, tinted.id == erased.id,
                  tinted.recolored.allSatisfy({ color in tinted.erased.allSatisfy { $0.intersection(color.span) == nil } })
            else { throw WorkspaceError.invalid("Recolor failed or crossed an erased gap") }
            model.delete(strokeID: drawn.strokes[1].id)
            guard model.document.strokes == [tinted] else { throw WorkspaceError.invalid("Stroke deletion failed") }
            model.undo()
            guard model.document == edited else { throw WorkspaceError.invalid("Undo did not restore deletion") }
            let exported = try await model.encodeForExport()
            guard try DrawingCodec.decode(exported) == edited else {
                throw WorkspaceError.invalid("Export did not preserve the drawing")
            }
            await model.saveUserDocument(to: url)
            guard !model.hasUnsavedChanges else { throw WorkspaceError.invalid("Background save failed") }
            let reopened = WorkspaceModel()
            await reopened.restoreSavedDrawingIfNeeded(from: url)
            guard reopened.document == edited, !reopened.hasUnsavedChanges else {
                throw WorkspaceError.invalid("Automatic library restore did not load the saved drawing")
            }
            model.newDrawing()
            await model.openUserDocument(url, securityScoped: false)
            renderer.update(model.document)
            guard model.document == edited, renderer.renderedDocument == edited, renderer.failure == nil else {
                throw WorkspaceError.invalid("Replay reload or RealityKit rendering failed")
            }
            let invalidURL = url.deletingLastPathComponent().appendingPathComponent("invalid-replay.json")
            try Data("{ invalid".utf8).write(to: invalidURL, options: .atomic)
            defer { try? FileManager.default.removeItem(at: invalidURL) }
            let openedInvalid = await model.openUserDocument(invalidURL, securityScoped: false)
            guard model.document == edited, !openedInvalid else {
                throw WorkspaceError.invalid("Invalid document did not preserve the current drawing")
            }
            await model.openUserDocument(url, securityScoped: false)
            let assetURL = url.deletingLastPathComponent().appendingPathComponent("spatial-replay-reference.usdz")
            await loadReference(assetURL, securityScoped: false, model: model)
            guard referenceName == assetURL.lastPathComponent, renderer.referenceRoot.children.count == 1,
                  model.document == edited else {
                throw WorkspaceError.invalid("USDZ reference import failed: \(model.status)")
            }
            let artworks = ArtworkLibrary()
            let firstArtwork = try await artworks.open(assetURL, from: nil)
            let secondArtwork = try await artworks.open(assetURL, from: nil)
            guard firstArtwork != secondArtwork, artworks.items.count == 2,
                  artworks.items[firstArtwork]?.entity !== artworks.items[secondArtwork]?.entity else {
                throw WorkspaceError.invalid("Opening one USDZ twice did not create independent artworks")
            }
            artworks.close(firstArtwork)
            guard artworks.items.count == 1, artworks.items[secondArtwork] != nil else {
                throw WorkspaceError.invalid("Closing a USDZ artwork affected another instance")
            }
            guard palette.hasAsset, book.loaded else {
                throw WorkspaceError.invalid("PICO palette or brush book failed to load")
            }
            let center = PicoPickerLayout.center
            let black = try pickerSample(CGPoint(x: center.x + 45, y: center.y + 90 * sin(.pi / 3)),
                                         as: .brightness, selected: pickerColor)
            let restored = try pickerSample(CGPoint(x: center.x + 45, y: center.y - 90 * sin(.pi / 3)),
                                            as: .brightness, selected: black)
            guard black.brightness < 0.001, restored.brightness > 0.999,
                  black.hue == restored.hue, black.saturation == restored.saturation,
                  (0..<5).allSatisfy({ PicoPickerLayout.hit(PicoPickerLayout.favorite($0)) == .favorite($0) }) else {
                throw WorkspaceError.invalid("PICO brightness or favorite slots did not preserve color selection")
            }
            let loadedInvalid = await loadReference(invalidURL, securityScoped: false, model: model)
            guard referenceName == assetURL.lastPathComponent, renderer.referenceRoot.children.count == 1,
                  !loadedInvalid else {
                throw WorkspaceError.invalid("An invalid model replaced the current reference")
            }
            let tiltURL = url.deletingLastPathComponent().appendingPathComponent("spatial-replay-source.tilt")
            await model.openUserDocument(tiltURL, securityScoped: false)
            let imported = model.document
            renderer.update(imported)
            guard imported.strokes.count == 5, model.hasUnsavedChanges,
                  imported.strokes.allSatisfy({ $0.brush == "d90c6ad8-af0f-4b54-b422-e0f92abe1b3c" }),
                  renderer.renderedDocument == imported, renderer.failure == nil,
                  try DrawingCodec.decode(await model.encodeForExport()) == imported else {
                throw WorkspaceError.invalid("Compressed TILT editable import failed: \(model.status)")
            }
            await model.openUserDocument(url, securityScoped: false)
            renderer.update(model.document)
            model.status = String(localized: "Opened \(url.lastPathComponent) and USDZ reference")
            var longStroke = drawn.strokes[0]
            longStroke.id = "simulator-preview-chunks"
            longStroke.points = (0..<220).map { index in
                let t = Float(index) * 0.002
                return PaintPoint(position: [t, sin(t * 12) * 0.04, t * 0.3],
                                  pressure: 1, timeMs: Int64(index * 10), normal: [0, 0, 1])
            }
            renderer.preview(longStroke, at: 3)
            let previewSegments = renderer.previewSegmentCount
            renderer.preview(nil, at: 4)
            guard previewSegments == 3, renderer.previewSegmentCount == 0 else {
                throw WorkspaceError.invalid("Long preview did not create and clear bounded chunks")
            }
            let brushIDs = OpenBrushCatalog.bundled.enabledIDs
            guard brushIDs.count == 102 else { throw WorkspaceError.invalid("Open Brush catalog is incomplete") }
            let priorBookMode = book.mode
            let priorExtendedPage = book.extendedPage
            _ = book.command(.mode(.extended))
            var brushSamples: [PaintStroke] = []
            for page in 0..<book.pageCount {
                _ = book.command(.page(page))
                for slot in book.slots.indices {
                    guard let brush = book.command(.slot(slot)) else { continue }
                    var sample = drawn.strokes[0]
                    sample.id = "open-brush-\(brush.rawValue)"
                    sample.brush = brush.rawValue
                    let column = Float(brushSamples.count % 8)
                    let row = Float(brushSamples.count / 8)
                    for i in sample.points.indices {
                        sample.points[i].position = sample.points[i].position * 0.20 + [column * 0.13, -row * 0.07, 0]
                    }
                    let extent = sample.length
                    sample.erased = [.init(start: extent * 0.42, end: extent * 0.58)]
                    sample.recolored = [.init(span: .init(start: extent * 0.65, end: extent * 0.90),
                                              color: .init(r: 1, g: 0.55, b: 0.05))]
                    var triangles = 0
                    let entity = try WorkspaceRenderer.entity(for: sample, parts: StrokeAppearance.parts(sample)) {
                        triangles += $0
                    }
                    guard entity != nil, triangles > 0 else {
                        throw WorkspaceError.invalid("Missing brush mesh: \(brush.rawValue)")
                    }
                    brushSamples.append(sample)
                }
            }
            _ = book.command(.page(priorExtendedPage))
            _ = book.command(.mode(priorBookMode))
            guard Set(brushSamples.map(\.brush)) == brushIDs else {
                throw WorkspaceError.invalid("Brush book does not expose every active profile")
            }
            let swatchURL = url.deletingLastPathComponent().appendingPathComponent("spatial-openbrush-replay-v3.json")
            let swatches = DrawingDocument(strokes: brushSamples)
            try DrawingCodec.encode(swatches).write(to: swatchURL, options: .atomic)
            let reopenedSwatches = try DrawingCodec.read(swatchURL)
            let swatchRenderer = WorkspaceRenderer()
            guard reopenedSwatches == swatches, swatchRenderer.update(reopenedSwatches),
                  swatchRenderer.root.children.first?.children.count == brushIDs.count else {
                throw WorkspaceError.invalid("Open Brush swatches did not reopen with all their meshes")
            }
            let renderedBrushes = swatchRenderer.root.children.first!.children
            let animated: [LowLevelMesh] = renderedBrushes.compactMap {
                switch $0.components[OpenBrushAnimationComponent.self]?.animation {
                case .particles(let animation): return animation.mesh
                case .wave(let animation, _): return animation.mesh
                default: return nil
                }
            }
            let movingMaterials = renderedBrushes.filter {
                switch $0.components[OpenBrushAnimationComponent.self]?.animation {
                case .material, .wave(_, .some): return true
                default: return false
                }
            }
            guard animated.count == 13, movingMaterials.count == 28 else {
                throw WorkspaceError.invalid("Missing animated brush meshes or materials after reload")
            }
            func positions() -> [SIMD3<Float>] {
                animated.map { mesh in
                    var position = SIMD3<Float>.zero
                    mesh.withUnsafeBytes(bufferIndex: 0) { position = $0.bindMemory(to: SIMD3<Float>.self)[0] }
                    return position
                }
            }
            func materialStates() -> [[Float]] {
                movingMaterials.map { entity in
                    let material = entity.components[ModelComponent.self]?.materials.first
                    if let lit = material as? PhysicallyBasedMaterial {
                        return [lit.textureCoordinateTransform.offset.x, lit.emissiveIntensity] +
                            (lit.baseColor.tint.cgColor.components ?? []).map(Float.init)
                    }
                    if let unlit = material as? UnlitMaterial {
                        return [unlit.textureCoordinateTransform.offset.x] +
                            (unlit.color.tint.cgColor.components ?? []).map(Float.init)
                    }
                    return []
                }
            }
            guard let analyzer = OpenBrushAudioAnalyzer() else { throw WorkspaceError.invalid("Could not create FFT") }
            let tone = (0..<OpenBrushAudioAnalyzer.windowSize).map { i in
                0.25 * sin(Float(i) * (2 * .pi * 16 / Float(OpenBrushAudioAnalyzer.windowSize)))
            }
            let audio = tone.withUnsafeBufferPointer { analyzer.analyze($0) }
            guard audio.level > 0.5, audio.spectrum.max() ?? 0 > 0.1,
                  audio.spectrum.firstIndex(of: audio.spectrum.max() ?? 0) == 8 else {
                throw WorkspaceError.invalid("Synthetic audio did not produce the expected spectrum")
            }
            let waves: [OpenBrushWaveMesh] = renderedBrushes.compactMap {
                if case .wave(let wave, _) = $0.components[OpenBrushAnimationComponent.self]?.animation { return wave }
                return nil
            }
            func wavePositions(_ mesh: LowLevelMesh) -> [SIMD3<Float>] {
                var positions: [SIMD3<Float>] = []
                mesh.withUnsafeBytes(bufferIndex: 0) {
                    positions = Array($0.bindMemory(to: SIMD3<Float>.self).prefix(mesh.vertexCapacity))
                }
                return positions
            }
            var audioResponsive = 0
            for wave in waves {
                wave.update(at: 0.41, audio: .silent)
                let silent = wavePositions(wave.mesh)
                wave.update(at: 0.41, audio: audio)
                if zip(silent, wavePositions(wave.mesh)).contains(where: { simd_distance($0.0, $0.1) > 1e-6 }) {
                    audioResponsive += 1
                }
            }
            guard audioResponsive == 5 else {
                throw WorkspaceError.invalid("Not every wave mesh responded to the synthetic audio")
            }
            let initial = positions()
            let initialMaterials = materialStates()
            renderer.root.addChild(swatchRenderer.root)
            var showSwatches = false
            defer { if !showSwatches { swatchRenderer.root.removeFromParent() } }
            var moving = 0, materialChanges = 0
            for _ in 0..<20 {
                try await Task.sleep(for: .milliseconds(50))
                moving = zip(initial, positions()).filter { simd_distance($0.0, $0.1) > 1e-6 }.count
                materialChanges = zip(initialMaterials, materialStates()).filter { $0.0 != $0.1 }.count
                if moving == animated.count && materialChanges == movingMaterials.count { break }
            }
            guard moving == animated.count, materialChanges == movingMaterials.count else {
                throw WorkspaceError.invalid("RealityKit did not advance every animated brush")
            }
            if ProcessInfo.processInfo.arguments.contains("--brush-swatches") {
                showSwatches = true
                for child in renderer.root.children where child !== swatchRenderer.root { child.isEnabled = false }
                menuRoot.isEnabled = false
                renderer.root.position = [0, 1.40, -1.10]
                swatchRenderer.root.scale = SIMD3(repeating: 1.32)
                let board = ModelEntity(mesh: .generatePlane(width: 0.61, height: 0.55, cornerRadius: 0.012),
                                        materials: [UnlitMaterial(color: .init(white: 0.11, alpha: 1))])
                board.position = [0, 0, -0.08]
                let sortGroup = ModelSortGroup(depthPass: .postPass)
                board.components.set(ModelSortGroupComponent(group: sortGroup, order: 0))
                for brush in renderedBrushes {
                    brush.components.set(ModelSortGroupComponent(group: sortGroup, order: 1))
                }
                swatchRenderer.root.addChild(board)
            }
            let report: [String: Any] = [
                "passed": true, "input": "synthetic simulator replay", "strokes": drawn.strokes.count,
                "pointCounts": drawn.strokes.map { $0.points.count }, "version": 3,
                "openBrushProfiles": brushIDs.count, "animatedBrushProfiles": moving, "animatedBrushMaterials": materialChanges,
                "audioReactiveWaveMeshes": audioResponsive,
                "longPreviewPoints": longStroke.points.count, "longPreviewSegments": previewSegments,
                "checks": [
                    "three-axis strokes", "distinct IDs", "palette brush, swatch and width", "palette undo", "palette redo",
                    "spatial selection", "local erase and recolor", "delete and undo",
                    "background v3 save, export, auto restore and reload", "invalid JSON preserves drawing",
                    "native USDZ reference and invalid model handling", "DEFLATE TILT import and v3 export",
                    "RealityKit entities", "bounded long-stroke preview",
                    "native PICO palette and brush book", "RealityKit mesh and v3 reload for every selectable original brush",
                    "RealityKit particle and wave positions and material parameters update after reopening v3",
                    "FFT and five waveform meshes respond to a synthetic microphone signal",
                    "reversible brightness and five favorite targets",
                    "independent USDZ artwork instances"
                ]
            ]
            try JSONSerialization.data(withJSONObject: report, options: [.prettyPrinted, .sortedKeys])
                .write(to: reportURL, options: .atomic)
            inputStatus = String(localized: "Replay passed · drawing, editing, saving and reopening")
        } catch {
            if !Task.isCancelled {
                arm(false, model: model)
                inputStatus = String(localized: "Replay failed · \(error.localizedDescription)")
                let report: [String: Any] = ["passed": false, "error": error.localizedDescription]
                if let data = try? JSONSerialization.data(withJSONObject: report) {
                    try? data.write(to: reportURL, options: .atomic)
                }
            }
        }
    }
    private func pickerSample(_ point: CGPoint, as input: PicoPickerLayout.Input, selected: PicoColor) throws -> PicoColor {
        guard let sample = PicoPickerLayout.sample(point, input: input, selected: selected) else {
            throw WorkspaceError.invalid("PICO picker failed to sample its brightness arc")
        }
        return sample
    }

    private func replayTool(_ mode: SpatialTool, at position: SIMD3<Float>, model: WorkspaceModel) async {
        chooseTool(mode, model: model)
        arm(true, model: model)
        ingest(.init(position: position, timestamp: 0), contact: .released, model: model)
        ingest(.init(position: position, timestamp: 0.02), contact: .engaged, model: model)
        ingest(.init(position: position, timestamp: 0.06), contact: .engaged, model: model)
        ingest(.init(position: position, timestamp: 0.10), contact: .released, model: model)
        await editTask?.value
        arm(false, model: model)
    }
    #endif
}

private extension SIMD4<Float> {
    var xyz: SIMD3<Float> { [x, y, z] }
}

struct SpatialStudioView: View {
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.openWindow) private var openWindow
    @Environment(\.dismissImmersiveSpace) private var dismissImmersiveSpace
    let studio: SpatialStudio
    let model: WorkspaceModel

    var body: some View {
        RealityView { content, attachments in
            content.add(studio.renderer.root)
            content.add(studio.menuRoot)
            attach(attachments)
            studio.renderer.update(model.document)
            await studio.palette.load()
            await studio.book.load()
        } update: { _, attachments in
            attach(attachments)
            studio.renderer.update(model.document)
            studio.renderer.select(studio.poseMode ? "" : model.selectedStrokeID)
            studio.palette.update(style: studio.style, visible: studio.paletteVisible, picker: studio.pickerMode,
                                  canUndo: model.canUndo, canRedo: model.canRedo)
            studio.book.update()
        } attachments: {
            Attachment(id: "picker") {
                PicoColorSurface(picker: studio.pickerMode, selected: studio.pickerColor, favorites: studio.favorites) {
                    studio.usePicker($0, point: $1, model: model)
                }
            }
            Attachment(id: "brushBook") {
                PicoBrushBookSurface(book: studio.book, brush: studio.style.brush) {
                    studio.useBook($0, model: model)
                }
            }
            Attachment(id: "hud") {
                PicoStudioHUD(studio: studio, model: model)
            }
        }
        .gesture(TapGesture().targetedToAnyEntity().onEnded { value in
            if let command = studio.palette.command(for: value.entity) {
                studio.usePaletteCommand(command, model: model)
            }
        })
        .onAppear {
            studio.enter()
            #if targetEnvironment(simulator)
            if ProcessInfo.processInfo.arguments.contains("--brush-book-preview") { studio.book.visible = true }
            #endif
        }
        .onDisappear {
            studio.leave(model)
            if studio.libraryHidden {
                studio.libraryHidden = false
                openWindow(id: "artwork-library")
            }
        }
        .onChange(of: studio.hudRequest) { _, request in
            switch request {
            case .settings:
                studio.arm(false, model: model)
                studio.libraryHidden = false
                studio.isShowingSettings = true
                openWindow(id: "artwork-library")
            case .leave:
                Task { if await studio.saveAndLeave(model) { await dismissImmersiveSpace() } }
            default: break
            }
            if request != nil { studio.hudRequest = nil }
        }
        .onChange(of: model.document) { _, _ in studio.scheduleSave(model) }
        .onChange(of: studio.leftHand) { _, _ in
            studio.hideHandMenus()
            studio.arm(!studio.poseMode, model: model)
        }
        .onChange(of: studio.isShowingSettings) { _, showing in
            if !showing { studio.arm(!studio.poseMode, model: model) }
        }
        .onChange(of: scenePhase, initial: true) { _, phase in
            studio.isSceneActive = phase == .active
            if phase == .background { OpenBrushAudioInput.shared.stop() }
            if phase != .active {
                studio.hideHandMenus()
                studio.arm(false, model: model)
            } else if !studio.isShowingSettings { studio.arm(!studio.poseMode, model: model) }
        }
        .task {
            #if targetEnvironment(simulator)
            if ProcessInfo.processInfo.arguments.contains("--spatial-replay") {
                await studio.replay(model)
                return
            }
            #endif
            await studio.trackHands(model)
        }
        .task {
            if !ProcessInfo.processInfo.arguments.contains("--spatial-replay") {
                await studio.trackWorkspacePlacement(model)
            }
        }
        .task {
            if #available(visionOS 26, *), !ProcessInfo.processInfo.arguments.contains("--spatial-replay") {
                await SpatialStylusInput.track(in: studio, model: model)
            }
        }
        .task { await studio.watchForTrackingLoss(model) }
    }

    private func attach(_ attachments: RealityViewAttachments) {
        if let picker = attachments.entity(for: "picker") { studio.palette.attachPicker(picker) }
        if let book = attachments.entity(for: "brushBook") { studio.book.attach(book) }
        if let hud = attachments.entity(for: "hud"), hud.parent !== studio.hud {
            studio.hud.addChild(hud)
            SpatialPalette.size(hud, width: 0.50, points: 800)
        }
    }
}
