import Foundation
import XCTest
@testable import Mixn

/// Opt-in native URLSession/WebSocket smoke. No account, secrets, writes or saved novels.
final class LiveSourceSmokeTests: XCTestCase {
    private final class NoAccountSecrets: SecretStore {
        func read(_ key: String) throws -> String? { nil }
        func write(_ key: String, value: String?) throws { throw SourceFailure.authentication("Anonymous smoke cannot save a session") }
    }
    @MainActor func testKingdomAnonymousListDetailCatalogIdentity() async throws {
        guard ProcessInfo.processInfo.environment["RUN_MIXN_LIVE_SMOKE"] == "1" else { throw XCTSkip("Explicit native live source smoke only") }
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("MixnLive-" + UUID().uuidString)
        addTeardownBlock { if FileManager.default.fileExists(atPath: root.path) { try FileManager.default.removeItem(at: root) } }
        let source = KingdomSource(secrets: NoAccountSecrets(), cache: ContentCache(rootURL: root))
        let page = try await source.discover(feed: .popular, page: 1, pageSize: 2)
        let selected = try XCTUnwrap(page.items.first)
        XCTAssertEqual(selected.key.source, .kingdom)
        let detail = try await source.detail(key: selected.key)
        XCTAssertEqual(detail.novel.key, selected.key)
        XCTAssertEqual(detail.novel.title, selected.title)
        let volumes = try await source.volumes(key: selected.key, page: 1)
        let volume = try XCTUnwrap(volumes.items.first)
        XCTAssertEqual(volume.novelKey, selected.key)
        let chapters = try await source.chapters(novel: selected.key, volume: volume.key, page: 1)
        let chapter = try XCTUnwrap(chapters.items.first)
        XCTAssertEqual(chapter.volumeKey, volume.key)
        XCTAssertEqual(chapter.novelKey, selected.key)
        XCTAssertFalse(source.session.loggedIn)
        print("Native anonymous LK list/detail/volume/chapter identity verified; no body or private API exercised")
    }
    @MainActor func testShelfAnonymousHandshakePreservesRealAuthenticationFailure() async throws {
        guard ProcessInfo.processInfo.environment["RUN_MIXN_LIVE_SMOKE"] == "1" else { throw XCTSkip("Explicit native live source smoke only") }
        let source = ShelfSource(secrets: NoAccountSecrets())
        do {
            _ = try await source.search(query: "刀剑神域", page: 1, pageSize: 2)
            XCTFail("Anonymous search unexpectedly bypassed the source authentication requirement")
        } catch let failure as SourceFailure {
            guard case .authentication = failure else { throw failure }
        }
        XCTAssertFalse(source.session.loggedIn)
        print("Native anonymous LNS handshake/server authentication boundary verified; logged-in books and authors remain unverified")
    }
}
