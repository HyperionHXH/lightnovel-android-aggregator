import Foundation
import ImageIO
import UniformTypeIdentifiers

struct BookAsset {
    var path: String
    var media: String
    var data: Data
    static func imageType(_ data: Data) throws -> (fileExtension: String, media: String) {
        guard data.count > 8, data.count <= 32 * 1024 * 1024 else { throw StorageFailure.message("图片文件大小无效") }
        guard let source = CGImageSourceCreateWithData(data as CFData, nil), CGImageSourceGetCount(source) > 0,
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? NSNumber,
              let height = properties[kCGImagePropertyPixelHeight] as? NSNumber,
              width.int64Value > 0, height.int64Value > 0, width.int64Value <= 100_000, height.int64Value <= 100_000,
              width.int64Value * height.int64Value <= 100_000_000 else { throw StorageFailure.message("图片数据损坏或尺寸过大") }
        let bytes = [UInt8](data.prefix(16))
        if bytes.starts(with: [137, 80, 78, 71, 13, 10, 26, 10]) { return ("png", "image/png") }
        if bytes.starts(with: [255, 216, 255]) { return ("jpg", "image/jpeg") }
        if bytes.starts(with: Array("GIF87a".utf8)) || bytes.starts(with: Array("GIF89a".utf8)) { return ("gif", "image/gif") }
        if bytes.starts(with: Array("RIFF".utf8)), bytes.count >= 12, Array(bytes[8..<12]) == Array("WEBP".utf8) { return ("webp", "image/webp") }
        throw StorageFailure.message("图片格式不受支持或下载内容无效")
    }
    static func epubImage(_ data: Data) throws -> (data: Data, fileExtension: String, media: String) {
        let type = try imageType(data)
        guard type.fileExtension == "webp" else { return (data, type.fileExtension, type.media) }
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { throw StorageFailure.message("无法读取 WebP 插图") }
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(output, UTType.png.identifier as CFString, 1, nil) else { throw StorageFailure.message("无法转换 EPUB 插图") }
        CGImageDestinationAddImage(destination, image, nil)
        guard CGImageDestinationFinalize(destination) else { throw StorageFailure.message("EPUB 插图转换失败") }
        return (output as Data, "png", "image/png")
    }
}

/// EPUB3 with a dependency-free, stored ZIP container and deterministic resource paths.
enum EPUBWriter {
    static func make(book: OfflineRecord, chapters: [ChapterContent]) throws -> Data {
        guard !chapters.isEmpty else { throw StorageFailure.message("没有可导出的章节") }
        var zip = StoredZIP(), assets: [String: BookAsset] = [:]
        var manifest: [String] = [], spine: [String] = [], navigation: [String] = []
        try zip.add("mimetype", data: Data("application/epub+zip".utf8))
        try zip.add("META-INF/container.xml", text: "<?xml version=\"1.0\" encoding=\"UTF-8\"?><container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>")
        try zip.add("OEBPS/styles.css", text: "body{line-height:1.7;overflow-wrap:anywhere}img{max-width:100%;height:auto}p{text-indent:2em}h1,h2{text-indent:0}rt{font-size:.6em}")
        manifest.append("<item id=\"css\" href=\"styles.css\" media-type=\"text/css\"/>")
        manifest.append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>")
        var coverPath: String?
        if let url = book.novel.coverURL, url.isFileURL {
            let image = try BookAsset.epubImage(Data(contentsOf: url)), data = image.data, type = image
            let path = "images/cover." + type.fileExtension
            assets[path] = BookAsset(path: path, media: type.media, data: data); coverPath = path
        }
        for (index, content) in chapters.enumerated() {
            try Task.checkCancellation()
            let path = String(format: "chapter-%05d.xhtml", index + 1), id = "chapter-\(index)"
            var fontCSS = ""
            if let url = content.fontURL {
                guard url.isFileURL else { throw StorageFailure.message("离线章节字体尚未保存，无法导出") }
                let data = try Data(contentsOf: url), type = try FontPayload.format(data)
                let path = "fonts/" + sha256(data) + "." + type.fileExtension
                assets[path] = BookAsset(path: path, media: type.media, data: data)
                fontCSS = "@font-face{font-family:ChapterFont;src:url('\(path)')}body{font-family:ChapterFont,serif}"
            }
            let body: String
            if content.bodyHTML.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                body = content.bodyText.components(separatedBy: .newlines).map { "<p>" + xml($0) + "</p>" }.joined(separator: "\n")
            } else { body = try xhtml(content.bodyHTML, assets: &assets) }
            let document = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"zh-CN\" lang=\"zh-CN\"><head><meta charset=\"UTF-8\"/><title>\(xml(content.chapter.title))</title><link rel=\"stylesheet\" type=\"text/css\" href=\"styles.css\"/><style type=\"text/css\">\(fontCSS)</style></head><body><h1>\(xml(content.volumeTitle))</h1><h2>\(xml(content.chapter.title))</h2>\(body)</body></html>"
            try zip.add("OEBPS/" + path, text: document)
            manifest.append("<item id=\"\(id)\" href=\"\(path)\" media-type=\"application/xhtml+xml\"/>")
            spine.append("<itemref idref=\"\(id)\"/>")
            navigation.append("<li><a href=\"\(path)\">\(xml(content.volumeTitle)) · \(xml(content.chapter.title))</a></li>")
        }
        for (index, path) in assets.keys.sorted().enumerated() {
            let asset = assets[path]!
            try zip.add("OEBPS/" + path, data: asset.data)
            let properties = path == coverPath ? " properties=\"cover-image\"" : ""
            manifest.append("<item id=\"asset-\(index)\" href=\"\(xml(path))\" media-type=\"\(asset.media)\"\(properties)/>")
        }
        let nav = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\" xml:lang=\"zh-CN\"><head><title>\(xml(book.novel.title))</title></head><body><nav epub:type=\"toc\" id=\"toc\"><h1>\(xml(book.novel.title))</h1><ol>\(navigation.joined())</ol></nav></body></html>"
        try zip.add("OEBPS/nav.xhtml", text: nav)
        let authors = book.novel.authors.map { "<dc:creator>\(xml($0))</dc:creator>" }.joined()
        let formatter = ISO8601DateFormatter(); formatter.formatOptions = [.withInternetDateTime]
        let modified = formatter.string(from: Date())
        let opf = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"book-id\" xml:lang=\"zh-CN\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"book-id\">urn:mixn:\(sha256(book.id))</dc:identifier><dc:title>\(xml(book.novel.title))</dc:title><dc:language>zh-CN</dc:language>\(authors)<meta property=\"dcterms:modified\">\(modified)</meta></metadata><manifest>\(manifest.joined())</manifest><spine>\(spine.joined())</spine></package>"
        try zip.add("OEBPS/content.opf", text: opf)
        return try zip.finish()
    }
    static func text(book: OfflineRecord, chapters: [ChapterContent]) -> String {
        var result = book.novel.title
        if !book.novel.authors.isEmpty { result += "\n作者：" + book.novel.authors.joined(separator: "、") }
        for chapter in chapters {
            let text = chapter.bodyText.trimmingCharacters(in: .whitespacesAndNewlines)
            result += "\n\n【\(chapter.volumeTitle)】\n\(chapter.chapter.title)\n\n" + (text.isEmpty ? plain(chapter.bodyHTML) : text)
        }
        return result + "\n"
    }
    static func xml(_ text: String) -> String {
        let clean = text.unicodeScalars.filter { $0.value == 9 || $0.value == 10 || $0.value == 13 || ($0.value >= 32 && $0.value != 0xFFFE && $0.value != 0xFFFF) }.map(String.init).joined()
        return clean.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;").replacingOccurrences(of: "\"", with: "&quot;").replacingOccurrences(of: "'", with: "&apos;")
    }
    private static func decodeEntities(_ value: String) -> String {
        let named: [String: String] = ["amp": "&", "lt": "<", "gt": ">", "quot": "\"", "apos": "'", "nbsp": " ", "ensp": " ", "emsp": " ", "hellip": "…", "mdash": "—", "ndash": "–", "ldquo": "“", "rdquo": "”", "lsquo": "‘", "rsquo": "’", "copy": "©"]
        guard let regex = try? NSRegularExpression(pattern: "&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);") else { return value }
        var result = value
        for match in regex.matches(in: value, range: NSRange(value.startIndex..., in: value)).reversed() {
            guard let keyRange = Range(match.range(at: 1), in: value), let range = Range(match.range, in: result) else { continue }
            let key = String(value[keyRange])
            var replacement = named[key]
            if key.hasPrefix("#") {
                let hex = key.lowercased().hasPrefix("#x")
                if let number = UInt32(key.dropFirst(hex ? 2 : 1), radix: hex ? 16 : 10), let scalar = UnicodeScalar(number) { replacement = String(scalar) }
            }
            if let replacement = replacement { result.replaceSubrange(range, with: replacement) }
        }
        return result
    }
    private static func plain(_ html: String) -> String {
        var text = html.replacingOccurrences(of: "(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>", with: "", options: .regularExpression)
        text = text.replacingOccurrences(of: "(?is)<img\\b[^>]*>", with: "\n[插图]\n", options: .regularExpression)
        text = text.replacingOccurrences(of: "(?i)</?(p|div|h[1-6]|li|br)\\b[^>]*>", with: "\n", options: .regularExpression)
        text = text.replacingOccurrences(of: "<[^>]*>", with: "", options: .regularExpression)
        return decodeEntities(text).replacingOccurrences(of: "\n{3,}", with: "\n\n", options: .regularExpression).trimmingCharacters(in: .whitespacesAndNewlines)
    }
    /// Restrict source HTML to a well-formed XHTML subset; no scripts, remote resources or event handlers.
    private static func xhtml(_ input: String, assets: inout [String: BookAsset]) throws -> String {
        let html = input.replacingOccurrences(of: "(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>", with: "", options: .regularExpression)
        let tokenRegex = try NSRegularExpression(pattern: "<[^>]*>|[^<]+|<")
        let nameRegex = try NSRegularExpression(pattern: "^<\\s*(/?)\\s*([a-zA-Z][a-zA-Z0-9]*)")
        let srcRegex = try NSRegularExpression(pattern: "(?i)\\bsrc\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
        let allowed: Set<String> = ["p", "div", "span", "b", "strong", "em", "i", "u", "s", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "blockquote", "ruby", "rt", "rp", "sub", "sup"]
        var output = "", stack: [String] = []
        for token in tokenRegex.matches(in: html, range: NSRange(html.startIndex..., in: html)) {
            guard let range = Range(token.range, in: html) else { continue }
            let text = String(html[range]), full = NSRange(text.startIndex..., in: text)
            guard text.hasPrefix("<"), let match = nameRegex.firstMatch(in: text, range: full), let nameRange = Range(match.range(at: 2), in: text) else {
                if !text.hasPrefix("<!--") { output += xml(decodeEntities(text)) }; continue
            }
            let name = text[nameRange].lowercased(), closing = match.range(at: 1).length > 0
            if closing {
                if let index = stack.lastIndex(of: name) {
                    while stack.count > index { output += "</" + stack.removeLast() + ">" }
                }
                continue
            }
            if name == "br" { output += "<br/>"; continue }
            if name == "hr" { output += "<hr/>"; continue }
            if name == "img" {
                guard let src = srcRegex.firstMatch(in: text, range: full) else { throw StorageFailure.message("章节图片地址缺失") }
                let capture = (1...3).first { src.range(at: $0).location != NSNotFound }!
                guard let valueRange = Range(src.range(at: capture), in: text), let url = URL(string: decodeEntities(String(text[valueRange]))), url.isFileURL else { throw StorageFailure.message("章节图片尚未下载，无法导出") }
                let image = try BookAsset.epubImage(Data(contentsOf: url)), data = image.data, type = image
                let path = "images/" + sha256(data) + "." + type.fileExtension
                assets[path] = BookAsset(path: path, media: type.media, data: data)
                output += "<img src=\"\(path)\" alt=\"插图\"/>"; continue
            }
            guard allowed.contains(name) else { continue }
            if name == "p", let index = stack.lastIndex(of: "p") { while stack.count > index { output += "</" + stack.removeLast() + ">" } }
            output += "<" + name + ">"; stack.append(name)
            if text.hasSuffix("/>") { output += "</" + stack.removeLast() + ">" }
        }
        while !stack.isEmpty { output += "</" + stack.removeLast() + ">" }
        return output
    }
}

/// ZIP32 writer. The mimetype member is caller-inserted first, uncompressed, without extras.
private struct StoredZIP {
    private struct Entry { var name: Data; var crc: UInt32; var size: UInt32; var offset: UInt32 }
    private var data = Data()
    private var entries: [Entry] = []
    mutating func add(_ path: String, text: String) throws { try add(path, data: Data(text.utf8)) }
    mutating func add(_ path: String, data content: Data) throws {
        guard !path.hasPrefix("/"), !path.split(separator: "/").contains(".."), !entries.contains(where: { $0.name == Data(path.utf8) }),
              content.count <= Int(UInt32.max), data.count <= Int(UInt32.max), entries.count < Int(UInt16.max) else { throw StorageFailure.message("EPUB 超过 ZIP32 大小或路径限制") }
        let name = Data(path.utf8)
        guard name.count <= Int(UInt16.max) else { throw StorageFailure.message("EPUB 资源路径过长") }
        let entry = Entry(name: name, crc: Self.crc32(content), size: UInt32(content.count), offset: UInt32(data.count))
        data.le32(0x04034b50); data.le16(20); data.le16(0x0800); data.le16(0)
        data.le16(0); data.le16(0x0021); data.le32(entry.crc); data.le32(entry.size); data.le32(entry.size)
        data.le16(UInt16(name.count)); data.le16(0); data.append(name); data.append(content)
        entries.append(entry)
    }
    mutating func finish() throws -> Data {
        guard data.count <= Int(UInt32.max) else { throw StorageFailure.message("EPUB 文件过大") }
        let start = UInt32(data.count)
        for entry in entries {
            data.le32(0x02014b50); data.le16(20); data.le16(20); data.le16(0x0800); data.le16(0)
            data.le16(0); data.le16(0x0021); data.le32(entry.crc); data.le32(entry.size); data.le32(entry.size)
            data.le16(UInt16(entry.name.count)); data.le16(0); data.le16(0); data.le16(0); data.le16(0)
            data.le32(0); data.le32(entry.offset); data.append(entry.name)
        }
        guard data.count <= Int(UInt32.max) else { throw StorageFailure.message("EPUB 文件过大") }
        let size = UInt32(data.count) - start
        data.le32(0x06054b50); data.le16(0); data.le16(0); data.le16(UInt16(entries.count)); data.le16(UInt16(entries.count)); data.le32(size); data.le32(start); data.le16(0)
        return data
    }
    private static let crcTable: [UInt32] = (0..<256).map { value in
        var crc = UInt32(value)
        for _ in 0..<8 { crc = (crc & 1) == 0 ? crc >> 1 : (crc >> 1) ^ 0xedb88320 }
        return crc
    }
    private static func crc32(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xffffffff
        for byte in data { crc = (crc >> 8) ^ crcTable[Int((crc ^ UInt32(byte)) & 0xff)] }
        return crc ^ 0xffffffff
    }
}
private extension Data {
    mutating func le16(_ number: UInt16) { append(UInt8(truncatingIfNeeded: number)); append(UInt8(truncatingIfNeeded: number >> 8)) }
    mutating func le32(_ number: UInt32) { le16(UInt16(truncatingIfNeeded: number)); le16(UInt16(truncatingIfNeeded: number >> 16)) }
}
