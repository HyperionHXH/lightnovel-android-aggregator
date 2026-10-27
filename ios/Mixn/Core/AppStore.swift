import SwiftUI

@MainActor final class AppStore: ObservableObject {
    let sources: [SourceID: any NovelSource]
    let preferences: PreferencesStore
    let library: LibraryStore
    let fonts: FontStore
    let cache: ContentCache
    @Published var sessions: [SourceID: AccountSession] = [:]
    @Published var profiles: [SourceID: SourceProfile] = [:]
    @Published var sourceErrors: [SourceID: String] = [:]
    @Published private var accountGeneration: [SourceID: Int] = [:]
    @Published private(set) var cacheGeneration = 0
    init(sources: [SourceID: any NovelSource]? = nil, preferences: PreferencesStore? = nil,
         library: LibraryStore? = nil, fonts: FontStore? = nil, cache: ContentCache? = nil) {
        let sharedCache = cache ?? ContentCache()
        self.sources = sources ?? [.kingdom: KingdomSource(cache: sharedCache), .shelf: ShelfSource()]
        self.preferences = preferences ?? PreferencesStore()
        self.library = library ?? LibraryStore()
        self.fonts = fonts ?? FontStore()
        self.cache = sharedCache
        for (id, source) in self.sources { sessions[id] = source.session }
    }
    func source(_ id: SourceID) -> any NovelSource { sources[id]! }
    func accountRevision(_ id: SourceID) -> Int { accountGeneration[id, default: 0] }
    func restoreAccounts() async {
        await withTaskGroup(of: Void.self) { group in
            for id in SourceID.allCases {
                group.addTask { @MainActor [weak self] in
                    guard let self = self else { return }
                    self.accountGeneration[id, default: 0] += 1
                    let generation = self.accountGeneration[id, default: 0]
                    do {
                        let session = try await self.source(id).restoreSession()
                        guard self.accountGeneration[id, default: 0] == generation else { return }
                        self.sessions[id] = session
                        if session.loggedIn { await self.refreshProfile(id) }
                    } catch is CancellationError { } catch { self.sourceErrors[id] = error.localizedDescription }
                }
            }
        }
    }
    func login(_ id: SourceID, identifier: String, password: String) async throws {
        accountGeneration[id, default: 0] += 1
        let generation = accountGeneration[id, default: 0]
        let result = try await source(id).login(identifier: identifier, password: password)
        guard accountGeneration[id, default: 0] == generation else { throw CancellationError() }
        sessions[id] = result
        sourceErrors[id] = nil
        await refreshProfile(id)
    }
    func registerKingdom(email: String, captcha: String, nickname: String, password: String) async throws {
        accountGeneration[.kingdom, default: 0] += 1
        let generation = accountGeneration[.kingdom, default: 0]
        guard let kingdom = source(.kingdom) as? KingdomSource else { throw SourceFailure.unsupported("当前来源不支持邮箱注册") }
        let session = try await kingdom.register(email: email, captcha: captcha, nickname: nickname, password: password)
        guard accountGeneration[.kingdom, default: 0] == generation else { throw CancellationError() }
        sessions[.kingdom] = session
        sourceErrors[.kingdom] = nil
        await refreshProfile(.kingdom)
    }

    func logout(_ id: SourceID) async throws {
        accountGeneration[id, default: 0] += 1
        let old = scope(id)
        try await source(id).logout()
        sessions[id] = AccountSession()
        profiles[id] = nil
        sourceErrors[id] = nil
        try await cache.clear(scope: old)
    }
    func refreshProfile(_ id: SourceID) async {
        let generation = accountGeneration[id, default: 0]
        do {
            let profile = try await source(id).profile()
            guard accountGeneration[id, default: 0] == generation else { return }
            profiles[id] = profile
            sessions[id] = source(id).session
            sourceErrors[id] = nil
        } catch is CancellationError { } catch {
            guard accountGeneration[id, default: 0] == generation else { return }
            sourceErrors[id] = error.localizedDescription
        }
    }
    private func scope(_ id: SourceID) -> String {
        let session = sessions[id] ?? source(id).session
        return session.loggedIn ? "user:\(id.rawValue):\(session.accountID ?? "unknown")" : "public:\(id.rawValue)"
    }
    func loadChapter(novel: ResourceKey, chapter: ResourceKey) async throws -> ChapterContent {
        if let downloaded = try library.savedChapter(novel: novel, chapter: chapter) { return downloaded }
        let key = "chapter:\(novel.id):\(chapter.id)"
        let requestScope = scope(novel.source)
        let generation = cacheGeneration
        let revision = accountRevision(novel.source)
        let token = await cache.token(scope: requestScope)
        do {
            let result = try await source(novel.source).chapter(novel: novel, chapter: chapter)
            try Task.checkCancellation()
            guard revision == accountRevision(novel.source), requestScope == scope(novel.source) else { throw CancellationError() }
            if generation == cacheGeneration, requestScope == scope(novel.source) {
                do { try await cache.write(JSONEncoder().encode(result), key: key, scope: requestScope, ttl: 7 * 24 * 60 * 60, token: token) }
                catch { sourceErrors[novel.source] = "正文已加载，但临时缓存写入失败：" + error.localizedDescription }
            }
            guard revision == accountRevision(novel.source), requestScope == scope(novel.source) else { throw CancellationError() }
            return result
        } catch is CancellationError { throw CancellationError() } catch {
            guard revision == accountRevision(novel.source), requestScope == scope(novel.source) else { throw CancellationError() }
            guard let data = await cache.read(key: key, scope: requestScope),
                  let value = try? JSONDecoder().decode(ChapterContent.self, from: data) else { throw error }
            guard revision == accountRevision(novel.source), requestScope == scope(novel.source) else { throw CancellationError() }
            return value
        }
    }
    func recordProgress(novel: Novel, content: ChapterContent, paragraph: Int, percent: Int) async {
        do {
            try library.record(novel: novel, content: content, paragraph: max(0, paragraph), percent: min(100, max(0, percent)))
            let progress = ReadingProgress(novelKey: novel.key, chapterKey: content.chapter.key,
                                           volumeKey: content.chapter.volumeKey, paragraphIndex: max(0, paragraph), percent: min(100, max(0, percent)))
            if source(novel.key.source).session.loggedIn { try await source(novel.key.source).saveProgress(progress) }
        } catch is CancellationError { } catch { sourceErrors[novel.key.source] = "进度同步失败：" + error.localizedDescription }
    }
    func clearTemporaryCache() async throws {
        cacheGeneration += 1
        for source in sources.values { source.clearMetadataCache() }
        try await cache.clear(scope: nil)
        try fonts.clearTemporary()
        URLCache.shared.removeAllCachedResponses()
    }
    func refreshUpdates() async {
        for source in sources.values where source.session.loggedIn {
            do { try library.observeShelf(try await source.shelf()) }
            catch is CancellationError { return }
            catch { sourceErrors[source.sourceID] = error.localizedDescription }
        }
    }
}
