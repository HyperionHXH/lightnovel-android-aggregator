import Foundation
import XCTest
@testable import Mixn

final class KingdomSourceTests: XCTestCase {
    @MainActor func testCanonicalBookFixtureMatchesAndroidBoundary() throws {
        let url = try XCTUnwrap(Bundle(for: KingdomSourceTests.self).url(forResource: "lk-book", withExtension: "json", subdirectory: "contracts"))
        let value = try JSONDecoder().decode(JSONValue.self, from: Data(contentsOf: url))
        let book = KingdomJSON.book(value.object)
        XCTAssertEqual(book.key, ResourceKey(.kingdom, "7506"))
        XCTAssertEqual(book.authors, ["测试作者"])
        XCTAssertEqual(book.tags, ["测试", "排版"])
        XCTAssertEqual(book.volumeCount, 1)
        XCTAssertEqual(book.chapterCount, 9)
        XCTAssertEqual(book.unreadCount, 3)
        XCTAssertEqual(book.wordCount, 218791)
    }

    @MainActor func testBooksPageAliasesAndScoreConversion() throws {
        let value = try json(#"{"cards":[{"book_id":7,"book_title":"书","authorName":"作者","reason_tags":[{"label":"标签"}],"stats":{"chapter_count":18,"word_count":42},"rating":{"score_10":8.6},"in_shelf":true,"unread_chapter_count":0}],"pageInfo":{"cur":2,"count":99,"size":20,"has_next":true}}"#)
        let page = KingdomJSON.booksPage(value.object, requestedPage: 2, requestedPageSize: 20)
        XCTAssertEqual(page.page, 2)
        XCTAssertEqual(page.total, 99)
        XCTAssertTrue(page.hasMore)
        let book = try XCTUnwrap(page.items.first)
        XCTAssertEqual(book.authors, ["作者"])
        XCTAssertEqual(book.tags, ["标签"])
        XCTAssertEqual(book.chapterCount, 18)
        XCTAssertEqual(book.score ?? 0, 4.3, accuracy: 0.0001)
        XCTAssertEqual(book.inShelf, true)
        XCTAssertEqual(book.unreadCount, 0)
    }

    @MainActor func testNestedCommentMediaReplyAndInteractionAliases() throws {
        let data = try json(#"{"list":[{"commentId":20,"content":"","user":{"nickname":"图片用户"},"resources":[{"res_url":"https://example.test/photo.jpg","w":20,"h":30,"res_id":"media"}],"stats":{"likes":8,"conversation_count":2},"interactionState":{"is_liked":true},"reply_list":[{"comment_id":21,"content_text":"回复","root_comment_id":20,"reply_to_user":[{"nickname":"图片用户"}],"author":{"name":"回复者"}}]}],"page_info":{"count":9,"next":2}}"#)
        let page = KingdomJSON.commentsPage(data.object, page: 1)
        XCTAssertTrue(page.hasMore)
        let item = try XCTUnwrap(page.items.first)
        XCTAssertEqual(item.media.count, 1)
        XCTAssertEqual(item.media.first?.resourceID, "media")
        XCTAssertEqual(item.likeCount, 8)
        XCTAssertEqual(item.replyCount, 2)
        XCTAssertTrue(item.liked)
        XCTAssertEqual(item.replies.first?.rootID, "20")
        XCTAssertEqual(item.replies.first?.replyTo, "图片用户")
    }

    @MainActor func testLockedPriceAndNestedNavigationAliases() throws {
        let data = try json(#"{"chapter_id":8,"volume_id":4,"title":"锁定","unlocked":false,"price":{"amount":"6.0"},"body_snapshot":{"body_text":"正文","body_html":"<p>正文</p>"},"nav":{"prev":{"chapter":{"chapter_id":7}},"next":[{"id":9}]}}"#)
        let content = KingdomJSON.chapterContent(data.object, novel: ResourceKey(.kingdom, "1"), requestedChapter: ResourceKey(.kingdom, "8"), unlocked: [])
        XCTAssertTrue(content.chapter.locked)
        XCTAssertEqual(content.chapter.coinPrice, 6)
        XCTAssertEqual(content.previous, ResourceKey(.kingdom, "7"))
        XCTAssertEqual(content.next, ResourceKey(.kingdom, "9"))
        XCTAssertEqual(content.bodyText, "正文")
        XCTAssertEqual(content.bodyHTML, "<p>正文</p>")
    }

    @MainActor func testExperienceUsesActualValueWithoutInventingThresholds() throws {
        let absent = KingdomJSON.profile(try json(#"{"profile":{"uid":7,"nickname":"reader","level":2}}"#).object)
        XCTAssertNil(absent.experience)
        XCTAssertEqual(absent.levelLabel, "骑士")
        let actual = KingdomJSON.profile(try json(#"{"profile":{"uid":7,"nickname":"reader","level":2,"exp":0}}"#).object)
        XCTAssertEqual(actual.experience?.total, 0)
        XCTAssertNil(actual.experience?.nextLevel)
        XCTAssertNil(actual.experience?.fraction)
    }

    @MainActor func testAnonymousCommentReadsUseCommentHostAndOptionalEnvelope() async throws {
        let source = makeSource { request in
            XCTAssertEqual(request.url?.absoluteString, "https://api.lightnovel.fun/pc-comment-proxy/api/new-content-read/get-book-comments")
            let body = try Self.body(request)
            XCTAssertNil(body["security_key"])
            XCTAssertEqual(body["comment_sort"] as? String, "latest")
            XCTAssertEqual(body["include_user_interactions"] as? Int, 1)
            return Self.response(#"{"code":0,"d":{"list":[],"page_info":{"count":0}}}"#)
        }
        let result = try await source.comments(key: ResourceKey(.kingdom, "7"), latest: true, page: 1)
        XCTAssertTrue(result.items.isEmpty)
    }

    @MainActor func testSessionScopedKeychainAndRequestSecurityKeyOverride() async throws {
        let secrets = KingdomMemorySecrets()
        secrets.values["light_novel_shelf.security_key"] = "shelf-secret"
        let source = makeSource(secrets: secrets) { request in
            let body = try Self.body(request)
            if request.url?.lastPathComponent == "auth-password-login-v1" {
                XCTAssertEqual(body["username"] as? String, "reader")
                XCTAssertNil(body["security_key"])
                return Self.response(#"{"code":0,"data":{"auth":{"security_key":"lk-secret","uid":7},"user":{"nickname":"reader"}}}"#)
            }
            XCTAssertEqual(body["security_key"] as? String, "lk-secret")
            return Self.response(#"{"code":0,"data":{"list":[]}}"#)
        }
        let loggedIn = try await source.login(identifier: " reader ", password: "password")
        XCTAssertEqual(loggedIn.accountID, "7")
        _ = try await source.request(path: "api/bff/user-following-v1", body: ["security_key": j("caller-key")])
        XCTAssertEqual(secrets.values["light_novel_shelf.security_key"], "shelf-secret")
        try await source.logout()
        XCTAssertNil(secrets.values["light_novel_kingdom.security_key"])
        XCTAssertEqual(secrets.values["light_novel_shelf.security_key"], "shelf-secret")
    }

    @MainActor func testRankIsFixedThirtySnapshotAndSearchUsesZeroBasedPage() async throws {
        let source = makeSource { request in
            let body = try Self.body(request)
            if request.url?.lastPathComponent == "book-rank-list-v1" {
                XCTAssertEqual(body["rank_scene"] as? String, "weekly_hot")
                XCTAssertEqual(body["page_size"] as? Int, 30)
                return Self.response(#"{"code":0,"data":{"list":[{"book_id":7,"title":"书"}],"page_info":{"next":2,"count":30}}}"#)
            }
            XCTAssertEqual(request.url?.lastPathComponent, "apk-search-result-v1")
            XCTAssertEqual(body["page"] as? Int, 1)
            XCTAssertNotNil(body["filters"] as? [String: Any])
            return Self.response(#"{"code":0,"data":{"list":[],"page_info":{"count":0}}}"#)
        }
        let rank = try await source.discover(feed: .weekly, page: 1, pageSize: 20)
        XCTAssertFalse(rank.hasMore)
        _ = try await source.search(query: "reader", page: 2, pageSize: 20)
    }

    @MainActor func testMissingAuthenticationAndBusinessFailureAreErrors() async throws {
        let anonymous = makeSource { _ in Self.response(#"{"code":0,"data":{"success":false,"message":"余额不足"}}"#) }
        do { _ = try await anonymous.shelf(); XCTFail("An anonymous shelf request must fail") }
        catch let error as SourceFailure { guard case .authentication = error else { return XCTFail("Expected authentication error") } }
        let secrets = KingdomMemorySecrets()
        secrets.values["light_novel_kingdom.security_key"] = "lk-secret"
        let source = makeSource(secrets: secrets) { _ in Self.response(#"{"code":0,"data":{"success":false,"message":"余额不足"}}"#) }
        do { _ = try await source.request(path: "api/new-content-read/unlock-chapter"); XCTFail("Business failure must not become empty success") }
        catch let error as SourceFailure { XCTAssertEqual(error, .server("余额不足")) }
    }

    @MainActor func testPublishReplyUsesStructuredMentionsMediaStringAndRatingScope() async throws {
        let secrets = KingdomMemorySecrets()
        secrets.values["light_novel_kingdom.security_key"] = "lk-secret"
        let source = makeSource(secrets: secrets) { request in
            let body = try Self.body(request)
            XCTAssertEqual(request.url?.lastPathComponent, "publish-book-comment")
            if body["view"] as? String == "rating" {
                XCTAssertEqual(body["rating_stars"] as? Int, 4)
                XCTAssertEqual(body["content"] as? String, "")
                return Self.response(#"{"code":0,"data":{"success":true}}"#)
            }
            XCTAssertEqual(body["root_comment_id"] as? Int, 10)
            XCTAssertEqual(body["reply_comment_id"] as? Int, 11)
            XCTAssertEqual(body["mention_uids"] as? [Int], [7, 8])
            let media = try XCTUnwrap(body["media_json"] as? String).data(using: .utf8)!
            XCTAssertEqual((try JSONSerialization.jsonObject(with: media) as? [[String: Any]])?.first?["res_id"] as? String, "resource")
            return Self.response(#"{"code":0,"data":{"comment":{"comment_id":25,"content":"reply","user":{"nickname":"reader"}}}}"#)
        }
        let image = CommentMedia(url: URL(string: "https://example.test/image.jpg")!, width: 10, height: 20, resourceID: "resource")
        let reply = try await source.publishComment(key: ResourceKey(.kingdom, "7"), draft: CommentDraft(content: "reply", rootID: "10", replyID: "11", mentions: ["7", "8"], media: [image]))
        XCTAssertEqual(reply.id, "25")
        try await source.rate(key: ResourceKey(.kingdom, "7"), stars: 4)
    }

    @MainActor func testMalformedListResponseCannotBecomeEmptySuccess() async throws {
        let source = makeSource { _ in Self.response(#"{"code":0,"data":{"unexpected":"shape"}}"#) }
        do {
            _ = try await source.search(query: "book", page: 1, pageSize: 20)
            XCTFail("A malformed list must throw")
        } catch let failure as SourceFailure {
            guard case .parsing = failure else { return XCTFail("Expected a parsing failure") }
        }
    }

    @MainActor func testRegistrationUsesExactRoutesAndPersistsResultingSession() async throws {
        let secrets = KingdomMemorySecrets()
        let source = makeSource(secrets: secrets) { request in
            let body = try Self.body(request)
            switch request.url?.lastPathComponent {
            case "auth-email-status-v1":
                XCTAssertEqual(body["email"] as? String, "mail@example.test")
                return Self.response(#"{"code":0,"data":{"registered":false}}"#)
            case "auth-email-register-captcha-send-v1":
                return Self.response(#"{"code":0,"data":{"success":true}}"#)
            case "auth-email-register-v1":
                XCTAssertEqual(body["captcha"] as? String, "123456")
                XCTAssertEqual(body["code"] as? String, "123456")
                XCTAssertEqual(body["nickname"] as? String, "reader")
                return Self.response(#"{"code":0,"data":{"security_key":"registered-key","user":{"uid":8,"nickname":"reader"}}}"#)
            default: throw URLError(.unsupportedURL)
            }
        }
        try await source.sendRegistrationCode(email: " mail@example.test ")
        let session = try await source.register(email: "mail@example.test", captcha: " 123456 ", nickname: " reader ", password: "password")
        XCTAssertEqual(session.accountID, "8")
        XCTAssertEqual(secrets.values["light_novel_kingdom.security_key"], "registered-key")
    }

    @MainActor func testMissingUIDUsesSessionHashForSourceAndOfflineRestoreIdentity() async throws {
        let secrets = KingdomMemorySecrets()
        secrets.values["light_novel_kingdom.security_key"] = "existing-key"
        secrets.values["light_novel_kingdom.account_session"] = #"{"loggedIn":true}"#
        let existing = makeSource(secrets: secrets) { _ in throw URLError(.notConnectedToInternet) }
        XCTAssertEqual(existing.session.accountID, "session:" + sha256("existing-key"))
        let restored = try await existing.restoreSession()
        XCTAssertEqual(restored.accountID, "session:" + sha256("existing-key"))
        let source = makeSource(secrets: secrets) { _ in Self.response(#"{"code":0,"data":{"security_key":"new-key"}}"#) }
        let session = try await source.login(identifier: "reader", password: "password")
        XCTAssertEqual(session.accountID, "session:" + sha256("new-key"))
        XCTAssertNotEqual(session.accountID, restored.accountID)
    }

    @MainActor private func makeSource(
        secrets: KingdomMemorySecrets = KingdomMemorySecrets(),
        handler: @escaping (URLRequest) throws -> (Int, Data)
    ) -> KingdomSource {
        let id = UUID().uuidString
        KingdomFixtureProtocol.register(id: id, handler: handler)
        addTeardownBlock { KingdomFixtureProtocol.unregister(id: id) }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [KingdomFixtureProtocol.self]
        configuration.httpAdditionalHeaders = ["X-Kingdom-Fixture": id]
        configuration.httpCookieStorage = nil
        configuration.urlCache = nil
        let http = HTTPClient(session: URLSession(configuration: configuration))
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("KingdomTests-" + id, isDirectory: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: root) }
        return KingdomSource(http: http, secrets: secrets, cache: ContentCache(rootURL: root))
    }

    private func json(_ raw: String) throws -> JSONValue { try JSONDecoder().decode(JSONValue.self, from: Data(raw.utf8)) }
    private static func response(_ raw: String) -> (Int, Data) { (200, Data(raw.utf8)) }
    private static func body(_ request: URLRequest) throws -> [String: Any] {
        let data: Data
        if let body = request.httpBody { data = body }
        else if let stream = request.httpBodyStream {
            stream.open()
            defer { stream.close() }
            var result = Data()
            var buffer = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {
                let read = stream.read(&buffer, maxLength: buffer.count)
                if read < 0 { throw stream.streamError ?? URLError(.cannotDecodeRawData) }
                if read == 0 { break }
                result.append(buffer, count: read)
            }
            data = result
        } else { throw URLError(.cannotDecodeRawData) }
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
}

private final class KingdomMemorySecrets: SecretStore {
    var values: [String: String] = [:]
    func read(_ key: String) throws -> String? { values[key] }
    func write(_ key: String, value: String?) throws { values[key] = value }
}

private final class KingdomFixtureProtocol: URLProtocol {
    private static let lock = NSLock()
    private static var handlers: [String: (URLRequest) throws -> (Int, Data)] = [:]
    static func register(id: String, handler: @escaping (URLRequest) throws -> (Int, Data)) {
        lock.lock(); defer { lock.unlock() }
        handlers[id] = handler
    }
    static func unregister(id: String) { lock.lock(); defer { lock.unlock() }; handlers[id] = nil }
    override class func canInit(with request: URLRequest) -> Bool { request.value(forHTTPHeaderField: "X-Kingdom-Fixture") != nil }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            Self.lock.lock()
            let handler = request.value(forHTTPHeaderField: "X-Kingdom-Fixture").flatMap { Self.handlers[$0] }
            Self.lock.unlock()
            guard let handler else { throw URLError(.unsupportedURL) }
            let (status, data) = try handler(request)
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() {}
}
