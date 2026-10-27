import Foundation
import Combine
import Network

struct OfflineRecord: Codable, Hashable, Identifiable {
    var novel: Novel
    var volumes: [Volume] = []
    var chapters: [Chapter] = []
    var completed: Int = 0
    var status: String = "queued"
    var error: String? = nil
    var byteCount: Int64 = 0
    var downloadedChapterIDs: Set<String> = []
    var catalogComplete: Bool = false
    var volumesComplete: Bool = false
    var id: String { novel.id }
}

@MainActor final class LibraryStore: ObservableObject {
    @Published private(set) var records: [OfflineRecord] = []
    @Published private(set) var progress: [String: ReadingProgress] = [:]
    @Published private(set) var localHistory: [HistoryEntry] = []
    @Published private(set) var updated: Set<String> = []
    @Published private(set) var storageError: String?
    @Published private(set) var wifiOnly = true
    private struct Snapshot: Codable, Equatable {
        var volumes: Int
        var chapters: Int
        var words: Int64
        var unread: Int
        init(_ novel: Novel) { volumes = novel.volumeCount; chapters = novel.chapterCount; words = novel.wordCount; unread = novel.unreadCount ?? 0 }
    }
    private struct State: Codable {
        var records: [OfflineRecord] = []
        var progress: [String: ReadingProgress] = [:]
        var history: [HistoryEntry] = []
        var updated: Set<String> = []
        var snapshots: [String: Snapshot] = [:]
        var wifiOnly: Bool = true
    }
    private let root: URL
    private let io = StorageIO()
    private let http = HTTPClient()
    private var state = State()
    private var initializationError: Error?
    private var generations: [String: UInt64] = [:]
    private let diskEpochs = StorageEpoch()
    private var jobs: [String: Task<Void, Error>] = [:]
    private var activeJob: UUID?
    private var slotWaiters: [(id: UUID, continuation: CheckedContinuation<Void, Error>)] = []
    private let network = NWPathMonitor()
    private var networkPath: NWPath?
    private var networkWaiters: [UUID: CheckedContinuation<Void, Error>] = [:]
    init(rootURL: URL? = nil) {
        root = StorageIO.root(rootURL, component: "Library")
        let root = self.root
        do {
            state = try io.sync {
                try StorageIO.create(root)
                let url = root.appendingPathComponent("library.plist")
                guard FileManager.default.fileExists(atPath: url.path) else { return State() }
                var result = try StorageIO.decode(State.self, from: url)
                for index in result.records.indices {
                    if ["queued", "downloading"].contains(result.records[index].status) {
                        result.records[index].status = "queued"
                        result.records[index].error = nil
                    }
                    let book = root.appendingPathComponent("Books", isDirectory: true).appendingPathComponent(sha256(result.records[index].id), isDirectory: true)
                    if let cover = result.records[index].novel.coverURL, cover.isFileURL {
                        result.records[index].novel.coverURL = book.appendingPathComponent("Assets", isDirectory: true).appendingPathComponent(cover.lastPathComponent)
                        do { _ = try BookAsset.imageType(Data(contentsOf: result.records[index].novel.coverURL!)) }
                        catch { result.records[index].status = "failed"; result.records[index].error = "离线封面损坏：" + error.localizedDescription }
                    }
                    var present: Set<String> = []
                    for chapter in result.records[index].chapters where !chapter.locked {
                        let file = book.appendingPathComponent("Chapters", isDirectory: true).appendingPathComponent(sha256(chapter.id) + ".plist")
                        if FileManager.default.fileExists(atPath: file.path) {
                            do {
                                let content = try StorageIO.decode(ChapterContent.self, from: file)
                                guard content.chapter.key == chapter.key, content.chapter.novelKey == result.records[index].novel.key else { throw StorageFailure.message("离线章节归属不匹配") }
                                if let font = content.fontURL {
                                    let local = book.appendingPathComponent("Assets", isDirectory: true).appendingPathComponent(font.lastPathComponent)
                                    _ = try FontPayload.format(Data(contentsOf: local))
                                }
                                try Self.validateImages(content.bodyHTML, assets: book.appendingPathComponent("Assets", isDirectory: true))
                                present.insert(chapter.id)
                            } catch {
                                result.records[index].status = "failed"
                                result.records[index].error = "离线文件需要重新下载：" + error.localizedDescription
                            }
                        }
                    }
                    result.records[index].downloadedChapterIDs = present
                    result.records[index].completed = present.count
                    result.records[index].byteCount = try StorageIO.size(book)
                    if result.records[index].status == "complete", present.count != result.records[index].chapters.filter({ !$0.locked }).count {
                        result.records[index].status = "failed"
                        result.records[index].error = "部分离线章节丢失，请重试下载"
                    }
                }
                try StorageIO.encode(result, to: url)
                return result
            }
            publish()
        } catch { initializationError = error; storageError = error.localizedDescription }
        network.pathUpdateHandler = { [weak self] path in
            Task { @MainActor [weak self] in self?.networkChanged(path) }
        }
        networkChanged(network.currentPath)
        network.start(queue: DispatchQueue(label: "io.mixn.download-network", qos: .utility))
    }
    private func ready() throws { if let error = initializationError { throw error } }
    private func publish() {
        records = state.records; progress = state.progress; localHistory = state.history; updated = state.updated; wifiOnly = state.wifiOnly
    }
    private func commit(_ next: State) throws {
        let file = root.appendingPathComponent("library.plist")
        try io.sync { try StorageIO.encode(next, to: file) }
        state = next; publish()
    }
    func setWifiOnly(_ enabled: Bool) throws {
        try ready(); var next = state; next.wifiOnly = enabled; try commit(next)
        if let path = networkPath { networkChanged(path) }
    }
    private func networkChanged(_ path: NWPath) {
        networkPath = path
        guard path.status == .satisfied, !wifiOnly || path.usesInterfaceType(.wifi) || path.usesInterfaceType(.wiredEthernet) else { return }
        let continuations = networkWaiters.values
        networkWaiters.removeAll()
        for continuation in continuations { continuation.resume() }
    }
    private func waitForNetwork() async throws {
        try Task.checkCancellation()
        if let path = networkPath, path.status == .satisfied,
           !wifiOnly || path.usesInterfaceType(.wifi) || path.usesInterfaceType(.wiredEthernet) { return }
        let id = UUID()
        try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                if Task.isCancelled { continuation.resume(throwing: CancellationError()) }
                else { networkWaiters[id] = continuation }
            }
        }, onCancel: {
            Task { @MainActor [weak self] in self?.networkWaiters.removeValue(forKey: id)?.resume(throwing: CancellationError()) }
        })
    }
    deinit { network.cancel() }
    private static func uniqueVolumes(_ values: [Volume]) -> [Volume] {
        var result: [Volume] = []
        for value in values where !result.contains(where: { $0.key == value.key }) { result.append(value) }
        return result
    }
    private func bookURL(_ key: ResourceKey) -> URL {
        root.appendingPathComponent("Books", isDirectory: true).appendingPathComponent(sha256(key.id), isDirectory: true)
    }
    private func chapterURL(_ novel: ResourceKey, _ chapter: ResourceKey) -> URL {
        bookURL(novel).appendingPathComponent("Chapters", isDirectory: true).appendingPathComponent(sha256(chapter.id) + ".plist")
    }
    private func current(_ key: ResourceKey, _ generation: UInt64) throws {
        try Task.checkCancellation()
        guard generations[key.id, default: 0] == generation, state.records.contains(where: { $0.id == key.id }) else { throw CancellationError() }
    }
    func record(novel: Novel, content: ChapterContent, paragraph: Int, percent: Int) throws {
        try ready()
        guard content.chapter.novelKey == novel.key, content.chapter.key.source == novel.key.source else { throw StorageFailure.message("阅读进度来源不匹配") }
        var next = state
        next.progress[novel.id] = ReadingProgress(novelKey: novel.key, chapterKey: content.chapter.key, volumeKey: content.chapter.volumeKey, paragraphIndex: max(0, paragraph), percent: min(100, max(0, percent)))
        next.history.removeAll { $0.id == novel.id }
        next.history.insert(HistoryEntry(novel: novel, chapterKey: content.chapter.key, chapterTitle: content.chapter.title, readAt: ISO8601DateFormatter().string(from: Date())), at: 0)
        next.updated.remove(novel.id)
        try commit(next)
    }
    func deleteLocalHistory(_ key: ResourceKey) throws {
        try ready(); var next = state
        next.history.removeAll { $0.id == key.id }; next.progress[key.id] = nil
        try commit(next)
    }
    func savedChapter(novel: ResourceKey, chapter: ResourceKey) throws -> ChapterContent? {
        try ready()
        guard novel.source == chapter.source else { throw StorageFailure.message("章节来源不匹配") }
        let file = chapterURL(novel, chapter), book = bookURL(novel)
        return try io.sync {
            guard FileManager.default.fileExists(atPath: file.path) else { return nil }
            var content = try StorageIO.decode(ChapterContent.self, from: file)
            guard content.chapter.key == chapter, content.chapter.novelKey == novel else { throw StorageFailure.message("离线章节数据不匹配") }
            if let font = content.fontURL {
                let rebased = book.appendingPathComponent("Assets", isDirectory: true).appendingPathComponent(font.lastPathComponent)
                _ = try FontPayload.format(Data(contentsOf: rebased)); content.fontURL = rebased
            }
            // iOS may change the container path after an app restore.
            content.bodyHTML = Self.rebaseFiles(content.bodyHTML, assets: book.appendingPathComponent("Assets", isDirectory: true))
            try Self.validateImages(content.bodyHTML, assets: book.appendingPathComponent("Assets", isDirectory: true))
            return content
        }
    }
    /// Only files owned by this download become inert data URLs for the reader.
    /// Export continues to use savedChapter's local URLs, without base64 copies.
    func readableChapter(novel: ResourceKey, chapter: ResourceKey) async throws -> ChapterContent? {
        guard let saved = try savedChapter(novel: novel, chapter: chapter) else { return nil }
        let assets = bookURL(novel).appendingPathComponent("Assets", isDirectory: true).standardizedFileURL
        return try await io.perform {
            var content = saved
            let regex = try NSRegularExpression(pattern: "file://[^\"'<>\\s]+")
            let original = content.bodyHTML
            var html = original
            for match in regex.matches(in: original, range: NSRange(original.startIndex..., in: original)).reversed() {
                guard let range = Range(match.range, in: html), let url = URL(string: String(html[range])),
                      url.standardizedFileURL.deletingLastPathComponent() == assets else {
                    throw StorageFailure.message("离线插图不在此书拥有的目录内")
                }
                let data = try Data(contentsOf: url)
                let type = try BookAsset.imageType(data)
                html.replaceSubrange(range, with: "data:\(type.media);base64,\(data.base64EncodedString())")
            }
            content.bodyHTML = html
            return content
        }
    }
    func enqueue(novel: Novel, volumes: [Volume], source: any NovelSource, fonts: FontStore) async throws {
        try ready()
        guard source.sourceID == novel.key.source, !novel.key.remoteID.isEmpty,
              volumes.allSatisfy({ $0.novelKey == novel.key && $0.key.source == novel.key.source }) else { throw StorageFailure.message("下载来源或分卷不匹配") }
        if let existing = jobs[novel.id] { try await existing.value; return }
        var next = state
        if let index = next.records.firstIndex(where: { $0.id == novel.id }) {
            next.records[index].novel = novel
            if !volumes.isEmpty {
                var merged = next.records[index].volumes
                for volume in volumes where !merged.contains(where: { $0.key == volume.key }) { merged.append(volume) }
                next.records[index].volumes = merged
                next.records[index].catalogComplete = false
                next.records[index].volumesComplete = true
            }
            else {
                next.records[index].volumesComplete = false
                next.records[index].catalogComplete = false
            }
            next.records[index].status = "queued"; next.records[index].error = nil
        } else { next.records.append(OfflineRecord(novel: novel, volumes: Self.uniqueVolumes(volumes), volumesComplete: !volumes.isEmpty)) }
        try commit(next)
        try await schedule(novel.key, source: source, fonts: fonts)
    }
    func retry(_ key: ResourceKey, source: any NovelSource, fonts: FontStore) async throws {
        try ready()
        guard source.sourceID == key.source, let index = state.records.firstIndex(where: { $0.id == key.id }) else { throw StorageFailure.message("找不到离线书籍") }
        if let running = jobs[key.id] { try await running.value; return }
        var next = state; next.records[index].status = "queued"; next.records[index].error = nil
        try commit(next)
        try await schedule(key, source: source, fonts: fonts)
    }
    private func schedule(_ key: ResourceKey, source: any NovelSource, fonts: FontStore) async throws {
        let generation = generations[key.id, default: 0]
        let task = Task { @MainActor [weak self] in
            guard let self = self else { throw CancellationError() }
            let slot = UUID()
            try await self.acquireSlot(slot)
            defer { self.releaseSlot(slot) }
            try self.current(key, generation)
            try await self.download(key, generation: generation, source: source, fonts: fonts)
        }
        jobs[key.id] = task
        do {
            try await withTaskCancellationHandler(operation: { try await task.value }, onCancel: { task.cancel() })
            if generations[key.id, default: 0] == generation { jobs[key.id] = nil }
        } catch {
            if generations[key.id, default: 0] == generation {
                jobs[key.id] = nil
                if error is CancellationError, let index = state.records.firstIndex(where: { $0.id == key.id }), state.records[index].status == "queued" {
                    var next = state; next.records[index].error = "下载中断，将在恢复后继续"; try commit(next)
                }
            }
            throw error
        }
    }
    private func acquireSlot(_ id: UUID) async throws {
        try Task.checkCancellation()
        if activeJob == nil { activeJob = id; return }
        try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                if Task.isCancelled { continuation.resume(throwing: CancellationError()) }
                else { slotWaiters.append((id, continuation)) }
            }
        }, onCancel: {
            Task { @MainActor [weak self] in
                guard let self = self, let index = self.slotWaiters.firstIndex(where: { $0.id == id }) else { return }
                self.slotWaiters.remove(at: index).continuation.resume(throwing: CancellationError())
            }
        })
    }
    private func releaseSlot(_ id: UUID) {
        guard activeJob == id else { return }
        if slotWaiters.isEmpty { activeJob = nil }
        else {
            let next = slotWaiters.removeFirst(); activeJob = next.id; next.continuation.resume()
        }
    }
    func resume(sources: [SourceID: any NovelSource], fonts: FontStore) async {
        let pending = state.records.filter { $0.status == "queued" || $0.status == "downloading" }
        for record in pending where jobs[record.id] == nil {
            guard let source = sources[record.novel.key.source] else {
                storageError = "无法恢复下载：缺少来源 \(record.novel.key.source.title)"
                continue
            }
            do { try await retry(record.novel.key, source: source, fonts: fonts) }
            catch is CancellationError { if Task.isCancelled { return } }
            catch { storageError = error.localizedDescription }
        }
    }
    /// Cancels a job but preserves completed checkpoints. It stays failed until explicit retry.
    func cancel(_ key: ResourceKey) throws {
        try ready(); generations[key.id, default: 0] &+= 1
        diskEpochs.advance(key.id)
        jobs.removeValue(forKey: key.id)?.cancel()
        guard let index = state.records.firstIndex(where: { $0.id == key.id }) else { return }
        var next = state; next.records[index].status = "failed"; next.records[index].error = "下载已取消，可重试继续"
        try commit(next)
    }
    func delete(_ key: ResourceKey) throws {
        try ready(); generations[key.id, default: 0] &+= 1
        diskEpochs.advance(key.id)
        jobs.removeValue(forKey: key.id)?.cancel()
        // Persist the tombstone before removing owned files. A stale task cannot recreate it.
        var next = state; next.records.removeAll { $0.id == key.id }
        try commit(next)
        let directory = bookURL(key)
        try io.sync { if FileManager.default.fileExists(atPath: directory.path) { try FileManager.default.removeItem(at: directory) } }
    }
    private func update(_ record: OfflineRecord) throws {
        guard let index = state.records.firstIndex(where: { $0.id == record.id }) else { throw CancellationError() }
        var next = state; next.records[index] = record; try commit(next)
    }
    private func download(_ key: ResourceKey, generation: UInt64, source: any NovelSource, fonts: FontStore) async throws {
        guard var record = state.records.first(where: { $0.id == key.id }) else { throw CancellationError() }
        do {
            record.status = "downloading"; record.error = nil; try update(record)
            try await waitForNetwork(); try current(key, generation)
            if !record.volumesComplete {
                var page = 1
                repeat {
                    try await waitForNetwork(); try current(key, generation)
                    let result = try await source.volumes(key: key, page: page)
                    try current(key, generation)
                    guard result.items.allSatisfy({ $0.novelKey == key && $0.key.source == key.source }) else { throw StorageFailure.message("来源返回了其他书籍的分卷") }
                    for volume in result.items where !record.volumes.contains(where: { $0.id == volume.id }) { record.volumes.append(volume) }
                    try update(record)
                    if !result.hasMore { break }
                    page += 1; if page > 1000 { throw StorageFailure.message("分卷分页数量异常") }
                } while true
                record.volumesComplete = true; try update(record)
            }
            if !record.catalogComplete {
                for volume in record.volumes {
                    var page = 1
                    repeat {
                        try await waitForNetwork(); try current(key, generation)
                        let result = try await source.chapters(novel: key, volume: volume.key, page: page)
                        try current(key, generation)
                        guard result.items.allSatisfy({ $0.novelKey == key && $0.volumeKey == volume.key && $0.key.source == key.source }) else { throw StorageFailure.message("来源返回了其他书籍的章节") }
                        for chapter in result.items {
                            if let index = record.chapters.firstIndex(where: { $0.id == chapter.id }) { record.chapters[index] = chapter }
                            else { record.chapters.append(chapter) }
                        }
                        try update(record)
                        if !result.hasMore { break }
                        page += 1; if page > 1000 { throw StorageFailure.message("章节分页数量异常") }
                    } while true
                }
                record.downloadedChapterIDs.formIntersection(Set(record.chapters.filter { !$0.locked }.map(\.id)))
                record.completed = record.downloadedChapterIDs.count
                let order = Dictionary(uniqueKeysWithValues: record.volumes.enumerated().map { ($0.element.id, $0.offset) })
                record.chapters.sort { lhs, rhs in
                    let l = order[lhs.volumeKey.id, default: 0], r = order[rhs.volumeKey.id, default: 0]
                    if l != r { return l < r }
                    return lhs.order == rhs.order ? lhs.id < rhs.id : lhs.order < rhs.order
                }
                record.catalogComplete = true; try update(record)
            }
            guard !record.chapters.filter({ !$0.locked }).isEmpty else { throw StorageFailure.message("没有可下载的未锁定章节") }
            let book = bookURL(key)
            if let cover = record.novel.coverURL {
                let local = try await saveAsset(cover, book: book, generation: generation, key: key)
                try current(key, generation)
                record.novel.coverURL = local; try update(record)
            }
            for chapter in record.chapters where !chapter.locked {
                try await waitForNetwork(); try current(key, generation)
                try current(key, generation)
                let existing: ChapterContent?
                do { existing = try savedChapter(novel: key, chapter: chapter.key) }
                catch {
                    // Retry repairs a corrupt checkpoint from its authenticated source.
                    record.downloadedChapterIDs.remove(chapter.id)
                    record.completed = record.downloadedChapterIDs.count
                    record.error = "重新下载损坏的章节：" + error.localizedDescription
                    try update(record); existing = nil
                }
                if let existing = existing {
                    // A crash after file commit but before manifest commit is recovered here.
                    if existing.chapter.key == chapter.key {
                        record.downloadedChapterIDs.insert(chapter.id)
                        record.completed = record.downloadedChapterIDs.count
                        try update(record); continue
                    }
                }
                try await waitForNetwork(); try current(key, generation)
                var content = try await source.chapter(novel: key, chapter: chapter.key)
                try current(key, generation)
                guard content.chapter.key == chapter.key, content.chapter.novelKey == key else { throw StorageFailure.message("章节下载结果不匹配") }
                guard !content.bodyText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !content.bodyHTML.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                    throw StorageFailure.message("来源返回了空章节，下载未完成")
                }
                if let font = content.fontURL {
                    let owner = "download:\(key.id):\(generation):\(chapter.id)"
                    do {
                        try await waitForNetwork(); try current(key, generation)
                        let pinned = try await fonts.retainOffline(font, owner: owner)
                        try current(key, generation)
                        let data = try await fonts.fontData(pinned)
                        try current(key, generation)
                        let ext = try await io.perform { try FontPayload.format(data).fileExtension }
                        try current(key, generation)
                        let file = book.appendingPathComponent("Assets", isDirectory: true).appendingPathComponent(sha256(font.absoluteString) + "." + ext)
                        let epochs = diskEpochs
                        try await io.perform { try epochs.check(key.id, generation); try StorageIO.atomic(data, to: file) }
                        try current(key, generation)
                        content.fontURL = file
                        try fonts.releaseOffline(owner: owner)
                    } catch {
                        do { try fonts.releaseOffline(owner: owner) }
                        catch { storageError = error.localizedDescription }
                        throw error
                    }
                }
                for image in try Self.imageURLs(content.bodyHTML, baseURL: key.source.website) {
                    let local = try await saveAsset(image, book: book, generation: generation, key: key)
                    try current(key, generation)
                    content.bodyHTML = Self.replacingImage(image, with: local, in: content.bodyHTML, baseURL: key.source.website)
                }
                try current(key, generation)
                let file = chapterURL(key, chapter.key), saved = content
                let epochs = diskEpochs
                try await io.perform { try epochs.check(key.id, generation); try StorageIO.encode(saved, to: file) }
                try current(key, generation)
                record.downloadedChapterIDs.insert(chapter.id)
                record.completed = record.downloadedChapterIDs.count
                record.byteCount = try await io.perform { try StorageIO.size(book) }
                try current(key, generation); try update(record)
            }
            record.status = "complete"; record.error = nil
            record.byteCount = try await io.perform { try StorageIO.size(book) }
            try current(key, generation); try update(record)
        } catch {
            // A deleted/cancelled generation never writes its old record back.
            if generations[key.id, default: 0] == generation, state.records.contains(where: { $0.id == key.id }) {
                record.status = error is CancellationError ? "queued" : "failed"
                record.error = error is CancellationError ? "下载中断，将在恢复后继续" : error.localizedDescription
                do { try update(record) } catch { storageError = error.localizedDescription; throw error }
            }
            throw error
        }
    }
    private func saveAsset(_ url: URL, book: URL, generation: UInt64, key: ResourceKey) async throws -> URL {
        if url.isFileURL {
            let local = book.appendingPathComponent("Assets", isDirectory: true).appendingPathComponent(url.lastPathComponent)
            let exists = try await io.perform {
                guard FileManager.default.fileExists(atPath: local.path) else { return false }
                _ = try BookAsset.imageType(Data(contentsOf: local)); return true
            }
            try current(key, generation)
            if exists { return local }
        }
        let data: Data
        if url.scheme?.lowercased() == "data" { data = try await io.perform { try Self.imageDataURL(url) } }
        else if url.isFileURL { data = try await io.perform { try Data(contentsOf: url) } }
        else {
            try await waitForNetwork(); try current(key, generation)
            data = try await http.bytes(url)
        }
        try current(key, generation)
        let type = try await io.perform { try BookAsset.imageType(data) }
        try current(key, generation)
        let destination = book.appendingPathComponent("Assets", isDirectory: true).appendingPathComponent(sha256(url.absoluteString) + "." + type.fileExtension)
        let epochs = diskEpochs
        try await io.perform { try epochs.check(key.id, generation); try StorageIO.atomic(data, to: destination) }
        try current(key, generation)
        return destination
    }
    func observeShelf(_ novels: [Novel]) throws {
        try ready(); var next = state
        for novel in novels {
            let value = Snapshot(novel)
            if let previous = next.snapshots[novel.id], value != previous,
               value.volumes > previous.volumes || value.chapters > previous.chapters || value.words > previous.words || value.unread > previous.unread {
                next.updated.insert(novel.id)
            }
            next.snapshots[novel.id] = value
        }
        try commit(next)
    }
    func markAllUpdatesSeen() throws { try ready(); var next = state; next.updated.removeAll(); try commit(next) }
    func usage() -> Int64 {
        let books = root.appendingPathComponent("Books", isDirectory: true)
        do { return try io.sync { try StorageIO.size(books) } }
        catch { storageError = error.localizedDescription; return 0 }
    }
    func export(_ key: ResourceKey, epub: Bool, fonts: FontStore) async throws -> URL {
        try ready()
        guard var record = state.records.first(where: { $0.id == key.id }) else { throw StorageFailure.message("找不到离线书籍") }
        let book = bookURL(key), directory = root.appendingPathComponent("Exports", isDirectory: true)
        let generation = generations[key.id, default: 0]
        let destination = directory.appendingPathComponent(Self.safeName(record.novel.title) + "-" + String(sha256(key.id).prefix(8)) + (epub ? ".epub" : ".txt"))
        var chapters: [ChapterContent] = []
        for chapter in record.chapters where record.downloadedChapterIDs.contains(chapter.id) && !chapter.locked {
            guard let content = try savedChapter(novel: key, chapter: chapter.key) else { throw StorageFailure.message("离线章节缺失，无法完整导出") }
            chapters.append(content)
        }
        guard !chapters.isEmpty else { throw StorageFailure.message("没有可导出的离线章节") }
        if let cover = record.novel.coverURL, cover.isFileURL { record.novel.coverURL = book.appendingPathComponent("Assets", isDirectory: true).appendingPathComponent(cover.lastPathComponent) }
        let snapshot = record, contents = chapters, epochs = diskEpochs
        try await io.perform {
            let data: Data
            if epub { data = try EPUBWriter.make(book: snapshot, chapters: contents) }
            else { data = Data(EPUBWriter.text(book: snapshot, chapters: contents).utf8) }
            try epochs.check(key.id, generation)
            try StorageIO.atomic(data, to: destination)
        }
        try current(key, generation)
        return destination
    }
    /// A nil bookmark means the caller will present the local URL in a share sheet.
    func saveExport(_ url: URL, bookmark: Data?) throws -> URL {
        try ready()
        guard let bookmark = bookmark else { return url }
        let exports = root.appendingPathComponent("Exports", isDirectory: true).standardizedFileURL
        guard url.isFileURL, url.deletingLastPathComponent().standardizedFileURL == exports else { throw StorageFailure.message("只能保存本应用生成的导出文件") }
        return try io.sync {
            var stale = false
            let folder = try URL(resolvingBookmarkData: bookmark, options: [.withoutUI], relativeTo: nil, bookmarkDataIsStale: &stale)
            guard !stale else { throw StorageFailure.message("导出目录权限已过期，请重新选择目录") }
            guard folder.startAccessingSecurityScopedResource() else { throw StorageFailure.message("无法访问导出目录，请重新授权") }
            defer { folder.stopAccessingSecurityScopedResource() }
            let values = try folder.resourceValues(forKeys: [.isDirectoryKey])
            guard values.isDirectory == true else { throw StorageFailure.message("请选择导出文件夹") }
            let destination = folder.appendingPathComponent(url.lastPathComponent)
            if destination.standardizedFileURL == url.standardizedFileURL { return destination }
            let data = try Data(contentsOf: url)
            try StorageIO.atomic(data, to: destination)
            return destination
        }
    }
    private static func safeName(_ text: String) -> String {
        let invalid = CharacterSet(charactersIn: "/\\:\0\n\r")
        let name = text.components(separatedBy: invalid).joined(separator: "_").trimmingCharacters(in: .whitespacesAndNewlines)
        return String((name.isEmpty ? "小说" : name).prefix(80))
    }
    nonisolated private static func imageURLs(_ html: String, baseURL: URL) throws -> [URL] {
        let images = try NSRegularExpression(pattern: "(?is)<img\\b[^>]*>")
        let source = try NSRegularExpression(pattern: "(?is)\\bsrc\\s*=\\s*(?:\"([^\"]+)\"|'([^']+)'|([^\\s>]+))")
        var result: [URL] = []
        for match in images.matches(in: html, range: NSRange(html.startIndex..., in: html)) {
            guard let range = Range(match.range, in: html) else { continue }
            let tag = String(html[range])
            guard let value = source.firstMatch(in: tag, range: NSRange(tag.startIndex..., in: tag)),
                  let capture = (1...3).first(where: { value.range(at: $0).location != NSNotFound }),
                  let raw = Range(value.range(at: capture), in: tag),
                  let url = Self.resolveImage(String(tag[raw]), baseURL: baseURL),
                  ["http", "https", "data"].contains(url.scheme?.lowercased() ?? "") else { throw StorageFailure.message("章节插图地址无效或不受支持") }
            if !result.contains(url) { result.append(url) }
        }
        return result
    }
    nonisolated private static func resolveImage(_ raw: String, baseURL: URL) -> URL? {
        URL(string: raw.replacingOccurrences(of: "&amp;", with: "&"), relativeTo: baseURL)?.absoluteURL
    }
    nonisolated private static func imageDataURL(_ url: URL) throws -> Data {
        let value = url.absoluteString
        guard value.utf8.count <= 48 * 1024 * 1024, let comma = value.firstIndex(of: ",") else { throw StorageFailure.message("内嵌图片过大或格式无效") }
        let metadata = value[..<comma].lowercased(), payload = String(value[value.index(after: comma)...])
        guard metadata.hasPrefix("data:image/") else { throw StorageFailure.message("内嵌资源不是图片") }
        if metadata.hasSuffix(";base64"), let data = Data(base64Encoded: payload) { return data }
        guard !metadata.hasSuffix(";base64"), let decoded = payload.removingPercentEncoding else { throw StorageFailure.message("内嵌图片编码无效") }
        return Data(decoded.utf8)
    }
    nonisolated private static func replacingImage(_ image: URL, with local: URL, in html: String, baseURL: URL) -> String {
        guard let regex = try? NSRegularExpression(pattern: "(?is)<img\\b[^>]*?\\bsrc\\s*=\\s*(?:\"([^\"]+)\"|'([^']+)'|([^\\s>]+))") else { return html }
        var result = html
        for match in regex.matches(in: html, range: NSRange(html.startIndex..., in: html)).reversed() {
            guard let capture = (1...3).first(where: { match.range(at: $0).location != NSNotFound }),
                  let original = Range(match.range(at: capture), in: html), let target = Range(match.range(at: capture), in: result) else { continue }
            guard Self.resolveImage(String(html[original]), baseURL: baseURL) == image else { continue }
            result.replaceSubrange(target, with: local.absoluteString)
        }
        return result
    }
    nonisolated private static func rebaseFiles(_ html: String, assets: URL) -> String {
        guard let regex = try? NSRegularExpression(pattern: "file://[^\"'<>\\s]+") else { return html }
        var result = html
        for match in regex.matches(in: html, range: NSRange(html.startIndex..., in: html)).reversed() {
            guard let range = Range(match.range, in: result), let url = URL(string: String(result[range])) else { continue }
            result.replaceSubrange(range, with: assets.appendingPathComponent(url.lastPathComponent).absoluteString)
        }
        return result
    }
    nonisolated private static func validateImages(_ html: String, assets: URL) throws {
        let regex = try NSRegularExpression(pattern: "file://[^\"'<>\\s]+")
        for match in regex.matches(in: html, range: NSRange(html.startIndex..., in: html)) {
            guard let range = Range(match.range, in: html), let source = URL(string: String(html[range])) else { continue }
            let file = assets.appendingPathComponent(source.lastPathComponent)
            _ = try BookAsset.imageType(Data(contentsOf: file))
        }
    }
}
