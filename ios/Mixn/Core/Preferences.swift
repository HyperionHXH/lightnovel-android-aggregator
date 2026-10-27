import SwiftUI

enum ReadingMode: String, Codable, CaseIterable, Identifiable {
    case paged, card, scroll
    var id: String { rawValue }
    var title: String { self == .paged ? "左右翻页" : self == .card ? "卡片翻页" : "上下滚动" }
}
enum ReadingTheme: String, Codable, CaseIterable, Identifiable {
    case white, sepia, green, dark
    var id: String { rawValue }
    var title: String { [Self.white: "白色", .sepia: "米黄", .green: "护眼绿", .dark: "深色"][self]! }
    var background: Color { switch self { case .white: return .white; case .sepia: return Color(red: 0.95, green: 0.91, blue: 0.82); case .green: return Color(red: 0.85, green: 0.91, blue: 0.84); case .dark: return Color(white: 0.08) } }
    var foreground: Color { self == .dark ? Color(white: 0.85) : Color(white: 0.12) }
    var cssBackground: String { switch self { case .white: return "#ffffff"; case .sepia: return "#f2e8d1"; case .green: return "#d9e8d6"; case .dark: return "#141414" } }
}
struct ReaderSettings: Codable, Hashable {
    var font: String = "serif"
    var customFontID: String? = nil
    var fontSize: Double = 21
    var lineHeight: Double = 1.75
    var horizontalPadding: Double = 28
    var theme: ReadingTheme = .sepia
    var mode: ReadingMode = .paged
    var tapZone: String = "default"
    var tapInversion: String = "none"
    var orientation: String = "default"
    var imageScale: String = "fit"
    var keepScreenOn: Bool = true
    var showProgressBar: Bool = true
}
struct AppearanceSettings: Codable, Hashable {
    var theme: String = "system"
    var uiScale: Double = 1
    var iconScale: Double = 1
}
@MainActor final class PreferencesStore: ObservableObject {
    @Published var reader: ReaderSettings { didSet { save(reader, "reader") } }
    @Published var appearance: AppearanceSettings { didSet { save(appearance, "appearance") } }
    @Published var onboardingComplete: Bool { didSet { defaults.set(onboardingComplete, forKey: "onboardingComplete") } }
    @Published var updateNotifications: Bool { didSet { defaults.set(updateNotifications, forKey: "updateNotifications") } }
    @Published var exportDirectoryBookmark: Data? { didSet { defaults.set(exportDirectoryBookmark, forKey: "exportDirectoryBookmark") } }
    private let defaults: UserDefaults
    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        reader = defaults.data(forKey: "reader").flatMap { try? JSONDecoder().decode(ReaderSettings.self, from: $0) } ?? ReaderSettings()
        appearance = defaults.data(forKey: "appearance").flatMap { try? JSONDecoder().decode(AppearanceSettings.self, from: $0) } ?? AppearanceSettings()
        onboardingComplete = defaults.bool(forKey: "onboardingComplete")
        updateNotifications = defaults.bool(forKey: "updateNotifications")
        exportDirectoryBookmark = defaults.data(forKey: "exportDirectoryBookmark")
    }
    private func save<T: Encodable>(_ value: T, _ key: String) { if let data = try? JSONEncoder().encode(value) { defaults.set(data, forKey: key) } }
}
