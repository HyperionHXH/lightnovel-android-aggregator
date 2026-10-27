import XCTest
import Foundation
import zlib
@testable import Mixn

final class ShelfSourceTests: XCTestCase {
    private func json(_ raw: String) throws -> JSONValue { try JSONDecoder().decode(JSONValue.self, from: Data(raw.utf8)) }

    func testCanonicalAuthorCasesAndDetailPrecedence() throws {
        struct AuthorCase: Decodable { let name: String; let input: JSONObject; let author: String? }
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "lns-author-cases", withExtension: "json", subdirectory: "contracts"))
        let cases = try JSONDecoder().decode([AuthorCase].self, from: Data(contentsOf: url))
        for fixture in cases {
            let expected = fixture.author.map { [$0] } ?? []
            XCTAssertEqual(try ShelfWire.book(fixture.input).authors, expected, fixture.name)
            var detail = fixture.input
            detail["Chapters"] = .array([])
            XCTAssertEqual(try ShelfWire.detail(.object(detail)).novel.authors, expected, fixture.name)
        }
    }

    func testLegacyCatalogAliasesDoNotLoseNoncontiguousSortNumbers() throws {
        let value = try json("""
        {"Book":{"Id":7,"Title":"作品","Chapters":null,"Chapter":[
          {"Id":90,"Title":"后章","SortNum":9},
          {"Id":20,"Title":"前章","SortNum":2},
          {"Id":30,"Title":"缺序号"}
        ]}}
        """)
        let detail = try ShelfWire.detail(value)
        XCTAssertEqual(detail.chapters.map(\.sort), [2, 3, 9])
        XCTAssertEqual(detail.chapters.map(\.id), [20, 30, 90])
        XCTAssertEqual(detail.novel.chapterCount, 3)
        let fallback = try json("""
        {"Id":7,"Title":"作品","Chapters":[],"ChapterList":{"Id":1,"Title":"正文","sortNumber":5}}
        """)
        XCTAssertEqual(try ShelfWire.detail(fallback).chapters.map(\.sort), [5])
        let duplicate = try json("""
        {"Id":7,"Title":"作品","Chapters":[{"Id":1,"Title":"甲","SortNum":2},{"Id":2,"Title":"乙","sortNum":2}]}
        """)
        XCTAssertThrowsError(try ShelfWire.detail(duplicate))
    }

    func testNestedContentTextAndWOFF2ArePreserved() throws {
        let value = try json("""
        {"Result":{"chapter":{"Id":99,"bookId":7,"title":"章节","sortNumber":9,
        "content":{"Html":"<p>正文</p>"},"fontUrl":"https://cdn.lightnovel.life/book.woff2",
        "ChapterTitles":["一","二"]}}}
        """)
        let content = try ShelfWire.content(value, bookID: 7, sort: 1)
        XCTAssertEqual(content.html, "<p>正文</p>")
        XCTAssertEqual(content.sort, 9)
        XCTAssertEqual(content.fontURL?.pathExtension, "woff2")
        XCTAssertEqual(content.titles, ["一", "二"])
    }

    func testQuotedAuthorSearchAndCoverFragmentRepair() {
        XCTAssertEqual(ShelfWire.quotedQuery(" 作者 "), "\"作者\"")
        XCTAssertEqual(ShelfWire.quotedQuery("\"作者\""), "\"作者\"")
        XCTAssertEqual(ShelfWire.cover("https://cdn.example/cover?w=120&placeholder=#abc&h=100")?.absoluteString, "https://cdn.example/cover?w=120&placeholder=%23abc&h=100")
        XCTAssertEqual(ShelfWire.cover("https://cdn.example/cover?placeholder=#abc")?.fragment, nil)
    }

    func testEnvelopeGzipArraysAndServerAuthenticationFailures() throws {
        let payload: JSONValue = .array([.object(["Id": 7, "Title": "作品"]), .object(["Id": 8, "Title": "作品二"])])
        let compressed = try gzip(JSONEncoder().encode(payload)).base64EncodedString()
        XCTAssertEqual(try ShelfResponseDecoder.unwrap(.object(["Success": true, "Response": j(compressed)])), payload)
        XCTAssertEqual(try ShelfResponseDecoder.unwrap(.object(["success": true, "response": .array([])])), .array([]))
        XCTAssertThrowsError(try ShelfResponseDecoder.unwrap(.object(["Success": false, "Status": -100, "Msg": "会话失效"]))) { error in
            XCTAssertEqual(error as? SourceFailure, .authentication("会话失效"))
        }
        XCTAssertThrowsError(try ShelfResponseDecoder.unwrap(.object(["Success": true, "Response": j(Data([0x1f, 0x8b, 0]).base64EncodedString())])))
        XCTAssertThrowsError(try ShelfResponseDecoder.unwrap(.object(["Response": 1])))
    }

    func testFrameParserHandlesPartialAndMultipleBinaryRecords() throws {
        var parser = ShelfSignalRFrames()
        XCTAssertTrue(try parser.append(Data("{\"type\":3,\"invocationId\":\"a\",".utf8)).isEmpty)
        let frames = try parser.append(Data("\"result\":1}\u{1e}{\"type\":6}\u{1e}".utf8))
        XCTAssertEqual(frames.count, 2)
        XCTAssertEqual(frames[0].string("invocationId"), "a")
        XCTAssertEqual(frames[1].int("type"), 6)
        XCTAssertThrowsError(try parser.append(Data("{bad}\u{1e}".utf8)))
    }

    func testMalformedContainersFailInsteadOfReturningSuccessfulEmptyLists() throws {
        XCTAssertThrowsError(try ShelfWire.bookArray(.object(["unexpected": .array([])])))
        XCTAssertThrowsError(try ShelfWire.bookArray(.array([.string("invalid book")])))
        XCTAssertThrowsError(try ShelfWire.shelf(.object(["unexpected": .array([])])))
        XCTAssertEqual(try ShelfWire.bookArray(.array([])).count, 0)
        XCTAssertEqual(try ShelfWire.shelf(.object(["data": .array([])])).items.count, 0)
    }

    func testShelfMutationRoundTripPreservesNestedFoldersAndUnknownFields() throws {
        let snapshot = try ShelfWire.shelf(json("""
        {"ver":"custom-version","data":[
          {"type":"FOLDER","id":"folder","title":"分类","index":0,"parents":[],"color":"red","children":[
            {"type":"BOOK","id":7,"index":0,"parents":["folder"],"updateAt":"old"},
            {"type":"FOLDER","id":"nested","index":1,"parents":["folder"],"title":"内层","children":[
              {"type":"BOOK","id":8,"index":0,"parents":["folder","nested"],"updateAt":"older"}
            ]}
          ]},
          {"type":"BOOK","id":9,"index":1,"parents":[]}
        ]}
        """))
        XCTAssertEqual(snapshot.items.compactMap(\.bookID), [7, 8, 9])
        let survivors = snapshot.items.filter { $0.bookID != 7 }
        let wire = ShelfWire.wireItems(ShelfWire.normalize(survivors))
        let folder = try XCTUnwrap(wire.first?.object)
        XCTAssertEqual(folder.string("color"), "red")
        XCTAssertEqual(folder.string("title"), "分类")
        XCTAssertEqual(folder.array("children").count, 1)
        XCTAssertEqual(folder.array("children").first?.object.array("children").first?.object.int("id"), 8)
        let restored = try ShelfWire.shelf(.object(["ver": snapshot.version!, "data": .array(wire)]))
        XCTAssertEqual(restored.items.compactMap(\.bookID), [8, 9])
        XCTAssertEqual(restored.items.first { $0.bookID == 8 }?.parents, ["folder", "nested"])
    }

    func testProfileUsesGrowthLevelAndNullableNextExperience() throws {
        let profile = try ShelfWire.profile(json("""
        {"Id":12,"UserName":"读者","Growth":{"Level":4,"GrowthLevel":2,"Coin":103,"SignStreak":9,
        "TodaySigned":true,"Exp":50,"CurrentLevelExp":20,"NextLevelExp":100}}
        """))
        XCTAssertEqual(profile.model.level, 2)
        XCTAssertEqual(profile.model.extra["访问等级"], "Lv.4")
        XCTAssertEqual(profile.model.experience?.fraction, 0.375)
        XCTAssertEqual(profile.coin, 103)
        let maximum = try ShelfWire.profile(json("{\"Id\":12,\"Growth\":{\"Exp\":999,\"NextLevelExp\":null}}"))
        XCTAssertTrue(maximum.model.experience?.maxLevel == true)
        let omitted = try ShelfWire.profile(json("{\"Id\":12,\"Growth\":{\"Exp\":999}}"))
        XCTAssertFalse(omitted.model.experience?.maxLevel == true)
    }

    @MainActor func testRateLimiterEnforcesGapAndRollingWindow() async throws {
        var time: TimeInterval = 0
        let limiter = ShelfRateLimiter(now: { time }, sleep: { time += $0 })
        var starts: [TimeInterval] = []
        for _ in 0..<12 { try await limiter.acquire(); starts.append(time) }
        for index in 1..<starts.count { XCTAssertGreaterThanOrEqual(starts[index] - starts[index - 1], 0.499999) }
        XCTAssertGreaterThanOrEqual(starts[9] - starts[0], 5.5)
    }

    @MainActor func testSignalRNegotiatesCorrelatesOutOfOrderAndPropagatesServerError() async throws {
        let fixture = ShelfTransportFixture()
        defer { fixture.close() }
        var calls: [JSONObject] = []
        let allSent = expectation(description: "both invocation frames sent")
        fixture.socket.onSend = { text in
            guard let message = try? JSONDecoder().decode(JSONValue.self, from: Data(text.dropLast().utf8)) else { return }
            if message.object.string("protocol") == "json" { fixture.socket.push("{}\u{1e}"); return }
            if message.object.int("type") == 1 {
                calls.append(message.object)
                if calls.count == 2 { allSent.fulfill() }
            }
        }
        let first = Task { try await fixture.hub.invoke("First", params: .object(["Id": 1]), token: "access-one") }
        let second = Task { try await fixture.hub.invoke("Second", params: .null, token: "access-one") }
        await fulfillment(of: [allSent], timeout: 2)
        let secondID = try XCTUnwrap(calls.first { $0.string("target") == "Second" }?.string("invocationId"))
        let firstID = try XCTUnwrap(calls.first { $0.string("target") == "First" }?.string("invocationId"))
        fixture.socket.push("{\"type\":3,\"invocationId\":\"\(secondID)\",\"result\":22}\u{1e}{\"type\":3,\"invocationId\":\"\(firstID)\",\"result\":11}\u{1e}")
        let firstResult = try await first.value, secondResult = try await second.value
        XCTAssertEqual(firstResult, 11); XCTAssertEqual(secondResult, 22)
        XCTAssertEqual(calls.first?.array("arguments").last?.object.bool("UseGzip"), true)
        XCTAssertEqual(fixture.requests.first?.url?.path, "/hub/api/negotiate")
        XCTAssertEqual(fixture.requests.first?.httpMethod, "POST")
        XCTAssertEqual(fixture.requests.first?.value(forHTTPHeaderField: "Authorization"), "Bearer access-one")
        XCTAssertEqual(fixture.socketRequest?.url?.scheme, "wss")
        let query = URLComponents(url: try XCTUnwrap(fixture.socketRequest?.url), resolvingAgainstBaseURL: false)?.queryItems
        XCTAssertEqual(query?.first { $0.name == "id" }?.value, "connection-token")
        XCTAssertEqual(query?.first { $0.name == "access_token" }?.value, "access-one")
        fixture.socket.onSend = { text in
            guard let root = try? JSONDecoder().decode(JSONValue.self, from: Data(text.dropLast().utf8)), root.object.int("type") == 1 else { return }
            fixture.socket.push("{\"type\":3,\"invocationId\":\"\(root.object.string("invocationId"))\",\"error\":\"unauthorized 401\"}\u{1e}")
        }
        do { _ = try await fixture.hub.invoke("Denied", params: .null, token: "access-one"); XCTFail("Expected server authentication error") }
        catch { XCTAssertEqual(error as? SourceFailure, .authentication("unauthorized 401")) }
    }

    @MainActor func testCanceledInvocationDoesNotReplayAndOtherInvocationSurvives() async throws {
        let fixture = ShelfTransportFixture(fallback: true)
        defer { fixture.close() }
        let canceledSent = expectation(description: "cancelable request sent")
        let survivorSent = expectation(description: "survivor request sent")
        var canceledID = "", survivorID = ""
        fixture.socket.onSend = { text in
            guard let root = try? JSONDecoder().decode(JSONValue.self, from: Data(text.dropLast().utf8)) else { return }
            let value = root.object
            if value.string("protocol") == "json" { fixture.socket.push("{}\u{1e}"); return }
            if value.string("target") == "Canceled" { canceledID = value.string("invocationId"); canceledSent.fulfill() }
            if value.string("target") == "Survivor" { survivorID = value.string("invocationId"); survivorSent.fulfill() }
        }
        let canceled = Task { try await fixture.hub.invoke("Canceled", params: .null, token: nil) }
        await fulfillment(of: [canceledSent], timeout: 2)
        canceled.cancel()
        do { _ = try await canceled.value; XCTFail("Expected cancellation") } catch { XCTAssertTrue(error is CancellationError) }
        let survivor = Task { try await fixture.hub.invoke("Survivor", params: .null, token: nil) }
        await fulfillment(of: [survivorSent], timeout: 2)
        fixture.socket.push("{\"type\":3,\"invocationId\":\"\(canceledID)\",\"result\":0}\u{1e}{\"type\":3,\"invocationId\":\"\(survivorID)\",\"result\":9}\u{1e}")
        let result = try await survivor.value
        XCTAssertEqual(result, 9)
        XCTAssertEqual(fixture.socket.sent.filter { $0.contains("Canceled") }.count, 1)
        XCTAssertEqual(fixture.requests.count, 1)
    }

    @MainActor func testTimeoutAndResetFinishEveryPendingContinuationAndStopReceive() async throws {
        let fixture = ShelfTransportFixture(invocationTimeout: 1)
        defer { fixture.close() }
        do { _ = try await fixture.hub.invoke("Timeout", params: .null, token: nil); XCTFail("Expected timeout") }
        catch { XCTAssertEqual(error as? SourceFailure, .timeout) }
        let sent = expectation(description: "requests waiting for reset")
        sent.expectedFulfillmentCount = 2
        fixture.socket.onSend = { text in if text.contains("\"type\":1") { sent.fulfill() } }
        let a = Task { try await fixture.hub.invoke("PendingA", params: .null, token: nil) }
        let b = Task { try await fixture.hub.invoke("PendingB", params: .null, token: nil) }
        await fulfillment(of: [sent], timeout: 2)
        fixture.hub.reset()
        do { _ = try await a.value; XCTFail("Expected reset cancellation") } catch { XCTAssertTrue(error is CancellationError) }
        do { _ = try await b.value; XCTFail("Expected reset cancellation") } catch { XCTAssertTrue(error is CancellationError) }
        XCTAssertTrue(fixture.socket.closed)
        XCTAssertFalse(fixture.socket.hasReceiver)
    }

    @MainActor func testFailedPrimaryNegotiationUsesFallbackWithoutReplayingServerFailure() async throws {
        let fixture = ShelfTransportFixture(fallback: true)
        defer { fixture.close() }
        fixture.primaryStatus = 503
        fixture.socket.onSend = { text in
            if text.contains("\"protocol\"") { fixture.socket.push("{}\u{1e}"); return }
            guard let root = try? JSONDecoder().decode(JSONValue.self, from: Data(text.dropLast().utf8)), root.object.int("type") == 1 else { return }
            fixture.socket.push("{\"type\":3,\"invocationId\":\"\(root.object.string("invocationId"))\",\"error\":\"not allowed\"}\u{1e}")
        }
        do { _ = try await fixture.hub.invoke("Rejected", params: .null, token: nil); XCTFail("Expected server error") }
        catch { XCTAssertEqual(error as? SourceFailure, .server("not allowed")) }
        XCTAssertEqual(fixture.requests.map { $0.url?.host ?? "" }, ["primary.test", "fallback.test"])
        XCTAssertEqual(fixture.socket.sent.filter { $0.contains("Rejected") }.count, 1)
    }

    @MainActor func testLoginRefreshAndLogoutIsolateShelfKeychainAndHashPassword() async throws {
        let fixture = ShelfTransportFixture()
        defer { fixture.close() }
        let secrets = ShelfMemorySecrets()
        secrets.values["light_novel_kingdom.session"] = "kingdom-secret"
        var loginBody: JSONObject = [:], refreshBody: JSONObject = [:]
        fixture.httpResponse = { request in
            let body = (try? JSONDecoder().decode(JSONValue.self, from: Self.requestBody(request)))?.object ?? [:]
            if request.url?.path == "/api/user/login" {
                loginBody = body
                return (200, Data("{\"Success\":true,\"Response\":{\"Token\":\"first-token\",\"RefreshToken\":\"refresh-secret\"}}".utf8))
            }
            if request.url?.path == "/api/user/refresh_token" {
                refreshBody = body
                return (200, Data("{\"Success\":true,\"Response\":\"new-token\"}".utf8))
            }
            return nil
        }
        let source = ShelfSource(http: fixture.http, secrets: secrets, hub: fixture.hub, limiter: fixture.limiter, origins: fixture.origins)
        let login = try await source.login(identifier: " reader@example.com ", password: "password")
        XCTAssertTrue(login.loggedIn)
        XCTAssertEqual(loginBody.string("email"), "reader@example.com")
        XCTAssertEqual(loginBody.string("password"), "5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8")
        let restored = try await source.restoreSession()
        XCTAssertTrue(restored.loggedIn)
        XCTAssertEqual(refreshBody.string("token"), "refresh-secret")
        XCTAssertTrue(secrets.values["light_novel_shelf.tokens.v1"]?.contains("new-token") == true)
        let stableIdentity = try XCTUnwrap(source.session.accountID)
        fixture.socket.onSend = { text in
            guard let root = try? JSONDecoder().decode(JSONValue.self, from: Data(text.dropLast().utf8)) else { return }
            if root.object.string("protocol") == "json" { fixture.socket.push("{}\u{1e}"); return }
            guard root.object.int("type") == 1 else { return }
            fixture.socket.push("{\"type\":3,\"invocationId\":\"\(root.object.string("invocationId"))\",\"result\":{\"Id\":0,\"UserName\":\"读者\",\"Growth\":{}}}\u{1e}")
        }
        _ = try await source.profile()
        XCTAssertEqual(source.session.accountID, stableIdentity, "A missing UID must not collapse private caches into a shared unknown identity")
        try await source.logout()
        XCTAssertNil(secrets.values["light_novel_shelf.tokens.v1"])
        XCTAssertEqual(secrets.values["light_novel_kingdom.session"], "kingdom-secret")
        XCTAssertFalse(source.session.loggedIn)
    }

    @MainActor func testMetadataCacheCoalescesFourLoadsAndClearPreventsStaleRefill() async throws {
        let cache = ShelfMetadataCache()
        let fourLoads = expectation(description: "four concurrent metadata loads")
        fourLoads.expectedFulfillmentCount = 4
        var calls = 0, active = 0, maximum = 0
        var loaders: [Int64: CheckedContinuation<ShelfBookDetail, Never>] = [:]
        func load(_ id: Int64) async -> ShelfBookDetail {
            calls += 1; active += 1; maximum = max(maximum, active)
            if calls <= 4 { fourLoads.fulfill() }
            let result: ShelfBookDetail = await withCheckedContinuation { loaders[id] = $0 }
            active -= 1
            return result
        }
        let owner = Task { try await cache.get(id: 1) { await load(1) } }
        let tasks = [owner] + (2...6).map { index in Task { try await cache.get(id: Int64(index)) { await load(Int64(index)) } } }
        let coalesced = Task { try await cache.get(id: 1) { XCTFail("Duplicate metadata loader"); return self.metadata(1) } }
        await fulfillment(of: [fourLoads], timeout: 2)
        XCTAssertEqual(maximum, 4); XCTAssertEqual(calls, 4)
        cache.clear()
        for (id, continuation) in loaders { continuation.resume(returning: metadata(id)) }
        loaders.removeAll()
        for task in tasks {
            do { _ = try await task.value; XCTFail("Cache clear must cancel waiters") }
            catch { XCTAssertTrue(error is CancellationError) }
        }
        do { _ = try await coalesced.value; XCTFail("Cache clear must cancel coalesced waiter") }
        catch { XCTAssertTrue(error is CancellationError) }
        let fresh = try await cache.get(id: 1) { self.metadata(1, title: "fresh") }
        XCTAssertEqual(fresh.novel.title, "fresh")
        let reused = try await cache.get(id: 1) { XCTFail("Expected cached metadata"); return self.metadata(1) }
        XCTAssertEqual(reused.novel.title, "fresh")
    }

    @MainActor func testMetadataCacheExpiresAndEvictsByEntryAndCharacterBounds() async throws {
        var time: TimeInterval = 0
        let cache = ShelfMetadataCache(now: { time })
        _ = try await cache.get(id: 1) { self.metadata(1) }
        time = 301
        var reloads = 0
        _ = try await cache.get(id: 1) { reloads += 1; return self.metadata(1) }
        XCTAssertEqual(reloads, 1)
        for id in 2...65 { _ = try await cache.get(id: Int64(id)) { self.metadata(Int64(id)) } }
        _ = try await cache.get(id: 1) { reloads += 1; return self.metadata(1) }
        XCTAssertEqual(reloads, 2)
        let huge = metadata(99, title: String(repeating: "x", count: 512 * 1024 + 1))
        _ = try await cache.get(id: 99) { huge }
        var oversizedReloaded = false
        _ = try await cache.get(id: 99) { oversizedReloaded = true; return huge }
        XCTAssertTrue(oversizedReloaded)
    }

    @MainActor func testCancellationDuringHandshakeClosesSocketWithoutFallback() async throws {
        let fixture = ShelfTransportFixture(fallback: true)
        defer { fixture.close() }
        let handshake = expectation(description: "handshake waiting for server")
        fixture.socket.onSend = { text in if text.contains("\"protocol\"") { handshake.fulfill() } }
        let request = Task { try await fixture.hub.invoke("NeverSent", params: .null, token: nil) }
        await fulfillment(of: [handshake], timeout: 2)
        request.cancel()
        do { _ = try await request.value; XCTFail("Expected handshake cancellation") }
        catch { XCTAssertTrue(error is CancellationError) }
        XCTAssertTrue(fixture.socket.closed)
        XCTAssertFalse(fixture.socket.hasReceiver)
        XCTAssertEqual(fixture.requests.count, 1)
        XCTAssertFalse(fixture.socket.sent.contains { $0.contains("NeverSent") })
    }

    @MainActor func testCanceledMetadataWaiterDoesNotCancelSharedLoad() async throws {
        let cache = ShelfMetadataCache()
        let started = expectation(description: "shared metadata loader started")
        var complete: CheckedContinuation<ShelfBookDetail, Never>?
        let owner = Task {
            try await cache.get(id: 1) {
                started.fulfill()
                return await withCheckedContinuation { complete = $0 }
            }
        }
        await fulfillment(of: [started], timeout: 2)
        let survivor = Task { try await cache.get(id: 1) { XCTFail("Unexpected second loader"); return self.metadata(1) } }
        await Task.yield()
        owner.cancel()
        do { _ = try await owner.value; XCTFail("Expected canceled waiter") }
        catch { XCTAssertTrue(error is CancellationError) }
        complete?.resume(returning: metadata(1, title: "survivor"))
        let value = try await survivor.value
        XCTAssertEqual(value.novel.title, "survivor")
    }

    @MainActor func testChapterNavigationUsesCatalogSortAndProgressUsesChapterID() async throws {
        let fixture = ShelfTransportFixture()
        defer { fixture.close() }
        let secrets = ShelfMemorySecrets()
        fixture.httpResponse = { request in
            if request.url?.path == "/api/user/login" {
                return (200, Data("{\"Success\":true,\"Response\":{\"Token\":\"token\",\"RefreshToken\":\"refresh\"}}".utf8))
            }
            return nil
        }
        var progress: JSONObject?
        fixture.socket.onSend = { text in
            guard let root = try? JSONDecoder().decode(JSONValue.self, from: Data(text.dropLast().utf8)) else { return }
            let message = root.object
            if message.string("protocol") == "json" { fixture.socket.push("{}\u{1e}"); return }
            guard message.int("type") == 1 else { return }
            let response: JSONValue
            switch message.string("target") {
            case "GetBookInfo":
                response = .object(["Id": 7, "Title": "作品", "Author": "作者", "Chapters": .array([
                    .object(["Id": 20, "Title": "前", "SortNum": 2]), .object(["Id": 50, "Title": "中", "SortNum": 5]), .object(["Id": 90, "Title": "后", "SortNum": 9])])])
            case "GetNovelContent":
                response = .object(["Chapter": .object(["Id": 50, "BookId": 7, "Title": "中", "SortNum": 5, "Content": "<p>正文</p>", "Font": "https://cdn.example/required.woff2"])])
            case "SaveReadPosition": progress = message.array("arguments").first?.object; response = .null
            default: response = .null
            }
            let envelope: JSONObject = ["type": 3, "invocationId": j(message.string("invocationId")), "result": .object(["Success": true, "Response": response])]
            let data = try! JSONEncoder().encode(JSONValue.object(envelope))
            fixture.socket.push(String(data: data, encoding: .utf8)! + "\u{1e}")
        }
        let source = ShelfSource(http: fixture.http, secrets: secrets, hub: fixture.hub, limiter: fixture.limiter, origins: fixture.origins)
        _ = try await source.login(identifier: "reader@example.com", password: "password")
        let novel = ResourceKey(.shelf, "7"), chapter = ResourceKey(.shelf, "5")
        let content = try await source.chapter(novel: novel, chapter: chapter)
        XCTAssertEqual(content.previous?.remoteID, "2")
        XCTAssertEqual(content.next?.remoteID, "9")
        XCTAssertEqual(content.fontURL?.pathExtension, "woff2")
        try await source.saveProgress(ReadingProgress(novelKey: novel, chapterKey: chapter, paragraphIndex: 10, percent: 40))
        XCTAssertEqual(progress?.int("Bid"), 7)
        XCTAssertEqual(progress?.int("Cid"), 50)
        XCTAssertEqual(progress?.string("XPath"), ".")
    }

    @MainActor func testSignInReportsObservedBalanceAndExperienceDifferences() async throws {
        let fixture = ShelfTransportFixture()
        defer { fixture.close() }
        fixture.httpResponse = { request in
            request.url?.path == "/api/user/login" ? (200, Data("{\"Response\":{\"Token\":\"token\",\"RefreshToken\":\"refresh\"}}".utf8)) : nil
        }
        var signed = false, signIns = 0
        fixture.socket.onSend = { text in
            guard let value = try? JSONDecoder().decode(JSONValue.self, from: Data(text.dropLast().utf8)) else { return }
            let message = value.object
            if message.string("protocol") == "json" { fixture.socket.push("{}\u{1e}"); return }
            guard message.int("type") == 1 else { return }
            let response: JSONValue
            if message.string("target") == "SignIn" {
                signIns += 1; signed = true
                response = .object(["Streak": 4, "Reward": 999])
            } else {
                response = .object(["Id": 1, "UserName": "读者", "Growth": .object([
                    "Coin": j(signed ? 107 : 100), "Exp": j(signed ? 23 : 20), "GrowthLevel": 2,
                    "TodaySigned": j(signed), "SignStreak": j(signed ? 4 : 3)])])
            }
            let frame: JSONObject = ["type": 3, "invocationId": j(message.string("invocationId")), "result": .object(["Success": true, "Response": response])]
            let data = try! JSONEncoder().encode(JSONValue.object(frame))
            fixture.socket.push(String(data: data, encoding: .utf8)! + "\u{1e}")
        }
        let source = ShelfSource(http: fixture.http, secrets: ShelfMemorySecrets(), hub: fixture.hub, limiter: fixture.limiter, origins: fixture.origins)
        _ = try await source.login(identifier: "reader@example.com", password: "password")
        let reward = try await source.claimReward()
        XCTAssertEqual(reward.coins, 7)
        XCTAssertEqual(reward.experience, 3)
        XCTAssertEqual(reward.balance, 107)
        XCTAssertEqual(reward.streak, 4)
        let already = try await source.claimReward()
        XCTAssertEqual(already.coins, 0)
        XCTAssertEqual(signIns, 1)
    }

    @MainActor func testSourceSearchShelfMutationAndHistoryUseProductionRequests() async throws {
        let fixture = ShelfTransportFixture()
        defer { fixture.close() }
        fixture.httpResponse = { request in
            request.url?.path == "/api/user/login" ? (200, Data("{\"Response\":{\"Token\":\"token\",\"RefreshToken\":\"refresh\"}}".utf8)) : nil
        }
        var requests: [(String, JSONObject)] = []
        var saved: JSONObject?
        fixture.socket.onSend = { text in
            guard let value = try? JSONDecoder().decode(JSONValue.self, from: Data(text.dropLast().utf8)) else { return }
            let message = value.object
            if message.string("protocol") == "json" { fixture.socket.push("{}\u{1e}"); return }
            guard message.int("type") == 1 else { return }
            let target = message.string("target"), params = message.array("arguments").first?.object ?? [:]
            requests.append((target, params))
            let book: JSONValue = .object(["Id": 7, "Title": "作品", "Author": "作者"])
            let response: JSONValue
            switch target {
            case "GetBookList": response = .object(["Page": 1, "TotalPages": 1, "Data": .array([book])])
            case "GetRank": response = .array([book])
            case "GetBookShelf": response = .object(["ver": "keep-version", "data": .array([
                .object(["type": "FOLDER", "id": "folder", "index": 0, "parents": .array([]), "title": "分类", "custom": "preserve"]),
                .object(["type": "BOOK", "id": 7, "index": 0, "parents": .array(["folder"]), "updateAt": "original-date"])])])
            case "SaveBookShelf": saved = params; response = .null
            case "GetBookListByIds": response = .array([book])
            case "GetReadHistory": response = .object(["Novel": .array([7, 7])])
            default: response = .null
            }
            let frame: JSONObject = ["type": 3, "invocationId": j(message.string("invocationId")), "result": .object(["Success": true, "Response": response])]
            let data = try! JSONEncoder().encode(JSONValue.object(frame))
            fixture.socket.push(String(data: data, encoding: .utf8)! + "\u{1e}")
        }
        let source = ShelfSource(http: fixture.http, secrets: ShelfMemorySecrets(), hub: fixture.hub, limiter: fixture.limiter, origins: fixture.origins)
        _ = try await source.login(identifier: "reader@example.com", password: "password")
        let search = try await source.search(query: " 作者 ", page: 1, pageSize: 24)
        XCTAssertEqual(search.items.first?.authors, ["作者"])
        XCTAssertEqual(requests.last?.1.string("KeyWords"), "\"作者\"")
        XCTAssertEqual(requests.last?.1.bool("IgnoreJapanese"), false)
        _ = try await source.discover(feed: .monthly, page: 1, pageSize: 24)
        XCTAssertEqual(requests.last?.0, "GetRank")
        XCTAssertEqual(requests.last?.1.int("Days"), 31)
        let shelf = try await source.shelf()
        XCTAssertEqual(shelf.map(\.key.remoteID), ["7"])
        XCTAssertEqual(shelf.first?.inShelf, true)
        try await source.setShelf(key: ResourceKey(.shelf, "8"), add: true)
        XCTAssertEqual(saved?.string("ver"), "keep-version")
        XCTAssertEqual(saved?.array("data").first?.object.int("id"), 8)
        let folder = saved?.array("data").map(\.object).first { $0.string("id") == "folder" }
        XCTAssertEqual(folder?.string("custom"), "preserve")
        let existing = saved?.array("data").map(\.object).first { $0.int("id") == 7 }
        XCTAssertEqual(existing?.array("parents"), ["folder"])
        XCTAssertEqual(existing?.string("updateAt"), "original-date")
        let history = try await source.history(page: 1)
        XCTAssertEqual(history.items.count, 1)
        XCTAssertEqual(history.items.first?.novel.key.remoteID, "7")
        do { try await source.deleteHistory(key: ResourceKey(.shelf, "7")); XCTFail("Remote deletion is unsupported") }
        catch { guard let failure = error as? SourceFailure, case .unsupported = failure else { XCTFail("Expected unsupported"); return } }
    }

    private func metadata(_ id: Int64, title: String = "book") -> ShelfBookDetail {
        ShelfBookDetail(novel: Novel(key: ResourceKey(.shelf, String(id)), title: title), favorites: 0, chapters: [])
    }

    private static func requestBody(_ request: URLRequest) -> Data {
        if let data = request.httpBody { return data }
        guard let stream = request.httpBodyStream else { return Data() }
        stream.open()
        defer { stream.close() }
        var result = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while true {
            let count = stream.read(&buffer, maxLength: buffer.count)
            if count <= 0 { break }
            result.append(contentsOf: buffer.prefix(count))
        }
        return result
    }

    private func gzip(_ data: Data) throws -> Data {
        var stream = z_stream()
        guard deflateInit2_(&stream, Z_DEFAULT_COMPRESSION, Z_DEFLATED, MAX_WBITS + 16, 8, Z_DEFAULT_STRATEGY, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size)) == Z_OK else { throw SourceFailure.parsing("test gzip initialization") }
        defer { deflateEnd(&stream) }
        return try data.withUnsafeBytes { input in
            stream.next_in = UnsafeMutablePointer(mutating: input.bindMemory(to: Bytef.self).baseAddress)
            stream.avail_in = uInt(data.count)
            var result = Data(), status: Int32 = Z_OK
            repeat {
                var chunk = [UInt8](repeating: 0, count: 4096)
                let produced = chunk.withUnsafeMutableBytes { output -> Int in
                    stream.next_out = output.bindMemory(to: Bytef.self).baseAddress
                    stream.avail_out = uInt(output.count)
                    status = deflate(&stream, Z_FINISH)
                    return output.count - Int(stream.avail_out)
                }
                guard status == Z_OK || status == Z_STREAM_END else { throw SourceFailure.parsing("test gzip compression") }
                result.append(contentsOf: chunk.prefix(produced))
            } while status != Z_STREAM_END
            return result
        }
    }
}

private final class ShelfMemorySecrets: SecretStore {
    var values: [String: String] = [:]
    func read(_ key: String) throws -> String? { values[key] }
    func write(_ key: String, value: String?) throws { values[key] = value }
}

private final class ShelfFixtureURLProtocol: URLProtocol {
    static var respond: ((URLRequest) -> (Int, Data))?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let request = request
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            let result = Self.respond?(request) ?? (500, Data())
            let response = HTTPURLResponse(url: request.url!, statusCode: result.0, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!
            self.client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            self.client?.urlProtocol(self, didLoad: result.1)
            self.client?.urlProtocolDidFinishLoading(self)
        }
    }
    override func stopLoading() {}
}

private final class ShelfFixtureSocket: ShelfSocket {
    var onSend: ((String) -> Void)?
    private let lock = NSLock()
    private var records: [String] = []
    private var messages: [Data] = []
    private var receiver: CheckedContinuation<Data, Error>?
    private var canceled = false
    var sent: [String] { lock.lock(); defer { lock.unlock() }; return records }
    var closed: Bool { lock.lock(); defer { lock.unlock() }; return canceled }
    var hasReceiver: Bool { lock.lock(); defer { lock.unlock() }; return receiver != nil }
    func resume() {}
    func send(_ text: String) async throws {
        try Task.checkCancellation()
        record(text)
        onSend?(text)
    }
    private func record(_ text: String) { lock.lock(); records.append(text); lock.unlock() }
    func receive() async throws -> Data {
        try await withCheckedThrowingContinuation { continuation in
            lock.lock()
            if canceled { lock.unlock(); continuation.resume(throwing: CancellationError()); return }
            if !messages.isEmpty { let data = messages.removeFirst(); lock.unlock(); continuation.resume(returning: data); return }
            receiver = continuation
            lock.unlock()
        }
    }
    func push(_ text: String) {
        let data = Data(text.utf8)
        lock.lock()
        if canceled { lock.unlock(); return }
        if let receiver = receiver { self.receiver = nil; lock.unlock(); receiver.resume(returning: data) }
        else { messages.append(data); lock.unlock() }
    }
    func cancel() {
        lock.lock()
        canceled = true
        let receiver = self.receiver; self.receiver = nil
        lock.unlock()
        receiver?.resume(throwing: CancellationError())
    }
}

@MainActor private final class ShelfTransportFixture {
    let socket = ShelfFixtureSocket()
    let http: HTTPClient
    let limiter: ShelfRateLimiter
    let origins: [URL]
    private(set) var hub: ShelfSignalR!
    private(set) var requests: [URLRequest] = []
    private(set) var socketRequest: URLRequest?
    var primaryStatus = 200
    var httpResponse: ((URLRequest) -> (Int, Data)?)?
    init(fallback: Bool = false, invocationTimeout: TimeInterval = 1) {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ShelfFixtureURLProtocol.self]
        http = HTTPClient(session: URLSession(configuration: configuration))
        var clock: TimeInterval = 0
        limiter = ShelfRateLimiter(now: { clock }, sleep: { clock += $0 })
        origins = fallback ? [URL(string: "https://primary.test")!, URL(string: "https://fallback.test")!] : [URL(string: "https://primary.test")!]
        ShelfFixtureURLProtocol.respond = { [weak self] request in
            guard let self = self else { return (500, Data()) }
            self.requests.append(request)
            if let response = self.httpResponse?(request) { return response }
            let status = request.url?.host == "primary.test" ? self.primaryStatus : 200
            return (status, Data("{\"connectionToken\":\"connection-token\",\"availableTransports\":[{\"transport\":\"WebSockets\"}]}".utf8))
        }
        hub = ShelfSignalR(http: http, limiter: limiter, origins: origins, handshakeTimeout: 1, invocationTimeout: invocationTimeout, socketFactory: { [weak self] request in
            self!.socketRequest = request
            return self!.socket
        })
        socket.onSend = { [weak socket] text in if text.contains("\"protocol\"") { socket?.push("{}\u{1e}") } }
    }
    func close() {
        hub.reset()
        socket.onSend = nil
        http.session.invalidateAndCancel()
        ShelfFixtureURLProtocol.respond = nil
    }
}
