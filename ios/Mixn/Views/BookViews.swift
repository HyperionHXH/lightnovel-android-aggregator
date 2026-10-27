import SwiftUI

@MainActor final class BookDetailModel: ObservableObject {
    struct ChaptersState {
        var items: [Chapter] = []
        var page = 0
        var hasMore = false
        var loading = false
        var loaded = false
        var error: String?
    }
    @Published var detail: NovelDetail?
    @Published var detailLoading = false
    @Published var detailError: String?
    @Published var volumes: [Volume] = []
    @Published var volumesPage = 0
    @Published var volumesMore = false
    @Published var volumesLoading = false
    @Published var volumesError: String?
    @Published var chapters: [String: ChaptersState] = [:]
    @Published var expanded: Set<String> = []
    @Published var inShelf: Bool?
    @Published var shelfLoading = false
    @Published var shelfError: String?
    @Published var actionError: String?
    @Published var starting = false
    @Published var enqueueing = false
    @Published var unlocking = false
    let key: ResourceKey
    private var generations: [String: UUID] = [:]
    private var cacheGenerations: [String: Int] = [:]
    private var accountRevisions: [String: Int] = [:]
    init(key: ResourceKey) { self.key = key }
    private func begin(_ name: String, app: AppStore) -> UUID { let id = UUID(); generations[name] = id; cacheGenerations[name] = app.cacheGeneration; accountRevisions[name] = app.accountRevision(key.source); return id }
    private func valid(_ name: String, _ generation: UUID, _ scope: String, _ app: AppStore) -> Bool { generations[name] == generation && cacheGenerations[name] == app.cacheGeneration && accountRevisions[name] == app.accountRevision(key.source) && sourceScope(app, key.source) == scope && !Task.isCancelled }
    func cacheCleared() { generations.removeAll(); cacheGenerations.removeAll(); detailLoading = false; volumesLoading = false; shelfLoading = false; for key in Array(chapters.keys) { chapters[key]?.loading = false } }
    func load(app: AppStore) async {
        await withTaskGroup(of: Void.self) { group in
            group.addTask { @MainActor in await self.loadDetail(app: app) }
            group.addTask { @MainActor in await self.loadVolumes(app: app) }
            group.addTask { @MainActor in await self.loadShelf(app: app) }
            for volume in volumes where expanded.contains(volume.id) { group.addTask { @MainActor in await self.loadChapters(volume, app: app) } }
        }
    }
    func accountChanged(app: AppStore) async {
        generations.removeAll(); inShelf = nil; shelfLoading = false; shelfError = nil
        detail = nil; chapters.removeAll(); expanded.removeAll(); volumes.removeAll()
        volumesPage = 0; volumesMore = false
        await load(app: app)
    }
    func loadDetail(app: AppStore) async {
        let name = "detail"; let id = begin(name, app: app); let scope = sourceScope(app, key.source)
        let cacheToken = await app.cache.token(scope: scope)
        guard valid(name, id, scope, app) else { return }
        detailLoading = true; detailError = nil
        if detail == nil {
            if let local = app.library.records.first(where: { $0.novel.key == key }) { detail = NovelDetail(novel: local.novel) }
            if let data = await app.cache.read(key: "detail:" + key.id, scope: scope),
               let cached = try? JSONDecoder().decode(NovelDetail.self, from: data), valid(name, id, scope, app) { detail = cached }
        }
        do {
            let result = try await app.source(key.source).detail(key: key)
            guard valid(name, id, scope, app) else { return }
            guard result.novel.key == key else { throw SourceFailure.parsing("来源返回了其他书籍的详情") }
            detail = result
            try await app.cache.write(JSONEncoder().encode(result), key: "detail:" + key.id, scope: scope, ttl: 300, token: cacheToken)
        } catch is CancellationError { } catch { if valid(name, id, scope, app) { detailError = error.localizedDescription } }
        if generations[name] == id { detailLoading = false }
    }
    func loadVolumes(app: AppStore, append: Bool = false) async {
        if append && (volumesLoading || !volumesMore) { return }
        let name = "volumes"; let id = begin(name, app: app); let scope = sourceScope(app, key.source)
        let cacheToken = await app.cache.token(scope: scope)
        guard valid(name, id, scope, app) else { return }
        volumesLoading = true; volumesError = nil
        if volumes.isEmpty {
            if let local = app.library.records.first(where: { $0.novel.key == key }) { volumes = local.volumes }
            if let data = await app.cache.read(key: "volumes:" + key.id, scope: scope), let cached = try? JSONDecoder().decode(Page<Volume>.self, from: data), valid(name, id, scope, app) {
                volumes = cached.items; volumesPage = cached.page; volumesMore = cached.hasMore
            }
        }
        do {
            let result = try await app.source(key.source).volumes(key: key, page: append ? volumesPage + 1 : 1)
            guard valid(name, id, scope, app) else { return }
            if append && (result.page < volumesPage + 1 || (result.hasMore && result.items.allSatisfy { item in volumes.contains { $0.id == item.id } })) { throw SourceFailure.parsing("分卷分页没有前进，请重试") }
            var ids = Set<String>()
            volumes = ((append ? volumes : []) + result.items).filter { $0.novelKey == key && ids.insert($0.id).inserted }
            volumesPage = result.page; volumesMore = result.hasMore
            let cached = Page(items: volumes, page: volumesPage, total: result.total, hasMore: volumesMore)
            try await app.cache.write(JSONEncoder().encode(cached), key: "volumes:" + key.id, scope: scope, ttl: 300, token: cacheToken)
        } catch is CancellationError { } catch { if valid(name, id, scope, app) { volumesError = error.localizedDescription } }
        if generations[name] == id { volumesLoading = false }
    }
    func loadShelf(app: AppStore) async {
        let name = "shelf"; let id = begin(name, app: app); let scope = sourceScope(app, key.source)
        shelfError = nil
        guard app.source(key.source).session.loggedIn else { inShelf = nil; shelfLoading = false; return }
        shelfLoading = true
        do {
            let result = try await app.source(key.source).shelf()
            guard valid(name, id, scope, app) else { return }
            inShelf = result.contains { $0.key == key }
            try app.library.observeShelf(result)
        } catch is CancellationError { } catch { if valid(name, id, scope, app) { shelfError = error.localizedDescription } }
        if generations[name] == id { shelfLoading = false }
    }
    func toggleShelf(app: AppStore) async {
        guard !shelfLoading else { return }
        let name = "shelf"; let id = begin(name, app: app); let scope = sourceScope(app, key.source)
        shelfLoading = true; shelfError = nil
        do {
            if inShelf == nil {
                let current = try await app.source(key.source).shelf()
                guard valid(name, id, scope, app) else { return }
                inShelf = current.contains { $0.key == key }
            }
            let add = !(inShelf ?? false)
            try await app.source(key.source).setShelf(key: key, add: add)
            guard valid(name, id, scope, app) else { return }
            inShelf = add
        } catch is CancellationError { } catch { if valid(name, id, scope, app) { shelfError = error.localizedDescription } }
        if generations[name] == id { shelfLoading = false }
    }
    func toggle(_ volume: Volume, app: AppStore) async {
        if expanded.contains(volume.id) { expanded.remove(volume.id); return }
        expanded.insert(volume.id)
        if chapters[volume.id]?.loaded != true { await loadChapters(volume, app: app) }
    }
    func loadChapters(_ volume: Volume, app: AppStore, append: Bool = false) async {
        var state = chapters[volume.id] ?? ChaptersState()
        if append && (state.loading || !state.hasMore) { return }
        let name = "chapters:" + key.id + ":" + volume.id; let id = begin(name, app: app); let scope = sourceScope(app, key.source)
        let cacheToken = await app.cache.token(scope: scope)
        guard valid(name, id, scope, app) else { return }
        state.loading = true; state.error = nil
        if state.items.isEmpty, let local = app.library.records.first(where: { $0.novel.key == key }) {
            state.items = local.chapters.filter { $0.volumeKey == volume.key }
        }
        chapters[volume.id] = state
        if state.items.isEmpty, let data = await app.cache.read(key: name, scope: scope), let cached = try? JSONDecoder().decode(Page<Chapter>.self, from: data), valid(name, id, scope, app) {
            chapters[volume.id]?.items = cached.items
        }
        do {
            let result = try await app.source(key.source).chapters(novel: key, volume: volume.key, page: append ? state.page + 1 : 1)
            guard valid(name, id, scope, app) else { return }
            if append && (result.page < state.page + 1 || (result.hasMore && result.items.allSatisfy { item in state.items.contains { $0.id == item.id } })) { throw SourceFailure.parsing("章节分页没有前进，请重试") }
            var ids = Set<String>()
            var next = chapters[volume.id] ?? ChaptersState()
            next.items = ((append ? next.items : []) + result.items).filter { $0.novelKey == key && $0.volumeKey == volume.key && ids.insert($0.id).inserted }
            next.page = result.page; next.hasMore = result.hasMore; next.loaded = true; next.loading = false; next.error = nil
            chapters[volume.id] = next
            let cached = Page(items: next.items, page: next.page, total: result.total, hasMore: next.hasMore)
            try await app.cache.write(JSONEncoder().encode(cached), key: name, scope: scope, ttl: 300, token: cacheToken)
        } catch is CancellationError { } catch { if valid(name, id, scope, app) { chapters[volume.id]?.error = error.localizedDescription } }
        if generations[name] == id { chapters[volume.id]?.loading = false }
    }
    func firstChapter(app: AppStore) async -> Chapter? {
        guard !starting else { return nil }
        starting = true; actionError = nil
        defer { starting = false }
        let scope = sourceScope(app, key.source)
        let accountRevision = app.accountRevision(key.source)
        let cacheGeneration = app.cacheGeneration
        if volumes.isEmpty { await loadVolumes(app: app) }
        var checked = Set<String>()
        while true {
            guard !Task.isCancelled, sourceScope(app, key.source) == scope, accountRevision == app.accountRevision(key.source), cacheGeneration == app.cacheGeneration else { return nil }
            for volume in volumes where checked.insert(volume.id).inserted {
                if chapters[volume.id]?.items.isEmpty != false { await loadChapters(volume, app: app) }
                guard !Task.isCancelled, accountRevision == app.accountRevision(key.source), cacheGeneration == app.cacheGeneration else { return nil }
                while chapters[volume.id]?.items.isEmpty != false && chapters[volume.id]?.hasMore == true {
                    guard !Task.isCancelled, accountRevision == app.accountRevision(key.source), cacheGeneration == app.cacheGeneration else { return nil }
                    await loadChapters(volume, app: app, append: true)
                    if chapters[volume.id]?.error != nil { break }
                }
                if let chapter = chapters[volume.id]?.items.first { return chapter }
                if let error = chapters[volume.id]?.error { actionError = error; return nil }
            }
            if !volumesMore { break }
            await loadVolumes(app: app, append: true)
            if let error = volumesError { actionError = error; return nil }
        }
        actionError = volumesError ?? "暂无可阅读章节"
        return nil
    }
    func download(app: AppStore, volume: Volume? = nil) async {
        guard !enqueueing, let novel = detail?.novel else { return }
        enqueueing = true; actionError = nil
        let scope = sourceScope(app, key.source)
        let accountRevision = app.accountRevision(key.source)
        defer { enqueueing = false }
        do {
            let selected: [Volume]
            if let volume = volume { selected = [volume] }
            else {
                var all: [Volume] = []; var page = 1; var seen = Set<String>()
                while true {
                    let result = try await app.source(key.source).volumes(key: key, page: page)
                    try Task.checkCancellation()
                    guard sourceScope(app, key.source) == scope, accountRevision == app.accountRevision(key.source) else { throw CancellationError() }
                    let fresh = result.items.filter { $0.novelKey == key && seen.insert($0.id).inserted }
                    all += fresh
                    if !result.hasMore { break }
                    guard !fresh.isEmpty, result.page >= page else { throw SourceFailure.parsing("分卷分页没有前进，请稍后重试") }
                    page = result.page + 1
                }
                selected = all
            }
            guard !selected.isEmpty else { throw SourceFailure.server("没有可下载的分卷") }
            guard sourceScope(app, key.source) == scope, accountRevision == app.accountRevision(key.source), !Task.isCancelled else { throw CancellationError() }
            try await app.library.enqueue(novel: novel, volumes: selected, source: app.source(key.source), fonts: app.fonts)
        } catch is CancellationError { } catch { if sourceScope(app, key.source) == scope && accountRevision == app.accountRevision(key.source) { actionError = error.localizedDescription } }
    }
    func unlock(_ chapter: Chapter, app: AppStore) async -> Bool {
        guard !unlocking else { return false }
        unlocking = true; actionError = nil
        let scope = sourceScope(app, key.source)
        let accountRevision = app.accountRevision(key.source)
        defer { unlocking = false }
        do {
            try await app.source(key.source).unlock(chapter: chapter.key)
            guard sourceScope(app, key.source) == scope, accountRevision == app.accountRevision(key.source) else { return false }
            if let index = chapters[chapter.volumeKey.id]?.items.firstIndex(where: { $0.id == chapter.id }) { chapters[chapter.volumeKey.id]?.items[index].locked = false }
            return true
        } catch { if sourceScope(app, key.source) == scope && accountRevision == app.accountRevision(key.source) { actionError = error.localizedDescription }; return false }
    }
}

@MainActor
struct BookDetailView: View {
    @EnvironmentObject private var app: AppStore
    let key: ResourceKey
    @StateObject private var model: BookDetailModel
    init(key: ResourceKey) { self.key = key; _model = StateObject(wrappedValue: BookDetailModel(key: key)) }
    var body: some View {
        BookDetailContentView(model: model, library: app.library)
            .task { await model.load(app: app) }
            .onChange(of: app.sessions[key.source]) { _ in Task { await model.accountChanged(app: app) } }
            .onChange(of: app.accountRevision(key.source)) { _ in Task { await model.accountChanged(app: app) } }
            .onChange(of: app.cacheGeneration) { _ in model.cacheCleared() }
    }
}

@MainActor
private struct BookDetailContentView: View {
    @EnvironmentObject private var app: AppStore
    @ObservedObject var model: BookDetailModel
    @ObservedObject var library: LibraryStore
    @State private var selectedTab = 0
    @State private var readerChapter: ResourceKey?
    @State private var reading = false
    @State private var unlockTarget: Chapter?
    private var novel: Novel? { model.detail?.novel ?? library.records.first(where: { $0.novel.key == model.key })?.novel }
    private var progress: ReadingProgress? { library.progress[model.key.id] }
    private var downloadActive: Bool { library.records.contains { $0.novel.key == model.key && ["queued", "downloading"].contains($0.status) } }
    var body: some View {
        List {
            if model.detailLoading && model.detail == nil { Section { ProgressView("加载书籍详情…") } }
            if let error = model.detailError { Section { InlineSourceError(message: error) { Task { await model.loadDetail(app: app) } } } }
            if let novel = novel {
                Section {
                    HStack(alignment: .top, spacing: 15) {
                        NovelCoverView(novel: novel, width: 100)
                        VStack(alignment: .leading, spacing: 7) {
                            Text(novel.key.source.title).font(.caption).foregroundColor(.secondary)
                            Text(novel.title).font(.title3.bold()).accessibilityIdentifier("detail.title")
                            Text(novel.authors.isEmpty ? "作者未知" : novel.authors.joined(separator: " / ")).font(.subheadline).foregroundColor(.secondary)
                            Text("\(novel.volumeCount) 卷 · \(novel.chapterCount) 章").font(.caption).foregroundColor(.secondary)
                            if novel.wordCount > 0 { Text("\(novel.wordCount.formatted()) 字").font(.caption) }
                            if let score = novel.score { Label(String(format: "%.1f", score), systemImage: "star.fill").foregroundColor(.orange) }
                        }
                    }
                    Button { startReading(novel) } label: {
                        HStack { Label(progress == nil ? "开始阅读" : "继续阅读（\(progress?.percent ?? 0)%）", systemImage: "book"); Spacer(); if model.starting { ProgressView() } }
                    }.disabled(model.starting).accessibilityIdentifier("detail.read")
                    if app.source(model.key.source).session.loggedIn {
                        Button { Task { await model.toggleShelf(app: app) } } label: {
                            HStack { Label(model.inShelf == true ? "移出书架" : "加入书架", systemImage: model.inShelf == true ? "bookmark.fill" : "bookmark"); Spacer(); if model.shelfLoading { ProgressView() } }
                        }.disabled(model.shelfLoading).accessibilityIdentifier("detail.shelf")
                    } else { NavigationLink("登录\(model.key.source.title)管理书架", destination: AccountView(sourceID: model.key.source)).accessibilityIdentifier("detail.login") }
                    if let error = model.shelfError { InlineSourceError(message: error) { Task { await model.loadShelf(app: app) } } }
                    Button { Task { await model.download(app: app) } } label: {
                        HStack { Label(downloadActive ? "下载中 / 等待网络" : model.enqueueing ? "正在准备目录…" : "下载全书 / 补全离线", systemImage: "arrow.down.circle"); Spacer(); if model.enqueueing { ProgressView() } }
                    }.disabled(model.enqueueing || downloadActive).accessibilityIdentifier("detail.download")
                    OfflineControlsView(library: library, key: model.key)
                }
                if let error = model.actionError { Section { InlineSourceError(message: error) } }
                Section {
                    Picker("书籍内容", selection: $selectedTab) { Text("简介").tag(0); Text("章节").tag(1) }.pickerStyle(.segmented).accessibilityIdentifier("detail.tabs")
                    NavigationLink(destination: CommentsView(key: model.key)) { Label("评论与评分（\(model.detail?.commentCount ?? 0)）", systemImage: "text.bubble") }.accessibilityIdentifier("detail.comments")
                }
                if selectedTab == 0 { synopsis(novel) }
            }
            if selectedTab == 1 || novel == nil { catalog }
        }.listStyle(.insetGrouped).navigationTitle(novel?.title ?? "书籍详情").navigationBarTitleDisplayMode(.inline)
            .background(readerNavigation)
            .refreshable { await model.load(app: app) }
            .buttonStyle(.borderless)
            .onChange(of: app.sessions[model.key.source]) { _ in readerChapter = nil; reading = false; unlockTarget = nil }
            .onChange(of: app.accountRevision(model.key.source)) { _ in readerChapter = nil; reading = false; unlockTarget = nil }
            .alert("解锁章节", isPresented: Binding(get: { unlockTarget != nil }, set: { if !$0 { unlockTarget = nil } })) {
                if let chapter = unlockTarget {
                    Button("确认购买并阅读") { Task { if await model.unlock(chapter, app: app) { readerChapter = chapter.key; reading = true }; unlockTarget = nil } }
                    Button("取消", role: .cancel) { unlockTarget = nil }
                }
            } message: {
                if let chapter = unlockTarget { Text("\(chapter.title)\n\(chapter.coinPrice.map { "需要 \($0) 金币。" } ?? "此章节需要购买，费用以来源账户为准。")确认后将从\(model.key.source.title)当前账户扣费。") }
            }
    }
    @ViewBuilder private var readerNavigation: some View {
        if let novel = novel, let key = readerChapter { NavigationLink(destination: ReaderView(novel: novel, chapterKey: key), isActive: $reading) { EmptyView() }.hidden() }
    }
    @ViewBuilder private func synopsis(_ novel: Novel) -> some View {
        if !novel.tags.isEmpty {
            Section(header: Text("标签")) {
                ScrollView(.horizontal, showsIndicators: false) { HStack { ForEach(novel.tags, id: \.self) { tag in NavigationLink(destination: SearchView(initialQuery: tag)) { Text(tag).padding(.horizontal, 9).padding(.vertical, 6).background(Color.accentColor.opacity(0.1)).cornerRadius(7) }.accessibilityIdentifier("detail.tag.\(tag)") } } }
            }
        }
        Section(header: Text("简介")) {
            Text(novel.synopsis.isEmpty ? "来源暂无简介" : novel.synopsis).textSelection(.enabled)
            if let publisher = model.detail?.publisher { LabeledContentCompat(label: "出版", value: publisher) }
            if let detail = model.detail { LabeledContentCompat(label: "收藏", value: String(detail.favorites)) }
        }
        if let alternates = model.detail?.alternateVersions, !alternates.isEmpty {
            Section(header: Text("同书其他版本")) {
                ForEach(alternates) { version in NavigationLink(destination: BookDetailView(key: version.key)) { NovelCardView(novel: version) }.accessibilityIdentifier("book.\(version.id)") }
            }
        }
    }
    @ViewBuilder private var catalog: some View {
        Section(header: Text("分卷与章节")) {
            if let error = model.volumesError { InlineSourceError(message: error) { Task { await model.loadVolumes(app: app) } } }
            if model.volumesLoading && model.volumes.isEmpty { ProgressView("加载目录…") }
            ForEach(model.volumes) { volume in
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Button { Task { await model.toggle(volume, app: app) } } label: {
                            HStack { Image(systemName: model.expanded.contains(volume.id) ? "chevron.down" : "chevron.right"); Text(volume.title).font(.headline); Spacer(); Text("\(volume.chapterCount) 章").font(.caption).foregroundColor(.secondary) }
                        }.buttonStyle(.plain).accessibilityIdentifier("volume.\(volume.id)")
                        Button { Task { await model.download(app: app, volume: volume) } } label: { Image(systemName: "arrow.down.circle") }.disabled(model.enqueueing || downloadActive).accessibilityLabel("下载\(volume.title)")
                    }
                    if model.expanded.contains(volume.id) { chapterRows(volume) }
                }.padding(.vertical, 5)
            }
            if model.volumesMore { Button { Task { await model.loadVolumes(app: app, append: true) } } label: { if model.volumesLoading { ProgressView() } else { Text("加载更多分卷") } }.disabled(model.volumesLoading).accessibilityIdentifier("detail.moreVolumes") }
            if !model.volumesLoading && model.volumes.isEmpty && model.volumesError == nil { Text("来源暂无目录").foregroundColor(.secondary) }
        }
    }
    @ViewBuilder private func chapterRows(_ volume: Volume) -> some View {
        let state = model.chapters[volume.id] ?? BookDetailModel.ChaptersState()
        if let error = state.error { InlineSourceError(message: error) { Task { await model.loadChapters(volume, app: app) } } }
        ForEach(state.items) { chapter in
            Divider()
            Button { open(chapter) } label: {
                HStack(alignment: .top) {
                    Text(chapter.title).foregroundColor(progress?.chapterKey == chapter.key ? .accentColor : .primary).frame(maxWidth: .infinity, alignment: .leading)
                    if chapter.locked { Label(chapter.coinPrice.map { "\($0)" } ?? "", systemImage: "lock.fill").foregroundColor(.orange).font(.caption) }
                    if chapter.wordCount > 0 { Text("\(chapter.wordCount) 字").font(.caption).foregroundColor(.secondary) }
                }.padding(.vertical, 4)
            }.buttonStyle(.plain).disabled(model.unlocking).accessibilityIdentifier("chapter.\(chapter.id)")
        }
        if state.loading { ProgressView("加载章节…") }
        else if state.hasMore { Button("加载更多章节") { Task { await model.loadChapters(volume, app: app, append: true) } }.accessibilityIdentifier("chapters.more.\(volume.id)") }
        else if state.loaded && state.items.isEmpty && state.error == nil { Text("本卷暂无章节").foregroundColor(.secondary) }
    }
    private func startReading(_ novel: Novel) {
        if let progress = progress { readerChapter = progress.chapterKey; reading = true; return }
        Task { if let chapter = await model.firstChapter(app: app) { open(chapter) } }
    }
    private func open(_ chapter: Chapter) {
        if chapter.locked {
            do {
                if try library.savedChapter(novel: model.key, chapter: chapter.key) != nil { readerChapter = chapter.key; reading = true }
                else { unlockTarget = chapter }
            } catch { model.actionError = error.localizedDescription }
        } else { readerChapter = chapter.key; reading = true }
    }
}

private struct LabeledContentCompat: View {
    let label: String
    let value: String
    var body: some View { HStack { Text(label).foregroundColor(.secondary); Spacer(); Text(value) }.font(.subheadline) }
}
