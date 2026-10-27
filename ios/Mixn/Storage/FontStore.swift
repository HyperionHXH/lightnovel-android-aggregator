import Foundation
import Combine

struct FontDefinition: Identifiable, Hashable {
    var id: String
    var name: String
    var url: URL
    var sha256: String? = nil
    var fallbackURLs: [URL] = []
}
struct InstalledFont: Identifiable, Codable, Hashable {
    var id: String
    var name: String
    var url: URL
}

/// Font files stay in their actual container; WebKit supports WOFF2 without conversion.
enum FontPayload {
    static let maximumBytes = 32 * 1024 * 1024
    static func format(_ data: Data) throws -> (fileExtension: String, media: String) {
        guard data.count >= 12, data.count <= maximumBytes else { throw StorageFailure.message("字体文件大小无效") }
        let bytes = [UInt8](data.prefix(48))
        func number(_ offset: Int, _ count: Int) -> UInt64 {
            guard offset + count <= bytes.count else { return 0 }
            return bytes[offset..<(offset + count)].reduce(0) { ($0 << 8) | UInt64($1) }
        }
        let magic = number(0, 4)
        if magic == 0x774F4632 || magic == 0x774F4646 {
            let header = magic == 0x774F4632 ? 48 : 44
            guard data.count >= header, number(8, 4) == UInt64(data.count),
                  number(12, 2) > 0, number(12, 2) <= 4096,
                  number(14, 2) == 0, number(16, 4) >= 12,
                  number(16, 4) <= 256 * 1024 * 1024 else { throw StorageFailure.message("字体 WOFF 文件头损坏") }
            let flavor = number(4, 4)
            guard flavor == 0x00010000 || flavor == 0x4F54544F || flavor == 0x74727565 else { throw StorageFailure.message("字体 WOFF 类型无效") }
            if magic == 0x774F4632 {
                guard number(20, 4) > 0, number(20, 4) <= UInt64(data.count - header) else { throw StorageFailure.message("字体 WOFF2 数据损坏") }
                return ("woff2", "font/woff2")
            }
            let count = Int(number(12, 2))
            guard 44 + count * 20 <= data.count else { throw StorageFailure.message("字体 WOFF 目录损坏") }
            try validateTables(data, start: 44, count: count, stride: 20, offsetField: 4, lengthField: 8)
            return ("woff", "font/woff")
        }
        guard magic == 0x00010000 || magic == 0x4F54544F || magic == 0x74727565 else {
            throw StorageFailure.message("下载内容不是受支持的字体（可能是服务器错误页面）")
        }
        let count = Int(number(4, 2))
        guard count > 0, count <= 4096, 12 + count * 16 <= data.count else { throw StorageFailure.message("字体目录损坏") }
        try validateTables(data, start: 12, count: count, stride: 16, offsetField: 8, lengthField: 12)
        return magic == 0x4F54544F ? ("otf", "font/otf") : ("ttf", "font/ttf")
    }
    private static func validateTables(_ data: Data, start: Int, count: Int, stride: Int, offsetField: Int, lengthField: Int) throws {
        func uint(_ at: Int) -> UInt64 { data[at..<(at + 4)].reduce(0) { ($0 << 8) | UInt64($1) } }
        for index in 0..<count {
            let at = start + index * stride, offset = uint(at + offsetField), length = uint(at + lengthField)
            guard offset <= UInt64(data.count), length <= UInt64(data.count), offset + length <= UInt64(data.count) else { throw StorageFailure.message("字体表数据越界") }
        }
    }
}

@MainActor final class FontStore: ObservableObject {
    @Published private(set) var installed: [InstalledFont] = []
    @Published private(set) var storageError: String?
    private struct Manifest: Codable {
        var installed: [InstalledFont] = []
        var pins: [String: Set<String>] = [:]
    }
    private let root: URL
    private let temporary: URL
    private let offline: URL
    private let users: URL
    private let io = StorageIO()
    private let http: HTTPClient
    private var manifest = Manifest()
    private var generation: UInt64 = 0
    private let diskEpochs = StorageEpoch()
    private var pendingPins: [String: Int] = [:]
    private let temporaryLimit: Int64 = 64 * 1024 * 1024
    private var initializationError: Error?
    static let catalog: [FontDefinition] = [
        FontDefinition(id: "source-han-serif-cn", name: "思源宋体", url: URL(string: "https://cdn.jsdelivr.net/gh/adobe-fonts/source-han-serif@7889f11bf31170b5d092a083b357c8c8130f89e0/SubsetOTF/CN/SourceHanSerifCN-Regular.otf")!, sha256: "3754EA669C530E2473354F8F6D9F79680A44D7E26EC7D00EEABEE4A7E0753C5D", fallbackURLs: [URL(string: "https://raw.githubusercontent.com/adobe-fonts/source-han-serif/7889f11bf31170b5d092a083b357c8c8130f89e0/SubsetOTF/CN/SourceHanSerifCN-Regular.otf")!]),
        FontDefinition(id: "source-han-sans-cn", name: "思源黑体", url: URL(string: "https://cdn.jsdelivr.net/gh/adobe-fonts/source-han-sans@a4f7cf94edfb9d7ffbdfc4841de276358bd7e0f2/SubsetOTF/CN/SourceHanSansCN-Regular.otf")!, sha256: "E2BC8A2E7F37474B774FFF8DB758681ECE40BB6947A90D571BCE9DD60671A8E4", fallbackURLs: [URL(string: "https://raw.githubusercontent.com/adobe-fonts/source-han-sans/a4f7cf94edfb9d7ffbdfc4841de276358bd7e0f2/SubsetOTF/CN/SourceHanSansCN-Regular.otf")!]),
        FontDefinition(id: "lxgw-wenkai", name: "霞鹜文楷", url: URL(string: "https://cdn.jsdelivr.net/gh/lxgw/LxgwWenKai@50f4b182415a8c33d9a456df220b66a284e2509b/fonts/TTF/LXGWWenKai-Regular.ttf")!, sha256: "39AD71264B588165B469E35E6AFB162A378DACD1F95348160240BA9038AC3009", fallbackURLs: [URL(string: "https://raw.githubusercontent.com/lxgw/LxgwWenKai/50f4b182415a8c33d9a456df220b66a284e2509b/fonts/TTF/LXGWWenKai-Regular.ttf")!]),
        FontDefinition(id: "genryu-min", name: "源流明体", url: URL(string: "https://cdn.jsdelivr.net/gh/ButTaiwan/genryu-font@030cd4fa3dd6b0607c5e7f35f13977a2e0a10522/otf/TW/GenRyuMin2TW-R.otf")!, sha256: "D244C6115431B2A18B5DFB0066E08381F56E6A33532915A0F517DD3F7063B646", fallbackURLs: [URL(string: "https://raw.githubusercontent.com/ButTaiwan/genryu-font/030cd4fa3dd6b0607c5e7f35f13977a2e0a10522/otf/TW/GenRyuMin2TW-R.otf")!]),
        FontDefinition(id: "glow-sans-sc", name: "未来荧黑", url: URL(string: "https://github.com/welai/glow-sans/releases/download/v0.93/GlowSansSC-Normal-Regular.otf")!, sha256: "CA7D34947401B2FA6DD4FD46676D970A8CC47A40EE60C022EB7DD5068BEE67FF", fallbackURLs: [URL(string: "https://ghproxy.net/https://github.com/welai/glow-sans/releases/download/v0.93/GlowSansSC-Normal-Regular.otf")!]),
        FontDefinition(id: "huiwen-mincho", name: "汇文明朝体", url: URL(string: "https://github.com/bosswnx/huiwenmincho-improved/releases/download/20241203/Huiwenmincho-improved.otf")!, sha256: "1C491750613DE17D007EF9B8B4937B15CDAAA936A6AE2485C5DC0CEE200FB044", fallbackURLs: [URL(string: "https://raw.githubusercontent.com/bosswnx/huiwenmincho-improved/40b9dbb9bfa51bc4979b62d0dcd3bc064c5ea049/Huiwenmincho-improved.otf")!])
    ]
    init(rootURL: URL? = nil, http: HTTPClient = HTTPClient()) {
        root = StorageIO.root(rootURL, component: "Fonts")
        temporary = rootURL == nil
            ? FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("Mixn/ChapterFonts", isDirectory: true)
            : root.appendingPathComponent("Temporary", isDirectory: true)
        offline = root.appendingPathComponent("Offline", isDirectory: true)
        users = root.appendingPathComponent("Installed", isDirectory: true)
        self.http = http
        let root = self.root, temporary = self.temporary, offline = self.offline, users = self.users
        let catalog = Self.catalog
        do {
            var restoreWarnings: [String] = []
            manifest = try io.sync {
                for directory in [temporary, offline, users] { try StorageIO.create(directory) }
                let temporaryFiles = try FileManager.default.contentsOfDirectory(at: temporary, includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey])
                let ordered = try temporaryFiles.map { ($0, try $0.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey])) }.sorted { ($0.1.contentModificationDate ?? .distantPast) < ($1.1.contentModificationDate ?? .distantPast) }
                var temporaryBytes = ordered.reduce(Int64(0)) { $0 + Int64($1.1.fileSize ?? 0) }
                for (file, metadata) in ordered {
                    if temporaryBytes <= 64 * 1024 * 1024 { break }
                    try FileManager.default.removeItem(at: file); temporaryBytes -= Int64(metadata.fileSize ?? 0)
                }
                let url = root.appendingPathComponent("manifest.plist")
                if !FileManager.default.fileExists(atPath: url.path) { return Manifest() }
                var state = try StorageIO.decode(Manifest.self, from: url)
                // Persist filenames, then rebase on the current app-container location.
                state.installed = state.installed.map { item in
                    let url = users.appendingPathComponent(item.url.lastPathComponent)
                    do {
                        let data = try Data(contentsOf: url)
                        _ = try FontPayload.format(data)
                        if let checksum = catalog.first(where: { $0.id == item.id })?.sha256,
                           sha256(data).lowercased() != checksum.lowercased() { throw StorageFailure.message("已安装字体校验失败") }
                    } catch { restoreWarnings.append(item.name + "：" + error.localizedDescription) }
                    return InstalledFont(id: item.id, name: item.name, url: url)
                }
                // Download handoff leases never outlive the queue process; chapters own copies.
                state.pins = state.pins.filter { !$0.key.hasPrefix("download:") }
                let retained = Set(state.pins.values.flatMap { $0 })
                for file in try FileManager.default.contentsOfDirectory(at: offline, includingPropertiesForKeys: nil) where !retained.contains(file.lastPathComponent) {
                    try FileManager.default.removeItem(at: file)
                }
                try StorageIO.encode(state, to: url)
                return state
            }
            installed = manifest.installed
            if !restoreWarnings.isEmpty { storageError = restoreWarnings.joined(separator: "\n") }
        } catch { initializationError = error; storageError = error.localizedDescription }
    }
    private func ready() throws { if let error = initializationError { throw error } }
    private func persist(_ state: Manifest) throws {
        let file = root.appendingPathComponent("manifest.plist")
        try io.sync { try StorageIO.encode(state, to: file) }
        manifest = state; installed = state.installed
    }
    func install(_ definition: FontDefinition) async throws {
        try ready()
        let installEpoch = diskEpochs.value("installed:" + definition.id), epochs = diskEpochs
        guard !definition.id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !definition.name.isEmpty, definition.url.host != nil, ["https", "http"].contains(definition.url.scheme?.lowercased() ?? ""),
              definition.sha256 == nil || (definition.sha256!.count == 64 && definition.sha256!.allSatisfy({ $0.isHexDigit })) else { throw StorageFailure.message("字体信息无效") }
        var bytes: Data?, failure: Error?
        for url in [definition.url] + definition.fallbackURLs {
            do {
                let data = try await http.bytes(url)
                try Task.checkCancellation()
                try await io.perform {
                    _ = try FontPayload.format(data)
                    if let checksum = definition.sha256, sha256(data).lowercased() != checksum.lowercased() { throw StorageFailure.message("字体校验失败，请重新下载") }
                }
                bytes = data; break
            } catch is CancellationError { throw CancellationError() } catch { failure = error }
        }
        guard let data = bytes else { throw failure ?? StorageFailure.message("字体下载失败") }
        let fileExtension = try await io.perform { try FontPayload.format(data).fileExtension }
        let url = users.appendingPathComponent(sha256(definition.id) + "." + fileExtension)
        try epochs.check("installed:" + definition.id, installEpoch)
        try await io.perform { try epochs.check("installed:" + definition.id, installEpoch); try StorageIO.atomic(data, to: url) }
        try epochs.check("installed:" + definition.id, installEpoch)
        var next = manifest
        next.installed.removeAll { $0.id == definition.id }
        next.installed.append(InstalledFont(id: definition.id, name: definition.name, url: url))
        try persist(next)
    }
    func remove(_ id: String) throws {
        diskEpochs.advance("installed:" + id)
        try ready()
        guard let item = installed.first(where: { $0.id == id }) else { return }
        var next = manifest; next.installed.removeAll { $0.id == id }
        try persist(next)
        try io.sync { if FileManager.default.fileExists(atPath: item.url.path) { try FileManager.default.removeItem(at: item.url) } }
    }
    func fontURL(_ id: String) -> URL? {
        guard let item = installed.first(where: { $0.id == id }) else { return nil }
        let checksum = Self.catalog.first(where: { $0.id == id })?.sha256
        do {
            try io.sync {
                let data = try Data(contentsOf: item.url); _ = try FontPayload.format(data)
                if let checksum = checksum, sha256(data).lowercased() != checksum.lowercased() { throw StorageFailure.message("字体校验失败，请重新安装") }
            }
            return item.url
        } catch { storageError = error.localizedDescription; return nil }
    }
    func fontData(_ url: URL) async throws -> Data {
        try await io.perform { let bytes = try Data(contentsOf: url); _ = try FontPayload.format(bytes); return bytes }
    }
    func prepare(_ url: URL, offline: Bool) async throws -> URL {
        if offline { return try await retainOffline(url, owner: "reader-offline") }
        try ready()
        let captured = generation, key = sha256(url.absoluteString)
        if let cached = try await cachedURL(key, directory: self.offline) {
            guard generation == captured else { throw CancellationError() }; return cached
        }
        if let cached = try await cachedURL(key, directory: temporary) {
            guard generation == captured else { throw CancellationError() }; return cached
        }
        let data = try await obtain(url)
        try Task.checkCancellation()
        guard generation == captured else { throw CancellationError() }
        let ext = try await io.perform { try FontPayload.format(data).fileExtension }
        let destination = temporary.appendingPathComponent(key + "." + ext)
        let directory = temporary, limit = temporaryLimit, epochs = diskEpochs
        try await io.perform {
            try epochs.check("temporary", captured)
            if FileManager.default.fileExists(atPath: destination.path) { try FileManager.default.removeItem(at: destination) }
            let files = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.contentModificationDateKey, .fileSizeKey])
            var size = try StorageIO.size(directory)
            let sorted = try files.map { ($0, try $0.resourceValues(forKeys: [.contentModificationDateKey, .fileSizeKey])) }.sorted { ($0.1.contentModificationDate ?? .distantPast) < ($1.1.contentModificationDate ?? .distantPast) }
            for (file, values) in sorted {
                if size + Int64(data.count) <= limit { break }
                try FileManager.default.removeItem(at: file); size -= Int64(values.fileSize ?? 0)
            }
            try StorageIO.atomic(data, to: destination)
        }
        guard generation == captured else { throw CancellationError() }
        return destination
    }
    func retainOffline(_ url: URL, owner: String) async throws -> URL {
        try ready()
        guard !owner.isEmpty else { throw StorageFailure.message("离线字体缺少所有者") }
        let key = sha256(url.absoluteString)
        pendingPins[key, default: 0] += 1
        defer {
            let count = pendingPins[key, default: 1] - 1
            if count == 0 { pendingPins[key] = nil } else { pendingPins[key] = count }
        }
        let destination: URL
        if let existing = try await cachedURL(key, directory: offline) { destination = existing }
        else {
            let data: Data
            if let temporaryURL = try await cachedURL(key, directory: temporary) { data = try await fontData(temporaryURL) }
            else { data = try await obtain(url) }
            try Task.checkCancellation()
            let ext = try await io.perform { try FontPayload.format(data).fileExtension }
            destination = offline.appendingPathComponent(key + "." + ext)
            try await io.perform { try StorageIO.atomic(data, to: destination) }
        }
        do {
            try Task.checkCancellation()
            var next = manifest; next.pins[owner, default: []].insert(destination.lastPathComponent)
            try persist(next)
        } catch {
            if !manifest.pins.values.contains(where: { $0.contains(destination.lastPathComponent) }), pendingPins[key, default: 0] == 1 {
                do { try await io.perform { if FileManager.default.fileExists(atPath: destination.path) { try FileManager.default.removeItem(at: destination) } } }
                catch { storageError = error.localizedDescription }
            }
            throw error
        }
        return destination
    }
    func releaseOffline(owner: String) throws {
        try ready()
        var next = manifest
        let candidates = next.pins.removeValue(forKey: owner) ?? []
        try persist(next)
        let retained = Set(next.pins.values.flatMap { $0 }), directory = offline
        let pending = Set(pendingPins.keys)
        try io.sync {
            for name in candidates.subtracting(retained) where !pending.contains(URL(fileURLWithPath: name).deletingPathExtension().lastPathComponent) {
                let url = directory.appendingPathComponent(name)
                if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
            }
        }
    }
    func clearTemporary() throws {
        try ready(); generation &+= 1
        diskEpochs.advance("temporary")
        let temporary = self.temporary
        try io.sync {
            for url in try FileManager.default.contentsOfDirectory(at: temporary, includingPropertiesForKeys: nil) {
                try FileManager.default.removeItem(at: url)
            }
        }
    }
    func usage() -> (temporary: Int64, offline: Int64) {
        let temporary = self.temporary, offline = self.offline, users = self.users
        do { return try io.sync { (try StorageIO.size(temporary), try StorageIO.size(offline) + StorageIO.size(users)) } }
        catch { storageError = error.localizedDescription; return (0, 0) }
    }
    private func cachedURL(_ key: String, directory: URL) async throws -> URL? {
        try await io.perform {
            let files = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
            guard let file = files.first(where: { $0.deletingPathExtension().lastPathComponent == key }) else { return nil }
            _ = try FontPayload.format(Data(contentsOf: file))
            try FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: file.path)
            return file
        }
    }
    private func obtain(_ url: URL) async throws -> Data {
        let data: Data
        if url.isFileURL { data = try await io.perform { try Data(contentsOf: url) } }
        else {
            guard ["https", "http"].contains(url.scheme?.lowercased() ?? ""), url.host != nil else { throw StorageFailure.message("字体地址无效") }
            data = try await http.bytes(url)
        }
        _ = try await io.perform { try FontPayload.format(data) }
        return data
    }
}
