import Foundation

/// All synchronous storage APIs run their file work on this private utility queue.
/// The synchronous facade is retained for the reader's existing store contract.
final class StorageIO {
    private let queue = DispatchQueue(label: "io.mixn.storage.\(UUID().uuidString)", qos: .utility)
    func sync<T>(_ work: @escaping () throws -> T) throws -> T {
        // DispatchQueue.sync may execute on the caller's thread; enqueue explicitly.
        let completed = DispatchSemaphore(value: 0)
        var result: Result<T, Error>?
        queue.async { result = Result { try work() }; completed.signal() }
        completed.wait()
        return try result!.get()
    }
    func perform<T>(_ work: @escaping () throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { continuation in
            queue.async { continuation.resume(with: Result { try work() }) }
        }
    }
    static func root(_ supplied: URL?, component: String) -> URL {
        let base = supplied ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Mixn", isDirectory: true)
        return base.appendingPathComponent(component, isDirectory: true)
    }
    static func create(_ url: URL) throws {
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    }
    static func bytes(_ url: URL) throws -> Int64 {
        (try url.resourceValues(forKeys: [.fileSizeKey])).fileSize.map(Int64.init) ?? 0
    }
    static func size(_ root: URL) throws -> Int64 {
        guard FileManager.default.fileExists(atPath: root.path) else { return 0 }
        var traversalError: Error?
        guard let enumerator = FileManager.default.enumerator(at: root, includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey], options: [], errorHandler: { _, error in traversalError = error; return false }) else {
            throw StorageFailure.message("无法读取存储目录")
        }
        var result: Int64 = 0
        for case let url as URL in enumerator {
            let values = try url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
            if values.isRegularFile == true { result += Int64(values.fileSize ?? 0) }
        }
        if let error = traversalError { throw error }
        return result
    }
    static func atomic(_ data: Data, to url: URL) throws {
        try create(url.deletingLastPathComponent())
        try data.write(to: url, options: [.atomic])
    }
    static func encode<T: Encodable>(_ value: T, to url: URL) throws {
        let encoder = PropertyListEncoder(); encoder.outputFormat = .binary
        try atomic(encoder.encode(value), to: url)
    }
    static func decode<T: Decodable>(_ type: T.Type, from url: URL) throws -> T {
        try PropertyListDecoder().decode(type, from: Data(contentsOf: url))
    }
}

enum StorageFailure: Error, LocalizedError {
    case message(String)
    var errorDescription: String? { switch self { case .message(let text): return text } }
}

/// Main-actor cancellation and queued file writes share this generation fence.
final class StorageEpoch {
    private let lock = NSLock()
    private var values: [String: UInt64] = [:]
    func value(_ key: String) -> UInt64 { lock.lock(); defer { lock.unlock() }; return values[key, default: 0] }
    func advance(_ key: String) { lock.lock(); defer { lock.unlock() }; values[key, default: 0] &+= 1 }
    func check(_ key: String, _ expected: UInt64) throws {
        guard value(key) == expected else { throw CancellationError() }
    }
}

/// Reproducible online data only. Downloads and installed/offline fonts have other owners.
actor ContentCache {
    struct Token: Equatable {
        fileprivate let global: UInt64
        fileprivate let scope: UInt64
        fileprivate let scopeHash: String
    }
    private struct Entry: Codable {
        var scope: String
        var key: String
        var expiry: Date?
        var data: Data
    }
    private struct DiskEntry {
        var scope: String
        var expiry: Date?
        var bytes: Int64
        var accessed: Date
    }
    private struct MemoryEntry {
        var data: Data
        var expiry: Date?
        var accessed: Date
        var scope: String
    }
    private let root: URL
    private let memoryLimit = 8 * 1024 * 1024
    private let diskLimit: Int64 = 96 * 1024 * 1024
    private var disk: [String: DiskEntry] = [:]
    private var memory: [String: MemoryEntry] = [:]
    private var loaded = false
    private var globalGeneration: UInt64 = 0
    private var scopeGenerations: [String: UInt64] = [:]
    private(set) var lastError: String?
    init(rootURL: URL? = nil) {
        let base = rootURL ?? FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("Mixn", isDirectory: true)
        root = StorageIO.root(base, component: "TemporaryContent")
    }

    func token(scope: String = "public") -> Token {
        let hash = sha256(scope)
        return Token(global: globalGeneration, scope: scopeGenerations[hash, default: 0], scopeHash: hash)
    }
    private func identifier(_ key: String, _ scope: String) -> String { sha256(scope) + "-" + sha256(key) }
    private func file(_ id: String) -> URL { root.appendingPathComponent(id + ".mixncache") }
    private func load() throws {
        guard !loaded else { return }
        try StorageIO.create(root)
        let files = try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey])
        for url in files where url.pathExtension == "mixncache" {
            do {
                guard try StorageIO.bytes(url) <= diskLimit else { try FileManager.default.removeItem(at: url); continue }
                let entry = try StorageIO.decode(Entry.self, from: url)
                let id = url.deletingPathExtension().lastPathComponent
                guard id == entry.scope + "-" + entry.key else { try FileManager.default.removeItem(at: url); continue }
                if let expiry = entry.expiry, expiry <= Date() { try FileManager.default.removeItem(at: url); continue }
                let values = try url.resourceValues(forKeys: [.contentModificationDateKey, .fileSizeKey])
                disk[id] = DiskEntry(scope: entry.scope, expiry: entry.expiry, bytes: Int64(values.fileSize ?? 0), accessed: values.contentModificationDate ?? .distantPast)
            } catch {
                lastError = error.localizedDescription
                // A corrupt reproducible cache record can be removed; no other owners are traversed.
                try FileManager.default.removeItem(at: url)
            }
        }
        loaded = true
        try trimDisk()
    }
    func read(key: String, scope: String = "public") -> Data? {
        do {
            try load()
            let id = identifier(key, scope), now = Date()
            if var item = memory[id] {
                if let expiry = item.expiry, expiry <= now { try remove(id); return nil }
                item.accessed = now; memory[id] = item
                if var metadata = disk[id] { metadata.accessed = now; disk[id] = metadata }
                try FileManager.default.setAttributes([.modificationDate: now], ofItemAtPath: file(id).path)
                return item.data
            }
            guard var metadata = disk[id] else { return nil }
            if let expiry = metadata.expiry, expiry <= now { try remove(id); return nil }
            let entry = try StorageIO.decode(Entry.self, from: file(id))
            guard entry.scope == sha256(scope), entry.key == sha256(key) else { throw StorageFailure.message("缓存键不匹配") }
            metadata.accessed = now; disk[id] = metadata
            try FileManager.default.setAttributes([.modificationDate: now], ofItemAtPath: file(id).path)
            insertMemory(entry.data, id: id, scope: entry.scope, expiry: entry.expiry)
            return entry.data
        } catch { lastError = error.localizedDescription; return nil }
    }
    /// Capture token before network I/O to prevent a response refilling a cleared scope.
    func write(_ data: Data, key: String, scope: String = "public", ttl: TimeInterval? = nil, token requestToken: Token? = nil) throws {
        try load()
        if let requestToken = requestToken, requestToken != token(scope: scope) { return }
        let id = identifier(key, scope)
        let expiry = ttl.map { Date().addingTimeInterval(max(0, $0)) }
        if ttl != nil && ttl! <= 0 { try remove(id); return }
        let entry = Entry(scope: sha256(scope), key: sha256(key), expiry: expiry, data: data)
        let encoder = PropertyListEncoder(); encoder.outputFormat = .binary
        let encoded = try encoder.encode(entry)
        guard encoded.count <= diskLimit else { try remove(id); return }
        // The old reproducible record is removed first, including for an atomic rewrite.
        // Reserving the entire new file also accounts for Data.write's temporary file.
        try remove(id)
        try trimDisk(reserving: Int64(encoded.count))
        try StorageIO.atomic(encoded, to: file(id))
        disk[id] = DiskEntry(scope: entry.scope, expiry: expiry, bytes: Int64(encoded.count), accessed: Date())
        insertMemory(data, id: id, scope: entry.scope, expiry: expiry)
    }
    func clear(scope: String? = nil) throws {
        if let scope = scope { scopeGenerations[sha256(scope), default: 0] &+= 1 }
        else { globalGeneration &+= 1 }
        try load()
        let hash = scope.map(sha256)
        for id in Array(disk.keys) where hash == nil || disk[id]?.scope == hash { try remove(id) }
        for id in Array(memory.keys) where hash == nil || memory[id]?.scope == hash { memory[id] = nil }
    }
    func usage() -> (disk: Int64, memory: Int64) {
        do { try load() } catch { lastError = error.localizedDescription }
        return (disk.values.reduce(0) { $0 + $1.bytes }, Int64(memory.values.reduce(0) { $0 + $1.data.count }))
    }
    private func insertMemory(_ data: Data, id: String, scope: String, expiry: Date?) {
        memory[id] = nil
        guard data.count <= memoryLimit else { return }
        var size = memory.values.reduce(0) { $0 + $1.data.count }
        for victim in memory.keys.sorted(by: { memory[$0]!.accessed < memory[$1]!.accessed }) {
            if size + data.count <= memoryLimit { break }
            size -= memory[victim]!.data.count; memory[victim] = nil
        }
        memory[id] = MemoryEntry(data: data, expiry: expiry, accessed: Date(), scope: scope)
    }
    private func remove(_ id: String) throws {
        let url = file(id)
        if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
        disk[id] = nil; memory[id] = nil
    }
    private func trimDisk(reserving: Int64 = 0, excluding: String? = nil) throws {
        var size = disk.values.reduce(0) { $0 + $1.bytes }
        for victim in disk.keys.sorted(by: { disk[$0]!.accessed < disk[$1]!.accessed }) where victim != excluding {
            if size + reserving <= diskLimit { break }
            let bytes = disk[victim]!.bytes
            try remove(victim); size -= bytes
        }
    }
}
