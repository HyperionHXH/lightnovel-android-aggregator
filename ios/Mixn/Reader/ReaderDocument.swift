import Foundation
import UIKit

/// Pure reader decisions are shared by the WebKit host and geometry/state tests.
enum ReaderDocument {
    enum TapAction: String { case previous, next, controls }
    struct Block: Equatable {
        enum Kind: Equatable { case paragraph, heading, image }
        var kind: Kind
        var text: String
        var width: Int? = nil
        var height: Int? = nil
        var indent: Bool = true
    }
    struct Option: Identifiable { var id: String; var title: String; init(_ id: String, _ title: String) { self.id = id; self.title = title } }
    static let fonts: [Option] = [
        Option("default", "系统默认"), Option("sans", "系统黑体"), Option("serif", "系统宋体"),
        Option("mono", "等宽字体"), Option("cursive", "手写体"), Option("condensed", "紧凑黑体"), Option("rounded", "圆体")
    ]
    static let tapZones: [Option] = [Option("default", "默认"), Option("l_shape", "L 形"), Option("kindle", "Kindle"), Option("both_sides", "两侧"), Option("left_right", "左右"), Option("disabled", "关闭")]
    static let inversions: [Option] = [Option("none", "无"), Option("left_right", "左右"), Option("up_down", "上下"), Option("all", "全部")]
    static let imageScales: [Option] = [Option("fit", "适应屏幕"), Option("fill", "拉伸"), Option("fit_width", "适应宽度"), Option("fit_height", "适应高度"), Option("original", "原始大小"), Option("smart", "智能填充")]

    static func tapAction(zone: String, x: Double, y: Double, inversion: String = "none") -> TapAction {
        var x = min(1, max(0, x.isFinite ? x : 0.5))
        var y = min(1, max(0, y.isFinite ? y : 0.5))
        if inversion == "left_right" || inversion == "all" { x = 1 - x }
        if inversion == "up_down" || inversion == "all" { y = 1 - y }
        let limits: (Double, Double)
        switch zone {
        case "disabled": return .controls
        case "l_shape":
            if y < 0.34 || y > 0.66 {
                if x < 0.34 { return .previous }
                if x > 0.66 { return .next }
            }
            return .controls
        case "kindle": limits = (0.32, 0.68)
        case "both_sides": limits = (0.18, 0.82)
        case "left_right": limits = (0.40, 0.60)
        default: limits = (0.25, 0.75)
        }
        return x < limits.0 ? .previous : x > limits.1 ? .next : .controls
    }
    static func pageCount(extent: Double, viewport: Double) -> Int {
        guard extent.isFinite, viewport.isFinite, viewport > 0 else { return 1 }
        return max(1, Int(ceil(max(0, extent - 1) / viewport)))
    }
    static func pageIndex(offset: Double, viewport: Double, count: Int) -> Int {
        guard offset.isFinite, viewport.isFinite, viewport > 0 else { return 0 }
        return min(max(0, count - 1), max(0, Int((offset / viewport).rounded())))
    }
    static func shouldCommitDrag(translation: Double, width: Double, cancelled: Bool) -> Bool {
        !cancelled && width > 0 && abs(translation) >= width * 0.25
    }
    static func escaped(_ text: String) -> String {
        text.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;").replacingOccurrences(of: "\"", with: "&quot;")
            .replacingOccurrences(of: "'", with: "&#39;")
    }
    static func decoded(_ text: String) -> String {
        let pattern = "&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp|ensp|emsp|ndash|mdash|hellip|lsquo|rsquo|ldquo|rdquo);"
        guard let regex = try? NSRegularExpression(pattern: pattern, options: .caseInsensitive) else { return text }
        let source = text as NSString
        var result = text
        let named = ["amp": "&", "lt": "<", "gt": ">", "quot": "\"", "apos": "'", "nbsp": " ", "ensp": " ", "emsp": "　", "ndash": "–", "mdash": "—", "hellip": "…", "lsquo": "‘", "rsquo": "’", "ldquo": "“", "rdquo": "”"]
        for match in regex.matches(in: text, range: NSRange(location: 0, length: source.length)).reversed() {
            let key = source.substring(with: match.range(at: 1)).lowercased()
            var replacement = named[key]
            if key.hasPrefix("#") {
                let hex = key.hasPrefix("#x")
                if let value = UInt32(key.dropFirst(hex ? 2 : 1), radix: hex ? 16 : 10), let scalar = UnicodeScalar(value), value != 0 {
                    replacement = String(scalar)
                }
            }
            if let replacement = replacement, let range = Range(match.range, in: result) { result.replaceSubrange(range, with: replacement) }
        }
        return result
    }
    static func imageURL(_ raw: String) -> URL? {
        var value = decoded(raw).trimmingCharacters(in: .whitespacesAndNewlines)
        if value.hasPrefix("//") { value = "https:" + value }
        if value.lowercased().hasPrefix("http://") { value = "https://" + value.dropFirst(7) }
        guard !value.contains(where: { $0.isNewline || $0 == "\0" }), let url = URL(string: value) else { return nil }
        if url.scheme?.lowercased() == "https", url.host != nil, url.user == nil, url.password == nil { return url }
        // Only inert raster data is accepted. In particular SVG/data:text and local file URLs never enter WebKit.
        let prefixes = ["data:image/png;base64,", "data:image/jpeg;base64,", "data:image/gif;base64,", "data:image/webp;base64,"]
        if let prefix = prefixes.first(where: { value.lowercased().hasPrefix($0) }), value.utf8.count <= 24 * 1024 * 1024,
           let bytes = Data(base64Encoded: String(value.dropFirst(prefix.count))), !bytes.isEmpty { return url }
        return nil
    }
    private static func attributes(_ token: String) -> [String: String] {
        guard let regex = try? NSRegularExpression(pattern: #"([a-zA-Z][a-zA-Z0-9_-]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s'"=<>`]+))"#) else { return [:] }
        let ns = token as NSString
        var result: [String: String] = [:]
        for match in regex.matches(in: token, range: NSRange(location: 0, length: ns.length)) {
            for group in 2...4 where match.range(at: group).location != NSNotFound {
                result[ns.substring(with: match.range(at: 1)).lowercased()] = decoded(ns.substring(with: match.range(at: group)))
            }
        }
        return result
    }
    static func blocks(html: String, text: String) -> [Block] {
        guard !html.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return textBlocks(text) }
        // Tokenize into a small generated document, never load source markup or source attributes.
        guard let regex = try? NSRegularExpression(pattern: #"<!--[\s\S]*?(?:-->|$)|<[^>]*>|[^<]+|<"#) else { return textBlocks(text) }
        let ns = html as NSString
        let prohibited: Set<String> = ["script", "style", "iframe", "object", "embed", "svg", "math", "template", "noscript", "form", "head"]
        let boundaries: Set<String> = ["p", "div", "section", "article", "li", "blockquote", "h1", "h2", "h3", "h4", "h5", "h6", "tr"]
        var ignored: [String] = []
        var buffer = ""
        var heading = false
        var indent = true
        var result: [Block] = []
        func flush() {
            let value = decoded(buffer).trimmingCharacters(in: .whitespacesAndNewlines)
            if !value.isEmpty { result.append(Block(kind: heading ? .heading : .paragraph, text: value, indent: indent)) }
            buffer = ""
        }
        for match in regex.matches(in: html, range: NSRange(location: 0, length: ns.length)) {
            let token = ns.substring(with: match.range)
            if token.hasPrefix("<!--") { continue }
            guard token.hasPrefix("<"), token.hasSuffix(">") else {
                if ignored.isEmpty { buffer += token }
                continue
            }
            let closing = token.hasPrefix("</")
            let name = token.dropFirst(closing ? 2 : 1).prefix { $0.isLetter || $0.isNumber }.lowercased()
            if !ignored.isEmpty {
                if closing, name == ignored.last { ignored.removeLast() }
                else if !closing, prohibited.contains(name), !token.hasSuffix("/>") { ignored.append(name) }
                continue
            }
            if prohibited.contains(name) {
                if !closing && !token.hasSuffix("/>") && name != "embed" { ignored.append(name) }
                continue
            }
            if boundaries.contains(name) {
                flush()
                heading = !closing && name.first == "h" && name.count == 2
                let attrs = attributes(token)
                indent = !heading && !(attrs["class"] ?? "").contains("no-indent")
            } else if name == "br" { buffer += "\n" }
            else if name == "img" && !closing {
                flush()
                let attrs = attributes(token)
                if let url = ["src", "data-src", "data-original", "data-url"].compactMap({ attrs[$0] }).compactMap(imageURL).first {
                    let width = Int(attrs["img-width"] ?? attrs["width"] ?? "").flatMap { $0 > 0 && $0 < 100000 ? $0 : nil }
                    let height = Int(attrs["img-height"] ?? attrs["height"] ?? "").flatMap { $0 > 0 && $0 < 100000 ? $0 : nil }
                    result.append(Block(kind: .image, text: url.absoluteString, width: width, height: height, indent: false))
                }
            }
        }
        flush()
        return result.isEmpty ? textBlocks(text) : result
    }
    private static func textBlocks(_ text: String) -> [Block] {
        text.components(separatedBy: .newlines).compactMap {
            let line = $0.trimmingCharacters(in: .whitespacesAndNewlines)
            return line.isEmpty ? nil : Block(kind: .paragraph, text: line)
        }
    }
    static func fontMIME(_ data: Data) -> String {
        let signature = String(data: data.prefix(4), encoding: .ascii)
        switch signature { case "wOF2": return "font/woff2"; case "wOFF": return "font/woff"; case "OTTO": return "font/otf"; default: return "font/ttf" }
    }
    static func fontFamily(_ id: String) -> String {
        switch id {
        case "default", "sans": return "-apple-system, BlinkMacSystemFont, sans-serif"
        case "mono": return "ui-monospace, Menlo, monospace"
        case "cursive": return "'Kaiti SC', 'STKaiti', cursive"
        case "condensed": return "'Avenir Next Condensed', 'Arial Narrow', sans-serif"
        case "rounded": return "ui-rounded, 'Arial Rounded MT Bold', sans-serif"
        default: return "'Songti SC', 'STSong', ui-serif, serif"
        }
    }
    static func html(content: ChapterContent, blocks: [Block], settings: ReaderSettings, font: Data?, mandatory: Bool) -> String {
        let embedded: String
        if let font = font { embedded = "@font-face{font-family:MixnEmbedded;src:url('data:\(fontMIME(font));base64,\(font.base64EncodedString())');font-weight:100 900;font-style:normal;font-display:block;}" }
        else { embedded = "" }
        let family = font == nil ? fontFamily(settings.font) : "MixnEmbedded"
        let foreground = settings.theme == .dark ? "#dadada" : "#202020"
        let body = blocks.enumerated().map { index, block -> String in
            if block.kind == .image {
                let dimensions = [block.width.map { "width=\"\($0)\"" }, block.height.map { "height=\"\($0)\"" }].compactMap { $0 }.joined(separator: " ")
                return "<figure data-block=\"\(index)\"><img src=\"\(escaped(block.text))\" \(dimensions) alt=\"插图\" draggable=\"false\"><figcaption hidden>插图</figcaption></figure>"
            }
            let tag = block.kind == .heading ? "h2" : "p"
            return "<\(tag) data-block=\"\(index)\" class=\"\(block.indent ? "indent" : "")\">\(escaped(block.text).replacingOccurrences(of: "\n", with: "<br>"))</\(tag)>"
        }.joined()
        let padding = min(40, max(12, settings.horizontalPadding))
        let imageStyle: String
        switch settings.imageScale {
        case "fill": imageStyle = "width:100%;height:var(--image-height);object-fit:fill;"
        case "fit_width": imageStyle = "width:100%;height:auto;max-height:var(--image-height);object-fit:contain;"
        case "fit_height": imageStyle = "height:var(--image-height);width:auto;max-width:100%;object-fit:contain;"
        case "original": imageStyle = "width:auto;height:auto;max-width:100%;max-height:var(--image-height);object-fit:scale-down;"
        case "smart": imageStyle = "width:100%;height:var(--image-height);object-fit:cover;"
        default: imageStyle = "width:100%;height:auto;max-height:var(--image-height);object-fit:contain;"
        }
        return """
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; img-src https: data:; font-src data:; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"><style>
        \(embedded)
        :root{--pad:\(padding)px;--image-height:calc(100vh - 24px);color-scheme:\(settings.theme == .dark ? "dark" : "light");}
        *{box-sizing:border-box;-webkit-tap-highlight-color:transparent;}html,body{margin:0;padding:0;background:\(settings.theme.cssBackground);color:\(foreground);overflow-anchor:none;}body{visibility:hidden;font-family:\(family);font-size:\(min(32, max(14, settings.fontSize)))px;line-height:\(min(2.2, max(1.2, settings.lineHeight)));-webkit-text-size-adjust:100%;}body.ready{visibility:visible;}
        #content{margin:0 var(--pad);padding:8px 0 12px;overflow-wrap:anywhere;}body.pages{overflow-y:hidden;}body.scroll{overflow-x:hidden;}body.pages #content{height:100vh;width:calc(100vw - 2 * var(--pad));column-width:calc(100vw - 2 * var(--pad));column-gap:calc(2 * var(--pad));column-fill:auto;}body.scroll #content{min-height:100vh;}h1,h2,p,figure{margin:0 0 14px;}h1{font-size:1.24em;}h2{font-size:1.18em;}p.indent{text-indent:2em;}p{orphans:2;widows:2;}figure{break-inside:avoid;page-break-inside:avoid;text-align:center;line-height:0;}img{\(imageStyle)-webkit-touch-callout:none;}body.scroll{--image-height:85vh;}body.scroll img{max-height:var(--image-height);}::selection{background:#80808055;}
        </style></head><body class="\(settings.mode == .scroll ? "scroll" : "pages")" data-mandatory="\(mandatory ? "yes" : "no")" data-embedded="\(font == nil ? "no" : "yes")"><article id="content"><h1>\(escaped(content.chapter.title))</h1>\(body)</article></body></html>
        """
    }
}

/// Consulted by the app delegate as well as the reader's view controller.
enum ReaderOrientationPolicy {
    static var mask: UIInterfaceOrientationMask = .allButUpsideDown
}
