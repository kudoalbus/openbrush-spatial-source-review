import SwiftUI

enum PicoHUDAction: CaseIterable {
    case mode, thinner, thicker, draw, erase, recolor, book, example, settings, leave
}

struct PicoStudioHUD: View {
    let studio: SpatialStudio
    let model: WorkspaceModel

    private let background = Color(red: 0.125, green: 0.157, blue: 0.192)
    private let tile = Color(red: 0.204, green: 0.333, blue: 0.424)

    var body: some View {
        VStack(spacing: 5) {
            HStack(spacing: 7) {
                Text(studio.saveFeedback ?? studio.stylusStatus ?? studio.inputStatus).lineLimit(1).minimumScaleFactor(0.70)
                Text("·").accessibilityHidden(true)
                Text("\(studio.style.brush.localizedLabel) \(studio.style.width * 1000, specifier: "%.0f") mm")
                Text("·").accessibilityHidden(true)
                Text("\(model.document.strokes.count) strokes")
            }.font(.system(size: 17, weight: .medium)).frame(maxWidth: .infinity, minHeight: 30)
            HStack(spacing: 5) {
                key(studio.poseMode ? "Hand gestures" : "Pinch mode", icon: "hand.raised", .mode)
                key("Thinner", icon: "minus", .thinner)
                key("Thicker", icon: "plus", .thicker)
                key("Draw", icon: "pencil.tip", .draw, selected: studio.tool == .draw && !studio.poseMode)
                key("Erase", icon: "eraser", .erase, selected: studio.tool == .erase && !studio.poseMode)
                key("Recolor", icon: "paintbrush", .recolor, selected: studio.tool == .recolor && !studio.poseMode)
                key("Brush book", icon: "book", .book)
                key("Example", icon: "scribble", .example)
                key("Settings", icon: "gearshape", .settings)
                key(studio.isSavingAndLeaving ? "Saving…" : "Save & return", icon: "square.and.arrow.down", .leave)
            }.frame(height: 51)
        }
        .padding(7)
        .frame(width: 800, height: 100)
        .foregroundStyle(.white)
        .background(background.opacity(0.94), in: RoundedRectangle(cornerRadius: 6))
        .disabled(studio.isSavingAndLeaving || model.isFileBusy || studio.isEditing)
    }

    private func key(_ title: LocalizedStringKey, icon: String, _ action: PicoHUDAction,
                     selected: Bool = false) -> some View {
        Button { studio.useHUD(action, model: model) } label: {
            VStack(spacing: 3) {
                Image(systemName: icon).font(.system(size: 13, weight: .semibold))
                Text(title).font(.system(size: 14, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.66)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(selected ? Color.accentColor : tile, in: RoundedRectangle(cornerRadius: 3))
        }.buttonStyle(.plain).hoverEffect()
    }
}

extension SpatialBrush {
    var localizedLabel: String {
        switch self {
        case .tube: String(localized: "Round")
        case .flat: String(localized: "Flat")
        case .fan: String(localized: "Fan")
        case .openBrush: Bundle.main.localizedString(forKey: label, value: label, table: "BrushNames")
        }
    }
}

extension SpatialTool {
    var localizedLabel: String {
        switch self {
        case .draw: String(localized: "Draw")
        case .select: String(localized: "Select")
        case .erase: String(localized: "Erase")
        case .recolor: String(localized: "Recolor")
        }
    }
}
