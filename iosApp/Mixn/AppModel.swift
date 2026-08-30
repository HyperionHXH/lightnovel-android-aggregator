import Foundation
import SwiftUI

@MainActor
final class MixnAppModel: ObservableObject {
    @Published var selectedSource: MixnSource = .kingdom
    @Published var selectedFeed: MixnFeed = .popular
    @Published var books: [MixnBook] = []
    @Published var isLoading = false
    @Published var message: String?
    @Published var kingdomLoggedIn = false
    @Published var shelfLoggedIn = false

    let api: MixnAPIClient

    init(api: MixnAPIClient = MixnAPIClient()) {
        self.api = api
        Task { await refreshSessions() }
    }

    func refreshSessions() async {
        kingdomLoggedIn = await api.kingdomHasSession()
        shelfLoggedIn = await api.shelfHasSession()
    }

    func loadDiscover() async {
        isLoading = true
        message = nil
        do {
            switch selectedSource {
            case .kingdom:
                books = try await api.kingdomDiscover(feed: selectedFeed)
            case .shelf:
                books = try await api.shelfDiscover(feed: selectedFeed)
            }
            if books.isEmpty { message = "暂无可显示的书籍" }
        } catch {
            message = error.localizedDescription
        }
        isLoading = false
    }

    func search(_ query: String) async {
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else {
            await loadDiscover()
            return
        }
        isLoading = true
        message = nil
        do {
            switch selectedSource {
            case .kingdom:
                books = try await api.kingdomSearch(query: text)
            case .shelf:
                books = try await api.shelfSearch(query: text)
            }
        } catch {
            message = error.localizedDescription
        }
        isLoading = false
    }

    func login(source: MixnSource, identifier: String, password: String) async -> Bool {
        do {
            switch source {
            case .kingdom:
                try await api.kingdomLogin(identifier: identifier, password: password)
                kingdomLoggedIn = true
            case .shelf:
                try await api.shelfLogin(email: identifier, password: password)
                shelfLoggedIn = true
            }
            message = "登录成功"
            return true
        } catch {
            message = error.localizedDescription
            return false
        }
    }
}
