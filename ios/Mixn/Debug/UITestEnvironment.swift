#if DEBUG
import Foundation

// Compiled out of Release. UI automation injects this adapter at the production seam.
// Text and images are synthetic and do not contain third-party book content or secrets.
@MainActor enum UITestEnvironment {
    static func makeStore() -> AppStore {
        let identifier = ProcessInfo.processInfo.environment["MIXN_UI_RUN"] ?? UUID().uuidString
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("mixn-ui-" + identifier, isDirectory: true)
        let defaults = UserDefaults(suiteName: "io.github.hyperionhxh.mixn.uitests." + identifier)!
        let preferences = PreferencesStore(defaults: defaults)
        preferences.onboardingComplete = ProcessInfo.processInfo.environment["MIXN_UI_ONBOARDING"] != "1"
        let font = try! UITestFont.make(in: root)
        let sources: [SourceID: any NovelSource] = [.kingdom: UITestSource(.kingdom), .shelf: UITestSource(.shelf, mandatoryFont: font)]
        return AppStore(sources: sources, preferences: preferences, library: LibraryStore(rootURL: root),
                        fonts: FontStore(rootURL: root), cache: ContentCache(rootURL: root.appendingPathComponent("cache")))
    }
}
@MainActor final class UITestSource: NovelSource {
    let sourceID: SourceID
    var session = AccountSession(loggedIn: false)
    var supportsComments: Bool { sourceID == .kingdom }
    var feeds: [Feed] { [.popular, .latest, .newest, .daily, .weekly] }
    private var saved = Set<String>()
    private var commentLiked = false
    private var signed = false
    private var userComments: [Comment] = []
    private let mandatoryFont: URL?
    init(_ id: SourceID, mandatoryFont: URL? = nil) { sourceID = id; self.mandatoryFont = mandatoryFont }
    private func book(_ index: Int) -> Novel {
        Novel(key: ResourceKey(sourceID, String(index)), title: index == 1 ? "跨端阅读验收" : "长标题与段落排版检验 第\(index)卷",
              authors: [sourceID == .kingdom ? "测试作者甲" : "测试作者乙"], synopsis: "此内容是隔离 UI 验收夹具，不属于任何真实作品。用于检查作者、正文、列表和离线状态。",
              tags: ["原创测试", "排版"], volumeCount: 2, chapterCount: 4, wordCount: 2400, inShelf: saved.contains(String(index)))
    }
    func restoreSession() async throws -> AccountSession { session }
    func login(identifier: String, password: String) async throws -> AccountSession {
        guard !identifier.isEmpty, !password.isEmpty else { throw SourceFailure.authentication("账号与密码不能为空") }
        session = AccountSession(loggedIn: true, accountID: "fixture", displayName: "测试账号")
        return session
    }
    func logout() async throws { session = AccountSession() }
    func profile() async throws -> SourceProfile {
        guard session.loggedIn else { throw SourceFailure.authentication("请先登录该来源") }
        return SourceProfile(source: sourceID, accountID: "fixture", name: "测试账号", balance: 25, level: 3,
                             levelLabel: sourceID == .kingdom ? "LV3" : "成长等级 3", experience: Experience(total: 350, levelStart: 300, nextLevel: 500))
    }
    func discover(feed: Feed, page: Int, pageSize: Int) async throws -> Page<Novel> {
        try await Task.sleep(nanoseconds: 150_000_000)
        if ProcessInfo.processInfo.environment["MIXN_UI_ERROR"] == "1" { throw SourceFailure.network("隔离测试网络错误") }
        return Page(items: (0..<min(pageSize, 4)).map { book((page - 1) * 4 + $0 + 1) }, page: page, total: 8, hasMore: page < 2)
    }
    func search(query: String, page: Int, pageSize: Int) async throws -> Page<Novel> {
        try await discover(feed: .popular, page: page, pageSize: pageSize)
    }
    func detail(key: ResourceKey) async throws -> NovelDetail {
        try await Task.sleep(nanoseconds: 100_000_000)
        return NovelDetail(novel: book(Int(key.remoteID) ?? 1), favorites: 12, commentCount: 2)
    }
    func volumes(key: ResourceKey, page: Int) async throws -> Page<Volume> {
        Page(items: page == 1 ? [Volume(key: ResourceKey(sourceID, "v1"), novelKey: key, title: "第一卷", chapterCount: 2),
                               Volume(key: ResourceKey(sourceID, "v2"), novelKey: key, title: "第二卷", chapterCount: 2)] : [], page: page, total: 2)
    }
    func chapters(novel: ResourceKey, volume: ResourceKey, page: Int) async throws -> Page<Chapter> {
        let start = volume.remoteID == "v2" ? 3 : 1
        return Page(items: page == 1 ? (start..<(start + 2)).map {
            Chapter(key: ResourceKey(sourceID, String($0)), novelKey: novel, volumeKey: volume,
                    title: "第\($0)章 · 稳定阅读", order: $0, wordCount: 1200)
        } : [], page: page, total: 2)
    }
    func chapter(novel: ResourceKey, chapter: ResourceKey) async throws -> ChapterContent {
        let index = Int(chapter.remoteID) ?? 1
        let volume = ResourceKey(sourceID, index > 2 ? "v2" : "v1")
        let body = (1...50).map { "第\($0)段。清晨的风吹过书页，阅读器保留了安静的留白。这是跨平台阅读测试用的原创文字，用于观察长段落排版、连续翻页、进度恢复与滚动。" }.joined(separator: "\n\n")
        let image = "iVBORw0KGgoAAAANSUhEUgAAAKAAAABaBAMAAADN6EBhAAAAGFBMVEX59ODf6+Ty142AoY91hXlZf2ZBaGctRD7g4T37AAAB6klEQVR42u2Yu23DMBCGT2cRaa0NCE1gwEifIgOwUZ15so0HcJFJgmxgt0EMOIUfOdL8j2ebCIKAqmRL+vT91PEhdQPV3ZgasAH/IbAX+wMRbSoadkRE84rA+ZlaB9gJbBXgPOLeD+wScCvsZJtCXaAjVxcYiEJN4EQEFRWgx4GPlipwf9rZno6wEpiIXCgYbhOwR4pTJIqB+0SQiD0ODEOLXFspyB40sEjqCsC9FDzIjagBsaIcsTeX9/EfMDBQZL1kGAcGinngmNnLEZwRyKi8ndaiCtCDEy5r2ZmADOLnmiwYgGk9+7SLqIpcHhRY7WqhCGT0zAORQZEto5ZHgTP3YVyC8UlOHx0hEBZ6IDIp9rZh2j9qM5ZixLZciiLb5pGFPqlaekW0LYuzah6IAs+K8z5ggMCzhWHizwH5lgZMFRkPCtbAcTfiCoGj0FwhcBSai4L2BVkMHO8JLBS5SmChyHrg5TWr2sPbxABL5hlfnH0j/Fr9DF/5EnwV+w+f8scLVmR9sXrtyvsIrPSy4s6sOoIHRa75NuW0la+5iySKDAMvblPsUeClKMXdGxFR/yR/AcVVT1rgtfxrt7Yo8njfmHCh2I+wcw1Xdr327asBG7ABfxPYTfDQiK96J9NnZ/tV7aE04B8HfgMoAjLF7DzmJAAAAABJRU5ErkJggg=="
        let html = "<p>这是原创测试插图，点击可放大、长按可保存。随后长文用于检查真实翻页与进度。</p><img src=\"data:image/png;base64,\(image)\" alt=\"原创测试插图\">" + body.components(separatedBy: "\n\n").map { "<p>\($0.htmlEscaped)</p>" }.joined()
        return ChapterContent(chapter: Chapter(key: chapter, novelKey: novel, volumeKey: volume, title: "第\(index)章 · 稳定阅读", order: index),
                              novelTitle: book(Int(novel.remoteID) ?? 1).title, volumeTitle: "第一卷", bodyText: body, bodyHTML: html, fontURL: mandatoryFont,
                              previous: index > 1 ? ResourceKey(sourceID, String(index - 1)) : nil,
                              next: index < 4 ? ResourceKey(sourceID, String(index + 1)) : nil)
    }
    func shelf() async throws -> [Novel] { [book(1), book(2)] }
    func setShelf(key: ResourceKey, add: Bool) async throws { if add { saved.insert(key.remoteID) } else { saved.remove(key.remoteID) } }
    func history(page: Int) async throws -> Page<HistoryEntry> {
        Page(items: page == 1 ? [HistoryEntry(novel: book(1), chapterKey: ResourceKey(sourceID, "1"), chapterTitle: "第一章", readAt: "2026-10-27")] : [], page: page, total: 1)
    }
    func deleteHistory(key: ResourceKey) async throws { }
    func saveProgress(_ progress: ReadingProgress) async throws { }
    func rewards() async throws -> RewardStatus { RewardStatus(claimedToday: signed, balance: signed ? 26 : 25, streak: signed ? 2 : 1, days: (1...7).map { RewardDay(day: $0, claimed: $0 == 1, reward: "1") }) }
    func claimReward() async throws -> RewardResult { signed = true; return RewardResult(coins: 1, balance: 26, streak: 2) }
    func comments(key: ResourceKey, latest: Bool, page: Int) async throws -> Page<Comment> {
        let reply = Comment(id: "reply", author: "测试读者乙", content: "回复内容与主评论保持层级。", rootID: "root", replyTo: "测试读者甲")
        let replies = [reply] + (2...4).map { index in Comment(id: "reply\(index)", author: "测试读者\(index)", content: "第\(index)条折叠回复", rootID: "root", replyTo: "测试读者甲") }
        let original = Comment(id: "root", author: "测试读者甲", content: "测试书评正文。表情与回复不会挡住评分。", createdAt: "2026-10-27", likeCount: commentLiked ? 2 : 1, replyCount: replies.count, ratingStars: 4, liked: commentLiked, replies: replies)
        return Page(items: page == 1 ? [original] + userComments : [], page: page, total: 1 + userComments.count)
    }
    func publishComment(key: ResourceKey, draft: CommentDraft) async throws -> Comment {
        let comment = Comment(id: UUID().uuidString, author: "测试账号", content: draft.content, rootID: draft.rootID, media: draft.media)
        userComments.append(comment); return comment
    }
    func rate(key: ResourceKey, stars: Int) async throws { guard (1...5).contains(stars) else { throw SourceFailure.parsing("评分越界") } }
    func likeComment(key: ResourceKey, commentID: String, liked: Bool) async throws -> Bool { commentLiked = !liked; return commentLiked }
    func emojis() async throws -> [CommentEmoji] { [CommentEmoji(code: "[笑]", text: "☺", label: "笑")] }
    func mentions(query: String) async throws -> [MentionCandidate] { [MentionCandidate(id: "fixture", name: "测试读者甲")] }
}
#endif
