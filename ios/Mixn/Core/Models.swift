import Foundation

// IDs and wire semantics deliberately match Android core/source/SourceModels.kt.
enum SourceID: String, Codable, CaseIterable, Identifiable {
    case kingdom = "light_novel_kingdom"
    case shelf = "light_novel_shelf"
    var id: String { rawValue }
    var title: String { self == .kingdom ? "轻之国度" : "轻书架" }
    var website: URL { URL(string: self == .kingdom ? "https://www.lightnovel.fun/" : "https://www.lightnovel.life/")! }
}
struct ResourceKey: Codable, Hashable, Identifiable {
    var source: SourceID
    var remoteID: String
    var id: String { source.rawValue + ":" + remoteID }
    init(_ source: SourceID, _ remoteID: String) { self.source = source; self.remoteID = remoteID }
}
enum Feed: String, CaseIterable, Codable, Identifiable {
    case popular, latest, newest, daily, weekly, monthly, original, fanfic, epub
    var id: String { rawValue }
    var title: String {
        switch self { case .popular: return "热门"; case .latest: return "最近更新"; case .newest: return "新书"; case .daily: return "日榜"; case .weekly: return "周榜"; case .monthly: return "月榜"; case .original: return "原创"; case .fanfic: return "同人"; case .epub: return "EPUB" }
    }
}
struct Novel: Codable, Hashable, Identifiable {
    var key: ResourceKey
    var title: String
    var authors: [String] = []
    var synopsis: String = ""
    var coverURL: URL? = nil
    var tags: [String] = []
    var volumeCount: Int = 0
    var chapterCount: Int = 0
    var wordCount: Int64 = 0
    var score: Double? = nil
    var inShelf: Bool? = nil
    var unreadCount: Int? = nil
    var id: String { key.id }
}
struct NovelDetail: Codable, Hashable {
    var novel: Novel
    var alternateVersions: [Novel] = []
    var publisher: String? = nil
    var favorites: Int = 0
    var commentCount: Int = 0
}
struct Volume: Codable, Hashable, Identifiable {
    var key: ResourceKey
    var novelKey: ResourceKey
    var title: String
    var chapterCount: Int = 0
    var id: String { key.id }
}
struct Chapter: Codable, Hashable, Identifiable {
    var key: ResourceKey
    var novelKey: ResourceKey
    var volumeKey: ResourceKey
    var title: String
    var order: Int = 0
    var wordCount: Int = 0
    var locked: Bool = false
    var coinPrice: Int? = nil
    var id: String { key.id }
}
struct ChapterContent: Codable, Hashable {
    var chapter: Chapter
    var novelTitle: String
    var volumeTitle: String
    var bodyText: String
    var bodyHTML: String = ""
    var fontURL: URL? = nil
    var previous: ResourceKey? = nil
    var next: ResourceKey? = nil
}
struct Page<T: Codable & Hashable>: Codable, Hashable {
    var items: [T]
    var page: Int = 1
    var total: Int = 0
    var hasMore: Bool = false
}
struct AccountSession: Codable, Hashable {
    var loggedIn: Bool = false
    var accountID: String? = nil
    var displayName: String? = nil
}
struct Experience: Codable, Hashable {
    var total: Int64
    var levelStart: Int64? = nil
    var nextLevel: Int64? = nil
    var maxLevel: Bool = false
    var fraction: Double? {
        if maxLevel { return 1 }
        guard let start = levelStart, let next = nextLevel, start >= 0, next > start, total >= start else { return nil }
        return min(1, max(0, Double(total - start) / Double(next - start)))
    }
}
struct SourceProfile: Codable, Hashable {
    var source: SourceID
    var accountID: String? = nil
    var name: String = ""
    var avatarURL: URL? = nil
    var balance: Int64? = nil
    var level: Int? = nil
    var levelLabel: String? = nil
    var maximumLevel: Int? = nil
    var experience: Experience? = nil
    var extra: [String: String] = [:]
}
struct ReadingProgress: Codable, Hashable {
    var novelKey: ResourceKey
    var chapterKey: ResourceKey
    var volumeKey: ResourceKey? = nil
    var paragraphIndex: Int = 0
    var percent: Int = 0
    var updatedAt: Date = Date()
}
struct HistoryEntry: Codable, Hashable, Identifiable {
    var novel: Novel
    var chapterKey: ResourceKey? = nil
    var chapterTitle: String = ""
    var readAt: String = ""
    var id: String { novel.id }
}
struct RewardDay: Codable, Hashable, Identifiable {
    var day: Int
    var claimed: Bool = false
    var reward: String = ""
    var id: Int { day }
}
struct RewardStatus: Codable, Hashable {
    var claimedToday: Bool = false
    var balance: Int64? = nil
    var streak: Int? = nil
    var days: [RewardDay] = []
}
struct RewardResult: Codable, Hashable {
    var coins: Int64? = nil
    var balance: Int64? = nil
    var streak: Int? = nil
    var experience: Int64? = nil
}
struct CommentMedia: Codable, Hashable, Identifiable {
    var url: URL
    var width: Int? = nil
    var height: Int? = nil
    var resourceID: String? = nil
    var id: String { url.absoluteString }
}
struct Comment: Codable, Hashable, Identifiable {
    var id: String
    var author: String
    var avatarURL: URL? = nil
    var content: String
    var createdAt: String = ""
    var likeCount: Int = 0
    var replyCount: Int = 0
    var ratingStars: Int? = nil
    var rootID: String? = nil
    var replyTo: String? = nil
    var liked: Bool = false
    var media: [CommentMedia] = []
    var replies: [Comment] = []
}
struct CommentEmoji: Codable, Hashable, Identifiable {
    var code: String
    var imageURL: URL? = nil
    var text: String? = nil
    var label: String? = nil
    var id: String { code }
}
struct MentionCandidate: Codable, Hashable, Identifiable {
    var id: String
    var name: String
    var avatarURL: URL? = nil
}
struct CommentDraft {
    var content: String
    var rootID: String? = nil
    var replyID: String? = nil
    var mentions: [String] = []
    var media: [CommentMedia] = []
}
enum SourceFailure: Error, LocalizedError, Equatable {
    case authentication(String), network(String), timeout, server(String), parsing(String), unsupported(String), rateLimited
    var errorDescription: String? {
        switch self {
        case .authentication(let s): return s.isEmpty ? "请先登录该来源" : s
        case .network(let s): return s.isEmpty ? "网络连接失败，请检查网络" : s
        case .timeout: return "请求超时，请稍后重试"
        case .server(let s), .parsing(let s), .unsupported(let s): return s
        case .rateLimited: return "请求过于频繁，请稍后再试"
        }
    }
}
@MainActor protocol NovelSource: AnyObject {
    var sourceID: SourceID { get }
    var feeds: [Feed] { get }
    var session: AccountSession { get }
    var supportsComments: Bool { get }
    func restoreSession() async throws -> AccountSession
    func login(identifier: String, password: String) async throws -> AccountSession
    func logout() async throws
    func profile() async throws -> SourceProfile
    func discover(feed: Feed, page: Int, pageSize: Int) async throws -> Page<Novel>
    func search(query: String, page: Int, pageSize: Int) async throws -> Page<Novel>
    func detail(key: ResourceKey) async throws -> NovelDetail
    func volumes(key: ResourceKey, page: Int) async throws -> Page<Volume>
    func chapters(novel: ResourceKey, volume: ResourceKey, page: Int) async throws -> Page<Chapter>
    func chapter(novel: ResourceKey, chapter: ResourceKey) async throws -> ChapterContent
    func shelf() async throws -> [Novel]
    func setShelf(key: ResourceKey, add: Bool) async throws
    func history(page: Int) async throws -> Page<HistoryEntry>
    func deleteHistory(key: ResourceKey) async throws
    func saveProgress(_ progress: ReadingProgress) async throws
    func rewards() async throws -> RewardStatus
    func claimReward() async throws -> RewardResult
    func unlock(chapter: ResourceKey) async throws
    func comments(key: ResourceKey, latest: Bool, page: Int) async throws -> Page<Comment>
    func publishComment(key: ResourceKey, draft: CommentDraft) async throws -> Comment
    func rate(key: ResourceKey, stars: Int) async throws
    func likeComment(key: ResourceKey, commentID: String, liked: Bool) async throws -> Bool
    func emojis() async throws -> [CommentEmoji]
    func mentions(query: String) async throws -> [MentionCandidate]
    func uploadCommentImage(data: Data, fileName: String, mimeType: String) async throws -> CommentMedia
    func clearMetadataCache()
}
// Optional source capabilities are explicit errors, never fake successful writes.
extension NovelSource {
    var supportsComments: Bool { false }
    func deleteHistory(key: ResourceKey) async throws { throw SourceFailure.unsupported("该来源不支持删除远端历史") }
    func unlock(chapter: ResourceKey) async throws { throw SourceFailure.unsupported("该来源不支持章节购买") }
    func comments(key: ResourceKey, latest: Bool, page: Int) async throws -> Page<Comment> { throw SourceFailure.unsupported("该来源不支持评论") }
    func publishComment(key: ResourceKey, draft: CommentDraft) async throws -> Comment { throw SourceFailure.unsupported("该来源不支持评论") }
    func rate(key: ResourceKey, stars: Int) async throws { throw SourceFailure.unsupported("该来源不支持评分") }
    func likeComment(key: ResourceKey, commentID: String, liked: Bool) async throws -> Bool { throw SourceFailure.unsupported("该来源不支持评论点赞") }
    func emojis() async throws -> [CommentEmoji] { throw SourceFailure.unsupported("该来源不支持表情") }
    func mentions(query: String) async throws -> [MentionCandidate] { throw SourceFailure.unsupported("该来源不支持提及用户") }
    func uploadCommentImage(data: Data, fileName: String, mimeType: String) async throws -> CommentMedia { throw SourceFailure.unsupported("该来源不支持评论图片") }
    func clearMetadataCache() {}
}

indirect enum JSONValue: Codable, Hashable {
    case object([String: JSONValue]), array([JSONValue]), string(String), number(Double), bool(Bool), null
    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let x = try? c.decode(Bool.self) { self = .bool(x) }
        else if let x = try? c.decode(String.self) { self = .string(x) }
        else if let x = try? c.decode(Double.self) { self = .number(x) }
        else if let x = try? c.decode([JSONValue].self) { self = .array(x) }
        else { self = .object(try c.decode([String: JSONValue].self)) }
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self { case .null: try c.encodeNil(); case .bool(let x): try c.encode(x); case .string(let x): try c.encode(x); case .number(let x): try c.encode(x); case .array(let x): try c.encode(x); case .object(let x): try c.encode(x) }
    }
    var object: JSONObject { if case .object(let x) = self { return x }; return [:] }
    var array: [JSONValue] { if case .array(let x) = self { return x }; return [] }
    var text: String? {
        switch self { case .string(let x): return x; case .number(let x): return x.rounded() == x ? String(format: "%.0f", x) : String(x); case .bool(let x): return x ? "true" : "false"; default: return nil }
    }
    var int: Int? {
        switch self { case .number(let x): return Int(exactly: x); case .string(let x): return Int(x); default: return nil }
    }
    var double: Double? {
        switch self { case .number(let x): return x; case .string(let x): return Double(x); default: return nil }
    }
    var flag: Bool? {
        switch self {
        case .bool(let x): return x
        case .number(let x): return x != 0
        case .string(let x):
            switch x.lowercased() {
            case "true", "1", "yes", "add", "in_shelf": return true
            case "false", "0", "no", "remove": return false
            default: return nil
            }
        default: return nil
        }
    }
}
typealias JSONObject = [String: JSONValue]
extension Dictionary where Key == String, Value == JSONValue {
    func value(_ keys: String...) -> JSONValue { for key in keys { if let value = self[key], value != .null { return value } }; return .null }
    func string(_ keys: String...) -> String {
        for key in keys { if let value = self[key]?.text?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty { return value } }
        return ""
    }
    func int(_ keys: String...) -> Int { for key in keys { if let value = self[key]?.int { return value } }; return 0 }
    func optionalInt(_ keys: String...) -> Int? { for key in keys { if let value = self[key]?.int { return value } }; return nil }
    func bool(_ keys: String...) -> Bool? { for key in keys { if let value = self[key]?.flag { return value } }; return nil }
    func object(_ keys: String...) -> JSONObject { for k in keys { if case .object(let x) = self[k] { return x } }; return [:] }
    func array(_ keys: String...) -> [JSONValue] { for k in keys { if case .array(let x) = self[k] { return x } }; return [] }
}
extension JSONValue: ExpressibleByStringLiteral, ExpressibleByIntegerLiteral, ExpressibleByBooleanLiteral {
    init(stringLiteral value: String) { self = .string(value) }
    init(integerLiteral value: Int) { self = .number(Double(value)) }
    init(booleanLiteral value: Bool) { self = .bool(value) }
}
func j(_ value: String) -> JSONValue { .string(value) }
func j(_ value: Int) -> JSONValue { .number(Double(value)) }
func j(_ value: Int64) -> JSONValue { .number(Double(value)) }
func j(_ value: Bool) -> JSONValue { .bool(value) }
func webURL(_ raw: String) -> URL? {
    let s = raw.hasPrefix("//") ? "https:" + raw : raw
    guard let url = URL(string: s), ["https", "http"].contains(url.scheme?.lowercased() ?? ""), url.host != nil, url.user == nil, url.password == nil else { return nil }
    return url
}
extension String {
    var plainHTML: String {
        replacingOccurrences(of: "(?i)<br\\s*/?>|</p>|</div>", with: "\n", options: .regularExpression)
            .replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
            .replacingOccurrences(of: "&nbsp;", with: " ").replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&lt;", with: "<").replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"").trimmingCharacters(in: .whitespacesAndNewlines)
    }
    var htmlEscaped: String { replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;").replacingOccurrences(of: "\"", with: "&quot;").replacingOccurrences(of: "'", with: "&#39;") }
}
