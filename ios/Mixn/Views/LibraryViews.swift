import SwiftUI
import UIKit

// All source requests retain their own state. A failed source never removes another source's cards.
@MainActor final class NovelListModel: ObservableObject {
    struct State {
        var items: [Novel] = []
        var page = 0
        var total = 0
        var hasMore = false
        var loading = false
        var loaded = false
        var error: String?
        var pending: Set<String> = []
        var metadataError: String?
    }
    private struct Metadata {
        var authors: [String]
        var synopsis: String
        var expires: Date
        var used: Date
        var weight: Int { authors.reduce(synopsis.count) { $0 + $1.count } }
    }
    private struct MetadataJob { var novel: Novel; var source: any NovelSource; var scope: String; var revision: Int; var cacheGeneration: Int }
    @Published private(set) var states: [String: State] = [:]
    private var requests: [String: Task<Void, Never>] = [:]
    private var generations: [String: UUID] = [:]
    private var metadata: [String: Metadata] = [:]
    private var metadataQueue: [MetadataJob] = []
    private var metadataTasks: [String: Task<Void, Never>] = [:]
    private var scopes: [SourceID: String] = [:]
    private var metadataGeneration = UUID()
    private var metadataTaskGenerations: [String: UUID] = [:]
    private weak var app: AppStore?

    static func channel(_ source: SourceID, feed: Feed) -> String { source.rawValue + ":" + feed.rawValue }
    func state(_ channel: String) -> State { states[channel] ?? State() }
    func load(channel: String, source: any NovelSource, feed: Feed? = nil, query: String? = nil, append: Bool = false, scope: String, app: AppStore) async {
        self.app = app
        let revision = app.accountRevision(source.sourceID)
        let cacheGeneration = app.cacheGeneration
        if append && (state(channel).loading || !state(channel).hasMore) { return }
        requests[channel]?.cancel()
        let generation = UUID()
        generations[channel] = generation
        scopes[source.sourceID] = scope
        var current = state(channel)
        current.loading = true
        current.error = nil
        current.metadataError = nil
        states[channel] = current
        let page = append ? current.page + 1 : 1
        let task = Task { @MainActor [weak self] in
            guard let self = self else { return }
            do {
                let result: Page<Novel>
                if let query = query { result = try await source.search(query: query, page: page, pageSize: 20) }
                else { result = try await source.discover(feed: feed ?? .popular, page: page, pageSize: 20) }
                try Task.checkCancellation()
                guard self.generations[channel] == generation, self.isCurrent(source.sourceID, scope: scope, revision: revision, cacheGeneration: cacheGeneration) else { return }
                if append && (result.page < page || (result.hasMore && result.items.allSatisfy { item in self.state(channel).items.contains { $0.id == item.id } })) { throw SourceFailure.parsing("来源分页没有前进，请重试") }
                var next = self.state(channel)
                let incoming = result.items.map { self.cached($0, scope: scope) }
                next.items = uniqueNovels((append ? next.items : []) + incoming)
                next.page = result.page
                next.total = result.total
                next.hasMore = result.hasMore
                next.loaded = true
                next.loading = false
                next.pending = Set(next.items.filter { $0.authors.isEmpty && !self.hasMetadata($0.key, scope: scope) }.map(\.id))
                self.states[channel] = next
                for novel in next.items where (novel.authors.isEmpty || novel.synopsis.isEmpty) && !self.hasMetadata(novel.key, scope: scope) {
                    self.queueMetadata(novel, source: source, scope: scope)
                }
                self.pumpMetadata()
            } catch is CancellationError {
                if self.generations[channel] == generation { self.states[channel]?.loading = false }
            } catch {
                guard self.generations[channel] == generation, self.isCurrent(source.sourceID, scope: scope, revision: revision, cacheGeneration: cacheGeneration) else { return }
                self.states[channel]?.loading = false
                self.states[channel]?.error = error.localizedDescription
            }
        }
        requests[channel] = task
        await withTaskCancellationHandler(operation: { await task.value }, onCancel: { task.cancel() })
    }
    func invalidate(source: SourceID? = nil) {
        metadataGeneration = UUID()
        let channels = states.keys.filter { source == nil || $0.hasPrefix(source!.rawValue) }
        for channel in channels { requests[channel]?.cancel(); requests[channel] = nil; generations[channel] = nil; states[channel] = nil }
        for task in metadataTasks.values { task.cancel() }
        metadataQueue.removeAll()
        if let source = source { scopes[source] = nil; metadata = metadata.filter { !$0.key.contains(source.rawValue) } }
        else { scopes.removeAll(); metadata.removeAll() }
        for channel in Array(states.keys) { states[channel]?.pending.removeAll() }
    }
    func stop() {
        for task in requests.values { task.cancel() }
        for task in metadataTasks.values { task.cancel() }
        metadataGeneration = UUID()
        metadataQueue.removeAll()
    }
    func cacheCleared() {
        stop()
        metadata.removeAll()
        generations.removeAll()
        for channel in Array(states.keys) { states[channel]?.loading = false; states[channel]?.pending.removeAll(); states[channel]?.metadataError = nil }
    }
    func resetResults() {
        stop(); generations.removeAll(); states.removeAll()
    }
    func resumeMetadata(channel: String, source: any NovelSource, scope: String, app: AppStore) {
        self.app = app
        scopes[source.sourceID] = scope
        var current = state(channel)
        current.items = current.items.map { cached($0, scope: scope) }
        current.pending = Set(current.items.filter { $0.authors.isEmpty && !hasMetadata($0.key, scope: scope) }.map(\.id))
        states[channel] = current
        for novel in current.items where (novel.authors.isEmpty || novel.synopsis.isEmpty) && !hasMetadata(novel.key, scope: scope) { queueMetadata(novel, source: source, scope: scope) }
        pumpMetadata()
    }
    func replace(channel: String, items: [Novel], source: any NovelSource, app: AppStore) {
        self.app = app
        let scope = sourceScope(app, source.sourceID)
        scopes[source.sourceID] = scope
        var current = state(channel)
        current.items = items.map { cached($0, scope: scope) }
        current.loaded = true; current.loading = false; current.metadataError = nil
        current.pending = Set(current.items.filter { $0.authors.isEmpty && !hasMetadata($0.key, scope: scope) }.map(\.id))
        states[channel] = current
        for novel in current.items where (novel.authors.isEmpty || novel.synopsis.isEmpty) && !hasMetadata(novel.key, scope: scope) { queueMetadata(novel, source: source, scope: scope) }
        pumpMetadata()
    }
    private func isCurrent(_ source: SourceID, scope: String, revision: Int, cacheGeneration: Int) -> Bool {
        guard let app = app else { return false }
        return scopes[source] == scope && sourceScope(app, source) == scope && app.accountRevision(source) == revision && app.cacheGeneration == cacheGeneration
    }
    private func cacheKey(_ key: ResourceKey, scope: String) -> String { scope + ":" + key.id }
    private func hasMetadata(_ key: ResourceKey, scope: String) -> Bool { metadata[cacheKey(key, scope: scope)].map { $0.expires > Date() } ?? false }
    private func cached(_ novel: Novel, scope: String) -> Novel {
        let key = cacheKey(novel.key, scope: scope)
        guard var value = metadata[key], value.expires > Date() else { metadata[key] = nil; return novel }
        value.used = Date(); metadata[key] = value
        var novel = novel
        if novel.authors.isEmpty { novel.authors = value.authors }
        if novel.synopsis.isEmpty { novel.synopsis = value.synopsis }
        return novel
    }
    private func queueMetadata(_ novel: Novel, source: any NovelSource, scope: String) {
        let key = cacheKey(novel.key, scope: scope)
        if metadataTasks[key] != nil && metadataTaskGenerations[key] == metadataGeneration { return }
        guard !metadataQueue.contains(where: { cacheKey($0.novel.key, scope: $0.scope) == key }) else { return }
        guard let app = app else { return }
        metadataQueue.append(MetadataJob(novel: novel, source: source, scope: scope, revision: app.accountRevision(novel.key.source), cacheGeneration: app.cacheGeneration))
    }
    private func pumpMetadata() {
        while metadataTasks.count < 4, !metadataQueue.isEmpty {
            let job = metadataQueue.removeFirst()
            let key = cacheKey(job.novel.key, scope: job.scope)
            if metadataTasks[key] != nil { metadataQueue.append(job); break }
            let metadataGeneration = self.metadataGeneration
            metadataTaskGenerations[key] = metadataGeneration
            metadataTasks[key] = Task { @MainActor [weak self] in
                guard let self = self else { return }
                defer {
                    self.metadataTasks[key] = nil
                    self.metadataTaskGenerations[key] = nil
                    self.pumpMetadata()
                }
                do {
                    let detail = try await Self.timedDetail(job.source, key: job.novel.key)
                    try Task.checkCancellation()
                    guard metadataGeneration == self.metadataGeneration, self.isCurrent(job.novel.key.source, scope: job.scope, revision: job.revision, cacheGeneration: job.cacheGeneration) else { return }
                    guard detail.novel.key == job.novel.key else { throw SourceFailure.parsing("来源返回了其他书籍的信息") }
                    let entry = Metadata(authors: detail.novel.authors, synopsis: detail.novel.synopsis, expires: Date().addingTimeInterval(300), used: Date())
                    if entry.weight <= 256 * 1024 { self.metadata[key] = entry }
                    while self.metadata.count > 128 || self.metadata.values.reduce(0, { $0 + $1.weight }) > 256 * 1024 {
                        guard let oldest = self.metadata.min(by: { $0.value.used < $1.value.used })?.key else { break }
                        self.metadata[oldest] = nil
                    }
                    for channel in Array(self.states.keys) {
                        var state = self.state(channel)
                        state.items = state.items.map { original in
                            guard original.key == job.novel.key else { return original }
                            var novel = original
                            if novel.authors.isEmpty { novel.authors = detail.novel.authors }
                            if novel.synopsis.isEmpty { novel.synopsis = detail.novel.synopsis }
                            return novel
                        }
                        state.pending.remove(job.novel.id)
                        self.states[channel] = state
                    }
                } catch is CancellationError {
                    if metadataGeneration == self.metadataGeneration { for channel in Array(self.states.keys) { self.states[channel]?.pending.remove(job.novel.id) } }
                } catch {
                    guard metadataGeneration == self.metadataGeneration, self.isCurrent(job.novel.key.source, scope: job.scope, revision: job.revision, cacheGeneration: job.cacheGeneration) else { return }
                    for channel in Array(self.states.keys) where self.state(channel).items.contains(where: { $0.key == job.novel.key }) {
                        var state = self.state(channel)
                        state.pending.remove(job.novel.id)
                        state.metadataError = "书籍信息加载失败：" + error.localizedDescription
                        self.states[channel] = state
                    }
                }
            }
        }
    }
    private static func timedDetail(_ source: any NovelSource, key: ResourceKey) async throws -> NovelDetail {
        try await withThrowingTaskGroup(of: NovelDetail.self) { group in
            group.addTask { @MainActor in try await source.detail(key: key) }
            group.addTask { try await Task.sleep(nanoseconds: 8_000_000_000); throw SourceFailure.timeout }
            defer { group.cancelAll() }
            guard let result = try await group.next() else { throw CancellationError() }
            return result
        }
    }
}

func uniqueNovels(_ novels: [Novel]) -> [Novel] {
    var ids = Set<String>()
    return novels.filter { ids.insert($0.id).inserted }
}
@MainActor
func sourceScope(_ app: AppStore, _ source: SourceID) -> String {
    let session = app.sessions[source] ?? app.source(source).session
    return session.loggedIn ? "user:\(source.rawValue):\(session.accountID ?? "unknown")" : "public:\(source.rawValue)"
}
@MainActor
func accountRevisionSignature(_ app: AppStore) -> String {
    SourceID.allCases.map { "\($0.rawValue):\(app.accountRevision($0))" }.joined(separator: "|")
}

struct NovelCoverView: View {
    let novel: Novel
    var width: CGFloat = 66
    var body: some View {
        AsyncImage(url: novel.coverURL) { image in image.resizable().scaledToFill() } placeholder: {
            ZStack { Color.secondary.opacity(0.12); Image(systemName: "book.closed").font(.title2).foregroundColor(.secondary) }
        }
        .frame(width: width, height: width * 1.42).clipped().cornerRadius(7)
        .accessibilityHidden(true)
    }
}

struct NovelCardView: View {
    let novel: Novel
    var metadataLoading = false
    var updated = false
    var body: some View {
        Group {
            if metadataLoading {
                HStack(alignment: .top, spacing: 12) {
                    RoundedRectangle(cornerRadius: 7).fill(Color.secondary.opacity(0.12)).frame(width: 66, height: 94)
                    VStack(alignment: .leading, spacing: 10) {
                        RoundedRectangle(cornerRadius: 4).fill(Color.secondary.opacity(0.12)).frame(height: 22)
                        RoundedRectangle(cornerRadius: 4).fill(Color.secondary.opacity(0.12)).frame(width: 110, height: 14)
                        RoundedRectangle(cornerRadius: 4).fill(Color.secondary.opacity(0.12)).frame(height: 14)
                        RoundedRectangle(cornerRadius: 4).fill(Color.secondary.opacity(0.12)).frame(height: 14)
                    }
                }.accessibilityElement(children: .ignore).accessibilityLabel("正在加载书籍信息")
            } else {
                HStack(alignment: .top, spacing: 12) {
                    NovelCoverView(novel: novel)
                    VStack(alignment: .leading, spacing: 5) {
                        HStack { Text(novel.title).font(.headline).lineLimit(2); if updated { Text("更新").font(.caption).foregroundColor(.orange) } }
                        Text(novel.authors.isEmpty ? "作者未知" : novel.authors.joined(separator: " / ")).font(.caption).foregroundColor(.secondary).frame(minHeight: 18, alignment: .leading)
                        Text(novel.key.source.title).font(.caption2).foregroundColor(.secondary)
                        if let unread = novel.unreadCount, unread > 0 { Text("未读 \(unread) 章").font(.caption).foregroundColor(.orange) }
                        if !novel.synopsis.isEmpty { Text(novel.synopsis).font(.subheadline).foregroundColor(.secondary).lineLimit(2) }
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }.frame(minHeight: 112).padding(.vertical, 5)
    }
}

struct InlineSourceError: View {
    let message: String
    var retry: (() -> Void)? = nil
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(message, systemImage: "exclamationmark.triangle").font(.subheadline).foregroundColor(.red)
            if let retry = retry { Button("重试", action: retry) }
        }.frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 5)
    }
}

@MainActor
struct DiscoverView: View {
    @EnvironmentObject private var app: AppStore
    @StateObject private var model = NovelListModel()
    @State private var selectedSource: SourceID = .kingdom
    @State private var selectedFeeds: [SourceID: Feed] = [:]
    private var feed: Feed { selectedFeeds[selectedSource] ?? app.source(selectedSource).feeds.first ?? .popular }
    private var channel: String { NovelListModel.channel(selectedSource, feed: feed) }
    private var state: NovelListModel.State { model.state(channel) }
    var body: some View {
        List {
            Section {
                HStack {
                    ForEach(SourceID.allCases) { source in
                        Button { selectedSource = source } label: {
                            Text(source.title).fontWeight(selectedSource == source ? .bold : .regular).frame(maxWidth: .infinity)
                        }.buttonStyle(.bordered).tint(selectedSource == source ? .accentColor : .secondary)
                            .accessibilityIdentifier("discover.source.\(source.rawValue)")
                    }
                }
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach(app.source(selectedSource).feeds) { item in
                            Button { selectedFeeds[selectedSource] = item } label: { Text(item.title).padding(.horizontal, 7) }
                                .buttonStyle(.bordered).tint(feed == item ? .accentColor : .secondary)
                                .accessibilityIdentifier("discover.feed.\(item.rawValue)")
                        }
                    }
                }
            }
            Section(header: Text("\(selectedSource.title) · \(feed.title)")) {
                if let error = state.error { InlineSourceError(message: error) { Task { await load() } } }
                if let error = state.metadataError { InlineSourceError(message: error) { Task { await load() } } }
                if let error = app.sourceErrors[selectedSource] { InlineSourceError(message: error); NavigationLink("来源账户", destination: AccountView(sourceID: selectedSource)) }
                if state.loading && state.items.isEmpty { ProgressView("加载榜单…") }
                ForEach(state.items) { novel in
                    if state.pending.contains(novel.id) { NovelCardView(novel: novel, metadataLoading: true) }
                    else { NavigationLink(destination: BookDetailView(key: novel.key)) { NovelCardView(novel: novel) }.accessibilityIdentifier("book.\(novel.id)") }
                }
                if state.loaded && state.items.isEmpty && state.error == nil { Text("此榜单暂时没有作品").foregroundColor(.secondary) }
                if state.hasMore {
                    Button { Task { await load(append: true) } } label: { if state.loading { ProgressView() } else { Text("加载更多") } }
                        .disabled(state.loading).accessibilityIdentifier("discover.loadMore")
                } else if state.loaded && !state.items.isEmpty { Text("已加载全部 \(state.items.count) 本").font(.caption).foregroundColor(.secondary) }
            }
        }
        .listStyle(.insetGrouped).navigationTitle("发现")
        .toolbar { NavigationLink(destination: SearchView()) { Image(systemName: "magnifyingglass") }.accessibilityIdentifier("discover.search") }
        .task(id: channel) {
            if !state.loaded { await load() }
            else { model.resumeMetadata(channel: channel, source: app.source(selectedSource), scope: sourceScope(app, selectedSource), app: app) }
        }
        .onChange(of: app.sessions) { _ in model.invalidate(); Task { await load() } }
        .onChange(of: accountRevisionSignature(app)) { _ in model.invalidate(); Task { await load() } }
        .onChange(of: app.cacheGeneration) { _ in model.cacheCleared() }
        .refreshable { await load() }
        .onDisappear { model.stop() }
    }
    private func load(append: Bool = false) async {
        let source = selectedSource
        let selectedFeed = feed
        await model.load(channel: NovelListModel.channel(source, feed: selectedFeed), source: app.source(source), feed: selectedFeed, append: append, scope: sourceScope(app, source), app: app)
    }
}

@MainActor
struct SearchView: View {
    @EnvironmentObject private var app: AppStore
    @StateObject private var model = NovelListModel()
    @State private var query: String
    @State private var submitted: String
    @State private var selectedSource: SourceID?
    @FocusState private var searchFocused: Bool
    init(initialQuery: String = "") { _query = State(initialValue: initialQuery); _submitted = State(initialValue: initialQuery.trimmingCharacters(in: .whitespacesAndNewlines)) }
    var body: some View {
        List {
            Section {
                HStack {
                    Image(systemName: "magnifyingglass").foregroundColor(.secondary)
                    TextField("书名、作者或标签", text: $query).focused($searchFocused).submitLabel(.search).onSubmit(submit)
                        .accessibilityIdentifier("search.field")
                    if !query.isEmpty { Button { query = "" } label: { Image(systemName: "xmark.circle.fill") }.accessibilityLabel("清除搜索") }
                    Button("搜索", action: submit).accessibilityIdentifier("search.submit")
                }
                Picker("搜索范围", selection: $selectedSource) {
                    Text("全部来源").tag(SourceID?.none)
                    ForEach(SourceID.allCases) { Text($0.title).tag(Optional($0)) }
                }.pickerStyle(.segmented)
            }
            if submitted.isEmpty { Section { Text("输入关键词，搜索各来源的真实作品").foregroundColor(.secondary) } }
            else {
                ForEach(SourceID.allCases.filter { selectedSource == nil || selectedSource == $0 }) { source in
                    results(source)
                }
            }
        }.listStyle(.insetGrouped).navigationTitle("搜索")
            .buttonStyle(.borderless)
            .task(id: submitted) {
                guard !submitted.isEmpty else { return }
                if SourceID.allCases.allSatisfy({ model.state($0.rawValue).loaded }) {
                    for source in SourceID.allCases { model.resumeMetadata(channel: source.rawValue, source: app.source(source), scope: sourceScope(app, source), app: app) }
                } else { await search() }
            }
            .onChange(of: app.sessions) { _ in model.invalidate(); Task { await search() } }
            .onChange(of: accountRevisionSignature(app)) { _ in model.invalidate(); Task { await search() } }
            .onChange(of: app.cacheGeneration) { _ in model.cacheCleared() }
            .refreshable { await search() }.onDisappear { model.stop() }
    }
    @ViewBuilder private func results(_ source: SourceID) -> some View {
        let state = model.state(source.rawValue)
        Section(header: HStack { Text(source.title); Spacer(); if state.total > 0 { Text("\(state.total) 本") } }) {
            if let error = state.error { InlineSourceError(message: error) { Task { await load(source) } } }
            if let error = state.metadataError { InlineSourceError(message: error) { Task { await load(source) } } }
            if state.loading && state.items.isEmpty { ProgressView("正在搜索…") }
            ForEach(state.items) { novel in
                if state.pending.contains(novel.id) { NovelCardView(novel: novel, metadataLoading: true) }
                else { NavigationLink(destination: BookDetailView(key: novel.key)) { NovelCardView(novel: novel) }.accessibilityIdentifier("book.\(novel.id)") }
            }
            if state.loaded && state.items.isEmpty && state.error == nil { Text("没有找到相关作品").foregroundColor(.secondary) }
            if state.hasMore { Button { Task { await load(source, append: true) } } label: { if state.loading { ProgressView() } else { Text("加载更多") } }.disabled(state.loading) }
        }
    }
    private func submit() {
        searchFocused = false
        let next = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if next == submitted { Task { await search() } }
        else { model.resetResults(); submitted = next }
    }
    private func search() async {
        guard !submitted.isEmpty else { return }
        await withTaskGroup(of: Void.self) { group in
            for source in SourceID.allCases { group.addTask { @MainActor in await load(source) } }
        }
    }
    private func load(_ source: SourceID, append: Bool = false) async {
        await model.load(channel: source.rawValue, source: app.source(source), query: submitted, append: append, scope: sourceScope(app, source), app: app)
    }
}

@MainActor final class ShelfModel: ObservableObject {
    @Published var items: [SourceID: [Novel]] = [:]
    @Published var loading: Set<SourceID> = []
    @Published var errors: [SourceID: String] = [:]
    private var generations: [SourceID: UUID] = [:]
    func load(_ source: SourceID, app: AppStore) async {
        let generation = UUID(); generations[source] = generation
        let scope = sourceScope(app, source)
        let revision = app.accountRevision(source)
        guard app.source(source).session.loggedIn else { items[source] = []; errors[source] = nil; loading.remove(source); return }
        loading.insert(source); errors[source] = nil
        do {
            let result = try await app.source(source).shelf()
            try Task.checkCancellation()
            guard generations[source] == generation, revision == app.accountRevision(source), sourceScope(app, source) == scope else { return }
            items[source] = uniqueNovels(result)
            try app.library.observeShelf(result)
        } catch is CancellationError { } catch {
            if generations[source] == generation, revision == app.accountRevision(source), sourceScope(app, source) == scope { errors[source] = error.localizedDescription }
        }
        if generations[source] == generation { loading.remove(source) }
    }
    func reset() { generations.removeAll(); items.removeAll(); loading.removeAll(); errors.removeAll() }
    func remove(_ novel: Novel, app: AppStore) async {
        let scope = sourceScope(app, novel.key.source)
        let revision = app.accountRevision(novel.key.source)
        do {
            try await app.source(novel.key.source).setShelf(key: novel.key, add: false)
            guard revision == app.accountRevision(novel.key.source), sourceScope(app, novel.key.source) == scope else { return }
            items[novel.key.source]?.removeAll { $0.id == novel.id }
        } catch { if revision == app.accountRevision(novel.key.source), sourceScope(app, novel.key.source) == scope { errors[novel.key.source] = error.localizedDescription } }
    }
}

@MainActor
struct ShelfView: View {
    @EnvironmentObject private var app: AppStore
    var downloadedOnly: Bool = false
    var body: some View { ShelfContentView(library: app.library, downloadedOnly: downloadedOnly) }
}

@MainActor
private struct ShelfContentView: View {
    @EnvironmentObject private var app: AppStore
    @ObservedObject var library: LibraryStore
    let downloadedOnly: Bool
    @StateObject private var model = ShelfModel()
    @StateObject private var cards = NovelListModel()
    @State private var downloadTab = false
    @State private var removeTarget: Novel?
    @State private var message: String?
    var body: some View {
        List {
            if !downloadedOnly { Picker("书架内容", selection: $downloadTab) { Text("在线书架").tag(false); Text("离线下载").tag(true) }.pickerStyle(.segmented) }
            if downloadedOnly || downloadTab {
                Toggle("仅 Wi-Fi 下载", isOn: Binding(get: { library.wifiOnly }, set: { value in do { try library.setWifiOnly(value) } catch { message = error.localizedDescription } })).accessibilityIdentifier("download.wifiOnly")
                if let error = library.storageError { InlineSourceError(message: error) }
            }
            if downloadedOnly || downloadTab {
                if library.records.isEmpty { Text("暂无离线书籍。可在书籍详情下载全书或指定分卷。").foregroundColor(.secondary) }
                ForEach(library.records) { record in
                    Section {
                        NavigationLink(destination: BookDetailView(key: record.novel.key)) { NovelCardView(novel: record.novel, updated: library.updated.contains(record.novel.id)) }
                            .accessibilityIdentifier("book.\(record.novel.id)")
                        OfflineControlsView(library: library, key: record.novel.key)
                    }
                }
            } else {
                ForEach(SourceID.allCases) { source in
                    Section(header: Text(source.title)) {
                        if !(app.sessions[source]?.loggedIn ?? app.source(source).session.loggedIn) {
                            NavigationLink("登录\(source.title)查看书架", destination: AccountView(sourceID: source)).accessibilityIdentifier("shelf.login.\(source.rawValue)")
                        } else {
                            if model.loading.contains(source) { ProgressView("同步书架…") }
                            if let error = model.errors[source] { InlineSourceError(message: error) { Task { await model.load(source, app: app) } } }
                            if let error = cards.state(source.rawValue).metadataError { InlineSourceError(message: error) { cards.replace(channel: source.rawValue, items: model.items[source] ?? [], source: app.source(source), app: app) } }
                            ForEach(cards.state(source.rawValue).items) { novel in
                                if cards.state(source.rawValue).pending.contains(novel.id) { NovelCardView(novel: novel, metadataLoading: true) }
                                else {
                                    NavigationLink(destination: BookDetailView(key: novel.key)) { NovelCardView(novel: novel, updated: library.updated.contains(novel.id)) }
                                        .accessibilityIdentifier("book.\(novel.id)")
                                        .swipeActions { Button(role: .destructive) { removeTarget = novel } label: { Label("移出书架", systemImage: "bookmark.slash") } }
                                }
                            }
                            if !model.loading.contains(source) && model.errors[source] == nil && (model.items[source] ?? []).isEmpty { Text("书架暂无作品").foregroundColor(.secondary) }
                        }
                    }
                }
            }
            if let message = message { InlineSourceError(message: message) }
        }.listStyle(.insetGrouped).navigationTitle(downloadedOnly ? "离线下载" : "书架")
            .toolbar { if !downloadedOnly { Button("标记更新已读") { do { try library.markAllUpdatesSeen() } catch { message = error.localizedDescription } } } }
            .task {
                if !downloadedOnly {
                    for source in SourceID.allCases where model.items[source] != nil { cards.replace(channel: source.rawValue, items: model.items[source] ?? [], source: app.source(source), app: app) }
                    await refresh()
                }
            }
            .onChange(of: app.sessions) { _ in model.reset(); cards.invalidate(); Task { await refresh() } }
            .onChange(of: accountRevisionSignature(app)) { _ in model.reset(); cards.invalidate(); removeTarget = nil; Task { await refresh() } }
            .onChange(of: model.items) { values in for source in SourceID.allCases { cards.replace(channel: source.rawValue, items: values[source] ?? [], source: app.source(source), app: app) } }
            .onChange(of: app.cacheGeneration) { _ in cards.cacheCleared() }
            .onDisappear { cards.stop() }
            .refreshable { await refresh() }
            .confirmationDialog("移出书架？离线文件和本地进度会保留。", isPresented: Binding(get: { removeTarget != nil }, set: { if !$0 { removeTarget = nil } }), titleVisibility: .visible) {
                if let novel = removeTarget { Button("移出书架", role: .destructive) { Task { await model.remove(novel, app: app) }; removeTarget = nil } }
                Button("取消", role: .cancel) { removeTarget = nil }
            }
    }
    private func refresh() async {
        guard !downloadedOnly else { return }
        await withTaskGroup(of: Void.self) { group in
            for source in SourceID.allCases { group.addTask { @MainActor in await model.load(source, app: app) } }
        }
    }
}

@MainActor
struct OfflineControlsView: View {
    @EnvironmentObject private var app: AppStore
    @ObservedObject var library: LibraryStore
    let key: ResourceKey
    @State private var busy = false
    @State private var error: String?
    @State private var share: LibraryShareItem?
    @State private var deleteConfirmation = false
    @State private var exportConfirmation = false
    private var record: OfflineRecord? { library.records.first { $0.novel.key == key } }
    var body: some View {
        if let record = record {
            VStack(alignment: .leading, spacing: 8) {
                HStack { Label(status(record.status), systemImage: record.status == "complete" ? "checkmark.circle" : "arrow.down.circle"); Spacer(); Text("\(record.completed)/\(record.chapters.count) 章").monospacedDigit() }.font(.caption)
                if record.status == "downloading" || record.status == "queued" { ProgressView(value: Double(record.completed), total: Double(max(record.chapters.count, 1))) }
                if record.status == "queued" || record.status == "downloading" {
                    Text(library.wifiOnly ? "仅 Wi-Fi 下载：网络不可用时等待连接" : "允许使用移动网络下载").font(.caption).foregroundColor(.secondary)
                    Button("暂停下载（保留已下载章节）") { do { try library.cancel(key) } catch { self.error = error.localizedDescription } }.accessibilityIdentifier("download.cancel")
                }
                Text(ByteCountFormatter.string(fromByteCount: record.byteCount, countStyle: .file)).font(.caption).foregroundColor(.secondary)
                if let failure = record.error { InlineSourceError(message: failure) }
                if let error = error { InlineSourceError(message: error) }
                HStack {
                    if record.status == "failed" { Button("重试") { Task { do { try await library.retry(key, source: app.source(key.source), fonts: app.fonts) } catch is CancellationError { } catch { self.error = error.localizedDescription } } }.accessibilityIdentifier("download.retry") }
                    Button("导出 / 分享") { exportConfirmation = true }.accessibilityIdentifier("download.export")
                    Spacer()
                    Button(role: .destructive) { deleteConfirmation = true } label: { Label("删除离线", systemImage: "trash") }.accessibilityIdentifier("download.delete")
                }.disabled(busy)
                if busy { ProgressView("正在处理…") }
            }
            .buttonStyle(.borderless)
            .sheet(item: $share) { item in ShareSheet(items: [item.url]) }
            .confirmationDialog("选择导出格式", isPresented: $exportConfirmation, titleVisibility: .visible) {
                Button("导出 TXT") { export(epub: false) }
                Button("导出 EPUB（含字体）") { export(epub: true) }
                Button("取消", role: .cancel) {}
            }
            .confirmationDialog("删除离线文件并取消该书下载？本地阅读进度和在线书架不受影响。", isPresented: $deleteConfirmation, titleVisibility: .visible) {
                Button("删除离线文件", role: .destructive) { do { try library.delete(key) } catch { self.error = error.localizedDescription } }
                Button("取消", role: .cancel) {}
            }
        }
    }
    private func status(_ value: String) -> String { switch value { case "queued": return "等待下载"; case "downloading": return "正在下载"; case "complete": return "下载完成"; case "failed": return "下载失败"; default: return value } }
    private func run(_ operation: @escaping @MainActor () async throws -> Void) {
        busy = true; error = nil
        Task { @MainActor in defer { busy = false }; do { try await operation() } catch is CancellationError { } catch { self.error = error.localizedDescription } }
    }
    private func export(epub: Bool) {
        run {
            let url = try await library.export(key, epub: epub, fonts: app.fonts)
            _ = try library.saveExport(url, bookmark: app.preferences.exportDirectoryBookmark)
            // Share the sandbox copy: the selected folder's security-scoped lease has ended.
            share = LibraryShareItem(url: url)
        }
    }
}

struct LibraryShareItem: Identifiable { let id = UUID(); let url: URL }

@MainActor final class HistoryModel: ObservableObject {
    @Published var items: [SourceID: [HistoryEntry]] = [:]
    @Published var pages: [SourceID: Int] = [:]
    @Published var more: Set<SourceID> = []
    @Published var loading: Set<SourceID> = []
    @Published var errors: [SourceID: String] = [:]
    private var generations: [SourceID: UUID] = [:]
    func reset() { generations.removeAll(); items.removeAll(); pages.removeAll(); more.removeAll(); errors.removeAll(); loading.removeAll() }
    func load(_ source: SourceID, app: AppStore, append: Bool = false) async {
        guard app.source(source).session.loggedIn else { return }
        if append && (loading.contains(source) || !more.contains(source)) { return }
        let generation = UUID(); generations[source] = generation
        let scope = sourceScope(app, source)
        let revision = app.accountRevision(source)
        loading.insert(source); errors[source] = nil
        do {
            let result = try await app.source(source).history(page: append ? (pages[source] ?? 0) + 1 : 1)
            try Task.checkCancellation()
            guard generations[source] == generation, revision == app.accountRevision(source), sourceScope(app, source) == scope else { return }
            if append && (result.page < (pages[source] ?? 0) + 1 || (result.hasMore && result.items.allSatisfy { entry in (items[source] ?? []).contains { $0.id == entry.id } })) { throw SourceFailure.parsing("历史分页没有前进，请重试") }
            var ids = Set<String>()
            items[source] = ((append ? items[source] ?? [] : []) + result.items).filter { ids.insert($0.id).inserted }
            pages[source] = result.page
            if result.hasMore { more.insert(source) } else { more.remove(source) }
        } catch is CancellationError { } catch { if generations[source] == generation && revision == app.accountRevision(source) && sourceScope(app, source) == scope { errors[source] = error.localizedDescription } }
        if generations[source] == generation { loading.remove(source) }
    }
    func delete(_ entry: HistoryEntry, app: AppStore) async {
        let source = entry.novel.key.source; let scope = sourceScope(app, source)
        let revision = app.accountRevision(source)
        do {
            try await app.source(source).deleteHistory(key: entry.novel.key)
            guard revision == app.accountRevision(source), sourceScope(app, source) == scope else { return }
            items[source]?.removeAll { $0.id == entry.id }
        } catch { if revision == app.accountRevision(source), sourceScope(app, source) == scope { errors[source] = error.localizedDescription } }
    }
}

@MainActor
struct HistoryView: View {
    @EnvironmentObject private var app: AppStore
    var body: some View { HistoryContentView(library: app.library) }
}
@MainActor
private struct HistoryContentView: View {
    @EnvironmentObject private var app: AppStore
    @ObservedObject var library: LibraryStore
    @StateObject private var model = HistoryModel()
    @State private var remote = false
    @State private var localError: String?
    @State private var deleteTarget: HistoryEntry?
    @State private var readerDestination: ReaderDestination?
    var body: some View {
        List {
            Picker("历史记录", selection: $remote) { Text("本地记录").tag(false); Text("来源记录").tag(true) }.pickerStyle(.segmented)
            if remote {
                ForEach(SourceID.allCases) { source in
                    Section(header: Text(source.title)) {
                        if !app.source(source).session.loggedIn { NavigationLink("登录查看来源历史", destination: AccountView(sourceID: source)) }
                        if let error = model.errors[source] { InlineSourceError(message: error) { Task { await model.load(source, app: app) } } }
                        ForEach(model.items[source] ?? []) { entry in historyRow(entry).swipeActions { Button("删除", role: .destructive) { deleteTarget = entry } } }
                        if model.loading.contains(source) { ProgressView() }
                        else if model.more.contains(source) { Button("加载更多") { Task { await model.load(source, app: app, append: true) } } }
                        else if app.source(source).session.loggedIn && (model.items[source] ?? []).isEmpty && model.errors[source] == nil { Text("暂无来源历史").foregroundColor(.secondary) }
                    }
                }
            } else {
                if let error = localError { InlineSourceError(message: error) }
                if library.localHistory.isEmpty { Text("尚无本地阅读记录").foregroundColor(.secondary) }
                ForEach(library.localHistory) { entry in historyRow(entry).swipeActions { Button("删除", role: .destructive) { deleteTarget = entry } } }
            }
        }.navigationTitle("阅读历史").listStyle(.insetGrouped)
            .fullScreenCover(item: $readerDestination) { destination in
                ReaderView(novel: destination.novel, chapterKey: destination.chapterKey)
            }
            .task { await refresh() }.refreshable { await refresh() }
            .onChange(of: app.sessions) { _ in readerDestination = nil; model.reset(); Task { await refresh() } }
            .onChange(of: accountRevisionSignature(app)) { _ in readerDestination = nil; model.reset(); deleteTarget = nil; Task { await refresh() } }
            .confirmationDialog(remote ? "删除该来源的阅读历史？" : "删除该书的本地阅读记录？", isPresented: Binding(get: { deleteTarget != nil }, set: { if !$0 { deleteTarget = nil } }), titleVisibility: .visible) {
                if let entry = deleteTarget {
                    Button("删除", role: .destructive) {
                        if remote { Task { await model.delete(entry, app: app) } }
                        else { do { try library.deleteLocalHistory(entry.novel.key) } catch { localError = error.localizedDescription } }
                        deleteTarget = nil
                    }
                }
                Button("取消", role: .cancel) { deleteTarget = nil }
            }
    }
    @ViewBuilder private func historyRow(_ entry: HistoryEntry) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            NavigationLink(destination: BookDetailView(key: entry.novel.key)) { NovelCardView(novel: entry.novel) }
            HStack {
                VStack(alignment: .leading) { Text(entry.chapterTitle).font(.caption); Text(entry.readAt).font(.caption2).foregroundColor(.secondary) }
                Spacer()
                if let chapter = library.progress[entry.novel.id]?.chapterKey ?? entry.chapterKey {
                    Button("继续阅读") { readerDestination = ReaderDestination(novel: entry.novel, chapterKey: chapter) }.buttonStyle(.borderless).accessibilityIdentifier("history.resume.\(entry.novel.id)")
                }
            }
        }
    }
    private func refresh() async {
        await withTaskGroup(of: Void.self) { group in
            for source in SourceID.allCases { group.addTask { @MainActor in await model.load(source, app: app) } }
        }
    }
}
