import SwiftUI

enum CommunityKind: String, CaseIterable, Identifiable {
    case following, followers, publishing, messages
    var id: String { rawValue }
    var title: String {
        switch self { case .following: return "关注"; case .followers: return "粉丝"; case .publishing: return "发布管理"; case .messages: return "消息中心" }
    }
}
private enum CommunityMessageCategory: String, CaseIterable, Identifiable {
    case reply, mention, like, fan, system, dm
    var id: String { rawValue }
    var title: String {
        switch self { case .reply: return "回复"; case .mention: return "@我"; case .like: return "点赞"; case .fan: return "新粉丝"; case .system: return "系统"; case .dm: return "私信" }
    }
    var path: String {
        switch self {
        case .reply, .mention: return "api/bff/message-replies-v1"
        case .like: return "api/bff/message-likes-v1"
        case .fan: return "api/bff/message-fans-v1"
        case .system: return "api/bff/message-system-v1"
        case .dm: return "api/bff/dm-conversations-v1"
        }
    }
    func unread(_ summary: JSONObject) -> Int {
        switch self {
        case .reply: return summary.int("reply_count", "replies")
        case .mention: return summary.int("mention_count", "mentions")
        case .like: return summary.int("like_count", "likes")
        case .fan: return summary.int("fan_count", "fans")
        case .system: return summary.int("system_count", "notifications")
        case .dm: return summary.int("dm_count", "dm_unread")
        }
    }
}
private struct CommunityUser: Identifiable, Hashable {
    var id: String
    var name: String
    var avatar: URL?
    var signature: String
    var level: String
    var followed: Bool
    init?(_ item: JSONObject) {
        let nested = item.object("profile", "user")
        let profile = nested.isEmpty ? item : nested
        let id = profile.string("uid", "user_id", "id")
        guard let numeric = Int64(id), numeric > 0 else { return nil }
        self.id = id
        let nickname = profile.string("nickname", "username", "name")
        name = nickname.isEmpty ? "用户 \(id)" : nickname
        avatar = webURL(profile.string("avatar", "avatar_url", "avatarUrl"))
        signature = profile.string("sign", "signature")
        let group = profile.object("level", "user_group", "group", "rank")
        let levelText = profile.string("level_name", "levelName", "level_title", "group_name", "user_group_name", "rank_name", "role_name")
        level = levelText.isEmpty ? group.string("name", "title") : levelText
        var relation = item.object("relation", "interaction_state")
        if relation.isEmpty { relation = profile.object("relation", "interaction_state") }
        followed = profile.bool("followed", "is_followed", "isFollowing", "is_following", "has_followed", "hasFollowed") ?? relation.bool("followed", "is_followed", "isFollowing", "is_following") ?? false
    }
}
private struct CommunityWork: Identifiable {
    var id: String
    var title: String
    var author: String
    var cover: URL?
    var synopsis: String
    var status: String
    var review: String
    var kind: String
    var chapters: Int
    var volumes: Int
    var words: String
    var updated: String
    init?(_ item: JSONObject) {
        id = item.string("book_id", "bookId", "id")
        guard (Int64(id) ?? 0) > 0 else { return nil }
        title = item.string("title"); if title.isEmpty { title = "未命名作品" }
        author = item.string("author_name", "author", "authorName")
        cover = webURL(item.string("cover_url", "coverUrl", "cover"))
        synopsis = item.string("summary", "long_summary", "longSummary").plainHTML
        let meta = item.object("meta_json", "metaJson"), reviewState = item.object("review_state", "reviewState")
        let raw = meta.string("serialize_status", "serial_status")
        let rawStatus = raw.isEmpty ? item.string("serial_status", "serialStatus", "status_text") : raw
        if rawStatus == "已完结" { status = "已完本" }
        else if !rawStatus.isEmpty { status = rawStatus }
        else if meta.bool("is_completed") == true || item.bool("is_completed", "isCompleted") == true { status = "已完本" }
        else if item.int("status") == 2 { status = "已隐藏" }
        else if item.int("status") == 0 { status = "草稿中" }
        else { status = "连载中" }
        let rawReview = reviewState.string("review_status", "reviewStatus")
        let reviewStatus = rawReview.isEmpty ? item.string("review_status", "reviewStatus") : rawReview
        let text = reviewState.string("progress_text", "progressText")
        review = text.isEmpty ? (["pending":"审核中", "approved":"审核通过", "rejected":"审核未通过"][reviewStatus] ?? reviewStatus) : text
        kind = meta.string("type"); if kind.isEmpty { kind = item.string("type") }
        chapters = item.int("chapter_count", "chapterCount"); volumes = item.int("volume_count", "volumeCount")
        words = item.string("word_count", "wordCount"); updated = item.string("updated_at", "updatedAt")
    }
}
private struct CommunityNotification: Identifiable {
    var id: String
    var title: String
    var sender: String
    var avatar: URL?
    var content: String
    var quote: String
    var related: String
    var created: String
    var unread: Bool
    var book: String?
    var chapter: String?
    init?(_ item: JSONObject, category: CommunityMessageCategory) {
        id = item.string("message_id", "id"); guard !id.isEmpty else { return nil }
        let user = item.object("user", "sender")
        sender = item.string("source_name"); if sender.isEmpty { sender = user.string("nickname", "username", "name") }
        let rawAvatar = item.string("source_avatar")
        avatar = webURL(rawAvatar.isEmpty ? user.string("avatar", "avatar_url", "avatarUrl") : rawAvatar)
        title = item.string("title", "category_text"); if title.isEmpty { title = category.title }
        content = item.string("content", "content_text", "message").plainHTML; if content.isEmpty { content = title }
        quote = item.string("quote_text").plainHTML; related = item.string("related_title"); created = item.string("created_at", "time")
        unread = item.bool("unread") ?? false
        let bookID = item.string("target_book_id"), chapterID = item.string("target_chapter_id")
        book = (Int64(bookID) ?? 0) > 0 ? bookID : nil
        chapter = (Int64(chapterID) ?? 0) > 0 ? chapterID : nil
    }
}
private struct CommunityConversation: Identifiable {
    var id: String
    var peer: CommunityUser
    var peerID: String
    var last: String
    var unread: Int
    var date: String
    var denyReason: String
    init?(_ item: JSONObject) {
        guard let user = CommunityUser(item.object("user", "peer", "peer_user", "user_info", "sender")) else { return nil }
        peer = user
        let rawID = item.string("peer_uid")
        peerID = (Int64(rawID) ?? 0) > 0 ? rawID : user.id
        id = item.string("conversation_id", "thread_id", "id"); if id.isEmpty { id = "peer-" + peerID }
        let nested = item.object("last_message", "latest_message", "lastMessage", "last_message_info")
        last = item.string("last_message", "last_message_text", "lastMessage", "summary", "content", "content_text")
        if last.isEmpty { last = nested.string("content", "content_text", "body", "text", "message") }
        last = last.plainHTML
        unread = item.int("unread_count", "unreadCount", "unread")
        date = item.string("updated_at", "updatedAt", "last_message_at", "time")
        if date.isEmpty { date = nested.string("created_at", "createdAt", "sent_at", "time") }
        denyReason = item.string("deny_reason", "denyReason", "dm_disabled_reason")
    }
}
private struct CommunityDirectMessage: Identifiable {
    var id: String
    var sender: String
    var content: String
    var created: String
    var mine: Bool
    init?(_ item: JSONObject, peer: CommunityUser) {
        id = item.string("message_id", "id")
        content = item.string("content", "content_text", "body", "text").plainHTML
        guard !id.isEmpty, !content.isEmpty else { return nil }
        sender = item.object("sender", "user", "author", "peer").string("nickname", "username", "name"); if sender.isEmpty { sender = peer.name }
        created = item.string("created_at", "createdAt", "sent_at", "time")
        mine = item.bool("mine", "is_mine", "from_me") ?? false
    }
}
private enum CommunityParsing {
    static func objects(_ source: JSONObject) throws -> [JSONObject] {
        let keys = ["list", "cards", "items", "books", "messages", "conversations"]
        let nested = source.object("data", "d")
        for object in [source, nested] {
            for key in keys {
                if let value = object[key], value != .null {
                    guard case .array(let list) = value else { throw SourceFailure.parsing("社区列表 \(key) 不是数组") }
                    return try list.map { value in
                        guard case .object(let item) = value else { throw SourceFailure.parsing("社区列表包含无效项目") }
                        return item
                    }
                }
            }
        }
        for key in ["data", "d"] {
            if case .array(let list)? = source[key] {
                return try list.map { value in
                    guard case .object(let item) = value else { throw SourceFailure.parsing("社区列表包含无效项目") }
                    return item
                }
            }
        }
        throw SourceFailure.parsing("社区响应缺少列表，不能确认空状态")
    }
    static func decode<T>(_ objects: [JSONObject], transform: (JSONObject) -> T?) throws -> [T] {
        let decoded = objects.compactMap(transform)
        guard decoded.count == objects.count else { throw SourceFailure.parsing("社区列表缺少必要编号或内容，请重试或反馈来源格式变化") }
        return decoded
    }
    static func unread(_ result: JSONObject) throws -> JSONObject {
        let nested = result.object("summary", "unread")
        let summary = nested.isEmpty ? result : nested
        let keys = ["unread_count", "unreadCount", "total_unread", "reply_count", "replies", "mention_count", "mentions", "like_count", "likes", "system_count", "notifications", "dm_count", "dm_unread", "fan_count", "fans"]
        guard keys.contains(where: { summary[$0]?.int != nil }) else { throw SourceFailure.parsing("来源未返回消息未读统计") }
        return summary
    }
    static func page(_ object: JSONObject, requested: Int, count: Int) -> (total: Int, more: Bool) {
        var info = object.object("pagination", "page_info", "pageInfo")
        if info.isEmpty { info = object.object("data", "d").object("pagination", "page_info", "pageInfo") }
        let total = max(0, info.optionalInt("total", "count") ?? object.optionalInt("total") ?? count)
        let size = max(1, info.optionalInt("page_size", "pageSize", "size") ?? 20)
        return (total, info.bool("has_more", "hasMore", "has_next") ?? (info.int("next") > 0 || requested * size < total))
    }
    static func unique<T: Identifiable>(_ values: [T]) -> [T] where T.ID: Hashable {
        var seen = Set<T.ID>(); return values.filter { seen.insert($0.id).inserted }
    }
}

@MainActor private final class CommunityModel: ObservableObject {
    @Published var users: [CommunityUser] = []
    @Published var works: [CommunityWork] = []
    @Published var messages: [CommunityNotification] = []
    @Published var conversations: [CommunityConversation] = []
    @Published var summary: JSONObject = [:]
    @Published var loading = false
    @Published var marking = false
    @Published var pending = Set<String>()
    @Published var error: String?
    @Published var summaryError: String?
    @Published var total = 0
    @Published var more = false
    private var page = 0
    private var generation = UUID()
    private var context = ""

    func invalidate() {
        generation = UUID(); context = ""; users = []; works = []; messages = []; conversations = []; summary = [:]
        loading = false; marking = false; pending = []; page = 0; total = 0; more = false; error = nil; summaryError = nil
    }

    func load(app: AppStore, kind: CommunityKind, category: CommunityMessageCategory, append: Bool = false) async {
        let session = app.sessions[.kingdom] ?? app.source(.kingdom).session
        let revision = app.accountRevision(.kingdom)
        let key = "\(kind.rawValue):\(category.rawValue):\(session.accountID ?? ""): \(session.loggedIn):\(revision)"
        if context != key { invalidate(); context = key }
        guard !loading, !append || more else { return }
        guard session.loggedIn else { error = "请先登录轻之国度账号"; return }
        guard let source = app.source(.kingdom) as? KingdomSource else { error = "当前来源不支持社区请求"; return }
        let token = generation, requested = append ? page + 1 : 1
        loading = true; error = nil
        defer { if generation == token { loading = false } }
        do {
            let path: String
            var body: JSONObject
            switch kind {
            case .following, .followers:
                let uid = session.accountID ?? app.profiles[.kingdom]?.accountID ?? ""
                guard let numeric = Int64(uid), numeric > 0 else { throw SourceFailure.parsing("登录响应缺少用户编号，请刷新来源资料") }
                path = kind == .following ? "api/bff/user-following-v1" : "api/bff/user-followers-v1"
                body = ["uid": j(numeric), "page": j(requested), "pageSize": 20]
            case .publishing:
                path = "api/bff/longform-book-list-v1"; body = ["page": j(requested), "pageSize": 20]
            case .messages:
                path = category.path
                body = ["page": j(category == .dm ? 1 : requested - 1), "page_size": 20]
                if category == .mention { body["filter"] = "mention" }
            }
            let result = try await source.request(path: path, body: body)
            try Task.checkCancellation()
            guard generation == token, app.sessions[.kingdom] == session, app.accountRevision(.kingdom) == revision else { return }
            let objects = try CommunityParsing.objects(result)
            let pagination = CommunityParsing.page(result, requested: requested, count: objects.count)
            switch kind {
            case .following, .followers:
                users = CommunityParsing.unique((append ? users : []) + (try CommunityParsing.decode(objects, transform: CommunityUser.init)))
            case .publishing:
                works = CommunityParsing.unique((append ? works : []) + (try CommunityParsing.decode(objects, transform: CommunityWork.init)))
            case .messages:
                if category == .dm { conversations = CommunityParsing.unique(try CommunityParsing.decode(objects, transform: CommunityConversation.init)) }
                else { messages = CommunityParsing.unique((append ? messages : []) + (try CommunityParsing.decode(objects) { CommunityNotification($0, category: category) })) }
            }
            page = requested; total = pagination.total; more = kind == .messages && category == .dm ? false : pagination.more
        } catch is CancellationError {} catch {
            if generation == token, app.sessions[.kingdom] == session, app.accountRevision(.kingdom) == revision { self.error = error.localizedDescription }
        }
        if kind == .messages, generation == token { await loadSummary(app: app, session: session, revision: revision, token: token) }
    }

    private func loadSummary(app: AppStore, session: AccountSession, revision: Int, token: UUID) async {
        guard let source = app.source(.kingdom) as? KingdomSource else { return }
        do {
            let result = try await source.request(path: "api/bff/message-unread-v1")
            try Task.checkCancellation()
            guard generation == token, app.sessions[.kingdom] == session, app.accountRevision(.kingdom) == revision else { return }
            summary = try CommunityParsing.unread(result); summaryError = nil
        } catch is CancellationError {} catch {
            if generation == token, app.sessions[.kingdom] == session, app.accountRevision(.kingdom) == revision { summaryError = error.localizedDescription }
        }
    }

    func follow(_ user: CommunityUser, app: AppStore) async {
        let session = app.sessions[.kingdom], token = generation, revision = app.accountRevision(.kingdom)
        guard session?.loggedIn == true, !pending.contains(user.id), let source = app.source(.kingdom) as? KingdomSource else { return }
        guard let uid = Int64(user.id) else { error = "用户编号无效"; return }
        pending.insert(user.id); error = nil
        defer { if generation == token { pending.remove(user.id) } }
        do {
            _ = try await source.request(path: "api/bff/toggle-user-follow-v1", body: ["uid": j(uid), "act": j(user.followed ? "unfollow" : "follow")])
            try Task.checkCancellation()
            guard generation == token, app.sessions[.kingdom] == session, app.accountRevision(.kingdom) == revision else { return }
            if let index = users.firstIndex(where: { $0.id == user.id }) { users[index].followed = !user.followed }
            await app.refreshProfile(.kingdom)
        } catch is CancellationError {} catch {
            if generation == token, app.sessions[.kingdom] == session, app.accountRevision(.kingdom) == revision { self.error = error.localizedDescription }
        }
    }

    func markRead(_ category: CommunityMessageCategory, app: AppStore) async {
        let session = app.sessions[.kingdom], token = generation, revision = app.accountRevision(.kingdom)
        guard session?.loggedIn == true, !marking, let source = app.source(.kingdom) as? KingdomSource else { return }
        marking = true; error = nil
        defer { if generation == token { marking = false } }
        var body: JSONObject = ["ts": j(Int64(Date().timeIntervalSince1970)), "nonce": j(String(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(16)))]
        if category != .dm { body["scope"] = "category"; body["category"] = j(category.rawValue) }
        do {
            _ = try await source.request(path: category == .dm ? "api/bff/dm-mark-read-v1" : "api/bff/message-mark-read-v1", body: body)
            try Task.checkCancellation()
            guard generation == token, app.sessions[.kingdom] == session, app.accountRevision(.kingdom) == revision else { return }
            messages = messages.map { var value = $0; value.unread = false; return value }
            conversations = conversations.map { var value = $0; value.unread = 0; return value }
            await loadSummary(app: app, session: session!, revision: revision, token: token)
        } catch is CancellationError {} catch {
            if generation == token, app.sessions[.kingdom] == session, app.accountRevision(.kingdom) == revision { self.error = error.localizedDescription }
        }
    }
}
struct CommunityView: View {
    let kind: CommunityKind
    @EnvironmentObject private var app: AppStore
    @StateObject private var model = CommunityModel()
    @State private var selectedKind: CommunityKind
    @State private var category: CommunityMessageCategory = .reply
    @State private var unfollow: CommunityUser?
    init(kind: CommunityKind) { self.kind = kind; _selectedKind = State(initialValue: kind) }
    private var loggedIn: Bool { app.sessions[.kingdom]?.loggedIn == true }
    private var taskID: String { "\(selectedKind.rawValue):\(category.rawValue):\(app.sessions[.kingdom]?.accountID ?? ""):\(loggedIn):\(app.accountRevision(.kingdom))" }
    var body: some View {
        List {
            if !loggedIn {
                Section { Text("社区功能属于轻之国度账号。"); NavigationLink("登录轻之国度", destination: AccountView(sourceID: .kingdom)) }
            } else {
                if kind == .following || kind == .followers {
                    Picker("用户关系", selection: $selectedKind) { Text("关注").tag(CommunityKind.following); Text("粉丝").tag(CommunityKind.followers) }.pickerStyle(.segmented)
                }
                if kind == .messages {
                    Section {
                        Picker("消息分类", selection: $category) {
                            ForEach(CommunityMessageCategory.allCases) { value in Text(value.title + (value.unread(model.summary) > 0 ? " (\(value.unread(model.summary)))" : "")).tag(value) }
                        }.accessibilityIdentifier("messages.category")
                        Button(model.marking ? "正在标记…" : "将本类消息标记为已读") { Task { await model.markRead(category, app: app) } }
                            .disabled(model.marking || model.loading || category.unread(model.summary) <= 0).accessibilityIdentifier("messages.markread")
                        if let error = model.summaryError { Text("未读数：" + error).font(.caption).foregroundColor(.red) }
                    }
                }
                if let error = model.error { Section { InlineSourceError(message: error) { Task { await load() } } } }
                if model.loading && isEmpty { ProgressView("加载\(selectedKind.title)…") }
                if !model.loading && model.error == nil && isEmpty { Text(emptyText).foregroundColor(.secondary) }
                if selectedKind == .following || selectedKind == .followers {
                    Section(header: Text("\(selectedKind.title) · \(model.total)")) {
                        ForEach(model.users) { user in userRow(user) }
                    }
                } else if selectedKind == .publishing {
                    ForEach(model.works) { work in
                        Section {
                            NavigationLink(destination: BookDetailView(key: ResourceKey(.kingdom, work.id))) {
                                HStack(alignment: .top, spacing: 12) {
                                    AsyncImage(url: work.cover) { image in image.resizable().scaledToFill() } placeholder: { Color.secondary.opacity(0.15) }.frame(width: 65, height: 90).clipped()
                                    VStack(alignment: .leading, spacing: 6) {
                                        Text(work.title).font(.headline)
                                        if !work.author.isEmpty { Text(work.author).font(.subheadline) }
                                        Text([work.kind, work.status, work.review].filter { !$0.isEmpty }.joined(separator: " · ")).font(.caption).foregroundColor(.accentColor)
                                        Text("\(work.volumes) 卷 · \(work.chapters) 章" + (work.words.isEmpty ? "" : " · \(work.words) 字")).font(.caption).foregroundColor(.secondary)
                                    }
                                }
                            }
                            if !work.synopsis.isEmpty { Text(work.synopsis).font(.subheadline).lineLimit(4) }
                            if !work.updated.isEmpty { Text("更新时间：" + work.updated).font(.caption).foregroundColor(.secondary) }
                        }
                    }
                } else if category == .dm {
                    ForEach(model.conversations) { conversation in
                        NavigationLink(destination: CommunityThreadView(conversation: conversation)) {
                            HStack(spacing: 12) {
                                avatar(conversation.peer.avatar)
                                VStack(alignment: .leading, spacing: 5) {
                                    HStack { Text(conversation.peer.name).font(.headline); Spacer(); if conversation.unread > 0 { Text("未读 \(conversation.unread)").foregroundColor(.red).font(.caption) } }
                                    Text(conversation.last.isEmpty ? "点击查看会话" : conversation.last).font(.subheadline).lineLimit(2)
                                    if !conversation.date.isEmpty { Text(conversation.date).font(.caption).foregroundColor(.secondary) }
                                }
                            }.padding(.vertical, 4)
                        }
                    }
                } else {
                    ForEach(model.messages) { message in
                        if let book = message.book {
                            NavigationLink(destination: CommunityTargetView(bookID: book, chapterID: message.chapter)) { notificationRow(message) }
                        } else { notificationRow(message) }
                    }
                }
                if model.more { Button(model.loading ? "正在加载…" : "加载更多") { Task { await model.load(app: app, kind: selectedKind, category: category, append: true) } }.disabled(model.loading).accessibilityIdentifier("community.more") }
            }
        }.navigationTitle(kind == .following || kind == .followers ? "关注与粉丝" : kind.title).navigationBarTitleDisplayMode(.inline)
            .task(id: taskID) { await load() }
            .refreshable { await load() }
            .onDisappear { model.invalidate() }
            .alert("取消关注？", isPresented: Binding(get: { unfollow != nil }, set: { if !$0 { unfollow = nil } })) {
                Button("取消关注", role: .destructive) { if let user = unfollow { Task { await model.follow(user, app: app) } }; unfollow = nil }
                Button("返回", role: .cancel) { unfollow = nil }
            } message: { Text("将不再关注 \(unfollow?.name ?? "此用户")。") }
    }
    private var isEmpty: Bool {
        switch selectedKind { case .following, .followers: return model.users.isEmpty; case .publishing: return model.works.isEmpty; case .messages: return category == .dm ? model.conversations.isEmpty : model.messages.isEmpty }
    }
    private var emptyText: String {
        switch selectedKind { case .following: return "还没有关注用户"; case .followers: return "还没有粉丝"; case .publishing: return "暂无发布作品"; case .messages: return category == .dm ? "暂无私信会话" : "暂无\(category.title)消息" }
    }
    private func load() async { await model.load(app: app, kind: selectedKind, category: category) }
    private func avatar(_ url: URL?) -> some View {
        AsyncImage(url: url) { image in image.resizable().scaledToFill() } placeholder: { Image(systemName: "person.crop.circle.fill").resizable().scaledToFit().foregroundColor(.secondary) }.frame(width: 44, height: 44).clipShape(Circle()).accessibilityHidden(true)
    }
    private func userRow(_ user: CommunityUser) -> some View {
        HStack(spacing: 12) {
            avatar(user.avatar)
            VStack(alignment: .leading, spacing: 5) { Text(user.name).font(.headline); if !user.level.isEmpty { Text(user.level).font(.caption).foregroundColor(.accentColor) }; Text(user.signature.isEmpty ? "这个人还没有填写签名" : user.signature).font(.caption).foregroundColor(.secondary).lineLimit(2) }
            Spacer()
            Button(model.pending.contains(user.id) ? "处理中" : user.followed ? "已关注" : "关注") {
                if user.followed { unfollow = user } else { Task { await model.follow(user, app: app) } }
            }.buttonStyle(.bordered).disabled(model.pending.contains(user.id)).accessibilityIdentifier("community.follow.\(user.id)")
        }.padding(.vertical, 5)
    }
    private func notificationRow(_ message: CommunityNotification) -> some View {
        HStack(alignment: .top, spacing: 12) {
            avatar(message.avatar)
            VStack(alignment: .leading, spacing: 6) {
                HStack { Text(message.title).font(.headline); if message.unread { Circle().fill(Color.red).frame(width: 7, height: 7).accessibilityLabel("未读") } }
                if !message.sender.isEmpty { Text(message.sender).font(.caption).foregroundColor(.accentColor) }
                Text(message.content).font(.subheadline)
                if !message.quote.isEmpty { Text("“\(message.quote)”").font(.caption).foregroundColor(.secondary).lineLimit(3) }
                if !message.related.isEmpty { Text(message.related).font(.caption).foregroundColor(.accentColor) }
                if !message.created.isEmpty { Text(message.created).font(.caption2).foregroundColor(.secondary) }
            }
        }.padding(.vertical, 6)
    }
}

private struct CommunityThreadView: View {
    let conversation: CommunityConversation
    @EnvironmentObject private var app: AppStore
    @State private var messages: [CommunityDirectMessage] = []
    @State private var error: String?
    @State private var loading = false
    @State private var token = UUID()
    var body: some View {
        List {
            Section { Text("只读会话：本版本不会发送消息。").font(.caption).foregroundColor(.secondary); if !conversation.denyReason.isEmpty { Text(conversation.denyReason).font(.caption).foregroundColor(.secondary) } }
            if app.sessions[.kingdom]?.loggedIn != true { NavigationLink("登录轻之国度", destination: AccountView(sourceID: .kingdom)) }
            if let error = error { InlineSourceError(message: error) { Task { await load() } } }
            if loading && messages.isEmpty { ProgressView("加载私信…") }
            if !loading && error == nil && messages.isEmpty { Text("暂无私信消息").foregroundColor(.secondary) }
            ForEach(messages) { message in
                HStack {
                    if message.mine { Spacer(minLength: 35) }
                    VStack(alignment: .leading, spacing: 6) { if !message.mine { Text(message.sender).font(.caption).foregroundColor(.accentColor) }; Text(message.content); if !message.created.isEmpty { Text(message.created).font(.caption2).foregroundColor(.secondary) } }
                        .padding(12).background(message.mine ? Color.accentColor.opacity(0.12) : Color.secondary.opacity(0.1)).cornerRadius(12)
                    if !message.mine { Spacer(minLength: 35) }
                }.listRowSeparator(.hidden)
            }
        }.navigationTitle("与 \(conversation.peer.name) 的私信").navigationBarTitleDisplayMode(.inline)
            .task(id: "\(app.accountRevision(.kingdom)):\(app.sessions[.kingdom]?.loggedIn == true)") { messages = []; token = UUID(); await load() }
            .refreshable { await load() }
            .onDisappear { token = UUID() }
    }
    @MainActor private func load() async {
        let session = app.sessions[.kingdom], generation = token, revision = app.accountRevision(.kingdom)
        guard session?.loggedIn == true, let source = app.source(.kingdom) as? KingdomSource else { return }
        loading = true; error = nil
        defer { if token == generation { loading = false } }
        do {
            guard let peerID = Int64(conversation.peerID) else { throw SourceFailure.parsing("私信用户编号无效") }
            let result = try await source.request(path: "api/bff/dm-messages-v1", body: ["peer_uid": j(peerID), "page": 1, "page_size": 20])
            try Task.checkCancellation()
            guard token == generation, app.sessions[.kingdom] == session, app.accountRevision(.kingdom) == revision else { return }
            let objects = try CommunityParsing.objects(result)
            messages = CommunityParsing.unique(try CommunityParsing.decode(objects) { CommunityDirectMessage($0, peer: conversation.peer) })
        } catch is CancellationError {} catch { if token == generation, app.sessions[.kingdom] == session, app.accountRevision(.kingdom) == revision { self.error = error.localizedDescription } }
    }
}

/// Fetch book metadata before entering the reader; message payloads do not contain a Novel.
private struct CommunityTargetView: View {
    let bookID: String
    let chapterID: String?
    @EnvironmentObject private var app: AppStore
    @State private var novel: Novel?
    @State private var error: String?
    var body: some View {
        Group {
            if let chapter = chapterID {
                if let novel = novel { ReaderView(novel: novel, chapterKey: ResourceKey(.kingdom, chapter)) }
                else { VStack(spacing: 18) { if let error = error { Text(error).foregroundColor(.red); Button("重试") { Task { await load() } } } else { ProgressView("读取目标书籍…") } }.padding() }
            } else { BookDetailView(key: ResourceKey(.kingdom, bookID)) }
        }.task(id: "\(app.accountRevision(.kingdom)):\(app.sessions[.kingdom]?.loggedIn == true)") { if chapterID != nil { novel = nil; await load() } }
    }
    @MainActor private func load() async {
        let session = app.sessions[.kingdom], revision = app.accountRevision(.kingdom)
        error = nil
        do {
            let detail = try await app.source(.kingdom).detail(key: ResourceKey(.kingdom, bookID))
            try Task.checkCancellation()
            guard app.sessions[.kingdom] == session, app.accountRevision(.kingdom) == revision else { return }
            novel = detail.novel
        } catch is CancellationError {} catch { if app.sessions[.kingdom] == session, app.accountRevision(.kingdom) == revision { self.error = error.localizedDescription } }
    }
}
