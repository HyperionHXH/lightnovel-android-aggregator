import SwiftUI
import PhotosUI
import UniformTypeIdentifiers
import UIKit

@MainActor final class CommentsModel: ObservableObject {
    let key: ResourceKey
    @Published var items: [Comment] = []
    @Published var latest = true
    @Published var page = 0
    @Published var more = false
    @Published var loading = false
    @Published var error: String?
    @Published var emojis: [CommentEmoji] = []
    @Published var emojiLoading = false
    @Published var emojiError: String?
    @Published var candidates: [MentionCandidate] = []
    @Published var mentionLoading = false
    @Published var mentionError: String?
    @Published var media: [CommentMedia] = []
    @Published var uploading = false
    @Published var publishing = false
    @Published var rating = false
    @Published var rated: Int?
    @Published var actionError: String?
    @Published var liking: Set<String> = []
    private var generation = UUID()
    private var mentionGeneration = UUID()
    init(key: ResourceKey) { self.key = key }
    func reset(accountChanged: Bool = false) {
        generation = UUID(); mentionGeneration = UUID(); items.removeAll(); page = 0; more = false; loading = false; error = nil
        if accountChanged { candidates.removeAll(); media.removeAll(); rated = nil; liking.removeAll(); actionError = nil; emojis.removeAll() }
    }
    func cacheCleared() { generation = UUID(); mentionGeneration = UUID(); loading = false; emojis.removeAll(); candidates.removeAll(); emojiLoading = false; mentionLoading = false }
    func load(app: AppStore, append: Bool = false) async {
        if append && (loading || !more) { return }
        let id = UUID(); generation = id
        let scope = sourceScope(app, key.source); let sort = latest
        let revision = app.accountRevision(key.source)
        let cacheGeneration = app.cacheGeneration
        let cacheToken = await app.cache.token(scope: scope)
        guard generation == id, revision == app.accountRevision(key.source), cacheGeneration == app.cacheGeneration, sourceScope(app, key.source) == scope, !Task.isCancelled else { return }
        loading = true; error = nil
        let cacheKey = "comments:\(key.id):\(sort)"
        if items.isEmpty, let data = await app.cache.read(key: cacheKey, scope: scope),
           let cached = try? JSONDecoder().decode(Page<Comment>.self, from: data), generation == id && revision == app.accountRevision(key.source) && cacheGeneration == app.cacheGeneration && sourceScope(app, key.source) == scope {
            items = cached.items
        }
        do {
            let result = try await app.source(key.source).comments(key: key, latest: sort, page: append ? page + 1 : 1)
            try Task.checkCancellation()
            guard generation == id, latest == sort, revision == app.accountRevision(key.source), cacheGeneration == app.cacheGeneration, sourceScope(app, key.source) == scope else { return }
            if append && (result.page < page + 1 || (result.hasMore && result.items.allSatisfy { value in items.contains { $0.id == value.id } })) { throw SourceFailure.parsing("评论分页没有前进，请重试") }
            var ids = Set<String>()
            items = ((append ? items : []) + result.items).filter { ids.insert($0.id).inserted }
            page = result.page; more = result.hasMore
            let cached = Page(items: items, page: page, total: result.total, hasMore: more)
            try await app.cache.write(JSONEncoder().encode(cached), key: cacheKey, scope: scope, ttl: 300, token: cacheToken)
        } catch is CancellationError { } catch { if generation == id && revision == app.accountRevision(key.source) && cacheGeneration == app.cacheGeneration && sourceScope(app, key.source) == scope { self.error = error.localizedDescription } }
        if generation == id { loading = false }
    }
    func loadEmojis(app: AppStore) async {
        guard !emojiLoading else { return }
        let scope = sourceScope(app, key.source)
        let revision = app.accountRevision(key.source)
        let cacheGeneration = app.cacheGeneration
        emojiLoading = true; emojiError = nil
        defer { emojiLoading = false }
        do {
            let result = try await app.source(key.source).emojis()
            try Task.checkCancellation()
            if sourceScope(app, key.source) == scope && revision == app.accountRevision(key.source) && cacheGeneration == app.cacheGeneration { emojis = result }
        } catch is CancellationError { } catch { if sourceScope(app, key.source) == scope && revision == app.accountRevision(key.source) && cacheGeneration == app.cacheGeneration { emojiError = error.localizedDescription } }
    }
    func mentions(_ query: String, app: AppStore) async {
        let id = UUID(); mentionGeneration = id; let scope = sourceScope(app, key.source)
        let revision = app.accountRevision(key.source)
        let cacheGeneration = app.cacheGeneration
        mentionLoading = true; mentionError = nil
        do {
            try await Task.sleep(nanoseconds: 250_000_000)
            try Task.checkCancellation()
            let result = try await app.source(key.source).mentions(query: query)
            guard mentionGeneration == id, revision == app.accountRevision(key.source), cacheGeneration == app.cacheGeneration, sourceScope(app, key.source) == scope else { return }
            candidates = result
        } catch is CancellationError { } catch { if mentionGeneration == id && revision == app.accountRevision(key.source) && sourceScope(app, key.source) == scope { mentionError = error.localizedDescription } }
        if mentionGeneration == id { mentionLoading = false }
    }
    func upload(_ photo: PickedCommentPhoto, app: AppStore) async {
        guard !uploading else { return }
        let scope = sourceScope(app, key.source)
        let revision = app.accountRevision(key.source)
        uploading = true; actionError = nil
        defer { uploading = false }
        do {
            let result = try await app.source(key.source).uploadCommentImage(data: photo.data, fileName: photo.fileName, mimeType: photo.mimeType)
            try Task.checkCancellation()
            if sourceScope(app, key.source) == scope && revision == app.accountRevision(key.source) { media.append(result) }
        } catch is CancellationError { } catch { if sourceScope(app, key.source) == scope && revision == app.accountRevision(key.source) { actionError = error.localizedDescription } }
    }
    func publish(content: String, reply: Comment?, mentions: [MentionCandidate], app: AppStore) async -> Bool {
        guard !publishing, !uploading, !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !media.isEmpty else { return false }
        guard content.count <= 1_000 else { actionError = "评论最多 1,000 字"; return false }
        publishing = true; actionError = nil
        let scope = sourceScope(app, key.source)
        let revision = app.accountRevision(key.source)
        defer { publishing = false }
        do {
            let draft = CommentDraft(content: content, rootID: reply.map { $0.rootID ?? $0.id }, replyID: reply?.id, mentions: mentions.filter { content.contains("@\($0.name)") }.map(\.id), media: media)
            let result = try await app.source(key.source).publishComment(key: key, draft: draft)
            try Task.checkCancellation()
            guard sourceScope(app, key.source) == scope, revision == app.accountRevision(key.source) else { return false }
            if let reply = reply {
                let root = reply.rootID ?? reply.id
                items = items.map { comment in
                    guard comment.id == root else { return comment }
                    var comment = comment; comment.replies.append(result); comment.replyCount += 1; return comment
                }
            } else { items.insert(result, at: 0) }
            media.removeAll()
            return true
        } catch is CancellationError { return false } catch { if sourceScope(app, key.source) == scope && revision == app.accountRevision(key.source) { actionError = error.localizedDescription }; return false }
    }
    func rate(_ stars: Int, app: AppStore) async {
        guard !rating, (1...5).contains(stars) else { return }
        let scope = sourceScope(app, key.source)
        let revision = app.accountRevision(key.source)
        rating = true; actionError = nil
        defer { rating = false }
        do {
            try await app.source(key.source).rate(key: key, stars: stars)
            try Task.checkCancellation()
            guard sourceScope(app, key.source) == scope, revision == app.accountRevision(key.source) else { return }
            rated = stars
        } catch is CancellationError { } catch { if sourceScope(app, key.source) == scope && revision == app.accountRevision(key.source) { actionError = error.localizedDescription } }
    }
    func like(_ comment: Comment, app: AppStore) async {
        guard liking.insert(comment.id).inserted else { return }
        let scope = sourceScope(app, key.source)
        let revision = app.accountRevision(key.source)
        actionError = nil
        defer { liking.remove(comment.id) }
        do {
            let liked = try await app.source(key.source).likeComment(key: key, commentID: comment.id, liked: !comment.liked)
            try Task.checkCancellation()
            guard sourceScope(app, key.source) == scope, revision == app.accountRevision(key.source) else { return }
            items = Self.updating(items, id: comment.id) { current in
                var next = current
                if current.liked != liked { next.likeCount = max(0, current.likeCount + (liked ? 1 : -1)) }
                next.liked = liked
                return next
            }
        } catch is CancellationError { } catch { if sourceScope(app, key.source) == scope && revision == app.accountRevision(key.source) { actionError = error.localizedDescription } }
    }
    private static func updating(_ comments: [Comment], id: String, change: (Comment) -> Comment) -> [Comment] {
        comments.map { original in
            var result = original.id == id ? change(original) : original
            result.replies = updating(result.replies, id: id, change: change)
            return result
        }
    }
}

@MainActor
struct CommentsView: View {
    @EnvironmentObject private var app: AppStore
    let key: ResourceKey
    @StateObject private var model: CommentsModel
    @State private var draft = ""
    @State private var reply: Comment?
    @State private var mentions: [MentionCandidate] = []
    @State private var stars = 0
    @State private var showEmoji = false
    @State private var showMentions = false
    @State private var showPhoto = false
    @State private var photoRevision = 0
    @State private var mentionQuery = ""
    @State private var expandedReplies: Set<String> = []
    @FocusState private var composerFocused: Bool
    init(key: ResourceKey) { self.key = key; _model = StateObject(wrappedValue: CommentsModel(key: key)) }
    private var loggedIn: Bool { app.sessions[key.source]?.loggedIn ?? app.source(key.source).session.loggedIn }
    var body: some View {
        ScrollViewReader { scroller in
        List {
            if !app.source(key.source).supportsComments {
                Section { Label("\(key.source.title)暂不提供评论与评分接口", systemImage: "info.circle").foregroundColor(.secondary) }
            } else {
                Section {
                    Picker("评论排序", selection: $model.latest) { Text("最新").tag(true); Text("热门").tag(false) }.pickerStyle(.segmented).accessibilityIdentifier("comments.sort")
                }
                if loggedIn {
                    ratingSection
                    composer.id("comment-composer")
                } else { Section { NavigationLink("登录\(key.source.title)发表评论、点赞与评分", destination: AccountView(sourceID: key.source)).accessibilityIdentifier("comments.login") } }
                if let error = model.error { Section { InlineSourceError(message: error) { Task { await model.load(app: app) } } } }
                if let error = model.actionError { Section { InlineSourceError(message: error) } }
                ForEach(model.items) { comment in
                    Section {
                        commentRow(comment)
                        if !comment.replies.isEmpty {
                            let replies = flattenedReplies(comment.replies, rootID: comment.id)
                            ForEach(expandedReplies.contains(comment.id) ? replies : Array(replies.prefix(3))) { reply in
                                commentRow(reply).padding(.leading, 12).listRowBackground(Color.secondary.opacity(0.05))
                            }
                            if replies.count > 3 {
                                Button(expandedReplies.contains(comment.id) ? "收起回复" : "查看全部 \(replies.count) 条回复") {
                                    if expandedReplies.contains(comment.id) { expandedReplies.remove(comment.id) } else { expandedReplies.insert(comment.id) }
                                }.accessibilityIdentifier("comments.replies.\(comment.id)")
                            }
                        }
                    }
                }
                if model.loading { Section { ProgressView("正在加载评论…") } }
                else if model.more { Section { Button("加载更多评论") { Task { await model.load(app: app, append: true) } }.accessibilityIdentifier("comments.loadMore") } }
                else if model.page > 0 && model.items.isEmpty && model.error == nil { Section { Text("还没有评论，分享你的阅读感受吧").foregroundColor(.secondary) } }
            }
        }.listStyle(.insetGrouped).navigationTitle("评论与评分").navigationBarTitleDisplayMode(.inline)
            .task {
                guard app.source(key.source).supportsComments else { return }
                await withTaskGroup(of: Void.self) { group in
                    group.addTask { @MainActor in await model.load(app: app) }
                    group.addTask { @MainActor in await model.loadEmojis(app: app) }
                }
            }
            .onChange(of: reply?.id) { value in if value != nil { withAnimation { scroller.scrollTo("comment-composer", anchor: .top) }; composerFocused = true } }
            .onChange(of: model.latest) { _ in model.reset(); Task { await model.load(app: app) } }
            .onChange(of: app.sessions[key.source]) { _ in model.reset(accountChanged: true); draft = ""; reply = nil; mentions = []; stars = 0; Task { await model.load(app: app); await model.loadEmojis(app: app) } }
            .onChange(of: app.accountRevision(key.source)) { _ in model.reset(accountChanged: true); draft = ""; reply = nil; mentions = []; stars = 0; showPhoto = false; showMentions = false; showEmoji = false; Task { if app.source(key.source).supportsComments { await model.load(app: app) } } }
            .refreshable { if app.source(key.source).supportsComments { await model.load(app: app) } }
            .onChange(of: app.cacheGeneration) { _ in model.cacheCleared() }
            .sheet(isPresented: $showEmoji) { emojiPicker }
            .sheet(isPresented: $showMentions) { mentionPicker }
            .sheet(isPresented: $showPhoto) {
                CommentPhotoPicker { result in
                    guard photoRevision == app.accountRevision(key.source) else { showPhoto = false; return }
                    showPhoto = false
                    switch result {
                    case .success(let photo): if let photo = photo { Task { await model.upload(photo, app: app) } }
                    case .failure(let error): model.actionError = error.localizedDescription
                    }
                }
            }
        }
    }
    private var ratingSection: some View {
        Section(header: Text("作品评分"), footer: Text("评分单独提交，不会自动发布评论。")) {
            HStack {
                ForEach(1...5, id: \.self) { value in
                    Button { stars = value } label: { Image(systemName: stars >= value ? "star.fill" : "star").font(.title2).foregroundColor(.orange) }
                        .buttonStyle(.plain).accessibilityLabel("\(value) 星").accessibilityIdentifier("comments.rating.\(value)")
                }
                Spacer()
                Button { Task { await model.rate(stars, app: app) } } label: { if model.rating { ProgressView() } else { Text("确认评分") } }
                    .disabled(stars == 0 || model.rating).accessibilityIdentifier("comments.rate")
            }
            if let rated = model.rated { Label("已提交 \(rated) 星评分", systemImage: "checkmark.circle").font(.caption).foregroundColor(.green) }
        }
    }
    private var composer: some View {
        Section(header: Text(reply.map { "回复 \($0.author)" } ?? "发表评论")) {
            if reply != nil { Button("取消回复") { reply = nil } }
            TextEditor(text: $draft).frame(minHeight: 100).focused($composerFocused).accessibilityIdentifier("comments.draft")
                .disabled(model.publishing)
                .onChange(of: draft) { value in if value.count > 1_000 { draft = String(value.prefix(1_000)) } }
            HStack {
                Button { showEmoji = true; Task { if model.emojis.isEmpty { await model.loadEmojis(app: app) } } } label: { Image(systemName: "face.smiling") }.accessibilityLabel("添加表情").accessibilityIdentifier("comments.emojis")
                Button { showMentions = true } label: { Image(systemName: "at") }.accessibilityLabel("提及用户").accessibilityIdentifier("comments.mention")
                Button { photoRevision = app.accountRevision(key.source); showPhoto = true } label: { if model.uploading { ProgressView() } else { Image(systemName: "photo.badge.plus") } }.disabled(model.uploading).accessibilityLabel("添加评论图片").accessibilityIdentifier("comments.photo")
                Spacer()
                Text("\(draft.count)/1,000").font(.caption).foregroundColor(.secondary)
                Button {
                    Task {
                        if await model.publish(content: draft, reply: reply, mentions: mentions, app: app) { draft = ""; reply = nil; mentions = []; composerFocused = false }
                    }
                } label: { if model.publishing { ProgressView() } else { Label("发布", systemImage: "paperplane") } }
                    .disabled(model.publishing || model.uploading || (draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && model.media.isEmpty)).accessibilityIdentifier("comments.publish")
            }.buttonStyle(.borderless)
            if !mentions.isEmpty {
                ScrollView(.horizontal) { HStack { ForEach(mentions) { candidate in Button("@\(candidate.name) ×") { mentions.removeAll { $0.id == candidate.id }; draft = draft.replacingOccurrences(of: "@\(candidate.name) ", with: "") }.font(.caption) } } }
            }
            if !model.media.isEmpty {
                ScrollView(.horizontal) {
                    HStack { ForEach(model.media) { media in VStack { commentImage(media, size: 80); Button("移除") { model.media.removeAll { $0.id == media.id } }.font(.caption) } } }
                }
            }
        }
    }
    private func commentRow(_ comment: Comment) -> some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack {
                AsyncImage(url: comment.avatarURL) { $0.resizable().scaledToFill() } placeholder: { Image(systemName: "person.crop.circle.fill").resizable().foregroundColor(.secondary) }.frame(width: 30, height: 30).clipShape(Circle())
                Text(comment.author).font(.subheadline.bold())
                Spacer()
                Text(comment.createdAt).font(.caption2).foregroundColor(.secondary)
            }
            if let stars = comment.ratingStars { HStack(spacing: 2) { ForEach(1...5, id: \.self) { value in Image(systemName: value <= stars ? "star.fill" : "star").foregroundColor(.orange) } }.font(.caption) }
            CommentRichTextView(content: comment.content.isEmpty ? "（图片评论）" : comment.content, emojis: model.emojis)
            if !comment.media.isEmpty { ScrollView(.horizontal) { HStack { ForEach(comment.media) { media in commentImage(media, size: 110) } } } }
            HStack(spacing: 18) {
                Button { Task { await model.like(comment, app: app) } } label: {
                    Label("\(comment.likeCount)", systemImage: comment.liked ? "heart.fill" : "heart").foregroundColor(comment.liked ? .red : .secondary)
                }.disabled(!loggedIn || model.liking.contains(comment.id)).accessibilityLabel(comment.liked ? "取消点赞" : "点赞").accessibilityIdentifier("comments.like.\(comment.id)")
                Button { reply = comment; composerFocused = true } label: { Label("回复 \(comment.replyCount)", systemImage: "arrowshape.turn.up.left") }.disabled(!loggedIn).accessibilityIdentifier("comments.reply.\(comment.id)")
            }.font(.subheadline).buttonStyle(.borderless)
        }.padding(.vertical, 5)
    }
    private func commentImage(_ media: CommentMedia, size: CGFloat) -> some View {
        NavigationLink(destination: CommentMediaView(media: media)) {
            AsyncImage(url: media.url) { $0.resizable().scaledToFit() } placeholder: { ZStack { Color.secondary.opacity(0.1); ProgressView() } }.frame(width: size, height: size).clipped().cornerRadius(6)
        }.buttonStyle(.plain).accessibilityLabel("查看评论图片")
    }
    private var emojiPicker: some View {
        NavigationView {
            List {
                if let error = model.emojiError { InlineSourceError(message: error) { Task { await model.loadEmojis(app: app) } } }
                if model.emojiLoading { ProgressView() }
                ForEach(model.emojis) { emoji in
                    Button {
                        draft = String((draft + emoji.code).prefix(1_000)); showEmoji = false; composerFocused = true
                    } label: {
                        HStack {
                            if let url = emoji.imageURL { AsyncImage(url: url) { $0.resizable().scaledToFit() } placeholder: { Color.clear }.frame(width: 30, height: 30) }
                            Text(emoji.label ?? emoji.text ?? emoji.code)
                        }
                    }
                }
                if !model.emojiLoading && model.emojis.isEmpty && model.emojiError == nil { Text("来源暂无可用表情").foregroundColor(.secondary) }
            }.navigationTitle("表情").toolbar { Button("完成") { showEmoji = false } }
        }.navigationViewStyle(.stack)
    }
    private var mentionPicker: some View {
        NavigationView {
            List {
                TextField("搜索关注用户", text: $mentionQuery).accessibilityIdentifier("comments.mention.query")
                if model.mentionLoading { ProgressView() }
                if let error = model.mentionError { InlineSourceError(message: error) { Task { await model.mentions(mentionQuery, app: app) } } }
                ForEach(model.candidates) { candidate in
                    Button {
                        if !mentions.contains(where: { $0.id == candidate.id }) { mentions.append(candidate) }
                        draft = String((draft + "@\(candidate.name) ").prefix(1_000)); showMentions = false; composerFocused = true
                    } label: { Text("@\(candidate.name)") }
                }
                if !model.mentionLoading && model.candidates.isEmpty && model.mentionError == nil { Text("没有可提及的用户").foregroundColor(.secondary) }
            }.navigationTitle("提及用户").toolbar { Button("完成") { showMentions = false } }.task(id: mentionQuery) { await model.mentions(mentionQuery, app: app) }
        }.navigationViewStyle(.stack)
    }
    private func flattenedReplies(_ comments: [Comment], rootID: String) -> [Comment] {
        var seen = Set<String>()
        func visit(_ values: [Comment]) -> [Comment] {
            values.flatMap { original -> [Comment] in
                guard seen.insert(original.id).inserted else { return [] }
                var value = original
                if value.rootID == nil { value.rootID = rootID }
                return [value] + visit(value.replies)
            }
        }
        return visit(comments)
    }
}

@MainActor
struct CommentRichTextView: View {
    let content: String
    let emojis: [CommentEmoji]
    private struct Token: Identifiable { let id: Int; let text: String; let emoji: CommentEmoji? }
    private var tokens: [Token] {
        let codes = emojis.map(\.code).filter { !$0.isEmpty }.sorted { $0.count > $1.count }
        guard !codes.isEmpty, let regex = try? NSRegularExpression(pattern: codes.map { NSRegularExpression.escapedPattern(for: $0) }.joined(separator: "|")) else { return [Token(id: 0, text: content, emoji: nil)] }
        let text = content as NSString
        let matches = regex.matches(in: content, range: NSRange(location: 0, length: text.length))
        var result: [Token] = []; var cursor = 0
        for match in matches {
            if match.range.location > cursor { result.append(Token(id: cursor, text: text.substring(with: NSRange(location: cursor, length: match.range.location - cursor)), emoji: nil)) }
            let code = text.substring(with: match.range)
            result.append(Token(id: match.range.location, text: code, emoji: emojis.first { $0.code == code }))
            cursor = match.range.location + match.range.length
        }
        if cursor < text.length { result.append(Token(id: cursor, text: text.substring(from: cursor), emoji: nil)) }
        return result
    }
    var body: some View {
        if tokens.allSatisfy({ $0.emoji?.imageURL == nil }) {
            Text(tokens.map { $0.emoji?.text ?? $0.text }.joined()).textSelection(.enabled)
        } else {
            CommentTokenFlow(tokens: tokens.map { token in
                CommentTokenFlow.Item(id: token.id, text: token.emoji?.text ?? token.text, url: token.emoji?.imageURL, label: token.emoji?.label)
            })
        }
    }
}

// UIKit text attachments provide true inline wrapping on iOS 15 (SwiftUI Layout requires iOS 16).
@MainActor
private struct CommentTokenFlow: UIViewRepresentable {
    struct Item: Hashable { let id: Int; let text: String; let url: URL?; let label: String? }
    let tokens: [Item]
    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeUIView(context: Context) -> IntrinsicCommentTextView {
        let view = IntrinsicCommentTextView()
        view.isEditable = false; view.isSelectable = true; view.isScrollEnabled = false
        view.backgroundColor = .clear; view.textContainerInset = .zero; view.textContainer.lineFragmentPadding = 0
        view.adjustsFontForContentSizeCategory = true
        view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        return view
    }
    func updateUIView(_ view: IntrinsicCommentTextView, context: Context) { context.coordinator.update(view, tokens: tokens) }
    static func dismantleUIView(_ uiView: IntrinsicCommentTextView, coordinator: Coordinator) { coordinator.task?.cancel() }
    @MainActor final class Coordinator {
        var task: Task<Void, Never>?
        var previous: [Item] = []
        private var images: [URL: UIImage] = [:]
        func update(_ view: IntrinsicCommentTextView, tokens: [Item]) {
            guard previous != tokens else { return }
            previous = tokens; task?.cancel(); render(view, tokens: tokens)
            task = Task { @MainActor [weak self, weak view] in
                guard let self = self else { return }
                for token in tokens {
                    guard let url = token.url, self.images[url] == nil else { continue }
                    do {
                        let data = try await HTTPClient().bytes(url)
                        try Task.checkCancellation()
                        if let image = UIImage(data: data) { self.images[url] = image }
                        if let view = view { self.render(view, tokens: tokens) }
                    } catch is CancellationError { return } catch { continue }
                }
            }
        }
        private func render(_ view: IntrinsicCommentTextView, tokens: [Item]) {
            let result = NSMutableAttributedString(string: "")
            let font = UIFont.preferredFont(forTextStyle: .body)
            for token in tokens {
                if let url = token.url, let image = images[url] {
                    let attachment = NSTextAttachment(); attachment.image = image
                    attachment.bounds = CGRect(x: 0, y: -4, width: font.lineHeight, height: font.lineHeight)
                    result.append(NSAttributedString(attachment: attachment))
                } else { result.append(NSAttributedString(string: token.text, attributes: [.font: font, .foregroundColor: UIColor.label])) }
            }
            view.attributedText = result
            view.accessibilityLabel = tokens.map { $0.label ?? $0.text }.joined()
            view.invalidateIntrinsicContentSize()
        }
    }
}
private final class IntrinsicCommentTextView: UITextView {
    override var intrinsicContentSize: CGSize {
        let width = bounds.width > 0 ? bounds.width : UIScreen.main.bounds.width - 64
        return CGSize(width: UIView.noIntrinsicMetric, height: sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude)).height)
    }
    override func layoutSubviews() { super.layoutSubviews(); invalidateIntrinsicContentSize() }
}

@MainActor
private struct CommentMediaView: View {
    let media: CommentMedia
    @State private var image: UIImage?
    @State private var error: String?
    @State private var scale: CGFloat = 1
    @State private var showShare = false
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let image = image { ScrollView([.horizontal, .vertical]) { Image(uiImage: image).resizable().scaledToFit().frame(width: UIScreen.main.bounds.width * scale).gesture(MagnificationGesture().onChanged { scale = min(5, max(1, $0)) }) } }
            else if let error = error { Text(error).foregroundColor(.white).padding() }
            else { ProgressView().tint(.white) }
        }.navigationTitle("评论图片").navigationBarTitleDisplayMode(.inline)
            .toolbar { Button { showShare = true } label: { Image(systemName: "square.and.arrow.up") }.disabled(image == nil) }
            .sheet(isPresented: $showShare) { if let image = image { ShareSheet(items: [image]) } else { ShareSheet(items: [media.url]) } }
            .task {
                do { let data = try await HTTPClient().bytes(media.url); try Task.checkCancellation(); guard let decoded = UIImage(data: data) else { throw SourceFailure.parsing("图片格式无法显示") }; image = decoded }
                catch is CancellationError { } catch { self.error = error.localizedDescription }
            }
    }
}

struct PickedCommentPhoto { let data: Data; let fileName: String; let mimeType: String }
@MainActor
private struct CommentPhotoPicker: UIViewControllerRepresentable {
    let completion: (Result<PickedCommentPhoto?, Error>) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(completion: completion) }
    func makeUIViewController(context: Context) -> PHPickerViewController {
        var configuration = PHPickerConfiguration(); configuration.filter = .images; configuration.selectionLimit = 1
        let controller = PHPickerViewController(configuration: configuration); controller.delegate = context.coordinator; return controller
    }
    func updateUIViewController(_ uiViewController: PHPickerViewController, context: Context) {}
    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let completion: (Result<PickedCommentPhoto?, Error>) -> Void
        init(completion: @escaping (Result<PickedCommentPhoto?, Error>) -> Void) { self.completion = completion }
        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            guard let provider = results.first?.itemProvider else { completion(.success(nil)); return }
            guard let identifier = provider.registeredTypeIdentifiers.first(where: { UTType($0)?.conforms(to: .image) == true }), let type = UTType(identifier) else {
                completion(.failure(SourceFailure.parsing("所选文件不是可用图片"))); return
            }
            let name = provider.suggestedName ?? "comment-\(UUID().uuidString)"
            let fileName = (name as NSString).pathExtension.isEmpty ? name + "." + (type.preferredFilenameExtension ?? "jpg") : name
            let mimeType = type.preferredMIMEType ?? "application/octet-stream"
            provider.loadDataRepresentation(forTypeIdentifier: identifier) { data, error in
                DispatchQueue.main.async {
                    if let error = error { self.completion(.failure(error)) }
                    else if let data = data, !data.isEmpty { self.completion(.success(PickedCommentPhoto(data: data, fileName: fileName, mimeType: mimeType))) }
                    else { self.completion(.failure(SourceFailure.parsing("无法读取所选图片"))) }
                }
            }
        }
    }
}
