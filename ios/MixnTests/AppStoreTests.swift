import XCTest
import Foundation
@testable import Mixn

final class AppStoreTests: XCTestCase {
    @MainActor private func setupStore(_ source: ChapterGateSource) throws -> AppStore {
        let id = UUID().uuidString
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("MixnAccountTests-" + id)
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
    private var pending: CheckedContinuation<ChapterContent, Error>?
    func finish(_ value: Result<ChapterContent, Error>) { pending?.resume(with: value); pending = nil }
    func chapter(novel: ResourceKey, chapter: ResourceKey) async throws -> ChapterContent {
        try await withCheckedThrowingContinuation { pending = $0; started?() }
    }
    func restoreSession() async throws -> AccountSession { session }
    func login(identifier: String, password: String) async throws -> AccountSession { session = AccountSession(loggedIn: true, accountID: identifier); return session }
    func logout() async throws { session = AccountSession() }
    func profile() async throws -> SourceProfile { SourceProfile(source: sourceID, accountID: session.accountID) }
    func discover(feed: Feed, page: Int, pageSize: Int) async throws -> Page<Novel> { throw SourceFailure.unsupported("Not used") }
    func search(query: String, page: Int, pageSize: Int) async throws -> Page<Novel> { throw SourceFailure.unsupported("Not used") }
    func detail(key: ResourceKey) async throws -> NovelDetail { throw SourceFailure.unsupported("Not used") }
    func volumes(key: ResourceKey, page: Int) async throws -> Page<Volume> { throw SourceFailure.unsupported("Not used") }
    func chapters(novel: ResourceKey, volume: ResourceKey, page: Int) async throws -> Page<Chapter> { throw SourceFailure.unsupported("Not used") }
    func shelf() async throws -> [Novel] { throw SourceFailure.unsupported("Not used") }
    func setShelf(key: ResourceKey, add: Bool) async throws { throw SourceFailure.unsupported("Not used") }
    func history(page: Int) async throws -> Page<HistoryEntry> { throw SourceFailure.unsupported("Not used") }
    func saveProgress(_ progress: ReadingProgress) async throws { throw SourceFailure.unsupported("Not used") }
    func rewards() async throws -> RewardStatus { throw SourceFailure.unsupported("Not used") }
    func claimReward() async throws -> RewardResult { throw SourceFailure.unsupported("Not used") }
}
