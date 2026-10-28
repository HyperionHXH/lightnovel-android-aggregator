import XCTest
import Foundation
@testable import Mixn

final class StorageTests: XCTestCase {
    private func root() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("MixnStorageTests-" + UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        addTeardownBlock { try FileManager.default.removeItem(at: url) }
        return url
    }
    private func font() -> Data {
        // A minimal sfnt with one bounded table, enough to exercise container validation.
        Data([0, 1, 0, 0, 0, 1, 0, 16, 0, 0, 0, 0,
              99, 109, 97, 112, 0, 0, 0, 0, 0, 0, 0, 28, 0, 0, 0, 4,
              0, 0, 0, 0])
    }
    private func chapterContent(_ chapter: Chapter, novel: Novel) -> ChapterContent {
        ChapterContent(chapter: chapter, novelTitle: novel.title, volumeTitle: "正文", bodyText: "离线测试正文")
    }
    private func offlineRecord(novel: Novel, volume: Volume, chapters: [Chapter], downloaded: Set<String> = []) -> OfflineRecord {
        OfflineRecord(novel: novel, volumes: [volume], chapters: chapters, completed: downloaded.count,
                      status: "queued", downloadedChapterIDs: downloaded, catalogComplete: true, volumesComplete: true)
    }
    private func seedLibrary(_ location: URL, records: [OfflineRecord], checkpoints: [ResourceKey: ChapterContent] = [:]) throws {
        struct SavedState: Encodable {
            var records: [OfflineRecord]
            var progress: [String: ReadingProgress] = [:]
            var history: [HistoryEntry] = []
            var updated: Set<String> = []
            var snapshots: [String: String] = [:]
            var wifiOnly = true
        }
        let library = location.appendingPathComponent("Library", isDirectory: true)
        try StorageIO.encode(SavedState(records: records), to: library.appendingPathComponent("library.plist"))
        for (chapterKey, content) in checkpoints {
            let book = library.appendingPathComponent("Books", isDirectory: true)
                .appendingPathComponent(sha256(content.chapter.novelKey.id), isDirectory: true)
            let file = book.appendingPathComponent("Chapters", isDirectory: true)
                .appendingPathComponent(sha256(chapterKey.id) + ".plist")
            try StorageIO.encode(content, to: file)
        }
    }
    @MainActor func testResumeDoesNotRestartRecordCancelledWhileEarlierDownloadRuns() async throws {
        let location = try root()
        let aKey = ResourceKey(.kingdom, "resume-a"), bKey = ResourceKey(.kingdom, "resume-b")
        let aNovel = Novel(key: aKey, title: "A"), bNovel = Novel(key: bKey, title: "B")
        let aVolume = Volume(key: ResourceKey(.kingdom, "resume-a-volume"), novelKey: aKey, title: "正文")
        let bVolume = Volume(key: ResourceKey(.kingdom, "resume-b-volume"), novelKey: bKey, title: "正文")
        let aChapter = Chapter(key: ResourceKey(.kingdom, "resume-a-chapter"), novelKey: aKey, volumeKey: aVolume.key, title: "A")
        let bChapter = Chapter(key: ResourceKey(.kingdom, "resume-b-chapter"), novelKey: bKey, volumeKey: bVolume.key, title: "B")
        try seedLibrary(location, records: [
            offlineRecord(novel: aNovel, volume: aVolume, chapters: [aChapter]),
            offlineRecord(novel: bNovel, volume: bVolume, chapters: [bChapter])
        ])
        let library = LibraryStore(rootURL: location)
        try library.setWifiOnly(false)
        let source = DownloadLifecycleFixtureSource(
            contents: [aChapter.id: chapterContent(aChapter, novel: aNovel), bChapter.id: chapterContent(bChapter, novel: bNovel)],
            heldChapters: [aChapter.id]
        )
        let fonts = FontStore(rootURL: location)
        let resuming = Task { await library.resume(sources: [.kingdom: source], fonts: fonts) }
        await source.waitForChapterRequest(aChapter.key)

        try library.cancel(bKey)
        source.finishChapter(aChapter.key, with: .success(chapterContent(aChapter, novel: aNovel)))
        await resuming.value

        XCTAssertEqual(library.records.first(where: { $0.id == aKey.id })?.status, "complete")
        XCTAssertEqual(source.chapterRequestCount(for: bChapter.key), 0)
        XCTAssertEqual(library.records.first(where: { $0.id == bKey.id })?.status, "failed")
    }
    @MainActor func testDependencyCancellationFailsRetryablyAndKeepsCompletedCheckpoint() async throws {
        let location = try root(), key = ResourceKey(.kingdom, "dependency-cancel")
        let novel = Novel(key: key, title: "依赖中断")
        let volume = Volume(key: ResourceKey(.kingdom, "dependency-volume"), novelKey: key, title: "正文")
        let completed = Chapter(key: ResourceKey(.kingdom, "completed"), novelKey: key, volumeKey: volume.key, title: "已完成")
        let interrupted = Chapter(key: ResourceKey(.kingdom, "interrupted"), novelKey: key, volumeKey: volume.key, title: "待下载")
        let saved = chapterContent(completed, novel: novel)
        try seedLibrary(location,
                        records: [offlineRecord(novel: novel, volume: volume, chapters: [completed, interrupted], downloaded: [completed.id])],
                        checkpoints: [completed.key: saved])
        let library = LibraryStore(rootURL: location)
        try library.setWifiOnly(false)
        let source = DownloadLifecycleFixtureSource(
            contents: [interrupted.id: chapterContent(interrupted, novel: novel)],
            heldChapters: [interrupted.id]
        )
        let fonts = FontStore(rootURL: location)
        let retrying = Task { try await library.retry(key, source: source, fonts: fonts) }
        await source.waitForChapterRequest(interrupted.key)
        source.finishChapter(interrupted.key, with: .failure(CancellationError()))
        do {
            try await retrying.value
            XCTFail("The interrupted dependency should propagate its cancellation")
        } catch is CancellationError {
        }
        XCTAssertFalse(source.consumerTaskWasCancelled ?? true)
        let record = try XCTUnwrap(library.records.first(where: { $0.id == key.id }))
        XCTAssertEqual(record.status, "failed")
        XCTAssertEqual(record.downloadedChapterIDs, [completed.id])
        XCTAssertEqual(record.completed, 1)
        XCTAssertEqual(try library.savedChapter(novel: key, chapter: completed.key), saved)

        try await library.retry(key, source: source, fonts: fonts)
        let retried = try XCTUnwrap(library.records.first(where: { $0.id == key.id }))
        XCTAssertEqual(retried.status, "complete")
        XCTAssertEqual(retried.downloadedChapterIDs, [completed.id, interrupted.id])
        XCTAssertEqual(source.chapterRequestCount(for: completed.key), 0)
        XCTAssertEqual(source.chapterRequestCount(for: interrupted.key), 2)
    }
    func testCacheScopeGenerationTTLAndRestoration() async throws {
        let location = try root(), cache = ContentCache(rootURL: location)
        let old = await cache.token(scope: "private:A")
        try await cache.write(Data("A".utf8), key: "same", scope: "private:A")
        try await cache.write(Data("B".utf8), key: "same", scope: "private:B")
        try await cache.clear(scope: "private:A")
        try await cache.write(Data("stale".utf8), key: "same", scope: "private:A", token: old)
        let deleted = await cache.read(key: "same", scope: "private:A")
        let retained = await cache.read(key: "same", scope: "private:B")
        XCTAssertNil(deleted); XCTAssertEqual(retained, Data("B".utf8))
        try await cache.write(Data([1]), key: "expired", ttl: 0)
        let expired = await cache.read(key: "expired")
        XCTAssertNil(expired)
        let restored = ContentCache(rootURL: location)
        let persisted = await restored.read(key: "same", scope: "private:B")
        XCTAssertEqual(persisted, retained)
        try await cache.clear()
        let empty = await cache.usage()
        XCTAssertEqual(empty.disk, 0); XCTAssertEqual(empty.memory, 0)
    }
    func testCachePhysicalBudgetAndLRU() async throws {
        let location = try root(), cache = ContentCache(rootURL: location)
        let payload = Data(repeating: 0x5a, count: 7 * 1024 * 1024)
        for index in 0..<14 { try await cache.write(payload, key: String(index)) }
        let usage = await cache.usage()
        XCTAssertLessThanOrEqual(usage.disk, 96 * 1024 * 1024)
        XCTAssertLessThanOrEqual(usage.memory, 8 * 1024 * 1024)
        let oldest = await cache.read(key: "0"), newest = await cache.read(key: "13")
        XCTAssertNil(oldest); XCTAssertEqual(newest, payload)
        let files = location.appendingPathComponent("TemporaryContent", isDirectory: true)
        let physical = try StorageIO.size(files)
        XCTAssertEqual(physical, usage.disk)
    }
    @MainActor func testReadingHistoryUpdatesAndSourceIsolationPersist() throws {
        let location = try root(), library = LibraryStore(rootURL: location)
        let first = Novel(key: ResourceKey(.kingdom, "same"), title: "国度", chapterCount: 2)
        let other = Novel(key: ResourceKey(.shelf, "same"), title: "书架", chapterCount: 2)
        let volume = ResourceKey(.kingdom, "v")
        let chapter = Chapter(key: ResourceKey(.kingdom, "c"), novelKey: first.key, volumeKey: volume, title: "第一章")
        let content = ChapterContent(chapter: chapter, novelTitle: first.title, volumeTitle: "卷一", bodyText: "正文")
        try library.observeShelf([first, other])
        var changed = first; changed.chapterCount = 3
        try library.observeShelf([changed])
        XCTAssertEqual(library.updated, [first.id])
        try library.record(novel: first, content: content, paragraph: 7, percent: 51)
        XCTAssertTrue(library.updated.isEmpty)
        let restored = LibraryStore(rootURL: location)
        XCTAssertEqual(restored.progress[first.id]?.paragraphIndex, 7)
        XCTAssertEqual(restored.localHistory.first?.chapterTitle, "第一章")
        XCTAssertNil(restored.progress[other.id])
        try restored.deleteLocalHistory(first.key)
        let again = LibraryStore(rootURL: location)
        XCTAssertTrue(again.localHistory.isEmpty); XCTAssertNil(again.progress[first.id])
        XCTAssertNil(again.storageError)
    }
    @MainActor func testFontOwnershipSurvivesTemporaryClearAndRestart() async throws {
        let location = try root(), source = location.appendingPathComponent("source.ttf")
        try font().write(to: source)
        let fonts = FontStore(rootURL: location)
        let temporary = try await fonts.prepare(source, offline: false)
        XCTAssertTrue(FileManager.default.fileExists(atPath: temporary.path))
        let permanent = try await fonts.retainOffline(source, owner: "book:A")
        try fonts.clearTemporary()
        XCTAssertFalse(FileManager.default.fileExists(atPath: temporary.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: permanent.path))
        let restored = FontStore(rootURL: location)
        XCTAssertGreaterThan(restored.usage().offline, 0)
        try restored.releaseOffline(owner: "book:A")
        XCTAssertFalse(FileManager.default.fileExists(atPath: permanent.path))
        XCTAssertNil(restored.storageError)
    }
    @MainActor func testDownloadRecoveryAndDeleteCannotRefillOwnedFiles() async throws {
        let location = try root(), key = ResourceKey(.shelf, "offline")
        let novel = Novel(key: key, title: "离线书籍")
        let volume = Volume(key: ResourceKey(.shelf, "volume"), novelKey: key, title: "第一卷")
        let chapter = Chapter(key: ResourceKey(.shelf, "chapter"), novelKey: key, volumeKey: volume.key, title: "第一章")
        var content = ChapterContent(chapter: chapter, novelTitle: novel.title, volumeTitle: volume.title, bodyText: "离线正文")
        let record = OfflineRecord(novel: novel, volumes: [volume], chapters: [chapter], status: "downloading", catalogComplete: true, volumesComplete: true)
        struct SavedState: Encodable {
            var records: [OfflineRecord]
            var progress: [String: ReadingProgress] = [:]
            var history: [HistoryEntry] = []
            var updated: Set<String> = []
            var snapshots: [String: String] = [:]
            var wifiOnly = true
        }
        let libraryRoot = location.appendingPathComponent("Library", isDirectory: true)
        let book = libraryRoot.appendingPathComponent("Books", isDirectory: true).appendingPathComponent(sha256(key.id), isDirectory: true)
        let checkpoint = book.appendingPathComponent("Chapters", isDirectory: true).appendingPathComponent(sha256(chapter.id) + ".plist")
        let image = Data(base64Encoded: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a+/sAAAAASUVORK5CYII=")!
        let imageURL = book.appendingPathComponent("Assets/illustration.png")
        try StorageIO.atomic(image, to: imageURL)
        content.bodyHTML = "<p>离线正文</p><img src=\"\(imageURL.absoluteString)\">"
        try StorageIO.encode(content, to: checkpoint)
        try StorageIO.encode(SavedState(records: [record]), to: libraryRoot.appendingPathComponent("library.plist"))
        let restored = LibraryStore(rootURL: location)
        XCTAssertEqual(restored.records.first?.status, "queued")
        XCTAssertEqual(restored.records.first?.completed, 1)
        XCTAssertEqual(try restored.savedChapter(novel: key, chapter: chapter.key)?.bodyText, "离线正文")
        let loadedReadable = try await restored.readableChapter(novel: key, chapter: chapter.key)
        let readable = try XCTUnwrap(loadedReadable)
        let blocks = ReaderDocument.blocks(html: readable.bodyHTML, text: readable.bodyText)
        let embedded = try XCTUnwrap(blocks.first { $0.kind == .image })
        XCTAssertEqual(Data(base64Encoded: String(embedded.text.split(separator: ",", maxSplits: 1)[1])), image)
        XCTAssertFalse(readable.bodyHTML.contains("file://"))
        XCTAssertTrue(try restored.savedChapter(novel: key, chapter: chapter.key)!.bodyHTML.contains("file://"), "EPUB keeps owned files instead of duplicate inline data")
        try restored.setWifiOnly(false)
        XCTAssertFalse(LibraryStore(rootURL: location).wifiOnly)
        let cache = ContentCache(rootURL: location)
        try await cache.write(Data([1]), key: "online")
        try await cache.clear()
        XCTAssertNotNil(try restored.savedChapter(novel: key, chapter: chapter.key))
        try Data("corrupt committed checkpoint".utf8).write(to: checkpoint)
        let committed = LibraryStore(rootURL: location)
        do {
            _ = try await committed.readableChapter(novel: key, chapter: chapter.key)
            XCTFail("A committed manifest must not bypass chapter validation before reading")
        } catch is DecodingError {
        } catch {
            XCTFail("Expected a corrupt checkpoint error, got \(error)")
        }
        try StorageIO.encode(content, to: checkpoint)
        try restored.delete(key)
        XCTAssertFalse(FileManager.default.fileExists(atPath: book.path))
        XCTAssertTrue(LibraryStore(rootURL: location).records.isEmpty)
        XCTAssertNil(try restored.savedChapter(novel: key, chapter: chapter.key))
        let epochs = StorageEpoch()
        let old = epochs.value(key.id); epochs.advance(key.id)
        XCTAssertThrowsError(try epochs.check(key.id, old))
    }
    func testFontRejectsHTMLAndOutOfBoundsTables() throws {
        XCTAssertThrowsError(try FontPayload.format(Data("<html><body>502 Bad Gateway</body></html>".utf8)))
        var bytes = font(); bytes[23] = 255
        XCTAssertThrowsError(try FontPayload.format(bytes))
        XCTAssertEqual(try FontPayload.format(font()).fileExtension, "ttf")
    }
    func testEPUBHasStoredMimetypeValidXMLFontsAndUTF8Text() throws {
        let location = try root(), file = location.appendingPathComponent("font.ttf")
        try font().write(to: file)
        let novel = Novel(key: ResourceKey(.shelf, "b"), title: "标题 & 小说", authors: ["作者"])
        let chapter = Chapter(key: ResourceKey(.shelf, "c"), novelKey: novel.key, volumeKey: ResourceKey(.shelf, "v"), title: "章节 <一>")
        let content = ChapterContent(chapter: chapter, novelTitle: novel.title, volumeTitle: "第一卷", bodyText: "", bodyHTML: "<p>甲&nbsp;&amp;乙<br>第二行<p>未闭合<script>unsafe()</script>", fontURL: file)
        let book = OfflineRecord(novel: novel, chapters: [chapter], completed: 1, status: "complete", downloadedChapterIDs: [chapter.id])
        let epub = try EPUBWriter.make(book: book, chapters: [content])
        let files = try unzipStored(epub)
        XCTAssertEqual(files.first?.0, "mimetype")
        XCTAssertEqual(files.first?.1, Data("application/epub+zip".utf8))
        XCTAssertTrue(files.contains { $0.0.hasPrefix("OEBPS/fonts/") && $0.1 == font() })
        for (path, data) in files where path.hasSuffix(".xhtml") || path.hasSuffix(".xml") || path.hasSuffix(".opf") {
            let parser = XMLParser(data: data)
            XCTAssertTrue(parser.parse(), "\(path): \(parser.parserError?.localizedDescription ?? "")")
        }
        let opf = String(data: files.first { $0.0 == "OEBPS/content.opf" }!.1, encoding: .utf8)!
        XCTAssertTrue(opf.contains("<dc:language>zh-CN</dc:language>"))
        XCTAssertTrue(opf.contains("dcterms:modified"))
        let xhtml = String(data: files.first { $0.0.contains("chapter-") }!.1, encoding: .utf8)!
        XCTAssertFalse(xhtml.contains("unsafe()")); XCTAssertTrue(xhtml.contains("@font-face"))
        let text = EPUBWriter.text(book: book, chapters: [content])
        XCTAssertTrue(text.contains("甲 &乙")); XCTAssertFalse(text.contains("unsafe()"))
    }
    /// Parse local ZIP records independently of the writer; verify store mode and CRC32.
    private func unzipStored(_ data: Data) throws -> [(String, Data)] {
        func u16(_ offset: Int) -> Int { Int(data[offset]) | (Int(data[offset + 1]) << 8) }
        func u32(_ offset: Int) -> UInt32 { UInt32(u16(offset)) | (UInt32(u16(offset + 2)) << 16) }
        var offset = 0, files: [(String, Data)] = []
        while offset + 30 <= data.count, u32(offset) == 0x04034b50 {
            XCTAssertEqual(u16(offset + 8), 0)
            let length = Int(u32(offset + 18)), nameLength = u16(offset + 26), extraLength = u16(offset + 28)
            let start = offset + 30 + nameLength + extraLength
            guard start + length <= data.count else { throw StorageFailure.message("Truncated ZIP") }
            let name = String(data: data[(offset + 30)..<(offset + 30 + nameLength)], encoding: .utf8)!
            let bytes = Data(data[start..<(start + length)])
            var crc: UInt32 = 0xffffffff
            for byte in bytes {
                crc ^= UInt32(byte)
                for _ in 0..<8 { crc = crc & 1 == 0 ? crc >> 1 : (crc >> 1) ^ 0xedb88320 }
            }
            XCTAssertEqual(crc ^ 0xffffffff, u32(offset + 14), name)
            files.append((name, bytes)); offset = start + length
        }
        XCTAssertEqual(u32(offset), 0x02014b50)
        return files
    }
}
@MainActor private final class DownloadLifecycleFixtureSource: NovelSource {
    let sourceID: SourceID = .kingdom
    let feeds: [Feed] = [.popular]
    var session = AccountSession()
    var supportsComments: Bool { false }
    private let contents: [String: ChapterContent]
    private var heldChapters: Set<String>
    private var requestCounts: [String: Int] = [:]
    private var observers: [String: [CheckedContinuation<Void, Never>]] = [:]
    private var continuations: [String: CheckedContinuation<ChapterContent, Error>] = [:]
    private(set) var consumerTaskWasCancelled: Bool?

    init(contents: [String: ChapterContent], heldChapters: Set<String> = []) {
        self.contents = contents
        self.heldChapters = heldChapters
    }
    func restoreSession() async throws -> AccountSession { session }
    func login(identifier: String, password: String) async throws -> AccountSession { throw SourceFailure.unsupported("Unused fixture operation") }
    func logout() async throws { session = AccountSession() }
    func profile() async throws -> SourceProfile { throw SourceFailure.unsupported("Unused fixture operation") }
    func discover(feed: Feed, page: Int, pageSize: Int) async throws -> Page<Novel> { throw SourceFailure.unsupported("Unused fixture operation") }
    func search(query: String, page: Int, pageSize: Int) async throws -> Page<Novel> { throw SourceFailure.unsupported("Unused fixture operation") }
    func detail(key: ResourceKey) async throws -> NovelDetail { throw SourceFailure.unsupported("Unused fixture operation") }
    func volumes(key: ResourceKey, page: Int) async throws -> Page<Volume> { throw SourceFailure.unsupported("Unused fixture operation") }
    func chapters(novel: ResourceKey, volume: ResourceKey, page: Int) async throws -> Page<Chapter> { throw SourceFailure.unsupported("Unused fixture operation") }
    func chapter(novel: ResourceKey, chapter: ResourceKey) async throws -> ChapterContent {
        requestCounts[chapter.id, default: 0] += 1
        let pendingObservers = observers.removeValue(forKey: chapter.id) ?? []
        for observer in pendingObservers { observer.resume() }
        if heldChapters.contains(chapter.id) {
            do {
                return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<ChapterContent, Error>) in
                    continuations[chapter.id] = continuation
                }
            } catch {
                consumerTaskWasCancelled = Task.isCancelled
                throw error
            }
        }
        guard let content = contents[chapter.id] else { throw SourceFailure.parsing("Fixture chapter missing") }
        return content
    }
    func shelf() async throws -> [Novel] { throw SourceFailure.unsupported("Unused fixture operation") }
    func setShelf(key: ResourceKey, add: Bool) async throws { throw SourceFailure.unsupported("Unused fixture operation") }
    func history(page: Int) async throws -> Page<HistoryEntry> { throw SourceFailure.unsupported("Unused fixture operation") }
    func deleteHistory(key: ResourceKey) async throws { throw SourceFailure.unsupported("Unused fixture operation") }
    func saveProgress(_ progress: ReadingProgress) async throws { throw SourceFailure.unsupported("Unused fixture operation") }
    func rewards() async throws -> RewardStatus { throw SourceFailure.unsupported("Unused fixture operation") }
    func claimReward() async throws -> RewardResult { throw SourceFailure.unsupported("Unused fixture operation") }

    func waitForChapterRequest(_ key: ResourceKey) async {
        if requestCounts[key.id, default: 0] > 0 { return }
        await withCheckedContinuation { observers[key.id, default: []].append($0) }
    }
    func finishChapter(_ key: ResourceKey, with result: Result<ChapterContent, Error>) {
        heldChapters.remove(key.id)
        guard let continuation = continuations.removeValue(forKey: key.id) else { preconditionFailure("No chapter request is waiting") }
        continuation.resume(with: result)
    }
    func chapterRequestCount(for key: ResourceKey) -> Int { requestCounts[key.id, default: 0] }
}
