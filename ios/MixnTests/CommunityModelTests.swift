import Foundation
import XCTest
@testable import Mixn

final class CommunityModelTests: XCTestCase {
    @MainActor func testSuspendingPreservesRowsAndRejectsLateResponseThenReloads() async throws {
        let gate = DispatchSemaphore(value: 0)
        let started = expectation(description: "Community response is held")
        let listRequests = CommunityFixtureCounter()
        let state = CommunityFixtureState { request in
            if request.url?.path.hasSuffix("message-unread-v1") == true { return #"{"unread_count":0}"# }
            switch listRequests.next() {
            case 1: return #"{"data":{"list":[{"message_id":"preserved","content":"existing"}]}}"#
            case 2:
                started.fulfill()
                gate.wait()
                return #"{"data":{"list":[{"message_id":"stale","content":"stale"}]}}"#
            default: return #"{"data":{"list":[{"message_id":"fresh","content":"fresh"}]}}"#
            }
        }
        let app = try makeApp(state: state)
        let model = CommunityModel()
        await model.load(app: app, kind: .messages, category: .reply)
        XCTAssertEqual(model.notificationIDs, ["preserved"])
        model.suspend()
        XCTAssertEqual(model.notificationIDs, ["preserved"])

        let load = Task { await model.load(app: app, kind: .messages, category: .reply) }
        await fulfillment(of: [started], timeout: 3)
        model.suspend()
        XCTAssertFalse(model.loading)
        XCTAssertEqual(model.notificationIDs, ["preserved"])
        gate.signal()
        await load.value
        XCTAssertEqual(model.notificationIDs, ["preserved"])

        await model.load(app: app, kind: .messages, category: .reply)
        XCTAssertEqual(model.notificationIDs, ["fresh"])
    }

    @MainActor func testAccountContextChangeClearsRowsBeforeLoadingNewAccount() async throws {
        let secondStarted = expectation(description: "Second account response is held")
        let releaseSecond = DispatchSemaphore(value: 0)
        let listRequests = CommunityFixtureCounter()
        let state = CommunityFixtureState { request in
            if request.url?.path.hasSuffix("message-unread-v1") == true { return #"{"unread_count":0}"# }
            if listRequests.next() == 1 { return #"{"data":{"list":[{"message_id":"account-A","content":"private A"}]}}"# }
            secondStarted.fulfill()
            releaseSecond.wait()
            return #"{"data":{"list":[{"message_id":"account-B","content":"private B"}]}}"#
        }
        let app = try makeApp(state: state)
        let model = CommunityModel()
        await model.load(app: app, kind: .messages, category: .reply)
        XCTAssertEqual(model.notificationIDs, ["account-A"])

        app.sessions[.kingdom] = AccountSession(loggedIn: true, accountID: "account-B")
        let load = Task { await model.load(app: app, kind: .messages, category: .reply) }
        await fulfillment(of: [secondStarted], timeout: 3)
        XCTAssertTrue(model.notificationIDs.isEmpty)
        XCTAssertTrue(model.loading)
        releaseSecond.signal()
        await load.value
        XCTAssertEqual(model.notificationIDs, ["account-B"])
        model.suspend()
    }

    @MainActor private func makeApp(state: CommunityFixtureState) throws -> AppStore {
        let id = UUID().uuidString
        CommunityFixtureProtocol.register(id: id, state: state)
        addTeardownBlock { CommunityFixtureProtocol.unregister(id: id) }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [CommunityFixtureProtocol.self]
        configuration.httpAdditionalHeaders = ["X-Community-Fixture": id]
        configuration.httpCookieStorage = nil
        configuration.urlCache = nil
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("MixnCommunityTests-" + id, isDirectory: true)
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "MixnCommunityTests." + id))
        addTeardownBlock {
            defaults.removePersistentDomain(forName: "MixnCommunityTests." + id)
            try? FileManager.default.removeItem(at: root)
        }
        let secrets = CommunityMemorySecrets()
        secrets.values["light_novel_kingdom.security_key"] = "fixture-key"
        secrets.values["light_novel_kingdom.account_session"] = #"{"loggedIn":true,"accountID":"account-A"}"#
        let cache = ContentCache(rootURL: root.appendingPathComponent("cache"))
        let source = KingdomSource(http: HTTPClient(session: URLSession(configuration: configuration)), secrets: secrets, cache: cache)
        return AppStore(sources: [.kingdom: source, .shelf: UITestSource(.shelf)],
                        preferences: PreferencesStore(defaults: defaults),
                        library: LibraryStore(rootURL: root), fonts: FontStore(rootURL: root), cache: cache)
    }
}

private final class CommunityMemorySecrets: SecretStore {
    var values: [String: String] = [:]
    func read(_ key: String) throws -> String? { values[key] }
    func write(_ key: String, value: String?) throws { values[key] = value }
}

private final class CommunityFixtureState {
    let respond: (URLRequest) -> String
    init(respond: @escaping (URLRequest) -> String) { self.respond = respond }
}

private final class CommunityFixtureCounter {
    private let lock = NSLock()
    private var value = 0
    func next() -> Int { lock.lock(); defer { lock.unlock() }; value += 1; return value }
}

private final class CommunityFixtureProtocol: URLProtocol {
    private static let lock = NSLock()
    private static var states: [String: CommunityFixtureState] = [:]
    static func register(id: String, state: CommunityFixtureState) { lock.lock(); defer { lock.unlock() }; states[id] = state }
    static func unregister(id: String) { lock.lock(); defer { lock.unlock() }; states[id] = nil }
    override class func canInit(with request: URLRequest) -> Bool { request.value(forHTTPHeaderField: "X-Community-Fixture") != nil }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            Self.lock.lock()
            let state = request.value(forHTTPHeaderField: "X-Community-Fixture").flatMap { Self.states[$0] }
            Self.lock.unlock()
            guard let state else { throw URLError(.unsupportedURL) }
            let data = Data(state.respond(request).utf8)
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() {}
}
