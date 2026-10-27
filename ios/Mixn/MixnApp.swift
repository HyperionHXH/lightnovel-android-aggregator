import SwiftUI
import UIKit

@MainActor final class MixnAppDelegate: NSObject, UIApplicationDelegate {
    let app: AppStore = {
        #if DEBUG
        if ProcessInfo.processInfo.environment["MIXN_UI_TEST"] == "1" { return UITestEnvironment.makeStore() }
        #endif
        return AppStore()
    }()
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        BackgroundUpdateScheduler.shared.register(app: app)
        return true
    }
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        ReaderOrientationPolicy.mask
    }
}
@main struct MixnApp: App {
    @UIApplicationDelegateAdaptor(MixnAppDelegate.self) private var delegate
    var body: some Scene {
        WindowGroup { MixnRootView().environmentObject(delegate.app) }
    }
}
struct MixnRootView: View {
    @EnvironmentObject private var app: AppStore
    @Environment(\.scenePhase) private var phase
    var body: some View {
        RootContent(preferences: app.preferences)
            .task {
                await app.restoreAccounts()
                await app.library.resume(sources: app.sources, fonts: app.fonts)
            }
            .onChange(of: phase) { phase in
                if phase == .background, app.preferences.updateNotifications {
                    do { try BackgroundUpdateScheduler.shared.schedule() }
                    catch { app.sourceErrors[.kingdom] = "后台检查调度失败：" + error.localizedDescription }
                } else if phase == .active {
                    Task { await app.library.resume(sources: app.sources, fonts: app.fonts) }
                }
            }
    }
}
private struct RootContent: View {
    @ObservedObject var preferences: PreferencesStore
    @Environment(\.sizeCategory) private var systemSizeCategory
    private static let sizeCategories: [ContentSizeCategory] = [
        .extraSmall, .small, .medium, .large, .extraLarge, .extraExtraLarge, .extraExtraExtraLarge,
        .accessibilityMedium, .accessibilityLarge, .accessibilityExtraLarge,
        .accessibilityExtraExtraLarge, .accessibilityExtraExtraExtraLarge
    ]
    private var scaledSizeCategory: ContentSizeCategory {
        let current = Self.sizeCategories.firstIndex(of: systemSizeCategory) ?? 3
        let offset = preferences.appearance.uiScale < 1 ? -1 : preferences.appearance.uiScale > 1 ? 1 : 0
        return Self.sizeCategories[min(Self.sizeCategories.count - 1, max(0, current + offset))]
    }
    var body: some View {
        Group {
            if preferences.onboardingComplete {
                TabView {
                    NavigationView { DiscoverView() }
                        .navigationViewStyle(.stack)
                        .tabItem { Label { Text("发现") } icon: { Image(systemName: "house.fill").font(.system(size: 24 * preferences.appearance.iconScale)) } }
                    NavigationView { ShelfView() }
                        .navigationViewStyle(.stack)
                        .tabItem { Label { Text("书架") } icon: { Image(systemName: "books.vertical").font(.system(size: 24 * preferences.appearance.iconScale)) } }
                    NavigationView { ProfileView() }
                        .navigationViewStyle(.stack)
                        .tabItem { Label { Text("我的") } icon: { Image(systemName: "person.fill").font(.system(size: 24 * preferences.appearance.iconScale)) } }
                }
            } else { OnboardingView() }
        }
        .preferredColorScheme(preferences.appearance.theme == "dark" ? .dark : preferences.appearance.theme == "light" ? .light : nil)
        .environment(\.sizeCategory, scaledSizeCategory)
        .font(.body)
        .tint(Color(red: 0.08, green: 0.39, blue: 0.38))
    }
}
