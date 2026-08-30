import SwiftUI

@main
struct MixnApp: App {
    @StateObject private var model = MixnAppModel()

    var body: some Scene {
        WindowGroup {
            RootView(model: model)
        }
    }
}
