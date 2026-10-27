import SwiftUI
import UIKit

struct ReaderView: View {
    @EnvironmentObject private var app: AppStore
    let novel: Novel
    let chapterKey: ResourceKey
    var body: some View {
        ReaderScreen(novel: novel, chapterKey: chapterKey, preferences: app.preferences, fonts: app.fonts)
            .environmentObject(app)
    }
}

private struct ReaderRender {
    var id: UUID
    var html: String
    var settings: ReaderSettings
    var paragraph: Int
    var percent: Int
    var atEnd: Bool
}
private struct ReaderCatalogSection: Identifiable {
    var volume: Volume
    var chapters: [Chapter]
    var id: String { volume.id }
}

@MainActor private final class ReaderState: ObservableObject {
    @Published var content: ChapterContent?
    @Published var render: ReaderRender?
    @Published var loading = false
    @Published var preparingDocument = false
    @Published var error: String?
    @Published var viewport = ReaderViewport()
    @Published var catalog: [ReaderCatalogSection] = []
    @Published var catalogLoading = false
    @Published var catalogError: String?
    @Published var lockedChapter: Chapter?
    @Published var unlocking = false
    @Published var controls = true
    private var loadTask: Task<Void, Never>?
    private var progressTask: Task<Void, Never>?
    private var fontTask: Task<Void, Never>?
    private var catalogTask: Task<Void, Never>?
    private var unlockTask: Task<Void, Never>?
    private var generation = UUID()
    private var fontGeneration = UUID()
    private var requested: ResourceKey?
    private var retryAtEnd = false
    private var bodyBlocks: [ReaderDocument.Block] = []
    private var font: Data?
    private var activeFontID: String?
    private var chapterFont = false
    private var settings = ReaderSettings()
    private var lastSaved: (ResourceKey, ReaderViewport)?
    var mandatoryFont: Bool { content?.fontURL != nil }

    func start(app: AppStore, novel: Novel, chapter: ResourceKey) {
        settings = app.preferences.reader
        if requested == nil || render == nil || loading { load(app: app, novel: novel, chapter: requested ?? chapter) }
        if catalog.isEmpty && !catalogLoading { loadCatalog(app: app, novel: novel) }
    }
    func load(app: AppStore, novel: Novel, chapter: ResourceKey, atEnd: Bool = false, purchased: Bool = false) {
        guard chapter.source == novel.key.source else { error = "章节来源与书籍不一致"; return }
        if let row = catalog.flatMap({ $0.chapters }).first(where: { $0.key == chapter }), row.locked, !purchased {
            lockedChapter = row; return
        }
        flush(app: app, novel: novel)
        loadTask?.cancel(); fontTask?.cancel(); progressTask?.cancel()
        generation = UUID(); fontGeneration = UUID()
        let ticket = generation
        requested = chapter; retryAtEnd = atEnd
        loading = true; error = nil; controls = false
        loadTask = Task { [weak self] in
            do {
                let content = try await app.loadChapter(novel: novel.key, chapter: chapter)
                try Task.checkCancellation()
                guard let self = self, self.generation == ticket else { return }
                if content.chapter.locked {
                    self.lockedChapter = content.chapter; self.loading = false; self.controls = true
                    return
                }
                let selectedFontID = self.settings.customFontID
                let font: Data?
                if let url = content.fontURL {
                    do {
                        let prepared = try await app.fonts.prepare(url, offline: false)
                        font = try await app.fonts.fontData(prepared)
                    } catch is CancellationError { throw CancellationError() }
                    catch { throw SourceFailure.parsing("本章必须使用来源字体，字体加载失败：" + error.localizedDescription) }
                } else if let id = selectedFontID {
                    guard let url = app.fonts.fontURL(id) else { throw SourceFailure.parsing("所选字体尚未安装，请在文字样式中重新选择字体") }
                    font = try await app.fonts.fontData(url)
                } else { font = nil }
                try Task.checkCancellation()
                guard self.generation == ticket else { return }
                let parsed = await Task.detached(priority: .userInitiated) { ReaderDocument.blocks(html: content.bodyHTML, text: content.bodyText) }.value
                guard !parsed.isEmpty else { throw SourceFailure.parsing("来源未返回可阅读正文，请重试或检查章节是否需要解锁") }
                try Task.checkCancellation()
                guard self.generation == ticket else { return }
                let progress = app.library.progress[novel.id]
                let restore = progress?.chapterKey == content.chapter.key ? progress : nil
                self.content = content; self.bodyBlocks = parsed; self.font = font
                self.chapterFont = content.fontURL != nil; self.activeFontID = selectedFontID
                self.viewport = ReaderViewport(paragraph: restore?.paragraphIndex ?? 0, percent: restore?.percent ?? 0)
                self.lastSaved = nil
                self.preparingDocument = true
                self.render = ReaderRender(id: UUID(), html: ReaderDocument.html(content: content, blocks: parsed, settings: self.settings, font: font, mandatory: self.chapterFont), settings: self.settings, paragraph: self.viewport.paragraph, percent: self.viewport.percent, atEnd: atEnd)
                self.loading = false; self.error = nil
                if !self.chapterFont && selectedFontID != self.settings.customFontID { self.updateSettings(self.settings, app: app, novel: novel) }
            } catch is CancellationError { }
            catch {
                guard let self = self, self.generation == ticket else { return }
                self.error = error.localizedDescription; self.loading = false; self.controls = true
                // Failure never swaps old readable content for encoded text or invented body.
            }
        }
    }
    func retry(app: AppStore, novel: Novel) {
        if let key = requested { load(app: app, novel: novel, chapter: key, atEnd: retryAtEnd) }
    }
    func updateSettings(_ value: ReaderSettings, app: AppStore, novel: Novel) {
        settings = value
        guard let content = content, let previous = render else { return }
        if previous.settings == value && activeFontID == value.customFontID,
           chapterFont || activeFontID == nil || activeFontID.flatMap({ app.fonts.fontURL($0) }) != nil { return }
        fontTask?.cancel(); fontGeneration = UUID()
        let ticket = fontGeneration
        fontTask = Task { [weak self] in
            do {
                guard let self = self else { return }
                var data = self.font
                if !self.chapterFont {
                    if let id = value.customFontID {
                        guard let url = app.fonts.fontURL(id) else { throw SourceFailure.parsing("所选字体尚未安装，请重新选择字体") }
                        data = try await app.fonts.fontData(url)
                    } else { data = nil }
                }
                try Task.checkCancellation()
                guard self.fontGeneration == ticket else { return }
                self.error = nil
                self.font = data; self.activeFontID = value.customFontID
                self.preparingDocument = true
                self.render = ReaderRender(id: UUID(), html: ReaderDocument.html(content: content, blocks: self.bodyBlocks, settings: value, font: data, mandatory: self.chapterFont), settings: value, paragraph: self.viewport.paragraph, percent: self.viewport.percent, atEnd: false)
            } catch is CancellationError { } catch {
                guard let self = self, self.fontGeneration == ticket else { return }
                self.error = error.localizedDescription; self.controls = true
            }
        }
    }
    func settled(_ position: ReaderViewport, app: AppStore, novel: Novel) {
        guard content != nil else { return }
        preparingDocument = false
        viewport = position
        progressTask?.cancel()
        guard let content = content else { return }
        if let last = lastSaved, last.0 == content.chapter.key, last.1 == position { return }
        progressTask = Task { [weak self] in
            do { try await Task.sleep(nanoseconds: 800_000_000); try Task.checkCancellation() }
            catch { return }
            guard let self = self, self.content?.chapter.key == content.chapter.key else { return }
            self.lastSaved = (content.chapter.key, position)
            await app.recordProgress(novel: novel, content: content, paragraph: position.paragraph, percent: position.percent)
        }
    }
    func documentFailed(_ message: String) {
        preparingDocument = false
        error = message
        controls = true
    }
    func flush(app: AppStore, novel: Novel) {
        progressTask?.cancel()
        guard let content = content, render != nil else { return }
        let position = viewport
        if let last = lastSaved, last.0 == content.chapter.key, last.1 == position { return }
        lastSaved = (content.chapter.key, position)
        Task { await app.recordProgress(novel: novel, content: content, paragraph: position.paragraph, percent: position.percent) }
    }
    func stop(app: AppStore, novel: Novel) {
        flush(app: app, novel: novel)
        loadTask?.cancel(); fontTask?.cancel(); catalogTask?.cancel(); unlockTask?.cancel()
        catalogLoading = false; unlocking = false
        generation = UUID(); fontGeneration = UUID()
    }
    func boundary(_ direction: Int, app: AppStore, novel: Novel) {
        guard !loading, !preparingDocument, lockedChapter == nil, let content = content else { return }
        let explicit = direction < 0 ? content.previous : content.next
        let chapters = catalog.flatMap { $0.chapters }
        let index = chapters.firstIndex { $0.key == content.chapter.key }
        let adjacent = index.flatMap { index -> ResourceKey? in
            let target = index + (direction < 0 ? -1 : 1)
            return chapters.indices.contains(target) ? chapters[target].key : nil
        }
        if let key = explicit ?? adjacent { load(app: app, novel: novel, chapter: key, atEnd: direction < 0) }
        else {
            error = catalogLoading ? "正在加载分卷目录，请稍后再翻章" : catalogError.map { "无法确认相邻章节：" + $0 } ?? (direction < 0 ? "已经是第一章" : "已经是最后一章")
            controls = true
        }
    }
    func unlock(app: AppStore, novel: Novel) {
        guard !unlocking, let chapter = lockedChapter else { return }
        unlocking = true
        let ticket = generation
        unlockTask = Task { [weak self] in
            do {
                try await app.source(chapter.key.source).unlock(chapter: chapter.key)
                try Task.checkCancellation()
                guard let self = self, self.generation == ticket else { return }
                self.unlocking = false; self.lockedChapter = nil
                for volumeIndex in self.catalog.indices {
                    if let index = self.catalog[volumeIndex].chapters.firstIndex(where: { $0.key == chapter.key }) { self.catalog[volumeIndex].chapters[index].locked = false }
                }
                self.load(app: app, novel: novel, chapter: chapter.key, purchased: true)
            } catch is CancellationError { }
            catch {
                guard let self = self, self.generation == ticket else { return }
                self.unlocking = false; self.lockedChapter = nil; self.error = error.localizedDescription; self.controls = true
            }
        }
    }
    func loadCatalog(app: AppStore, novel: Novel) {
        guard !catalogLoading else { return }
        if catalog.isEmpty, let offline = app.library.records.first(where: { $0.novel.key == novel.key }) {
            catalog = offline.volumes.map { volume in ReaderCatalogSection(volume: volume, chapters: offline.chapters.filter { $0.volumeKey == volume.key }.sorted { $0.order < $1.order }) }
        }
        catalogLoading = true; catalogError = nil
        catalogTask = Task { [weak self] in
            do {
                var volumes: [Volume] = []; var seenVolumes = Set<ResourceKey>(); var page = 1
                while true {
                    let result = try await app.source(novel.key.source).volumes(key: novel.key, page: page)
                    try Task.checkCancellation()
                    let new = result.items.filter { seenVolumes.insert($0.key).inserted }
                    volumes.append(contentsOf: new)
                    if !result.hasMore { break }
                    guard !new.isEmpty else { throw SourceFailure.parsing("分卷分页未前进，请重试") }
                    page += 1
                }
                guard !volumes.isEmpty || self?.catalog.isEmpty != false else { throw SourceFailure.parsing("来源返回空目录，已保留本地章节") }
                var sections: [ReaderCatalogSection] = []
                for volume in volumes {
                    var chapters: [Chapter] = []; var seen = Set<ResourceKey>(); page = 1
                    while true {
                        let result = try await app.source(novel.key.source).chapters(novel: novel.key, volume: volume.key, page: page)
                        try Task.checkCancellation()
                        let new = result.items.filter { seen.insert($0.key).inserted }
                        chapters.append(contentsOf: new)
                        if !result.hasMore { break }
                        guard !new.isEmpty else { throw SourceFailure.parsing("章节分页未前进，请重试") }
                        page += 1
                    }
                    sections.append(ReaderCatalogSection(volume: volume, chapters: chapters))
                    let loadedKeys = Set(sections.map { $0.volume.key })
                    self?.catalog = sections + (self?.catalog.filter { !loadedKeys.contains($0.volume.key) } ?? [])
                }
                self?.catalog = sections; self?.catalogLoading = false
            } catch is CancellationError { self?.catalogLoading = false }
            catch { self?.catalogError = error.localizedDescription; self?.catalogLoading = false }
        }
    }
}

private enum ReaderPanel: String, Identifiable { case menu, settings, catalog; var id: String { rawValue } }
private struct ReaderImageRequest: Identifiable { var id = UUID(); var url: URL; var actions: Bool }

private struct ReaderScreen: View {
    @EnvironmentObject private var app: AppStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    let novel: Novel
    let chapterKey: ResourceKey
    @ObservedObject var preferences: PreferencesStore
    @ObservedObject var fonts: FontStore
    @StateObject private var state = ReaderState()
    @State private var command: ReaderWebCommand?
    @State private var panel: ReaderPanel?
    @State private var image: ReaderImageRequest?
    @State private var scrub: Double = 0
    @State private var scrubbing = false
    @State private var brightness: Double = Double(UIScreen.main.brightness)
    @State private var originalBrightness: CGFloat?
    @State private var originalIdle: Bool?
    @State private var originalOrientation: UIInterfaceOrientationMask?
    @State private var active = false
    @State private var orientationError: String?

    var body: some View {
        ZStack {
            preferences.reader.theme.background.ignoresSafeArea()
            if let render = state.render {
                ReaderWebView(documentID: render.id, html: render.html, settings: render.settings,
                              restoreParagraph: render.paragraph, restorePercent: render.percent, restoreAtEnd: render.atEnd,
                              command: command,
                              onSettled: { position in state.settled(position, app: app, novel: novel); if !scrubbing { scrub = Double(position.percent) / 100 } },
                              onControls: { withAnimation(.easeInOut(duration: 0.16)) { state.controls.toggle() } },
                              onBoundary: { state.boundary($0, app: app, novel: novel) },
                              onImage: { image = ReaderImageRequest(url: $0, actions: $1) },
                              onError: { state.documentFailed($0) })
                    .padding(.top, 6).padding(.bottom, 20)
            } else if !state.loading {
                VStack(spacing: 16) {
                    Image(systemName: "book.closed").font(.largeTitle)
                    Text(state.error ?? "暂无可阅读章节").multilineTextAlignment(.center)
                    Button("重试") { state.retry(app: app, novel: novel) }.accessibilityIdentifier("reader.retry")
                    Button("打开目录") { panel = .catalog }.accessibilityIdentifier("reader.catalog")
                }.padding(24)
            }
            if state.loading || state.preparingDocument {
                VStack { if state.render == nil { Spacer() }; ProgressView("正在准备正文与字体…").accessibilityIdentifier("reader.loading").padding().background(preferences.reader.theme.background.opacity(0.95)).cornerRadius(10); Spacer() }
                    .allowsHitTesting(false)
            }
            if !state.controls {
                VStack { HStack { Spacer(); Button { state.controls = true } label: { Image(systemName: "ellipsis").padding(12) }.accessibilityLabel("阅读菜单").accessibilityIdentifier("reader.menu") }; Spacer() }
                    .padding(.horizontal, 6)
            }
        }
        .foregroundColor(preferences.reader.theme.foreground)
        .navigationBarHidden(true)
        .overlay(alignment: .top) { if state.controls { topControls } }
        .overlay(alignment: .bottom) { if state.controls { bottomControls } }
        .sheet(item: $panel) { item in panelView(item) }
        .fullScreenCover(item: $image) { request in ReaderImagePreview(url: request.url, initialActions: request.actions) }
        .alert("解锁章节", isPresented: Binding(get: { state.lockedChapter != nil }, set: { if !$0 && !state.unlocking { state.lockedChapter = nil } })) {
            Button(state.unlocking ? "正在解锁…" : "确认购买") { state.unlock(app: app, novel: novel) }.disabled(state.unlocking)
            Button("取消", role: .cancel) { state.lockedChapter = nil }.disabled(state.unlocking)
        } message: { Text(unlockMessage) }
        .onAppear {
            active = true
            if originalBrightness == nil { originalBrightness = UIScreen.main.brightness; brightness = Double(UIScreen.main.brightness) }
            if originalIdle == nil { originalIdle = UIApplication.shared.isIdleTimerDisabled }
            if originalOrientation == nil { originalOrientation = ReaderOrientationPolicy.mask }
            applyLifecycle(); applyOrientation()
            state.start(app: app, novel: novel, chapter: chapterKey)
        }
        .onDisappear {
            active = false
            state.stop(app: app, novel: novel)
            restoreLifecycle()
        }
        .onChange(of: preferences.reader) { value in
            state.updateSettings(value, app: app, novel: novel)
            applyLifecycle(); applyOrientation()
        }
        .onChange(of: fonts.installed) { _ in state.updateSettings(preferences.reader, app: app, novel: novel) }
        .onChange(of: scenePhase) { phase in
            if phase == .active { applyLifecycle(); applyOrientation() }
            else { state.flush(app: app, novel: novel); restoreBrightnessAndIdle() }
        }
        .onChange(of: brightness) { _ in applyLifecycle() }
    }
    private var unlockMessage: String {
        guard let chapter = state.lockedChapter else { return "" }
        return chapter.title + "\n" + (chapter.coinPrice.map { "需要 \($0) 枚硬币，确认后将向来源发送购买请求。" } ?? "确认后将向来源发送解锁请求，费用以来源账户为准。")
    }
    private var topControls: some View {
        HStack(spacing: 12) {
            Button { state.flush(app: app, novel: novel); dismiss() } label: { Image(systemName: "chevron.left").frame(width: 32, height: 40) }.accessibilityLabel("返回")
            VStack(alignment: .leading, spacing: 3) {
                Text(state.content?.chapter.title ?? novel.title).font(.headline).lineLimit(1)
                Text(state.content?.volumeTitle ?? novel.title).font(.caption).lineLimit(1).opacity(0.7)
            }
            Spacer(minLength: 0)
            Button { panel = .menu } label: { Image(systemName: "ellipsis").frame(width: 40, height: 40) }.accessibilityLabel("阅读菜单").accessibilityIdentifier("reader.menu")
        }.padding(.horizontal, 12).padding(.vertical, 3).background(preferences.reader.theme.background.opacity(0.98))
    }
    private var bottomControls: some View {
        VStack(spacing: 4) {
            if let error = state.error, state.render != nil {
                HStack { Text(error).font(.caption).lineLimit(3); Spacer(); Button("重试") { state.retry(app: app, novel: novel) }.accessibilityIdentifier("reader.retry") }
            }
            if let syncError = app.sourceErrors[novel.key.source], syncError.contains("进度") { Text(syncError).font(.caption).foregroundColor(.red) }
            if preferences.reader.showProgressBar && state.render != nil {
                Text(preferences.reader.mode == .scroll ? "已阅读 \(state.viewport.percent)%" : "第 \(state.viewport.page + 1) / \(state.viewport.pageCount) 页 · \(state.viewport.percent)%")
                    .font(.caption).accessibilityIdentifier("reader.position")
                Slider(value: $scrub, in: 0...1, onEditingChanged: { editing in
                    scrubbing = editing
                    if !editing { command = ReaderWebCommand(action: .seek(scrub)) }
                }).accessibilityLabel("阅读进度").accessibilityIdentifier("reader.progress")
            }
            HStack(spacing: 18) {
                Button { command = ReaderWebCommand(action: .move(-1)) } label: { Label("上一页", systemImage: "chevron.left") }.accessibilityIdentifier("reader.previous")
                Spacer(minLength: 0)
                Button { panel = .catalog } label: { Image(systemName: "list.bullet") }.accessibilityLabel("目录").accessibilityIdentifier("reader.catalog")
                Button { panel = .settings } label: { Image(systemName: "textformat.size") }.accessibilityLabel("阅读设置").accessibilityIdentifier("reader.settings")
                Spacer(minLength: 0)
                Button { command = ReaderWebCommand(action: .move(1)) } label: { Label("下一页", systemImage: "chevron.right").labelStyle(ReaderTrailingLabelStyle()) }.accessibilityIdentifier("reader.next")
            }.font(.subheadline).padding(.vertical, 8).disabled(state.loading || state.preparingDocument)
        }.padding(.horizontal, 16).padding(.top, 6).background(preferences.reader.theme.background.opacity(0.98))
    }
    @ViewBuilder private func panelView(_ item: ReaderPanel) -> some View {
        switch item {
        case .catalog:
            NavigationView {
                List {
                    if state.catalogLoading { ProgressView("正在加载分卷目录…") }
                    if let error = state.catalogError { Text(error).foregroundColor(.red); Button("重试目录") { state.loadCatalog(app: app, novel: novel) } }
                    if state.catalog.isEmpty && !state.catalogLoading && state.catalogError == nil { Text("暂无目录") }
                    ForEach(state.catalog) { group in
                        Section(group.volume.title) {
                            ForEach(group.chapters) { chapter in
                                Button {
                                    panel = nil
                                    state.load(app: app, novel: novel, chapter: chapter.key)
                                } label: {
                                    HStack { Text(chapter.title); Spacer(); if chapter.locked { Image(systemName: "lock"); if let price = chapter.coinPrice { Text("\(price)").font(.caption) } }; if chapter.key == state.content?.chapter.key { Image(systemName: "checkmark") } }
                                }.foregroundColor(chapter.key == state.content?.chapter.key ? .accentColor : .primary)
                            }
                        }
                    }
                }.navigationTitle("章节目录").toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { panel = nil } } }
                    .task { state.loadCatalog(app: app, novel: novel) }
            }.navigationViewStyle(.stack)
        case .settings:
            ReaderSettingsPanel(preferences: preferences, mandatory: state.mandatoryFont, brightness: $brightness, orientationError: orientationError) { panel = nil }
        case .menu:
            NavigationView {
                List {
                    Section { Text(novel.title).font(.headline); Text(state.content?.chapter.title ?? "正在加载"); Text("已阅读 \(state.viewport.percent)%").font(.caption) }
                    Button("打开章节目录") { panel = .catalog }.accessibilityIdentifier("reader.menu.catalog")
                    Button("文字样式与阅读设置") { panel = .settings }.accessibilityIdentifier("reader.menu.settings")
                    Toggle("显示进度条", isOn: Binding(get: { preferences.reader.showProgressBar }, set: { preferences.reader.showProgressBar = $0 }))
                    Button("上一章") { panel = nil; state.boundary(-1, app: app, novel: novel) }.disabled(state.loading)
                    Button("下一章") { panel = nil; state.boundary(1, app: app, novel: novel) }.disabled(state.loading)
                    Button("重新加载本章") { panel = nil; state.retry(app: app, novel: novel) }.accessibilityIdentifier("reader.menu.retry")
                }.navigationTitle("阅读菜单").toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { panel = nil } } }
            }.navigationViewStyle(.stack)
        }
    }
    private func applyLifecycle() {
        guard active, scenePhase == .active else { return }
        UIApplication.shared.isIdleTimerDisabled = preferences.reader.keepScreenOn || (originalIdle ?? false)
        UIScreen.main.brightness = CGFloat(min(1, max(0.05, brightness)))
    }
    private func restoreBrightnessAndIdle() {
        if let value = originalBrightness { UIScreen.main.brightness = value }
        if let value = originalIdle { UIApplication.shared.isIdleTimerDisabled = value }
    }
    private func restoreLifecycle() {
        restoreBrightnessAndIdle()
        ReaderOrientationPolicy.mask = originalOrientation ?? .allButUpsideDown
        UIViewController.attemptRotationToDeviceOrientation()
        if #available(iOS 16.0, *) {
            for scene in UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }) {
                scene.windows.first?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
                scene.requestGeometryUpdate(.iOS(interfaceOrientations: ReaderOrientationPolicy.mask))
            }
        }
    }
    private func applyOrientation() {
        guard active else { return }
        switch preferences.reader.orientation {
        case "portrait": ReaderOrientationPolicy.mask = .portrait
        case "landscape": ReaderOrientationPolicy.mask = .landscape
        default: ReaderOrientationPolicy.mask = .allButUpsideDown
        }
        orientationError = nil
        if #available(iOS 16.0, *) {
            for scene in UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }) where scene.activationState == .foregroundActive {
                scene.windows.first?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
                scene.requestGeometryUpdate(.iOS(interfaceOrientations: ReaderOrientationPolicy.mask)) { error in
                    DispatchQueue.main.async { orientationError = "系统暂时无法切换方向：" + error.localizedDescription }
                }
            }
        } else { UIViewController.attemptRotationToDeviceOrientation() }
    }
}

private struct ReaderTrailingLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View { HStack(spacing: 4) { configuration.title; configuration.icon } }
}

private struct ReaderSettingsPanel: View {
    @ObservedObject var preferences: PreferencesStore
    var mandatory: Bool
    @Binding var brightness: Double
    var orientationError: String?
    var done: () -> Void
    private func bind<T>(_ path: WritableKeyPath<ReaderSettings, T>) -> Binding<T> {
        Binding(get: { preferences.reader[keyPath: path] }, set: { preferences.reader[keyPath: path] = $0 })
    }
    var body: some View {
        NavigationView {
            Form {
                Section("翻页与背景") {
                    Picker("翻页方式", selection: bind(\.mode)) { ForEach(ReadingMode.allCases) { Text($0.title).tag($0) } }.accessibilityIdentifier("reader.mode")
                    Picker("背景", selection: bind(\.theme)) { ForEach(ReadingTheme.allCases) { Text($0.title).tag($0) } }
                    Picker("点击区域", selection: bind(\.tapZone)) { ForEach(ReaderDocument.tapZones, id: \.id) { Text($0.title).tag($0.id) } }
                    Picker("反转点击区域", selection: bind(\.tapInversion)) { ForEach(ReaderDocument.inversions, id: \.id) { Text($0.title).tag($0.id) } }
                }
                Section("文字样式") {
                    Picker("系统字体", selection: Binding(get: { preferences.reader.font }, set: { preferences.reader.customFontID = nil; preferences.reader.font = $0 })) {
                        ForEach(ReaderDocument.fonts, id: \.id) { Text($0.title).tag($0.id) }
                    }
                    NavigationLink("下载字体、预览与选择") { FontSelectionView() }
                    if mandatory { Text("本章使用来源专用字体以正确还原文字。自选字体在无需专用字体的章节生效。").font(.caption) }
                    VStack(alignment: .leading) { Text("字号 \(Int(preferences.reader.fontSize))"); Slider(value: bind(\.fontSize), in: 14...32, step: 1) }.accessibilityIdentifier("reader.fontSize")
                    VStack(alignment: .leading) { Text(String(format: "行高 %.1f", preferences.reader.lineHeight)); Slider(value: bind(\.lineHeight), in: 1.2...2.2, step: 0.1) }
                    VStack(alignment: .leading) { Text("页边距 \(Int(preferences.reader.horizontalPadding))"); Slider(value: bind(\.horizontalPadding), in: 12...40, step: 2) }
                }
                Section("屏幕与插图") {
                    Picker("屏幕方向", selection: bind(\.orientation)) { Text("跟随系统").tag("default"); Text("竖屏").tag("portrait"); Text("横屏").tag("landscape") }
                    if #unavailable(iOS 16.0) { Text("iOS 15 按选定方向限制旋转；请转动设备以采用该方向。").font(.caption) }
                    if let error = orientationError { Text(error).font(.caption).foregroundColor(.red) }
                    Picker("图片缩放", selection: bind(\.imageScale)) { ForEach(ReaderDocument.imageScales, id: \.id) { Text($0.title).tag($0.id) } }
                    Toggle("保持屏幕常亮", isOn: bind(\.keepScreenOn))
                    Toggle("显示进度条", isOn: bind(\.showProgressBar))
                    VStack(alignment: .leading) { Text("亮度"); Slider(value: $brightness, in: 0.05...1) { _ in UIScreen.main.brightness = CGFloat(brightness) } }
                    Text("退出阅读或进入后台后恢复原亮度。插图可轻点放大、双击缩放，长按保存到相册或分享。").font(.caption)
                }
                Section("系统限制") {
                    Text("iOS 不提供拦截并抑制实体音量键的公开接口，因此此版本不提供音量键翻页；音量键保持系统音量功能。").font(.caption)
                }
            }.navigationTitle("阅读设置").toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成", action: done) } }
        }.navigationViewStyle(.stack)
    }
}
