import Foundation

@MainActor final class ShelfSource: NovelSource {
    let sourceID: SourceID = .shelf
    let feeds: [Feed] = [.popular, .latest, .newest, .daily, .weekly, .monthly]
    private(set) var session = AccountSession()
    private let http: HTTPClient
    private let secrets: SecretStore
    private let limiter: ShelfRateLimiter
    private let hub: ShelfSignalR
    private let metadata = ShelfMetadataCache()
    private let authGate = ShelfAsyncGate()
    private let shelfGate = ShelfAsyncGate()
    private let rewardGate = ShelfAsyncGate()
    private let origins: [URL]
    private let secretKey = "light_novel_shelf.tokens.v1"
    private var tokens: Tokens?
    private var accountGeneration: UInt64 = 0
    private var activeAuthTask: Task<HTTPResult, Error>?
    private struct Tokens: Codable { var access: String; var refresh: String }

    init(http: HTTPClient = HTTPClient(), secrets: SecretStore = KeychainStore()) {
        self.http = http
        self.secrets = secrets
        let limiter = ShelfRateLimiter()
        self.limiter = limiter
        origins = [URL(string: "https://api.lightnovel.life")!, URL(string: "https://cf-api.lightnovel.life")!]
        hub = ShelfSignalR(http: http, limiter: limiter)
    }

    /// The injectable transport keeps protocol tests on the same production request path.
    init(http: HTTPClient, secrets: SecretStore, hub: ShelfSignalR, limiter: ShelfRateLimiter, origins: [URL]) {
        self.http = http; self.secrets = secrets; self.hub = hub; self.limiter = limiter; self.origins = origins
    }

    func restoreSession() async throws -> AccountSession {
        try await authGate.run {
            if self.tokens == nil, let encoded = try self.secrets.read(self.secretKey) {
                guard let data = encoded.data(using: .utf8), let stored = try? JSONDecoder().decode(Tokens.self, from: data), !stored.access.isEmpty, !stored.refresh.isEmpty else {
                    try self.clearTokens()
                    throw SourceFailure.authentication("轻书架保存的会话无效，请重新登录")
                }
                self.tokens = stored
            }
            guard self.tokens != nil else { self.session = AccountSession(); return self.session }
            _ = try await self.refreshLocked()
            return self.session
        }
    }

    func login(identifier: String, password: String) async throws -> AccountSession {
        let email = identifier.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !email.isEmpty, !password.isEmpty else { throw SourceFailure.authentication("请输入邮箱和密码") }
        let generation = accountGeneration
        return try await authGate.run {
            guard generation == self.accountGeneration else { throw CancellationError() }
            let root = try await self.authPost(path: "/api/user/login", body: ["email": j(email), "password": j(sha256(password))], login: true)
            try Task.checkCancellation()
            guard generation == self.accountGeneration else { throw CancellationError() }
            let nested = root.object("Response", "response")
            let response = nested.isEmpty ? root : nested
            let access = response.string("Token", "token")
            let refresh = response.string("RefreshToken", "refreshToken")
            guard !access.isEmpty, !refresh.isEmpty else { throw SourceFailure.parsing("轻书架登录响应缺少会话令牌") }
            try self.persist(Tokens(access: access, refresh: refresh))
            self.accountGeneration &+= 1
            self.session = AccountSession(loggedIn: true, accountID: "session:" + sha256(refresh))
            self.clearMetadataCache()
            self.hub.reset()
            return self.session
        }
    }

    func logout() async throws {
        try clearTokens()
    }

    private func persist(_ value: Tokens) throws {
        let data = try JSONEncoder().encode(value)
        guard let text = String(data: data, encoding: .utf8) else { throw SourceFailure.parsing("无法保存轻书架会话") }
        try secrets.write(secretKey, value: text)
        tokens = value
    }

    private func clearTokens() throws {
        accountGeneration &+= 1
        activeAuthTask?.cancel()
        activeAuthTask = nil
        tokens = nil
        session = AccountSession()
        hub.reset()
        clearMetadataCache()
        try secrets.write(secretKey, value: nil)
    }

    private func refreshLocked() async throws -> Bool {
        guard let current = tokens else { return false }
        let generation = accountGeneration
        do {
            let response = try await authPost(path: "/api/user/refresh_token", body: ["token": j(current.refresh)], login: false)
            try Task.checkCancellation()
            guard generation == accountGeneration else { throw CancellationError() }
            let value = response.value("Response", "response", "Token", "token")
            let access = value.text ?? value.object.string("Token", "token")
            guard !access.isEmpty else { throw SourceFailure.parsing("轻书架刷新响应缺少会话令牌") }
            try persist(Tokens(access: access, refresh: current.refresh))
            session = AccountSession(loggedIn: true, accountID: session.accountID ?? "session:" + sha256(current.refresh), displayName: session.displayName)
            hub.reset()
            return true
        } catch let error as SourceFailure {
            if case .authentication = error {
                guard generation == accountGeneration else { throw CancellationError() }
                try clearTokens()
                return false
            }
            throw error
        }
    }

    private func refresh(after rejectedToken: String?) async throws -> Bool {
        try await authGate.run {
            try Task.checkCancellation()
            if let current = self.tokens, current.access != rejectedToken { return true }
            return try await self.refreshLocked()
        }
    }

    private func authPost(path: String, body: JSONObject, login: Bool) async throws -> JSONObject {
        let generation = accountGeneration
        for (index, origin) in origins.enumerated() {
            try Task.checkCancellation()
            guard generation == accountGeneration else { throw CancellationError() }
            do {
                try await limiter.acquire()
                guard generation == accountGeneration else { throw CancellationError() }
                var request = URLRequest(url: origin.appendingPathComponent(String(path.dropFirst())))
                request.httpMethod = "POST"
                request.httpBody = try JSONEncoder().encode(JSONValue.object(body))
                request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
                request.setValue("application/json", forHTTPHeaderField: "Accept")
                let response = try await sendAuth(request)
                try Task.checkCancellation()
                guard generation == accountGeneration else { throw CancellationError() }
                if response.status >= 500, index + 1 < origins.count { continue }
                let object = (try? JSONDecoder().decode(JSONValue.self, from: response.data))?.object ?? [:]
                let rawMessage = object.string("Msg", "msg", "message", "Error", "error")
                let message = rawMessage.isEmpty ? "轻书架服务器返回 \(response.status)" : rawMessage
                if response.status == 429 { throw SourceFailure.rateLimited }
                if response.status == 401 || (!login && response.status == 404) || (login && (400..<500).contains(response.status)) {
                    throw SourceFailure.authentication(message)
                }
                guard (200..<300).contains(response.status) else {
                    if response.status >= 500 { throw SourceFailure.server(message) }
                    throw SourceFailure.network(message)
                }
                guard !object.isEmpty else { throw SourceFailure.parsing("轻书架返回了无法识别的数据") }
                try ShelfResponseDecoder.throwFailure(object, authenticationContext: login)
                return object
            } catch is CancellationError { throw CancellationError() }
            catch {
                try Task.checkCancellation()
                if index + 1 < origins.count {
                    if let failure = error as? SourceFailure {
                        if case .network = failure { continue }
                        if case .timeout = failure { continue }
                    } else if error is URLError { continue }
                }
                throw error
            }
        }
        throw SourceFailure.network("无法连接轻书架")
    }

    private func sendAuth(_ request: URLRequest) async throws -> HTTPResult {
        let task = Task { try await http.send(request) }
        activeAuthTask = task
        defer { activeAuthTask = nil }
        return try await withTaskCancellationHandler(operation: {
            let result = try await task.value
            try Task.checkCancellation()
            return result
        }, onCancel: { task.cancel() })
    }

    private func invoke(_ target: String, _ params: JSONValue = .null, requiresAccount: Bool = false) async throws -> JSONValue {
        if requiresAccount && !session.loggedIn { throw SourceFailure.authentication("请先登录轻书架") }
        let generation = accountGeneration
        let access = tokens?.access
        do {
            let response = try await hub.invoke(target, params: params, token: access)
            try Task.checkCancellation()
            guard generation == accountGeneration else { throw CancellationError() }
            return try ShelfResponseDecoder.unwrap(response)
        } catch let error as SourceFailure {
            guard case .authentication = error, access != nil else { throw error }
            try Task.checkCancellation()
            guard generation == accountGeneration else { throw CancellationError() }
            guard try await refresh(after: access) else { throw SourceFailure.authentication("轻书架登录已失效，请重新登录") }
            try Task.checkCancellation()
            let response = try await hub.invoke(target, params: params, token: tokens?.access)
            try Task.checkCancellation()
            guard generation == accountGeneration else { throw CancellationError() }
            return try ShelfResponseDecoder.unwrap(response)
        }
    }

    func discover(feed: Feed, page: Int, pageSize: Int) async throws -> Page<Novel> {
        guard feeds.contains(feed) else { throw SourceFailure.unsupported("轻书架不支持该发现分类") }
        let page = max(1, page), size = min(50, max(1, pageSize))
        let days: Int?
        switch feed { case .daily: days = 1; case .weekly: days = 7; case .monthly: days = 31; default: days = nil }
        if let days = days {
            let response = try await invoke("GetRank", .object(["Days": j(days)]))
            let all = try ShelfWire.bookArray(response).map(ShelfWire.book)
            let start = min(all.count, (page - 1) * size), end = min(all.count, start + size)
            let novels = try await enrich(Array(all[start..<end]))
            return Page(items: novels, page: page, total: all.count, hasMore: end < all.count)
        }
        let order = feed == .popular ? "view" : feed == .latest ? "latest" : "new"
        let value = try await invoke("GetBookList", .object(["Page": j(page), "Size": j(size), "Order": j(order), "IgnoreJapanese": true, "IgnoreAI": true]))
        return try await bookPage(value, requestedPage: page, size: size)
    }

    func search(query: String, page: Int, pageSize: Int) async throws -> Page<Novel> {
        let page = max(1, page), size = min(50, max(1, pageSize))
        let value = try await invoke("GetBookList", .object(["KeyWords": j(ShelfWire.quotedQuery(query)), "Page": j(page), "Size": j(size), "IgnoreJapanese": false, "IgnoreAI": false]))
        return try await bookPage(value, requestedPage: page, size: size)
    }

    private func bookPage(_ value: JSONValue, requestedPage: Int, size: Int) async throws -> Page<Novel> {
        var root = try ShelfWire.object(value, name: "书籍列表")
        for key in ["Data", "data", "Books", "books"] { if case .object(let nested) = root[key] { root = nested; break } }
        let page = max(1, root.optionalInt("Page", "page") ?? requestedPage)
        let pages = max(1, root.optionalInt("TotalPages", "totalPages", "PageCount", "pageCount") ?? 1)
        let values = try ShelfWire.array(root, keys: ["Data", "data", "Books", "books", "Items", "items"], name: "书籍列表")
        let novels = try values.map { try ShelfWire.book(ShelfWire.object($0, name: "书籍条目")) }
        let enriched = try await enrich(novels)
        return Page(items: enriched, page: page, total: page >= pages ? (page - 1) * size + novels.count : pages * size, hasMore: page < pages)
    }

    private func enrich(_ novels: [Novel]) async throws -> [Novel] {
        try await withThrowingTaskGroup(of: (Int, Novel).self) { group in
            for (index, novel) in novels.enumerated() where novel.authors.isEmpty {
                group.addTask { @MainActor [weak self] in
                    try Task.checkCancellation()
                    guard let self = self else { throw CancellationError() }
                    do {
                        let detail = try await self.bookDetail(novel.key)
                        var result = novel
                        if result.authors.isEmpty { result.authors = detail.novel.authors }
                        if result.synopsis.isEmpty { result.synopsis = detail.novel.synopsis }
                        if result.coverURL == nil { result.coverURL = detail.novel.coverURL }
                        return (index, result)
                    } catch is CancellationError { throw CancellationError() }
                    catch { return (index, novel) }
                }
            }
            var output = novels
            for try await (index, novel) in group { output[index] = novel }
            try Task.checkCancellation()
            return output
        }
    }

    private func bookDetail(_ key: ResourceKey) async throws -> ShelfBookDetail {
        let id = try bookID(key)
        return try await metadata.get(id: id) { [weak self] in
            guard let self = self else { throw CancellationError() }
            return try ShelfWire.detail(try await self.invoke("GetBookInfo", .object(["Id": j(id)])))
        }
    }

    func detail(key: ResourceKey) async throws -> NovelDetail {
        let detail = try await bookDetail(key)
        return NovelDetail(novel: detail.novel, favorites: detail.favorites)
    }

    func volumes(key: ResourceKey, page: Int) async throws -> Page<Volume> {
        let detail = try await bookDetail(key)
        return Page(items: page <= 1 ? [Volume(key: ResourceKey(.shelf, "default"), novelKey: detail.novel.key, title: "正文", chapterCount: detail.chapters.count)] : [], page: max(1, page), total: 1)
    }

    func chapters(novel: ResourceKey, volume: ResourceKey, page: Int) async throws -> Page<Chapter> {
        guard volume.source == .shelf, volume.remoteID == "default" else { throw SourceFailure.parsing("无效的轻书架分卷") }
        let detail = try await bookDetail(novel)
        let page = max(1, page), start = min(detail.chapters.count, (page - 1) * 50), end = min(detail.chapters.count, start + 50)
        let chapters = detail.chapters[start..<end].map { Chapter(key: ResourceKey(.shelf, String($0.sort)), novelKey: novel, volumeKey: volume, title: $0.title, order: $0.sort) }
        return Page(items: chapters, page: page, total: detail.chapters.count, hasMore: end < detail.chapters.count)
    }

    func chapter(novel: ResourceKey, chapter: ResourceKey) async throws -> ChapterContent {
        let id = try bookID(novel), sort = try sortNumber(chapter)
        let response = try await invoke("GetNovelContent", .object(["Bid": j(id), "SortNum": j(sort)]))
        let content = try ShelfWire.content(response, bookID: id, sort: sort)
        let detail: ShelfBookDetail?
        do { detail = try await bookDetail(novel) }
        catch is CancellationError { throw CancellationError() }
        catch { detail = nil }
        let catalog = detail?.chapters.map(\.sort) ?? []
        let index = catalog.firstIndex(of: content.sort)
        let previous: Int?, next: Int?
        if let index = index {
            previous = index > 0 ? catalog[index - 1] : nil
            next = index + 1 < catalog.count ? catalog[index + 1] : nil
        } else {
            previous = content.sort > 1 ? content.sort - 1 : nil
            next = content.titles.isEmpty || content.sort < content.titles.count ? content.sort + 1 : nil
        }
        let summary = Chapter(key: ResourceKey(.shelf, String(content.sort)), novelKey: ResourceKey(.shelf, String(content.bookID)), volumeKey: ResourceKey(.shelf, "default"), title: content.title, order: content.sort)
        return ChapterContent(chapter: summary, novelTitle: detail?.novel.title ?? "", volumeTitle: "正文", bodyText: "", bodyHTML: content.html, fontURL: content.fontURL, previous: previous.map { ResourceKey(.shelf, String($0)) }, next: next.map { ResourceKey(.shelf, String($0)) })
    }

    func shelf() async throws -> [Novel] {
        let generation = accountGeneration
        let snapshot = try ShelfWire.shelf(try await invoke("GetBookShelf", requiresAccount: true))
        let ids = ShelfWire.unique(snapshot.items.compactMap(\.bookID))
        let books = try await booksByIDs(ids)
        var result = ids.compactMap { books[$0] }
        for index in result.indices { result[index].inShelf = true }
        let novels = try await enrich(result)
        guard generation == accountGeneration else { throw CancellationError() }
        return novels
    }

    func setShelf(key: ResourceKey, add: Bool) async throws {
        let id = try bookID(key)
        let generation = accountGeneration
        try await shelfGate.run {
            guard generation == self.accountGeneration else { throw CancellationError() }
            var snapshot = try ShelfWire.shelf(try await self.invoke("GetBookShelf", requiresAccount: true))
            guard generation == self.accountGeneration else { throw CancellationError() }
            let present = snapshot.items.contains { $0.bookID == id }
            if present == add { return }
            if add {
                snapshot.items.insert(ShelfRemoteItem(raw: ["id": j(id), "index": -1, "parents": .array([]), "type": "BOOK", "updateAt": j(ISO8601DateFormatter().string(from: Date()))], type: .book, id: String(id), index: -1, parents: []), at: 0)
            } else { snapshot.items.removeAll { $0.bookID == id } }
            let normalized = ShelfWire.normalize(snapshot.items)
            _ = try await self.invoke("SaveBookShelf", .object(["data": .array(ShelfWire.wireItems(normalized)), "ver": snapshot.version ?? "20220211"]), requiresAccount: true)
        }
    }

    private func booksByIDs(_ ids: [Int64]) async throws -> [Int64: Novel] {
        var books: [Int64: Novel] = [:]
        let ids = ShelfWire.unique(ids)
        for start in stride(from: 0, to: ids.count, by: 24) {
            let batch = Array(ids[start..<min(ids.count, start + 24)])
            let response = try await invoke("GetBookListByIds", .object(["Ids": .array(batch.map(j))]))
            for object in try ShelfWire.bookArray(response) {
                let novel = try ShelfWire.book(object)
                if let id = Int64(novel.key.remoteID) { books[id] = novel }
            }
        }
        return books
    }

    func history(page: Int) async throws -> Page<HistoryEntry> {
        let generation = accountGeneration
        let root = try ShelfWire.object(try await invoke("GetReadHistory", requiresAccount: true), name: "阅读历史")
        let values = try ShelfWire.array(root, keys: ["Novel"], name: "阅读历史")
        let ids = try ShelfWire.unique(values.map { value -> Int64 in
            guard let text = value.text, let id = Int64(text), id > 0 else { throw SourceFailure.parsing("轻书架阅读历史包含无效书籍 ID") }
            return id
        })
        let page = max(1, page), start = min(ids.count, (page - 1) * 24), end = min(ids.count, start + 24)
        let batch = Array(ids[start..<end]), books = try await booksByIDs(batch)
        let novels = try await enrich(batch.compactMap { books[$0] })
        guard generation == accountGeneration else { throw CancellationError() }
        return Page(items: novels.map { HistoryEntry(novel: $0) }, page: page, total: ids.count, hasMore: end < ids.count)
    }

    func saveProgress(_ progress: ReadingProgress) async throws {
        let generation = accountGeneration
        let id = try bookID(progress.novelKey), sort = try sortNumber(progress.chapterKey)
        let detail = try await bookDetail(progress.novelKey)
        guard generation == accountGeneration else { throw CancellationError() }
        guard let chapter = detail.chapters.first(where: { $0.sort == sort }) else { throw SourceFailure.parsing("轻书架目录中未找到当前章节，无法同步阅读位置") }
        _ = try await invoke("SaveReadPosition", .object(["Bid": j(id), "Cid": j(chapter.id), "XPath": "."]), requiresAccount: true)
    }

    private func accountProfile() async throws -> ShelfProfile {
        try ShelfWire.profile(try await invoke("GetMyInfo", .object([:]), requiresAccount: true))
    }

    func profile() async throws -> SourceProfile {
        let profile = try await accountProfile()
        session.accountID = profile.id > 0 ? String(profile.id) : nil
        session.displayName = profile.name
        return profile.model
    }

    func rewards() async throws -> RewardStatus {
        let profile = try await accountProfile()
        return RewardStatus(claimedToday: profile.signed, balance: profile.coin, streak: profile.streak)
    }

    func claimReward() async throws -> RewardResult {
        let generation = accountGeneration
        return try await rewardGate.run {
            guard generation == self.accountGeneration else { throw CancellationError() }
            let before = try await self.accountProfile()
            guard generation == self.accountGeneration else { throw CancellationError() }
            if before.signed { return RewardResult(coins: 0, balance: before.coin, streak: before.streak) }
            let result = try ShelfWire.object(try await self.invoke("SignIn", .object([:]), requiresAccount: true), name: "签到结果")
            guard generation == self.accountGeneration else { throw CancellationError() }
            let after: ShelfProfile?
            do { after = try await self.accountProfile() }
            catch is CancellationError { throw CancellationError() }
            catch { after = nil }
            let coins = after.flatMap { $0.coin >= before.coin ? $0.coin - before.coin : nil }
            let experience = after?.experience.flatMap { value in before.experience.flatMap { value >= $0 ? value - $0 : nil } }
            return RewardResult(coins: coins, balance: after?.coin, streak: after?.streak ?? result.int("Streak"), experience: experience)
        }
    }

    func clearMetadataCache() { metadata.clear() }

    private func bookID(_ key: ResourceKey) throws -> Int64 {
        guard key.source == .shelf, let id = Int64(key.remoteID), id > 0 else { throw SourceFailure.parsing("无效的轻书架书籍 ID") }
        return id
    }
    private func sortNumber(_ key: ResourceKey) throws -> Int {
        guard key.source == .shelf, let sort = Int(key.remoteID), sort > 0 else { throw SourceFailure.parsing("无效的轻书架章节排序号") }
        return sort
    }
}

struct ShelfBookDetail {
    struct Entry { var id: Int64; var title: String; var sort: Int }
    var novel: Novel
    var favorites: Int
    var chapters: [Entry]
    var characters: Int {
        let authors = novel.authors.reduce(0) { $0 + $1.utf16.count }
        let tags = novel.tags.reduce(0) { $0 + $1.utf16.count }
        let titles = chapters.reduce(0) { $0 + $1.title.utf16.count + 32 }
        return novel.title.utf16.count + authors + novel.synopsis.utf16.count + (novel.coverURL?.absoluteString.utf16.count ?? 0) + tags + titles
    }
}

struct ShelfNovelContent {
    var bookID: Int64; var title: String; var html: String; var fontURL: URL?; var sort: Int; var titles: [String]
}
struct ShelfProfile {
    var id: Int64; var name: String; var coin: Int64; var streak: Int; var signed: Bool
    var accessLevel: Int?; var growthLevel: Int?; var experience: Int64?; var levelStart: Int64?; var nextLevel: Int64?; var maxLevel: Bool
    var model: SourceProfile {
        SourceProfile(source: .shelf, accountID: id > 0 ? String(id) : nil, name: name, balance: coin, level: growthLevel, levelLabel: "成长等级", experience: experience.map { Experience(total: $0, levelStart: levelStart, nextLevel: nextLevel, maxLevel: maxLevel) }, extra: ["连续签到": "\(streak) 天", "今日签到": signed ? "已完成" : "未完成", "经验获取": "每日签到累积经验，连签有额外奖励；实际奖励以来源返回为准。", "访问等级": accessLevel.map { "Lv.\($0)" } ?? "未提供"])
    }
}
struct ShelfRemoteSnapshot { var version: JSONValue?; var items: [ShelfRemoteItem] }
struct ShelfRemoteItem {
    enum Kind { case book, folder }
    var raw: JSONObject; var type: Kind; var id: String; var index: Int; var parents: [String]
    var nestedParent: [String]? = nil
    var childrenKey: String? = nil
    var bookID: Int64? { type == .book ? Int64(id) : nil }
    var wire: JSONObject {
        var value = raw
        for key in ["Id", "Type", "Index", "Parents", "Title", "UpdateAt"] { value.removeValue(forKey: key) }
        value["id"] = bookID.map(j) ?? j(id)
        value["type"] = j(type == .book ? "BOOK" : "FOLDER")
        value["index"] = j(index)
        value["parents"] = .array(parents.map(j))
        value["updateAt"] = j(raw.string("updateAt", "UpdateAt"))
        if type == .folder { value["title"] = j(raw.string("title", "Title")) }
        return value
    }
}

enum ShelfWire {
    static func object(_ value: JSONValue, name: String) throws -> JSONObject {
        guard case .object(let object) = value else { throw SourceFailure.parsing("轻书架返回了无效的" + name) }
        return object
    }
    static func array(_ object: JSONObject, keys: [String], name: String) throws -> [JSONValue] {
        for key in keys { if case .array(let array) = object[key] { return array } }
        throw SourceFailure.parsing("轻书架响应缺少" + name + "数组")
    }
    static func optionalString(_ object: JSONObject, _ keys: [String]) -> String {
        for key in keys {
            if let value = object[key]?.text, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return value }
        }
        return ""
    }
    static func required(_ object: JSONObject, _ keys: String...) throws -> String {
        for key in keys { if let value = object[key]?.text, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return value } }
        throw SourceFailure.parsing("轻书架响应缺少 \(keys.first ?? "字段")")
    }
    static func int64(_ object: JSONObject, _ keys: [String], fallback: Int64? = nil) throws -> Int64 {
        for key in keys { if let value = object[key]?.text, let number = Int64(value) { return number } }
        if let fallback = fallback { return fallback }
        throw SourceFailure.parsing("轻书架响应缺少 \(keys.first ?? "数字字段")")
    }
    static func quotedQuery(_ input: String) -> String {
        let query = input.trimmingCharacters(in: .whitespacesAndNewlines)
        return query.hasPrefix("\"") && query.hasSuffix("\"") ? query : "\"" + query + "\""
    }
    static func unique<T: Hashable>(_ values: [T]) -> [T] {
        var seen = Set<T>()
        return values.filter { seen.insert($0).inserted }
    }
    static func text(_ value: JSONValue) -> String {
        if let text = value.text { return text }
        return value.object.string("Html", "html", "Content", "content", "Text", "text", "Value", "value", "Description", "description")
    }
    static func strings(_ values: [JSONValue]) -> [String] {
        values.compactMap { value in
            let text = value.text ?? value.object.string("Name", "name", "Title", "title")
            return text.isEmpty ? nil : text
        }
    }
    static func cleanHTML(_ html: String) -> String {
        html.replacingOccurrences(of: "\\r\\n", with: "\n").replacingOccurrences(of: "\\n", with: "\n").replacingOccurrences(of: "\\r", with: "")
            .replacingOccurrences(of: "(?i)</(h[1-6]|li)>", with: "\n", options: .regularExpression).plainHTML
            .replacingOccurrences(of: "\n{3,}", with: "\n\n", options: .regularExpression)
    }
    static func cover(_ raw: String) -> URL? {
        var result = raw
        if let placeholder = raw.range(of: "[?&]placeholder=[^&]*", options: .regularExpression) {
            let part = String(raw[placeholder]).replacingOccurrences(of: "#", with: "%23")
            result.replaceSubrange(placeholder, with: part)
        }
        return webURL(result)
    }
    static func book(_ object: JSONObject) throws -> Novel {
        let id = try int64(object, ["Id", "id", "BookId", "bookId"])
        let title = try required(object, "Title", "title", "Name", "name")
        let classification = object.object("Extra", "extra").object("classification", "Classification")
        let directAuthor = optionalString(object, ["Author", "author"])
        let author = directAuthor.isEmpty ? optionalString(classification, ["author", "Author"]) : directAuthor
        let synopsis = text(object.value("Introduction", "introduction", "Synopsis", "synopsis", "Description", "description", "Summary", "summary", "Brief", "brief", "Intro", "intro"))
        return Novel(key: ResourceKey(.shelf, String(id)), title: title, authors: author.isEmpty ? [] : [author], synopsis: cleanHTML(synopsis), coverURL: cover(object.string("Cover", "cover", "CoverUrl", "coverUrl")))
    }
    static func bookArray(_ value: JSONValue) throws -> [JSONObject] {
        let values: [JSONValue]
        switch value {
        case .array(let items): values = items
        case .object(let object): values = try array(object, keys: ["Data", "data", "Books", "books"], name: "书籍列表")
        default: throw SourceFailure.parsing("轻书架书籍列表无效")
        }
        return try values.map { try object($0, name: "书籍条目") }
    }
    static func detail(_ value: JSONValue) throws -> ShelfBookDetail {
        let root = try object(value, name: "书籍详情")
        let data = root.object("Data", "data")
        let book: JSONObject
        if let nested = nestedObject(root, ["Book", "book", "BookInfo", "bookInfo"]) { book = nested }
        else if let nested = nestedObject(data, ["Book", "book", "BookInfo", "bookInfo"]) { book = nested }
        else { book = root }
        var arrays: [[JSONValue]] = []
        for container in [book, root, data] {
            for name in ["Chapters", "chapters", "Chapter", "chapter", "ChapterList", "chapterList"] {
                if case .array(let array) = container[name] { arrays.append(array) }
                else if case .object(let object) = container[name] { arrays.append([.object(object)]) }
            }
        }
        guard let values = arrays.first(where: { !$0.isEmpty }) ?? arrays.first else { throw SourceFailure.parsing("轻书架书籍详情缺少章节目录") }
        let objects = values.compactMap { if case .object(let object) = $0 { return object }; return nil }
        let explicit = objects.compactMap { $0.optionalInt("SortNum", "sortNum", "SortNumber", "sortNumber") }.filter { $0 > 0 }
        let reserved = Set(explicit)
        guard reserved.count == explicit.count else { throw SourceFailure.parsing("轻书架章节目录包含重复排序号") }
        var used = Set<Int>(), entries: [ShelfBookDetail.Entry] = []
        for (index, chapter) in objects.enumerated() {
            let explicit = chapter.optionalInt("SortNum", "sortNum", "SortNumber", "sortNumber").flatMap { $0 > 0 ? $0 : nil }
            var sort = explicit ?? index + 1
            while used.contains(sort) || (explicit == nil && reserved.contains(sort)) { sort += 1 }
            used.insert(sort)
            entries.append(ShelfBookDetail.Entry(id: try int64(chapter, ["Id", "id"]), title: try required(chapter, "Title", "title"), sort: sort))
        }
        entries.sort { $0.sort < $1.sort }
        var novel = try self.book(book)
        let classification = book.object("Extra", "extra").object("classification", "Classification")
        let tags = (classification["tags"] != nil || classification["Tags"] != nil) ? classification.array("tags", "Tags") : book.array("Tags", "tags")
        novel.tags = strings(tags); novel.volumeCount = 1; novel.chapterCount = entries.count
        return ShelfBookDetail(novel: novel, favorites: max(0, book.int("Favorite", "favorite", "FavoriteCount", "favoriteCount")), chapters: entries)
    }
    private static func nestedObject(_ object: JSONObject, _ keys: [String]) -> JSONObject? {
        for key in keys { if case .object(let nested) = object[key] { return nested } }
        return nil
    }
    static func content(_ value: JSONValue, bookID: Int64, sort: Int) throws -> ShelfNovelContent {
        let root = try object(value, name: "小说正文")
        let chapter = nestedObject(root, ["Chapter", "chapter"]) ?? nestedObject(root.object("Data", "data"), ["Chapter", "chapter"]) ?? nestedObject(root.object("Result", "result"), ["Chapter", "chapter"]) ?? (root.keys.contains(where: { ["Content", "content", "Html", "html", "Text", "text"].contains($0) }) ? root : nil)
        guard let chapter = chapter else { throw SourceFailure.parsing("轻书架正文响应缺少章节") }
        _ = try int64(chapter, ["Id", "id"])
        let font = chapter.string("Font", "font", "FontUrl", "fontUrl")
        let body = chapter.value("Content", "content", "Html", "html", "Text", "text")
        guard body != .null else { throw SourceFailure.parsing("轻书架正文缺少内容") }
        if !font.isEmpty && webURL(font) == nil { throw SourceFailure.parsing("轻书架正文字体地址无效") }
        return ShelfNovelContent(bookID: try int64(chapter, ["BookId", "bookId"], fallback: bookID), title: try required(chapter, "Title", "title"), html: text(chapter.value("Content", "content", "Html", "html", "Text", "text")), fontURL: font.isEmpty ? nil : webURL(font), sort: chapter.optionalInt("SortNum", "sortNum", "SortNumber", "sortNumber") ?? sort, titles: strings(chapter.array("Chapters", "chapters", "ChapterTitles", "chapterTitles")))
    }
    static func profile(_ value: JSONValue) throws -> ShelfProfile {
        let root = try object(value, name: "用户资料"), growth = root.object("Growth")
        func positive(_ key: String) -> Int64? { growth[key]?.text.flatMap(Int64.init).flatMap { $0 >= 0 ? $0 : nil } }
        return ShelfProfile(id: try int64(root, ["Id"]), name: root.string("UserName"), coin: try int64(growth, ["Coin"], fallback: 0), streak: growth.int("SignStreak"), signed: growth.bool("TodaySigned") ?? false, accessLevel: positive("Level").flatMap { Int(exactly: $0) }, growthLevel: positive("GrowthLevel").flatMap { Int(exactly: $0) }, experience: positive("Exp"), levelStart: positive("CurrentLevelExp"), nextLevel: positive("NextLevelExp"), maxLevel: growth["NextLevelExp"] == .null)
    }
    static func shelf(_ value: JSONValue) throws -> ShelfRemoteSnapshot {
        let values: [JSONValue], version: JSONValue?
        switch value {
        case .array(let array): values = array; version = nil
        case .object(let object): values = try array(object, keys: ["data", "Data"], name: "书架"); version = object["ver"] ?? object["Ver"]
        default: throw SourceFailure.parsing("轻书架返回了无效的书架")
        }
        var items: [ShelfRemoteItem] = []
        func append(_ values: [JSONValue], inherited: [String], nestedParent: [String]?) throws {
            for value in values {
                let raw = try object(value, name: "书架条目")
                let type: ShelfRemoteItem.Kind
                switch raw.string("type", "Type").uppercased() {
                case "BOOK", "0": type = .book
                case "FOLDER", "1": type = .folder
                default: throw SourceFailure.parsing("轻书架返回了未知的书架条目类型")
                }
                let id = try required(raw, "id", "Id")
                if type == .book, Int64(id) == nil { throw SourceFailure.parsing("轻书架书架条目包含无效书籍 ID") }
                let parents = raw["parents"] == nil && raw["Parents"] == nil ? inherited : strings(raw.array("parents", "Parents"))
                let childKey = ["children", "Children", "items", "Items"].first { if case .array = raw[$0] { return true }; return false }
                let item = ShelfRemoteItem(raw: raw, type: type, id: id, index: raw.int("index", "Index"), parents: parents, nestedParent: nestedParent, childrenKey: childKey)
                items.append(item)
                if type == .folder {
                    let children = raw.array("children", "Children", "items", "Items")
                    if !children.isEmpty { try append(children, inherited: parents + [id], nestedParent: parents + [id]) }
                }
            }
        }
        try append(values, inherited: [], nestedParent: nil)
        return ShelfRemoteSnapshot(version: version == .null ? nil : version, items: items)
    }
    static func wireItems(_ items: [ShelfRemoteItem]) -> [JSONValue] {
        func level(_ parent: [String]?) -> [JSONValue] {
            items.filter { $0.nestedParent == parent }.map { item in
                var raw = item.wire
                if let key = item.childrenKey { raw[key] = .array(level(item.parents + [item.id])) }
                return .object(raw)
            }
        }
        return level(nil)
    }
    static func normalize(_ items: [ShelfRemoteItem]) -> [ShelfRemoteItem] {
        let sorted = items.enumerated().sorted { lhs, rhs in
            if lhs.element.index != rhs.element.index { return lhs.element.index < rhs.element.index }
            if lhs.element.parents.count != rhs.element.parents.count { return lhs.element.parents.count < rhs.element.parents.count }
            return lhs.offset < rhs.offset
        }
        var indices: [[String]: Int] = [:]
        return sorted.map { pair in
            var item = pair.element
            item.index = indices[item.parents, default: 0]
            indices[item.parents] = item.index + 1
            return item
        }
    }
}

/// Cancellation-aware serial gate; removed waiters can never consume the next permit.
@MainActor final class ShelfAsyncGate {
    private var held = false
    private var queue: [UUID] = []
    private var waiters: [UUID: CheckedContinuation<Void, Error>] = [:]
    func run<T>(_ operation: () async throws -> T) async throws -> T {
        try await acquire()
        defer { release() }
        try Task.checkCancellation()
        return try await operation()
    }
    private func acquire() async throws {
        try Task.checkCancellation()
        if !held { held = true; return }
        let id = UUID()
        try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                guard !Task.isCancelled else { continuation.resume(throwing: CancellationError()); return }
                queue.append(id); waiters[id] = continuation
            }
        }, onCancel: { [weak self] in
            Task { @MainActor in
                self?.waiters.removeValue(forKey: id)?.resume(throwing: CancellationError())
                self?.queue.removeAll { $0 == id }
            }
        })
    }
    private func release() {
        while !queue.isEmpty {
            let id = queue.removeFirst()
            if let waiter = waiters.removeValue(forKey: id) { waiter.resume(); return }
        }
        held = false
    }
    deinit { for waiter in waiters.values { waiter.resume(throwing: CancellationError()) } }
}

@MainActor final class ShelfMetadataCache {
    private struct Entry { var detail: ShelfBookDetail; var expiry: TimeInterval; var touched: UInt64 }
    private final class Flight {
        let generation: UInt64
        let loader: () async throws -> ShelfBookDetail
        var waiters: [UUID: CheckedContinuation<ShelfBookDetail, Error>] = [:]
        var task: Task<Void, Never>?
        init(generation: UInt64, loader: @escaping () async throws -> ShelfBookDetail) { self.generation = generation; self.loader = loader }
    }
    private var entries: [Int64: Entry] = [:]
    private var flights: [Int64: Flight] = [:]
    private var queue: [Int64] = []
    private var active = 0
    private var characters = 0
    private var generation: UInt64 = 0
    private var counter: UInt64 = 0
    private let now: () -> TimeInterval
    init(now: @escaping () -> TimeInterval = { ProcessInfo.processInfo.systemUptime }) { self.now = now }

    func get(id: Int64, loader: @escaping () async throws -> ShelfBookDetail) async throws -> ShelfBookDetail {
        try Task.checkCancellation()
        if var entry = entries[id] {
            if entry.expiry > now() {
                counter &+= 1; entry.touched = counter; entries[id] = entry
                return entry.detail
            }
            entries.removeValue(forKey: id); characters -= entry.detail.characters
        }
        let waiterID = UUID()
        return try await withTaskCancellationHandler(operation: {
            let result = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<ShelfBookDetail, Error>) in
                guard !Task.isCancelled else { continuation.resume(throwing: CancellationError()); return }
                let flight: Flight
                if let existing = flights[id] { flight = existing }
                else {
                    flight = Flight(generation: generation, loader: loader)
                    flights[id] = flight; queue.append(id)
                }
                flight.waiters[waiterID] = continuation
                pump()
            }
            try Task.checkCancellation()
            return result
        }, onCancel: { [weak self] in
            Task { @MainActor in self?.cancel(id: id, waiter: waiterID) }
        })
    }

    func clear() {
        generation &+= 1
        entries.removeAll(); characters = 0
        let old = Array(flights.values)
        flights.removeAll(); queue.removeAll()
        for flight in old {
            flight.task?.cancel()
            let waiters = Array(flight.waiters.values); flight.waiters.removeAll()
            for waiter in waiters { waiter.resume(throwing: CancellationError()) }
        }
    }

    private func cancel(id: Int64, waiter: UUID) {
        guard let flight = flights[id] else { return }
        flight.waiters.removeValue(forKey: waiter)?.resume(throwing: CancellationError())
        if flight.waiters.isEmpty {
            flights.removeValue(forKey: id); queue.removeAll { $0 == id }
            flight.task?.cancel()
        }
    }

    private func pump() {
        while active < 4 && !queue.isEmpty {
            let id = queue.removeFirst()
            guard let flight = flights[id], flight.task == nil else { continue }
            active += 1
            flight.task = Task { [weak self, flight] in
                let result: Result<ShelfBookDetail, Error>
                do { let detail = try await flight.loader(); try Task.checkCancellation(); result = .success(detail) }
                catch { result = .failure(error) }
                flight.task = nil
                self?.finish(id: id, flight: flight, result: result)
            }
        }
    }

    private func finish(id: Int64, flight: Flight, result: Result<ShelfBookDetail, Error>) {
        active -= 1
        if flights[id] === flight {
            flights.removeValue(forKey: id)
            if case .success(let detail) = result, flight.generation == generation, detail.characters <= 512 * 1024 {
                if let previous = entries.removeValue(forKey: id) { characters -= previous.detail.characters }
                counter &+= 1
                entries[id] = Entry(detail: detail, expiry: now() + 300, touched: counter)
                characters += detail.characters
                while entries.count > 64 || characters > 512 * 1024 {
                    guard let eldest = entries.min(by: { $0.value.touched < $1.value.touched }) else { break }
                    characters -= eldest.value.detail.characters; entries.removeValue(forKey: eldest.key)
                }
            }
            let waiters = Array(flight.waiters.values); flight.waiters.removeAll()
            for waiter in waiters { waiter.resume(with: result) }
        }
        flight.task = nil
        pump()
    }
    deinit {
        for flight in flights.values {
            flight.task?.cancel()
            for waiter in flight.waiters.values { waiter.resume(throwing: CancellationError()) }
        }
    }
}
