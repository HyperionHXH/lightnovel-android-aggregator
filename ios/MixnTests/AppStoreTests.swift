import XCTest
import Foundation
import Combine
@testable import Mixn

final class AppStoreTests: XCTestCase {
    @MainActor private func setupStore(_ source: ChapterGateSource, rootURL: URL? = nil) throws -> AppStore {
        let id = UUID().uuidString
        let root = rootURL ?? FileManager.default.temporaryDirectory.appendingPathComponent("MixnAccountTests-" + id)
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "MixnAccountTests." + id))
        addTeardownBlock {
            defaults.removePersistentDomain(forName: "MixnAccountTests." + id)
            try FileManager.default.removeItem(at: root)
        }
        return AppStore(sources: [.kingdom: source, .shelf: UITestSource(.shelf)],
                        preferences: PreferencesStore(defaults: defaults), library: LibraryStore(rootURL: root),
                        fonts: FontStore(rootURL: root), cache: ContentCache(rootURL: root.appendingPathComponent("cache")))
    }

    @MainActor func testChapterResponseCannotCrossAccountOrSameAccountRelogin() async throws {
        for nextAccount in ["B", "A"] {
            let source = ChapterGateSource(), app = try setupStore(source)
            let started = expectation(description: "Chapter suspended before account change")
            source.started = { started.fulfill() }
            let load = Task { try await app.loadChapter(novel: source.novel, chapter: source.content.chapter.key) }
            await fulfillment(of: [started], timeout: 3)
            try await app.login(.kingdom, identifier: nextAccount, password: "synthetic")
            source.finish(.success(source.content))
            do { _ = try await load.value; XCTFail("Obsolete account chapter was displayed") }
            catch is CancellationError { }
            catch { XCTFail("Expected cancellation, got \(error)") }
            let cached = await app.cache.read(key: "chapter:\(source.novel.id):\(source.content.chapter.id)", scope: "user:light_novel_kingdom:A")
            XCTAssertNil(cached)
        }
    }

    @MainActor func testFailedOldAccountRequestCannotExposeCachedBodyAfterRelogin() async throws {
        let source = ChapterGateSource(), app = try setupStore(source)
        let key = "chapter:\(source.novel.id):\(source.content.chapter.id)"
        try await app.cache.write(JSONEncoder().encode(source.content), key: key, scope: "user:light_novel_kingdom:A")
        let started = expectation(description: "Old request suspended")
        source.started = { started.fulfill() }
        let load = Task { try await app.loadChapter(novel: source.novel, chapter: source.content.chapter.key) }
        await fulfillment(of: [started], timeout: 3)
        try await app.login(.kingdom, identifier: "B", password: "synthetic")
        source.finish(.failure(SourceFailure.network("synthetic network failure")))
        do { _ = try await load.value; XCTFail("Old account cache was displayed") }
        catch is CancellationError { }
        catch { XCTFail("Expected account cancellation, got \(error)") }
    }

    @MainActor func testClearDuringFetchPreservesLoadedBodyWithoutRefillingTemporaryCache() async throws {
        let source = ChapterGateSource(), app = try setupStore(source)
        let started = expectation(description: "Chapter suspended before clear")
        source.started = { started.fulfill() }
        let load = Task { try await app.loadChapter(novel: source.novel, chapter: source.content.chapter.key) }
        await fulfillment(of: [started], timeout: 3)
        try await app.clearTemporaryCache()
        source.finish(.success(source.content))
        let loaded = try await load.value
        XCTAssertEqual(loaded.bodyText, "Account A private body")
        let usage = await app.cache.usage()
        XCTAssertEqual(usage.disk, 0)
        XCTAssertEqual(usage.memory, 0)
    }

    @MainActor func testDownloadedBookStartsWithoutWaitingForUnavailableNetwork() async throws {
        let source = ChapterGateSource()
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("MixnOfflineStart-" + UUID().uuidString)
        let content = source.content
        let novel = Novel(key: source.novel, title: content.novelTitle)
        let volume = Volume(key: content.chapter.volumeKey, novelKey: source.novel, title: content.volumeTitle)
        let record = OfflineRecord(novel: novel, volumes: [volume], chapters: [content.chapter], completed: 1,
                                   status: "complete", downloadedChapterIDs: [content.chapter.id],
                                   catalogComplete: true, volumesComplete: true)
        struct Checkpoint: Encodable {
            var records: [OfflineRecord]
            var progress: [String: ReadingProgress] = [:]
            var history: [HistoryEntry] = []
            var updated: Set<String> = []
            var snapshots: [String: String] = [:]
            var wifiOnly = true
        }
        let libraryRoot = root.appendingPathComponent("Library")
        let chapterURL = libraryRoot.appendingPathComponent("Books").appendingPathComponent(sha256(source.novel.id))
            .appendingPathComponent("Chapters").appendingPathComponent(sha256(content.chapter.id) + ".plist")
        try StorageIO.encode(content, to: chapterURL)
        try StorageIO.encode(Checkpoint(records: [record]), to: libraryRoot.appendingPathComponent("library.plist"))
        let app = try setupStore(source, rootURL: root)
        source.started = { [weak source] in source?.finish(.failure(SourceFailure.network("Offline"))) }
        let opened = expectation(description: "Downloaded book starts before network metadata timeout")
        let task = Task { @MainActor in
            let chapter = await BookDetailModel(key: source.novel).firstChapter(app: app)
            opened.fulfill()
            return chapter
        }
        await fulfillment(of: [opened], timeout: 2)
        task.cancel()
        let chapter = await task.value
        XCTAssertEqual(chapter?.key, content.chapter.key)
        let restored = try await app.loadChapter(novel: source.novel, chapter: content.chapter.key)
        XCTAssertEqual(restored.bodyText, content.bodyText)
    }

    @MainActor func testLogoutClearsProvisionalAndResolvedIdentityButNotAnotherAccount() async throws {
        let source = ChapterGateSource()
        source.session.accountID = "session:opaque"
        let app = try setupStore(source)
        let provisional = "user:light_novel_kingdom:session:opaque"
        let resolved = "user:light_novel_kingdom:A"
        let other = "user:light_novel_kingdom:B"
        try await app.cache.write(Data("provisional".utf8), key: "chapter", scope: provisional)
        source.session.accountID = "A"
        await app.refreshProfile(.kingdom)
        try await app.cache.write(Data("resolved".utf8), key: "chapter", scope: resolved)
        try await app.cache.write(Data("other account".utf8), key: "chapter", scope: other)
        try await app.logout(.kingdom)
        let old = await app.cache.read(key: "chapter", scope: provisional)
        let current = await app.cache.read(key: "chapter", scope: resolved)
        let retained = await app.cache.read(key: "chapter", scope: other)
        XCTAssertNil(old)
        XCTAssertNil(current)
        XCTAssertEqual(retained, Data("other account".utf8))
        XCTAssertFalse(app.sessions[.kingdom]?.loggedIn ?? true)
    }

    @MainActor func testFastAuthorCardAppearsWithoutWaitingForSlowCardAndKeepsSourceOrder() async throws {
        let source = ChapterGateSource(), app = try setupStore(source)
        let first = Novel(key: ResourceKey(.kingdom, "slow"), title: "慢卡")
        let known = Novel(key: ResourceKey(.kingdom, "known"), title: "已知", authors: ["已有作者"], synopsis: "已有简介")
        let fast = Novel(key: ResourceKey(.kingdom, "fast"), title: "快卡")
        source.list = [first, known, fast]
        let started = expectation(description: "Missing authors load concurrently")
        started.expectedFulfillmentCount = 2
        source.detailStarted = { _ in started.fulfill() }
        let model = NovelListModel(), channel = "test"
        await model.load(channel: channel, source: source, feed: .popular,
                         scope: "user:light_novel_kingdom:A", app: app)
        await fulfillment(of: [started], timeout: 3)
        XCTAssertEqual(model.state(channel).pending, [first.id, fast.id])
        XCTAssertEqual(model.state(channel).items[1].authors, ["已有作者"])
        let fastVisible = expectation(description: "Fast card resolves ahead of slow card")
        let observation = model.$states.sink { states in
            if states[channel]?.items.last?.authors == ["快作者"], states[channel]?.pending.contains(first.id) == true { fastVisible.fulfill() }
        }
        source.finishDetail(fast.key, authors: ["快作者"])
        await fulfillment(of: [fastVisible], timeout: 3)
        observation.cancel()
        XCTAssertEqual(model.state(channel).items.map(\.key), [first.key, known.key, fast.key])
        XCTAssertTrue(model.state(channel).pending.contains(first.id))
        let settled = expectation(description: "Slow card eventually resolves")
        let completion = model.$states.sink { states in
            if states[channel]?.pending.isEmpty == true { settled.fulfill() }
        }
        source.finishDetail(first.key, authors: ["慢作者"])
        await fulfillment(of: [settled], timeout: 3)
        completion.cancel()
        XCTAssertEqual(model.state(channel).items.map(\.authors), [["慢作者"], ["已有作者"], ["快作者"]])
        model.stop()
    }
}

@MainActor private final class ChapterGateSource: NovelSource {
    let sourceID: SourceID = .kingdom
    let feeds: [Feed] = [.popular]
    var session = AccountSession(loggedIn: true, accountID: "A")
    let novel = ResourceKey(.kingdom, "book")
    var content: ChapterContent {
        ChapterContent(chapter: Chapter(key: ResourceKey(.kingdom, "chapter"), novelKey: novel,
                                        volumeKey: ResourceKey(.kingdom, "volume"), title: "Chapter"),
                       novelTitle: "Book", volumeTitle: "Volume", bodyText: "Account A private body")
    }
    var started: (() -> Void)?
    var list: [Novel]?
    var detailStarted: ((ResourceKey) -> Void)?
    private var details: [ResourceKey: CheckedContinuation<NovelDetail, Error>] = [:]
    func finishDetail(_ key: ResourceKey, authors: [String]) {
        var novel = list!.first { $0.key == key }!
        novel.authors = authors
        details.removeValue(forKey: key)?.resume(returning: NovelDetail(novel: novel))
    }
    private var pending: CheckedContinuation<ChapterContent, Error>?
    func finish(_ value: Result<ChapterContent, Error>) { pending?.resume(with: value); pending = nil }
    func chapter(novel: ResourceKey, chapter: ResourceKey) async throws -> ChapterContent {
        try await withCheckedThrowingContinuation { pending = $0; started?() }
    }
    func restoreSession() async throws -> AccountSession { session }
    func login(identifier: String, password: String) async throws -> AccountSession { session = AccountSession(loggedIn: true, accountID: identifier); return session }
    func logout() async throws { session = AccountSession() }
    func profile() async throws -> SourceProfile { SourceProfile(source: sourceID, accountID: session.accountID) }
    func discover(feed: Feed, page: Int, pageSize: Int) async throws -> Page<Novel> {
        guard let list else { throw SourceFailure.unsupported("Not used") }
        return Page(items: list, total: list.count)
    }
    func search(query: String, page: Int, pageSize: Int) async throws -> Page<Novel> { throw SourceFailure.unsupported("Not used") }
    func detail(key: ResourceKey) async throws -> NovelDetail {
        guard list != nil else { throw SourceFailure.unsupported("Not used") }
        return try await withCheckedThrowingContinuation { details[key] = $0; detailStarted?(key) }
    }
    func volumes(key: ResourceKey, page: Int) async throws -> Page<Volume> {
        try await Task.sleep(nanoseconds: 30_000_000_000)
        throw SourceFailure.network("Offline metadata timeout")
    }
    func chapters(novel: ResourceKey, volume: ResourceKey, page: Int) async throws -> Page<Chapter> {
        try await Task.sleep(nanoseconds: 30_000_000_000)
        throw SourceFailure.network("Offline metadata timeout")
    }
    func shelf() async throws -> [Novel] { throw SourceFailure.unsupported("Not used") }
    func setShelf(key: ResourceKey, add: Bool) async throws { throw SourceFailure.unsupported("Not used") }
    func history(page: Int) async throws -> Page<HistoryEntry> { throw SourceFailure.unsupported("Not used") }
    func saveProgress(_ progress: ReadingProgress) async throws { throw SourceFailure.unsupported("Not used") }
    func rewards() async throws -> RewardStatus { throw SourceFailure.unsupported("Not used") }
    func claimReward() async throws -> RewardResult { throw SourceFailure.unsupported("Not used") }
}
