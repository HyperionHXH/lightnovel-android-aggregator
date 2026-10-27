import Foundation

@MainActor final class KingdomSource: NovelSource {
    let sourceID: SourceID = .kingdom
    let feeds: [Feed] = [.popular, .latest, .newest, .daily, .weekly, .original, .fanfic, .epub]
    let supportsComments = true
    private(set) var session: AccountSession

    private let http: HTTPClient
    private let secrets: SecretStore
    private let cache: ContentCache
    private var unlockedChapters = Set<String>()

    private enum Keys {
        static let securityKey = "light_novel_kingdom.security_key"
        static let session = "light_novel_kingdom.account_session"
    }
    private static let apiBase = URL(string: "https://www.lightnovel.fun/api/pc-proxy/")!
    private static let commentBase = URL(string: "https://api.lightnovel.fun/pc-comment-proxy/")!

    init(http: HTTPClient = HTTPClient(), secrets: SecretStore = KeychainStore(), cache: ContentCache = ContentCache()) {
        self.http = http
        self.secrets = secrets
        self.cache = cache
        let storedJSON = (try? secrets.read(Keys.session)) ?? ""
        let storedKey = (try? secrets.read(Keys.securityKey)) ?? ""
        let saved = storedJSON.data(using: .utf8).flatMap { try? JSONDecoder().decode(AccountSession.self, from: $0) }
        if storedKey.isEmpty { session = AccountSession() }
        else {
            session = saved ?? AccountSession(loggedIn: true)
            session.loggedIn = true
            if session.accountID?.isEmpty != false { session.accountID = "session:\(sha256(storedKey))" }
            unlockedChapters = Self.loadUnlocked(securityKey: storedKey, secrets: secrets)
        }
    }
    private static func unlockedSecretKey(_ key: String) -> String { "light_novel_kingdom.unlocked.\(sha256(key))" }

    private static func loadUnlocked(securityKey: String, secrets: SecretStore) -> Set<String> {
        guard let stored = (try? secrets.read(unlockedSecretKey(securityKey))) ?? nil,
              let data = stored.data(using: .utf8), let ids = try? JSONDecoder().decode([String].self, from: data) else { return [] }
        return Set(ids)
    }

    private func persistUnlocks(for key: String) throws {
        let data = try JSONEncoder().encode(unlockedChapters.sorted())
        try secrets.write(Self.unlockedSecretKey(key), value: String(data: data, encoding: .utf8))
    }

    private var authGeneration: UInt64 = 0
    private var metadataGeneration: UInt64 = 0

    private struct CachedObject: Codable {
        var savedAt: Date
        var object: JSONObject
    }

    func request(path: String, body: JSONObject = [:], comment: Bool = false) async throws -> JSONObject {
        let key: String
        if Self.authenticatedPaths.contains(path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))) {
            key = try requireSessionKey()
        } else { key = try secrets.read(Keys.securityKey) ?? "" }
        let result = try await performRequest(path: path, body: body, comment: comment, securityKey: key)
        if Self.mutationPaths.contains(path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))) {
            try await removePrivateCache(for: key)
        }
        return result
    }

    private static let authenticatedPaths: Set<String> = [
        "api/bff/my-home-v1", "api/bff/bookshelf-v1", "api/bff/history-v1",
        "api/bff/user-following-v1", "api/bff/user-followers-v1", "api/bff/toggle-user-follow-v1",
        "api/bff/longform-book-list-v1", "api/bff/message-unread-v1", "api/bff/message-replies-v1",
        "api/bff/message-likes-v1", "api/bff/message-fans-v1", "api/bff/message-system-v1",
        "api/bff/dm-conversations-v1", "api/bff/dm-messages-v1", "api/bff/dm-mark-read-v1", "api/bff/message-mark-read-v1",
        "api/bff/save-my-reader-settings-v1", "api/bff/welfare-sign-detail-v1", "api/bff/claim-welfare-sign-v1",
        "api/new-content-read/toggle-book-shelf", "api/new-content-read/delete-book-history", "api/new-content-read/save-book-history",
        "api/new-content-read/unlock-chapter", "api/discuss/publish-book-comment", "api/discuss/like-book-comment", "api/dynamic/upload-image-v1"
    ]

    private static let mutationPaths: Set<String> = [
        "api/bff/toggle-user-follow-v1", "api/bff/dm-mark-read-v1", "api/bff/message-mark-read-v1",
        "api/bff/save-my-reader-settings-v1", "api/bff/claim-welfare-sign-v1",
        "api/new-content-read/toggle-book-shelf", "api/new-content-read/delete-book-history", "api/new-content-read/save-book-history",
        "api/new-content-read/unlock-chapter", "api/discuss/publish-book-comment", "api/discuss/like-book-comment"
    ]

    func restoreSession() async throws -> AccountSession {
        authGeneration &+= 1
        let generation = authGeneration
        let key = try secrets.read(Keys.securityKey) ?? ""
        guard !key.isEmpty else {
            session = AccountSession()
            return session
        }
        do {
            let data = try await performRequest(path: "api/bff/auth-session-v1", body: [:], securityKey: key)
            guard generation == authGeneration else { throw CancellationError() }
            guard let loggedIn = data.bool("logged_in", "loggedIn") else {
                throw SourceFailure.parsing("会话响应缺少登录状态")
            }
            if !loggedIn {
                try await removePrivateCache(for: key)
                guard generation == authGeneration else { throw CancellationError() }
                try secrets.write(Keys.securityKey, value: nil)
                try secrets.write(Keys.session, value: nil)
                session = AccountSession()
                unlockedChapters.removeAll()
                return session
            }
            let restoredKey = try sessionKey(from: data, fallback: key)
            let restored = accountSession(from: data, fallbackKey: restoredKey)
            try persist(restored, securityKey: restoredKey)
            session = restored
            unlockedChapters = Self.loadUnlocked(securityKey: restoredKey, secrets: secrets)
            return restored
        } catch let error as SourceFailure {
            guard generation == authGeneration else { throw CancellationError() }
            switch error {
            case .network, .timeout: return session
            default: throw error
            }
        }
    }

    func login(identifier: String, password: String) async throws -> AccountSession {
        authGeneration &+= 1
        let generation = authGeneration
        let oldKey = try secrets.read(Keys.securityKey) ?? ""
        let response = try await performRequest(
            path: "api/bff/auth-password-login-v1",
            body: ["username": j(identifier.trimmingCharacters(in: .whitespacesAndNewlines)), "password": j(password)],
            securityKey: ""
        )
        let key = try sessionKey(from: response, fallback: "")
        let result = accountSession(from: response, fallbackKey: key)
        if !oldKey.isEmpty && oldKey != key { try await removePrivateCache(for: oldKey) }
        guard generation == authGeneration else { throw CancellationError() }
        try persist(result, securityKey: key)
        session = result
        unlockedChapters = Self.loadUnlocked(securityKey: key, secrets: secrets)
        return result
    }

    func sendRegistrationCode(email: String) async throws {
        let normalized = email.trimmingCharacters(in: .whitespacesAndNewlines)
        let status = try await performRequest(
            path: "api/bff/auth-email-status-v1", body: ["email": j(normalized)], securityKey: ""
        )
        if status.bool("registered") == true { throw SourceFailure.server("该邮箱已经注册，请直接登录") }
        _ = try await performRequest(
            path: "api/bff/auth-email-register-captcha-send-v1", body: ["email": j(normalized)], securityKey: ""
        )
    }

    func register(email: String, captcha: String, nickname: String, password: String) async throws -> AccountSession {
        authGeneration &+= 1
        let generation = authGeneration
        let oldKey = try secrets.read(Keys.securityKey) ?? ""
        let normalizedCode = captcha.trimmingCharacters(in: .whitespacesAndNewlines)
        let response = try await performRequest(
            path: "api/bff/auth-email-register-v1",
            body: [
                "email": j(email.trimmingCharacters(in: .whitespacesAndNewlines)),
                "captcha": j(normalizedCode),
                "code": j(normalizedCode),
                "nickname": j(nickname.trimmingCharacters(in: .whitespacesAndNewlines)),
                "password": j(password)
            ],
            securityKey: ""
        )
        let key = try sessionKey(from: response, fallback: "")
        let result = accountSession(from: response, fallbackKey: key)
        if !oldKey.isEmpty && oldKey != key { try await removePrivateCache(for: oldKey) }
        guard generation == authGeneration else { throw CancellationError() }
        try persist(result, securityKey: key)
        session = result
        unlockedChapters = Self.loadUnlocked(securityKey: key, secrets: secrets)
        return result
    }

    func logout() async throws {
        authGeneration &+= 1
        let key = try secrets.read(Keys.securityKey) ?? ""
        session = AccountSession()
        unlockedChapters.removeAll()
        var secretError: Error?
        do { try secrets.write(Keys.securityKey, value: nil) } catch { secretError = error }
        do { try secrets.write(Keys.session, value: nil) } catch { if secretError == nil { secretError = error } }
        if !key.isEmpty { try await removePrivateCache(for: key) }
        if let secretError { throw secretError }
    }

    func profile() async throws -> SourceProfile {
        let securityKey = try requireSessionKey()
        let data = try await performRequest(path: "api/bff/my-home-v1", body: [:], securityKey: securityKey)
        guard !data.isEmpty else { throw SourceFailure.parsing("来源未返回账户资料") }
        return KingdomJSON.profile(data)
    }

    func discover(feed: Feed, page: Int, pageSize: Int) async throws -> Page<Novel> {
        let page = max(1, page)
        let size = max(1, pageSize)
        let path: String
        var body: JSONObject
        let ttl: TimeInterval = 300
        switch feed {
        case .popular, .latest, .original, .fanfic, .epub:
            switch feed {
            case .popular: path = "api/bff/home-feed-v1"
            case .latest: path = "api/bff/home-recent-updates-feed-v1"
            case .original: path = "api/bff/home-original-feed-v1"
            case .fanfic: path = "api/bff/home-fanfic-feed-v1"
            case .epub: path = "api/bff/home-epub-feed-v1"
            default: throw SourceFailure.unsupported("该来源不支持此分类")
            }
            body = [
                "page": j(page), "page_size": j(size), "pageSize": j(size),
                "read_filter": j("all"), "status_filter": j("all"), "category_filter": j("all")
            ]
        case .newest, .daily, .weekly:
            path = "api/bff/book-rank-list-v1"
            let scene = feed == .newest ? "daily_fresh" : (feed == .daily ? "daily_hot" : "weekly_hot")
            body = ["rank_scene": j(scene), "page": j(page), "page_size": j(30), "pageSize": j(30)]
        case .monthly:
            throw SourceFailure.unsupported("轻之国度没有月榜频道")
        }
        let data = try await cachedRequest(path: path, body: body, ttl: ttl)
        let parsed = KingdomJSON.booksPage(data, requestedPage: page, requestedPageSize: feed == .newest || feed == .daily || feed == .weekly ? 30 : size)
        if feed == .newest || feed == .daily || feed == .weekly { return Page(items: parsed.items, page: parsed.page, total: parsed.total, hasMore: false) }
        return parsed
    }

    func search(query: String, page: Int, pageSize: Int) async throws -> Page<Novel> {
        let normalized = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let page = max(1, page)
        let size = max(1, pageSize)
        let body: JSONObject = [
            "q": j(normalized), "scope": j(""), "source": j(""), "primary_tag": j(""),
            "channel_code": j(""), "work_type": j(""),
            "preset": j(normalized.isEmpty ? "default" : ""), "source_type": j(""),
            "filters": .object([:]), "word_count_bucket": j(""), "status_bucket": j(""),
            "page": j(max(0, page - 1)), "pageSize": j(size), "sort": j("relevance")
        ]
        let data = try await cachedRequest(path: "api/bff/apk-search-result-v1", body: body, ttl: 300)
        return KingdomJSON.booksPage(data, requestedPage: page, requestedPageSize: size)
    }

    func detail(key: ResourceKey) async throws -> NovelDetail {
        let id = try Self.requireID(key, source: .kingdom)
        let body: JSONObject = ["book_id": j(id), "with_volumes": j(0)]
        let data = try await cachedRequest(path: "api/new-content-read/get-book-detail", body: body, ttl: 1800)
        let result = KingdomJSON.bookDetail(data)
        guard Int(result.novel.key.remoteID) ?? 0 > 0 else { throw SourceFailure.parsing("来源未返回有效作品详情") }
        return result
    }

    func volumes(key: ResourceKey, page: Int) async throws -> Page<Volume> {
        let id = try Self.requireID(key, source: .kingdom)
        let page = max(1, page)
        let size = 50
        let data = try await cachedRequest(
            path: "api/new-content-read/get-book-volumes",
            body: ["book_id": j(id), "page": j(page), "page_size": j(size), "pageSize": j(size)], ttl: 1800
        )
        return KingdomJSON.volumesPage(data, novel: key, page: page, pageSize: size)
    }

    func chapters(novel: ResourceKey, volume: ResourceKey, page: Int) async throws -> Page<Chapter> {
        let bookID = try Self.requireID(novel, source: .kingdom)
        let volumeID = try Self.requireID(volume, source: .kingdom)
        let page = max(1, page)
        let size = 50
        let body: JSONObject = ["book_id": j(bookID), "volume_id": j(volumeID), "page": j(page), "page_size": j(size), "pageSize": j(size)]
        let data = try await cachedRequest(path: "api/new-content-read/get-volume-chapters", body: body, ttl: 1800)
        return KingdomJSON.chaptersPage(data, novel: novel, volume: volume, page: page, pageSize: size, unlocked: unlockedChapters)
    }

    func chapter(novel: ResourceKey, chapter: ResourceKey) async throws -> ChapterContent {
        let bookID = try Self.requireID(novel, source: .kingdom)
        let chapterID = try Self.requireID(chapter, source: .kingdom)
        let body: JSONObject = ["book_id": j(bookID), "chapter_id": j(chapterID)]
        let data = try await cachedRequest(path: "api/new-content-read/get-chapter-detail", body: body, ttl: 7 * 24 * 60 * 60)
        guard !data.isEmpty else { throw SourceFailure.parsing("来源未返回章节内容") }
        var content = KingdomJSON.chapterContent(data, novel: novel, requestedChapter: chapter, unlocked: unlockedChapters)
        if !content.chapter.locked && content.bodyText.isEmpty && content.bodyHTML.isEmpty {
            throw SourceFailure.parsing("来源未返回可阅读的章节正文")
        }
        if !content.chapter.locked && (content.previous == nil || content.next == nil) {
            do { content = try await completeNavigation(content, novel: novel, chapterID: chapterID) }
            catch is CancellationError { throw CancellationError() }
            catch { /* Android preserves readable content when catalog navigation cannot be refreshed. */ }
        }
        return content
    }

    func shelf() async throws -> [Novel] {
        _ = try requireSessionKey()
        var page = 1
        var novels: [Novel] = []
        var seen = Set<ResourceKey>()
        while true {
            let data = try await cachedRequest(path: "api/bff/bookshelf-v1", body: ["page": j(page), "pageSize": j(50)], ttl: 60)
            let current = KingdomJSON.booksPage(data, requestedPage: page, requestedPageSize: 50)
            let next = current.items.filter { seen.insert($0.key).inserted }
            novels.append(contentsOf: next)
            guard current.hasMore else { return novels }
            guard !next.isEmpty else { throw SourceFailure.parsing("书架分页未前进") }
            page += 1
        }
    }

    func setShelf(key: ResourceKey, add: Bool) async throws {
        let id = try Self.requireID(key, source: .kingdom)
        let securityKey = try requireSessionKey()
        _ = try await performRequest(
            path: "api/new-content-read/toggle-book-shelf",
            body: ["book_id": j(id), "action": j(add ? "add" : "remove"), "source": j("pc_web")],
            securityKey: securityKey
        )
        let state = try await performRequest(
            path: "api/new-content-read/get-book-library-state", body: ["book_id": j(id)], securityKey: securityKey
        )
        guard state.bool("in_shelf", "already_fav", "in_collection", "favorite") == add else {
            throw SourceFailure.server("书架状态未能同步")
        }
        try await removePrivateCache(for: securityKey)
    }

    func history(page: Int) async throws -> Page<HistoryEntry> {
        _ = try requireSessionKey()
        let page = max(1, page)
        let size = 20
        let data = try await cachedRequest(
            path: "api/bff/history-v1", body: ["page": j(page), "pageSize": j(size)], ttl: 60
        )
        return KingdomJSON.historyPage(data, page: page, pageSize: size)
    }

    func deleteHistory(key: ResourceKey) async throws {
        let id = try Self.requireID(key, source: .kingdom)
        let securityKey = try requireSessionKey()
        _ = try await performRequest(path: "api/new-content-read/delete-book-history", body: ["book_id": j(id)], securityKey: securityKey)
        try await removePrivateCache(for: securityKey)
    }

    func saveProgress(_ progress: ReadingProgress) async throws {
        let bookID = try Self.requireID(progress.novelKey, source: .kingdom)
        let chapterID = try Self.requireID(progress.chapterKey, source: .kingdom)
        let volumeID = try progress.volumeKey.map { try Self.requireID($0, source: .kingdom) } ?? 0
        let securityKey = try requireSessionKey()
        var body: JSONObject = [
            "book_id": j(bookID), "chapter_id": j(chapterID),
            "progress_percent": j(min(100, max(0, progress.percent))),
            "last_position": j(max(0, progress.paragraphIndex)),
            "read_finished": j(progress.percent >= 98 ? 1 : 0)
        ]
        if volumeID > 0 { body["volume_id"] = j(volumeID) }
        _ = try await performRequest(path: "api/new-content-read/save-book-history", body: body, securityKey: securityKey)
        try await removePrivateCache(for: securityKey)
    }

    func saveReaderSettings(_ settings: ReaderSettings) async throws {
        let securityKey = try requireSessionKey()
        let mode = settings.mode == .paged ? "page" : "scroll"
        _ = try await performRequest(
            path: "api/bff/save-my-reader-settings-v1",
            body: [
                "font_size": j(Int(settings.fontSize.rounded())), "line_height": .number(settings.lineHeight),
                "theme": j(settings.theme.rawValue), "page_mode": j(mode), "traditional_chinese": j(0),
                "updated_at": j(Int(Date().timeIntervalSince1970))
            ], securityKey: securityKey
        )
    }

    func rewards() async throws -> RewardStatus {
        let securityKey = try requireSessionKey()
        let data = try await performRequest(path: "api/bff/welfare-sign-detail-v1", body: [:], securityKey: securityKey)
        var balance: Int64?
        do { balance = try await profile().balance }
        catch is CancellationError { throw CancellationError() }
        catch { /* A successful sign-in calendar remains usable if the balance request fails. */ }
        return KingdomJSON.rewardStatus(data, balance: balance)
    }

    func claimReward() async throws -> RewardResult {
        let securityKey = try requireSessionKey()
        let data = try await performRequest(path: "api/bff/claim-welfare-sign-v1", body: [:], securityKey: securityKey)
        try await removePrivateCache(for: securityKey)
        return KingdomJSON.rewardResult(data)
    }

    func unlock(chapter: ResourceKey) async throws {
        let chapterID = try Self.requireID(chapter, source: .kingdom)
        let securityKey = try requireSessionKey()
        let response = try await performRequest(
            path: "api/new-content-read/unlock-chapter", body: ["chapter_id": j(chapterID)], securityKey: securityKey
        )
        if response.bool("success", "ok", "unlocked", "purchased") == false {
            throw SourceFailure.server(response.string("message", "msg", "error").isEmpty ? "章节解锁未完成" : response.string("message", "msg", "error"))
        }
        unlockedChapters.insert(chapter.remoteID)
        try persistUnlocks(for: securityKey)
        try await removePrivateCache(for: securityKey)
    }

    func comments(key: ResourceKey, latest: Bool, page: Int) async throws -> Page<Comment> {
        let bookID = try Self.requireID(key, source: .kingdom)
        let page = max(1, page)
        let size = 20
        let data = try await cachedRequest(
            path: "api/new-content-read/get-book-comments",
            body: [
                "book_id": j(bookID), "volume_id": j(0), "chapter_id": j(0), "view": j(""), "comment_id": j(0),
                "page": j(page), "pageSize": j(size), "comment_sort": j(latest ? "latest" : "hot"),
                "rating_filter": j("all"), "include_user_interactions": j(1)
            ], comment: true, ttl: 1800
        )
        return KingdomJSON.commentsPage(data, page: page)
    }

    func publishComment(key: ResourceKey, draft: CommentDraft) async throws -> Comment {
        let bookID = try Self.requireID(key, source: .kingdom)
        let securityKey = try requireSessionKey()
        let content = draft.content.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !content.isEmpty || !draft.media.isEmpty else { throw SourceFailure.server("评论内容不能为空") }
        let rootID = try Self.numericID(draft.rootID)
        let replyID = try Self.numericID(draft.replyID)
        let mentionIDs = try draft.mentions.map { value -> Int in
            guard let uid = Int(value), uid > 0 else { throw SourceFailure.parsing("无效的提及用户编号") }
            return uid
        }
        let media: [JSONValue] = draft.media.map { item in
            var object: JSONObject = ["url": j(item.url.absoluteString)]
            if let width = item.width { object["width"] = j(width) }
            if let height = item.height { object["height"] = j(height) }
            if let id = item.resourceID { object["res_id"] = j(id) }
            return .object(object)
        }
        let mediaData = try JSONEncoder().encode(JSONValue.array(media))
        guard let mediaJSON = String(data: mediaData, encoding: .utf8) else { throw SourceFailure.parsing("无法编码评论图片") }
        let response = try await performRequest(
            path: "api/discuss/publish-book-comment",
            body: [
                "book_id": j(bookID), "volume_id": j(0), "chapter_id": j(0), "view": j(""),
                "root_comment_id": j(rootID), "reply_comment_id": j(replyID), "content": j(content),
                "mention_uids": .array(mentionIDs.map { j($0) }), "media_json": j(mediaJSON),
                "rating_stars": j(0), "read_duration_seconds": j(0)
            ], securityKey: securityKey
        )
        let comment = KingdomJSON.comment(response.object("comment").isEmpty ? response : response.object("comment"))
        guard Int(comment.id) ?? 0 > 0 else { throw SourceFailure.parsing("评论发布成功，但服务器未返回评论内容") }
        try await removePrivateCache(for: securityKey)
        return comment
    }

    func rate(key: ResourceKey, stars: Int) async throws {
        guard (1...5).contains(stars) else { throw SourceFailure.server("评分必须在 1 到 5 星之间") }
        let bookID = try Self.requireID(key, source: .kingdom)
        let securityKey = try requireSessionKey()
        _ = try await performRequest(
            path: "api/discuss/publish-book-comment",
            body: [
                "book_id": j(bookID), "volume_id": j(0), "chapter_id": j(0), "view": j("rating"),
                "root_comment_id": j(0), "reply_comment_id": j(0), "content": j(""),
                "rating_stars": j(stars), "read_duration_seconds": j(0)
            ], securityKey: securityKey
        )
        try await removePrivateCache(for: securityKey)
    }

    func likeComment(key: ResourceKey, commentID: String, liked: Bool) async throws -> Bool {
        let bookID = try Self.requireID(key, source: .kingdom)
        let commentID = try Self.numericID(commentID)
        guard commentID > 0 else { throw SourceFailure.parsing("无效的评论编号") }
        let securityKey = try requireSessionKey()
        let data = try await performRequest(
            path: "api/discuss/like-book-comment",
            body: [
                "book_id": j(bookID), "volume_id": j(0), "chapter_id": j(0), "view": j(""),
                "comment_id": j(commentID), "root_comment_id": j(0), "act": j(liked ? "like" : "unlike")
            ], securityKey: securityKey
        )
        try await removePrivateCache(for: securityKey)
        return data.bool("liked", "is_liked", "has_liked") ?? liked
    }

    func emojis() async throws -> [CommentEmoji] {
        let data = try await cachedRequest(path: "api/bff/comment-emoji-list-v1", body: [:], ttl: 24 * 60 * 60)
        return KingdomJSON.emojis(data)
    }

    func mentions(query: String) async throws -> [MentionCandidate] {
        let securityKey = try requireSessionKey()
        let current = session.accountID.flatMap(Int.init) ?? 0
        let uid = current > 0 ? current : Int(try await profile().accountID ?? "") ?? 0
        guard uid > 0 else { throw SourceFailure.parsing("登录响应缺少用户编号") }
        let data = try await cachedRequest(
            path: "api/bff/user-following-v1", body: ["uid": j(uid), "page": j(1), "pageSize": j(50)],
            ttl: 120, securityKey: securityKey
        )
        let filter = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return KingdomJSON.mentionCandidates(data).filter { filter.isEmpty || $0.name.localizedCaseInsensitiveContains(filter) }
    }

    func uploadCommentImage(data: Data, fileName: String, mimeType: String) async throws -> CommentMedia {
        let securityKey = try requireSessionKey()
        let response = try await performMultipart(
            path: "api/dynamic/upload-image-v1", fields: ["security_key": securityKey, "scene": "book_comment"],
            fileName: fileName, mimeType: mimeType, fileData: data
        )
        let source = response.object("image", "media", "resource").isEmpty ? response : response.object("image", "media", "resource")
        let rawURL = source.string("url", "res_url", "stored_url", "source_url", "src", "image")
        guard let url = webURL(rawURL) else { throw SourceFailure.parsing("图片上传成功，但服务器未返回有效图片地址") }
        return CommentMedia(
            url: url, width: source.optionalInt("width", "w"), height: source.optionalInt("height", "h"),
            resourceID: source.string("res_id", "resId", "resource_id").nilIfBlank
        )
    }

    func clearMetadataCache() {
        metadataGeneration &+= 1
        let key = ((try? secrets.read(Keys.securityKey)) ?? nil) ?? ""
        let scopes = Set(["public:kingdom"] + (key.isEmpty ? [] : [Self.privateScope(key)]))
        Task { for scope in scopes { try? await cache.clear(scope: scope) } }
    }

    private func completeNavigation(_ initial: ChapterContent, novel: ResourceKey, chapterID: Int) async throws -> ChapterContent {
        let volumeID = initial.chapter.volumeKey.remoteID
        guard !volumeID.isEmpty else { return initial }
        let chaptersInVolume = try await allChapters(novel: novel, volumeID: volumeID)
        guard let index = chaptersInVolume.firstIndex(where: { $0.key.remoteID == String(chapterID) }) else { return initial }
        var previous = initial.previous ?? chaptersInVolume[..<index].last?.key
        var next = initial.next ?? chaptersInVolume.dropFirst(index + 1).first?.key
        if previous == nil || next == nil {
            let allVolumes = try await allVolumes(novel: novel)
            guard let volumeIndex = allVolumes.firstIndex(where: { $0.key.remoteID == volumeID }) else {
                return copy(initial, previous: previous, next: next)
            }
            if previous == nil, volumeIndex > 0 {
                let previousVolume = allVolumes[volumeIndex - 1]
                previous = try await allChapters(novel: novel, volumeID: previousVolume.key.remoteID).last?.key
            }
            if next == nil, volumeIndex + 1 < allVolumes.count {
                let nextVolume = allVolumes[volumeIndex + 1]
                next = try await allChapters(novel: novel, volumeID: nextVolume.key.remoteID).first?.key
            }
        }
        return copy(initial, previous: previous, next: next)
    }

    private func copy(_ content: ChapterContent, previous: ResourceKey?, next: ResourceKey?) -> ChapterContent {
        ChapterContent(
            chapter: content.chapter, novelTitle: content.novelTitle, volumeTitle: content.volumeTitle,
            bodyText: content.bodyText, bodyHTML: content.bodyHTML, fontURL: content.fontURL, previous: previous, next: next
        )
    }

    private func allVolumes(novel: ResourceKey) async throws -> [Volume] {
        var result: [Volume] = []
        var page = 1
        while true {
            let current = try await volumes(key: novel, page: page)
            result.append(contentsOf: current.items)
            guard current.hasMore else { return result }
            page += 1
        }
    }

    private func allChapters(novel: ResourceKey, volumeID: String) async throws -> [Chapter] {
        let volume = ResourceKey(.kingdom, volumeID)
        var result: [Chapter] = []
        var page = 1
        while true {
            let current = try await chapters(novel: novel, volume: volume, page: page)
            result.append(contentsOf: current.items)
            guard current.hasMore else { return result }
            page += 1
        }
    }

    private func cachedRequest(
        path: String, body: JSONObject, comment: Bool = false, ttl: TimeInterval, securityKey explicitKey: String? = nil
    ) async throws -> JSONObject {
        let generation = authGeneration
        let metadata = metadataGeneration
        let key = try explicitKey ?? (secrets.read(Keys.securityKey) ?? "")
        let scope = key.isEmpty ? "public:kingdom" : Self.privateScope(key)
        let cacheKey = Self.cacheKey(path: path, body: body, comment: comment)
        let token = await cache.token(scope: scope)
        let cachedData = await cache.read(key: cacheKey, scope: scope)
        let saved = cachedData.flatMap { try? JSONDecoder().decode(CachedObject.self, from: $0) }
        guard generation == authGeneration, metadata == metadataGeneration else { throw CancellationError() }
        if let saved, Date().timeIntervalSince(saved.savedAt) <= ttl { return saved.object }
        do {
            let object = try await performRequest(path: path, body: body, comment: comment, securityKey: key)
            guard generation == authGeneration, metadata == metadataGeneration else { throw CancellationError() }
            try Self.validateReadObject(object, path: path)
            let cached = CachedObject(savedAt: Date(), object: Self.withoutCredentials(object))
            if let data = try? JSONEncoder().encode(cached) {
                try? await cache.write(data, key: cacheKey, scope: scope, ttl: max(ttl, 7 * 24 * 60 * 60), token: token)
            }
            guard generation == authGeneration, metadata == metadataGeneration else { throw CancellationError() }
            return object
        } catch is CancellationError { throw CancellationError() }
        catch let error as SourceFailure {
            guard generation == authGeneration, metadata == metadataGeneration else { throw CancellationError() }
            switch error {
            case .network, .timeout, .rateLimited:
                if let saved { return saved.object }
            default: break
            }
            throw error
        }
    }

    private static func validateReadObject(_ object: JSONObject, path: String) throws {
        let keys: [String]
        switch path {
        case "api/new-content-read/get-book-volumes": keys = ["list", "volumes"]
        case "api/new-content-read/get-volume-chapters": keys = ["list", "chapters"]
        case "api/new-content-read/get-book-comments": keys = ["list", "root_comment", "items"]
        case "api/bff/comment-emoji-list-v1": keys = ["list", "items", "emojis", "default"]
        case "api/bff/user-following-v1": keys = ["list", "cards", "items", "books", "messages", "conversations"]
        case "api/bff/apk-search-result-v1", "api/bff/bookshelf-v1", "api/bff/history-v1", "api/bff/book-rank-list-v1",
             "api/bff/home-feed-v1", "api/bff/home-original-feed-v1", "api/bff/home-fanfic-feed-v1",
             "api/bff/home-epub-feed-v1", "api/bff/home-recent-updates-feed-v1":
            keys = ["list", "cards", "ranking_list", "items", "books"]
        default: return
        }
        let nested = object.object("data", "d")
        let valid = keys.contains { key in
            if case .array? = object[key] { return true }
            if case .array? = nested[key] { return true }
            return false
        }
        guard valid else { throw SourceFailure.parsing("来源列表响应缺少有效数据集合") }
    }

    private static func withoutCredentials(_ object: JSONObject) -> JSONObject {
        var result: JSONObject = [:]
        for (key, value) in object where !["security_key", "securityKey", "token", "access_token", "refresh_token", "password"].contains(key) {
            result[key] = withoutCredentials(value)
        }
        return result
    }

    private static func withoutCredentials(_ value: JSONValue) -> JSONValue {
        switch value {
        case .object(let object): return .object(withoutCredentials(object))
        case .array(let values): return .array(values.map { withoutCredentials($0) })
        default: return value
        }
    }

    private func performRequest(path: String, body: JSONObject, comment: Bool = false, securityKey: String) async throws -> JSONObject {
        let generation = authGeneration
        let url = try Self.endpoint(path: path, comment: comment)
        var payload = body
        payload.removeValue(forKey: "security_key")
        if !securityKey.isEmpty { payload["security_key"] = j(securityKey) }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = try JSONEncoder().encode(JSONValue.object(payload))
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.cachePolicy = .reloadIgnoringLocalCacheData
        let result = try await http.send(request)
        guard generation == authGeneration else { throw CancellationError() }
        return try Self.parseResponse(result)
    }

    private func performMultipart(
        path: String, fields: [String: String], fileName: String, mimeType: String, fileData: Data
    ) async throws -> JSONObject {
        guard !mimeType.contains("\r"), !mimeType.contains("\n") else { throw SourceFailure.parsing("无效的图片 MIME 类型") }
        let url = try Self.endpoint(path: path, comment: false)
        let generation = authGeneration
        let boundary = "----MixnKingdom-\(UUID().uuidString)"
        var body = Data()
        func append(_ string: String) { body.append(contentsOf: string.utf8) }
        for (name, value) in fields {
            append("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(name)\"\r\n\r\n\(value)\r\n")
        }
        let safeName = fileName.replacingOccurrences(of: "\r", with: "").replacingOccurrences(of: "\n", with: "").replacingOccurrences(of: "\"", with: "_")
        append("--\(boundary)\r\nContent-Disposition: form-data; name=\"file\"; filename=\"\(safeName)\"\r\nContent-Type: \(mimeType)\r\n\r\n")
        body.append(fileData)
        append("\r\n--\(boundary)--\r\n")
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = body
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.cachePolicy = .reloadIgnoringLocalCacheData
        let result = try await http.send(request)
        guard generation == authGeneration else { throw CancellationError() }
        return try Self.parseResponse(result)
    }

    private func requireSessionKey() throws -> String {
        let key = try secrets.read(Keys.securityKey) ?? ""
        guard !key.isEmpty, session.loggedIn else { throw SourceFailure.authentication("请先登录轻之国度") }
        return key
    }

    private func persist(_ session: AccountSession, securityKey: String) throws {
        guard !securityKey.isEmpty else { throw SourceFailure.authentication("登录响应缺少会话令牌") }
        let data = try JSONEncoder().encode(session)
        guard let metadata = String(data: data, encoding: .utf8) else { throw SourceFailure.parsing("无法保存登录状态") }
        try secrets.write(Keys.securityKey, value: securityKey)
        try secrets.write(Keys.session, value: metadata)
    }

    private func accountSession(from data: JSONObject, fallbackKey: String) -> AccountSession {
        let auth = data.object("auth")
        let user = data.object("user")
        let rawID = auth.string("uid").nilIfBlank
            ?? data.string("uid").nilIfBlank
            ?? user.string("uid", "id", "user_id").nilIfBlank
        let accountID = rawID.flatMap { (Int($0) ?? 0) > 0 ? $0 : nil } ?? "session:\(sha256(fallbackKey))"
        return AccountSession(loggedIn: true, accountID: accountID, displayName: user.string("nickname", "name", "username").nilIfBlank)
    }

    private func sessionKey(from data: JSONObject, fallback: String) throws -> String {
        let auth = data.object("auth")
        let key = auth.string("security_key", "securityKey", "token")
            .nilIfBlank ?? data.string("security_key", "securityKey", "token").nilIfBlank ?? fallback.nilIfBlank
        guard let key else { throw SourceFailure.authentication("登录响应缺少会话令牌") }
        return key
    }

    private func removePrivateCache(for key: String) async throws {
        metadataGeneration &+= 1
        guard !key.isEmpty else { return }
        try await cache.clear(scope: Self.privateScope(key))
    }

    private static func privateScope(_ key: String) -> String { "private:kingdom:\(sha256(key))" }

    private static func cacheKey(path: String, body: JSONObject, comment: Bool) -> String {
        let canonical = (try? JSONEncoder.sorted.encode(JSONValue.object(body))) ?? Data()
        return "lk:v1:\(sha256((comment ? "comment:" : "web:") + path + ":" + canonical.base64EncodedString()))"
    }

    private static func requireID(_ key: ResourceKey, source: SourceID) throws -> Int {
        guard key.source == source, let id = Int(key.remoteID), id > 0 else {
            throw SourceFailure.parsing("无效的轻之国度资源编号")
        }
        return id
    }

    private static func numericID(_ value: String?) throws -> Int {
        guard let value, !value.isEmpty else { return 0 }
        guard let id = Int(value), id >= 0 else { throw SourceFailure.parsing("无效的评论编号") }
        return id
    }

    private static func endpoint(path: String, comment: Bool) throws -> URL {
        let normalized = path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let components = normalized.split(separator: "/", omittingEmptySubsequences: false)
        guard !normalized.isEmpty,
              !normalized.contains("://"), !normalized.contains("?"), !normalized.contains("#"), !normalized.contains("\\"),
              components.allSatisfy({ !$0.isEmpty && $0 != "." && $0 != ".." }) else {
            throw SourceFailure.parsing("无效的轻之国度 API 路径")
        }
        let base = comment ? commentBase : apiBase
        guard let url = URL(string: normalized, relativeTo: base)?.absoluteURL,
              url.scheme == "https", url.host == base.host,
              url.path.hasPrefix(base.path) else { throw SourceFailure.parsing("无效的轻之国度 API 地址") }
        return url
    }

    private static func parseResponse(_ result: HTTPResult) throws -> JSONObject {
        // The caller chooses fallback only for transport failure; business/auth failures are never cached success.
        guard (200..<300).contains(result.status) else {
            if result.status == 401 || result.status == 403 { throw SourceFailure.authentication("轻之国度登录已失效，请重新登录") }
            if result.status == 429 { throw SourceFailure.rateLimited }
            throw SourceFailure.network("服务器返回 \(result.status)")
        }
        guard let value = try? JSONDecoder().decode(JSONValue.self, from: result.data), case .object(let root) = value else {
            throw SourceFailure.parsing("来源响应不是 JSON 对象")
        }
        if let code = root["code"], code != .null {
            guard let number = code.int else { throw SourceFailure.parsing("响应业务状态码无效") }
            if number != 0 { throw businessFailure(root, fallback: "请求失败（\(number)）", code: number) }
        }
        if root.bool("success", "ok") == false { throw businessFailure(root, fallback: "请求未成功") }
        let payload: JSONObject
        if case .object(let data)? = root["data"] { payload = data }
        else if case .object(let data)? = root["d"] { payload = data }
        else {
            var unwrapped = root
            ["code", "message", "msg", "t"].forEach { unwrapped.removeValue(forKey: $0) }
            payload = unwrapped
        }
        if payload.bool("success", "ok") == false { throw businessFailure(payload, fallback: "请求未成功") }
        if let code = payload["code"], let number = code.int, number != 0 {
            throw businessFailure(payload, fallback: "请求失败（\(number)）", code: number)
        }
        return payload
    }

    private static func businessFailure(_ object: JSONObject, fallback: String, code: Int? = nil) -> SourceFailure {
        let message = object.string("message", "msg", "error")
        let actual = message.isEmpty ? fallback : message
        let lower = actual.lowercased()
        if code == 401 || code == 403 || lower.contains("security_key") || lower.contains("unauthorized") || lower.contains("not logged") || actual.contains("未登录") || actual.contains("请先登录") || actual.contains("登录失效") || actual.contains("登录已失效") {
            return .authentication(actual)
        }
        return .server(actual)
    }
}

private extension JSONEncoder {
    static var sorted: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }
}

private extension String {
    var nilIfBlank: String? { isEmpty ? nil : self }
}

enum KingdomJSON {
    private static let levelNames = ["平民", "骑士", "勋爵", "子爵", "伯爵", "侯爵", "公爵", "王爵", "皇帝"]
    static func booksPage(_ source: JSONObject, requestedPage: Int, requestedPageSize: Int) -> Page<Novel> {
        let list = firstArray(source, ["list", "cards", "ranking_list", "items", "books"])
        let pagination = source.object("pagination")
        let pageInfo = source.object("page_info", "pageInfo")
        let page = pagination.optionalInt("page") ?? pageInfo.optionalInt("cur", "page") ?? requestedPage
        let total = pagination.optionalInt("total") ?? pageInfo.optionalInt("count", "total") ?? list.count
        let pageSize = pagination.optionalInt("page_size", "pageSize", "size")
            ?? pageInfo.optionalInt("size", "page_size", "pageSize") ?? max(1, requestedPageSize)
        let hasMore = pageInfo.bool("has_more", "hasMore", "has_next")
            ?? pagination.bool("has_more", "hasMore", "has_next")
            ?? ((pageInfo.int("next") > 0) || (pagination.int("next") > 0) || page * pageSize < total)
        let items = list.compactMap { $0.object }.map(book).filter { Int($0.key.remoteID) ?? 0 > 0 }
        return Page(items: items, page: page, total: total, hasMore: hasMore)
    }

    static func bookDetail(_ source: JSONObject) -> NovelDetail {
        let bookObject = source.object("book", "book_base").isEmpty ? source : source.object("book", "book_base")
        let novel = book(bookObject)
        let interaction = source.object("interaction_stats", "book_interaction")
        let publisher = source.object("poster_user", "publisher", "user")
        let alternate = source.array("alternate_versions", "alternateVersions").map { book($0.object) }.filter { Int($0.key.remoteID) ?? 0 > 0 }
        return NovelDetail(
            novel: novel, alternateVersions: alternate, publisher: publisher.string("nickname", "name").nilIfBlank,
            favorites: interaction.optionalInt("favorite_count") ?? source.int("favorite_count"),
            commentCount: interaction.optionalInt("comment_count") ?? source.int("comment_count")
        )
    }

    static func book(_ source: JSONObject) -> Novel {
        let readState = source.object("read_state", "readState")
        let stats = source.object("stats")
        let rating = source.object("rating")
        let tags = source.array("visible_tags", "tags", "reason_tags").compactMap { item -> String? in
            if let text = item.text, !text.isEmpty { return text }
            let object = item.object
            return object.string("label", "name", "text").nilIfBlank
        }.uniqued()
        let id = source.string("book_id", "id")
        let chapterCount = positive(source.int("chapter_count", "published_chapter_count"))
            ?? positive(stats.int("chapter_count", "published_chapter_count"))
            ?? readState.optionalInt("published_chapter_count") ?? 0
        let volumeCount = positive(source.int("volume_count")) ?? positive(stats.int("volume_count"))
            ?? readState.optionalInt("volume_count") ?? 0
        let score = positiveDouble(source.value("rating_score_10")).map { $0 / 2 }
            ?? positiveDouble(rating.value("score_10")).map { $0 / 2 }
            ?? positiveDouble(source.value("rating_score")).map { $0 / 2 }
            ?? positiveDouble(source.value("rating_stars_average"))
            ?? positiveDouble(rating.value("stars_average", "score"))
        let cover = source.string("cover_url", "cover", "banner_url", "image", "book_cover", "pic_url", "pic")
        return Novel(
            key: ResourceKey(.kingdom, id), title: source.string("title", "book_title").nilIfBlank ?? "未命名作品",
            authors: source.string("author_name", "author", "authorName").nilIfBlank.map { [$0] } ?? [],
            synopsis: source.string("summary_short", "summary", "content_preview", "intro"),
            coverURL: webURL(cover), tags: tags, volumeCount: volumeCount, chapterCount: chapterCount,
            wordCount: positive64(source.value("word_count")) ?? positive64(stats.value("word_count")) ?? 0,
            score: score, inShelf: source.bool("in_shelf", "in_collection", "favorited", "inBookshelf"),
            unreadCount: source.optionalInt("unread_chapter_count").flatMap { $0 >= 0 ? $0 : nil }
        )
    }

    static func volumesPage(_ source: JSONObject, novel: ResourceKey, page: Int, pageSize: Int) -> Page<Volume> {
        let list = firstArray(source, ["list", "volumes"])
        let pagination = source.object("pagination", "page_info")
        let items = list.compactMap { value -> Volume? in
            let item = value.object
            let id = item.string("volume_id", "id")
            guard Int(id) ?? 0 > 0 else { return nil }
            return Volume(
                key: ResourceKey(.kingdom, id), novelKey: novel,
                title: item.string("title", "volume_title").nilIfBlank ?? "未命名分卷",
                chapterCount: item.int("chapter_count")
            )
        }
        let total = pagination.optionalInt("total", "count") ?? items.count
        return Page(items: items, page: page, total: total, hasMore: pagination.bool("has_more", "hasMore", "has_next") ?? (page * pageSize < total))
    }

    static func chaptersPage(
        _ source: JSONObject, novel: ResourceKey, volume: ResourceKey, page: Int, pageSize: Int, unlocked: Set<String>
    ) -> Page<Chapter> {
        let list = firstArray(source, ["list", "chapters"])
        let pagination = source.object("pagination", "page_info")
        let items = list.compactMap { value -> Chapter? in
            let item = value.object
            let id = item.string("chapter_id", "id")
            guard Int(id) ?? 0 > 0 else { return nil }
            return chapter(item, novel: novel, volume: volume, unlocked: unlocked)
        }
        let total = pagination.optionalInt("total", "count") ?? items.count
        return Page(items: items, page: page, total: total, hasMore: pagination.bool("has_more", "hasMore", "has_next") ?? (page * pageSize < total))
    }

    static func chapterContent(_ source: JSONObject, novel: ResourceKey, requestedChapter: ResourceKey, unlocked: Set<String>) -> ChapterContent {
        let body = source.object("body_snapshot", "body", "content")
        let navigation = source.object("navigation", "chapter_navigation", "nav")
        let rawChapter = chapter(source, novel: novel, volume: ResourceKey(.kingdom, source.string("volume_id")), unlocked: unlocked)
        let mappedChapter = rawChapter.key.remoteID.isEmpty ? Chapter(
            key: requestedChapter, novelKey: novel, volumeKey: rawChapter.volumeKey, title: rawChapter.title,
            order: rawChapter.order, wordCount: rawChapter.wordCount, locked: rawChapter.locked, coinPrice: rawChapter.coinPrice
        ) : rawChapter
        let previous = navigationID(navigation.value("prev_chapter", "previous_chapter", "prev"))
            ?? navigationID(source.value("prev_chapter", "previous_chapter", "prev_chapter_id"))
        let next = navigationID(navigation.value("next_chapter", "next"))
            ?? navigationID(source.value("next_chapter", "next_chapter_id"))
        return ChapterContent(
            chapter: mappedChapter, novelTitle: source.string("book_title"),
            volumeTitle: source.string("volume_title", "origin_volume_title"),
            bodyText: body.string("body_text", "text", "content_text"),
            bodyHTML: body.string("body_html", "html", "content_html"),
            previous: previous.map { ResourceKey(.kingdom, String($0)) }, next: next.map { ResourceKey(.kingdom, String($0)) }
        )
    }

    static func historyPage(_ source: JSONObject, page: Int, pageSize: Int) -> Page<HistoryEntry> {
        let list = objectList(source, ["list", "cards", "items", "books"])
        let pagination = source.object("pagination", "page_info", "pageInfo")
        let items = list.compactMap { item -> HistoryEntry? in
            let novel = book(item)
            guard Int(novel.key.remoteID) ?? 0 > 0 else { return nil }
            let history = item.object("history", "read_state")
            let chapterID = item.string("last_read_chapter_id", "lastReadChapterId").nilIfBlank
                ?? history.string("last_read_chapter_id", "chapter_id", "default_chapter_id").nilIfBlank
                ?? item.string("default_chapter_id").nilIfBlank
            return HistoryEntry(
                novel: novel, chapterKey: chapterID.map { ResourceKey(.kingdom, $0) },
                chapterTitle: item.string("last_read_chapter_title", "chapter_title", "latest_chapter_title")
                    .nilIfBlank ?? history.string("last_read_chapter_title", "chapter_title", "title"),
                readAt: item.string("read_at", "last_read_at").nilIfBlank ?? history.string("read_at", "last_read_at", "updated_at")
            )
        }
        let total = pagination.optionalInt("total", "count") ?? source.int("total").takeIfPositive ?? items.count
        let actualSize = pagination.optionalInt("page_size", "pageSize", "size") ?? max(1, pageSize)
        let more = pagination.bool("has_more", "hasMore", "has_next") ?? (pagination.int("next") > 0 || page * actualSize < total)
        return Page(items: items, page: pagination.optionalInt("page", "cur", "current_page") ?? page, total: total, hasMore: more)
    }

    static func profile(_ source: JSONObject) -> SourceProfile {
        let profile = source.object("profile", "user").isEmpty ? source : source.object("profile", "user")
        let stats = source.object("stats").isEmpty ? profile.object("stats") : source.object("stats")
        let balanceObject = profile.object("balance")
        let levelObject = profile.object("level")
        let group = profile.object("user_group", "group", "rank")
        let user = profile.object("user")
        let accountID = profile.string("uid", "user_id", "id").nilIfBlank ?? user.string("uid", "id", "user_id").nilIfBlank
        let name = profile.string("nickname", "username", "name").nilIfBlank ?? "已登录用户"
        let level = positive(profile.int("level", "level_id", "levelId")) ?? positive(levelObject.int("level", "id"))
        let levelName = profile.string("level_name", "levelName", "level_title", "group_name", "user_group_name", "rank_name", "role_name")
            .nilIfBlank ?? levelObject.string("name").nilIfBlank ?? group.string("name", "title").nilIfBlank
            ?? level.flatMap { (1...levelNames.count).contains($0) ? levelNames[$0 - 1] : nil }
        let coin = profile.optionalInt("coin", "light_coin", "lightCoin", "balance")
            ?? balanceObject.optionalInt("coin", "light_coin", "lightCoin")
        let exp = profile.optionalInt("exp")
        return SourceProfile(
            source: .kingdom, accountID: accountID, name: name,
            avatarURL: webURL(profile.string("avatar_url", "avatar", "avatarUrl")),
            balance: coin.map(Int64.init), level: level, levelLabel: levelName,
            maximumLevel: levelNames.count, experience: exp.flatMap { $0 >= 0 ? Experience(total: Int64($0)) : nil },
            extra: [
                "关注": String(stats.optionalInt("following", "following_count") ?? profile.int("following", "following_count", "followingCount")),
                "粉丝": String(stats.optionalInt("followers", "fans", "fans_count") ?? profile.int("followers", "fans", "fans_count", "fansCount")),
                "发布": String(stats.optionalInt("publish_articles", "post_count", "posts") ?? profile.int("publish_articles", "post_count", "posts", "postCount")),
                "等级": levelName ?? "",
                "经验获取": "LK 未提供等级经验门槛；经验值以来源资料为准。"
            ]
        )
    }

    static func rewardStatus(_ source: JSONObject, balance: Int64?) -> RewardStatus {
        let days = source.array("rewards").compactMap { value -> RewardDay? in
            let item = value.object
            let day = item.int("day")
            guard day > 0 else { return nil }
            return RewardDay(day: day, claimed: item.bool("claimed") ?? false, reward: item.string("reward_amount", "amount"))
        }
        return RewardStatus(
            claimedToday: source.bool("claimed") ?? false, balance: balance,
            streak: source.optionalInt("progress"), days: days
        )
    }

    static func rewardResult(_ source: JSONObject) -> RewardResult {
        let reward = source.object("reward", "result")
        return RewardResult(
            coins: positive64(source.value("reward_amount", "amount", "coin"))
                ?? positive64(reward.value("reward_amount", "amount", "coin")),
            balance: nonnegative64(source.value("balance", "total_coin", "light_coin"))
                ?? nonnegative64(reward.value("balance", "total_coin", "light_coin")),
            streak: nonnegativeInt(source.value("streak_days", "continue_days", "progress"))
        )
    }

    static func commentsPage(_ source: JSONObject, page: Int) -> Page<Comment> {
        let list = firstArray(source, ["list", "root_comment", "items"])
        let items = list.compactMap { $0.object.isEmpty ? nil : comment($0.object) }.filter { !$0.content.isEmpty || !$0.media.isEmpty }
        let pagination = source.object("page_info", "pagination")
        let total = pagination.optionalInt("count", "total") ?? items.count
        let more = pagination.int("next") > 0 || (pagination.bool("has_next") ?? false)
        return Page(items: items, page: page, total: total, hasMore: more)
    }

    static func comment(_ source: JSONObject) -> Comment {
        let author = source.object("user", "author", "sender", "poster_user")
        let stats = source.object("stats")
        let interaction = source.object("interaction_state", "interactionState")
        let replyToObject = source.object("reply_to_user", "replyToUser", "to_user", "target_user")
        let replyToArray = source.array("reply_to_user").first?.object ?? [:]
        let replyTo = replyToObject.isEmpty ? replyToArray : replyToObject
        let replyItems = firstArray(source, ["reply_preview", "reply_list", "replies", "children"])
        let mediaObjects = firstArray(source, ["resources", "media", "images", "image_list"])
        var media = mediaObjects.compactMap { commentMedia($0.object) }
        media += source.array("imageUrls", "image_urls").compactMap { value in
            guard let url = value.text.flatMap(webURL) else { return nil }
            return CommentMedia(url: url)
        }
        let id = source.string("comment_id", "commentId", "id")
        return Comment(
            id: id, author: author.string("nickname", "name", "username").nilIfBlank ?? "匿名用户",
            avatarURL: webURL(author.string("avatar_url", "avatar", "avatarUrl")),
            content: source.string("content", "content_text", "body", "text"),
            createdAt: source.string("publish_time", "created_at", "createdAt", "date_text", "dateText", "time"),
            likeCount: source.optionalInt("like_count", "likeCount", "likes").flatMap { $0 != 0 ? $0 : nil } ?? stats.int("like_count", "likes"),
            replyCount: source.optionalInt("reply_count", "replyCount", "replies_count").flatMap { $0 != 0 ? $0 : nil } ?? stats.optionalInt("conversation_count", "replies") ?? replyItems.count,
            ratingStars: source.optionalInt("rating_stars", "rating", "stars", "star").flatMap { (1...5).contains($0) ? $0 : nil },
            rootID: source.string("root_comment_id", "rootCommentId").nilIfBlank.flatMap { (Int($0) ?? 0) > 0 ? $0 : nil },
            replyTo: replyTo.string("nickname", "name", "username").nilIfBlank,
            liked: interaction.bool("liked", "is_liked") ?? source.bool("liked", "is_liked") ?? false,
            media: media.uniqued(by: \.url),
            replies: replyItems.compactMap { $0.object.isEmpty ? nil : comment($0.object) }
        )
    }

    static func emojis(_ source: JSONObject) -> [CommentEmoji] {
        var result: [CommentEmoji] = []
        for packValue in firstArray(source, ["list", "items", "emojis", "default"]) {
            let pack = packValue.object
            let children = firstArray(pack, ["items", "emojis"])
            let entries = children.isEmpty ? [packValue] : children
            for itemValue in entries {
                let item = itemValue.object
                let code = item.string("code", "token", "text")
                guard !code.isEmpty else { continue }
                let raw = item.string("url", "image", "src", "icon")
                let imageRaw = raw.hasPrefix("/") && !raw.hasPrefix("//") ? "https://www.lightnovel.fun" + raw : raw
                let image = (raw.hasPrefix("http://") || raw.hasPrefix("https://") || raw.hasPrefix("/")) ? webURL(imageRaw) : nil
                let text = item.string("text").nilIfBlank.flatMap { $0 == code ? nil : $0 }
                    ?? (image == nil ? raw.nilIfBlank : nil)
                guard image != nil || text != nil else { continue }
                result.append(CommentEmoji(code: code, imageURL: image, text: text, label: item.string("label", "name", "title").nilIfBlank ?? pack.string("name", "title", "label").nilIfBlank))
            }
        }
        return result.uniqued(by: \.code)
    }

    static func mentionCandidates(_ source: JSONObject) -> [MentionCandidate] {
        objectList(source, ["list", "cards", "items", "books", "messages", "conversations"]).compactMap { item in
            let profile = item.object("profile", "user").isEmpty ? item : item.object("profile", "user")
            let id = profile.string("uid", "id", "user_id")
            guard Int(id) ?? 0 > 0 else { return nil }
            return MentionCandidate(id: id, name: profile.string("nickname", "name", "username").nilIfBlank ?? "用户\(id)", avatarURL: webURL(profile.string("avatar_url", "avatar", "avatarUrl")))
        }
    }

    private static func chapter(_ source: JSONObject, novel: ResourceKey, volume: ResourceKey, unlocked: Set<String>) -> Chapter {
        let id = source.string("chapter_id", "id")
        let locked = source.bool("locked") == true || source.bool("unlocked") == false
        return Chapter(
            key: ResourceKey(.kingdom, id), novelKey: novel,
            volumeKey: ResourceKey(.kingdom, source.string("volume_id").nilIfBlank ?? volume.remoteID),
            title: source.string("title", "chapter_title").nilIfBlank ?? "未命名章节",
            order: source.int("chapter_no", "order_no", "sort_index"),
            wordCount: source.int("word_count"), locked: locked && !unlocked.contains(id),
            coinPrice: coinPrice(source.value("coin_price", "coinPrice", "price", "cost"))
        )
    }

    private static func coinPrice(_ value: JSONValue) -> Int? {
        if let integer = value.int, integer > 0 { return integer }
        if let string = value.text, let double = Double(string), double > 0, double.rounded() == double, double <= Double(Int32.max) { return Int(double) }
        if case .object(let object) = value { return coinPrice(object.value("value", "amount", "coins", "coin_price", "price", "cost")) }
        return nil
    }

    private static func commentMedia(_ source: JSONObject) -> CommentMedia? {
        guard let url = webURL(source.string("url", "res_url", "stored_url", "source_url", "src", "image", "image_url")) else { return nil }
        return CommentMedia(url: url, width: source.optionalInt("width", "w"), height: source.optionalInt("height", "h"), resourceID: source.string("res_id", "resId", "resource_id").nilIfBlank)
    }

    private static func navigationID(_ value: JSONValue) -> Int? {
        if let id = value.int, id > 0 { return id }
        if case .object(let object) = value {
            return object.optionalInt("chapter_id", "id", "chapterId").flatMap { $0 > 0 ? $0 : nil }
                ?? navigationID(object.value("chapter", "value"))
        }
        if case .array(let values) = value { return values.lazy.compactMap(navigationID).first }
        return nil
    }

    private static func firstArray(_ source: JSONObject, _ keys: [String]) -> [JSONValue] {
        for key in keys {
            let values = source.array(key)
            if !values.isEmpty { return values }
        }
        return []
    }

    private static func objectList(_ source: JSONObject, _ keys: [String]) -> [JSONObject] {
        let direct = firstArray(source, keys).compactMap { $0.object.isEmpty ? nil : $0.object }
        if !direct.isEmpty { return direct }
        let nested = source.object("data", "d")
        return firstArray(nested, keys).compactMap { $0.object.isEmpty ? nil : $0.object }
    }

    private static func positive(_ value: Int) -> Int? { value > 0 ? value : nil }
    private static func positiveDouble(_ value: JSONValue) -> Double? { value.double.flatMap { $0 > 0 ? $0 : nil } }
    private static func positive64(_ value: JSONValue) -> Int64? { value.text.flatMap(Int64.init).flatMap { $0 > 0 ? $0 : nil } }
    private static func nonnegative64(_ value: JSONValue) -> Int64? { value.text.flatMap(Int64.init).flatMap { $0 >= 0 ? $0 : nil } }
    private static func nonnegativeInt(_ value: JSONValue) -> Int? { value.int.flatMap { $0 >= 0 ? $0 : nil } }
}

private extension Int {
    var takeIfPositive: Int? { self > 0 ? self : nil }
}

private extension Array where Element: Hashable {
    func uniqued() -> [Element] {
        var seen = Set<Element>()
        return filter { seen.insert($0).inserted }
    }
    func uniqued<Key: Hashable>(by keyPath: KeyPath<Element, Key>) -> [Element] {
        var seen = Set<Key>()
        return filter { seen.insert($0[keyPath: keyPath]).inserted }
    }
}
