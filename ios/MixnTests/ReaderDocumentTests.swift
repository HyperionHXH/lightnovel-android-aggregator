import XCTest
import Foundation
@testable import Mixn

final class ReaderDocumentTests: XCTestCase {
    func testTapZonesAndInversionsMatchAndroidBoundaries() {
        XCTAssertEqual(ReaderDocument.tapAction(zone: "default", x: 0.249, y: 0.5), .previous)
        XCTAssertEqual(ReaderDocument.tapAction(zone: "default", x: 0.25, y: 0.5), .controls)
        XCTAssertEqual(ReaderDocument.tapAction(zone: "default", x: 0.751, y: 0.5), .next)
        XCTAssertEqual(ReaderDocument.tapAction(zone: "l_shape", x: 0.1, y: 0.5), .controls)
        XCTAssertEqual(ReaderDocument.tapAction(zone: "l_shape", x: 0.1, y: 0.1), .previous)
        XCTAssertEqual(ReaderDocument.tapAction(zone: "l_shape", x: 0.1, y: 0.1, inversion: "all"), .next)
        XCTAssertEqual(ReaderDocument.tapAction(zone: "kindle", x: 0.31, y: 0.5), .previous)
        XCTAssertEqual(ReaderDocument.tapAction(zone: "both_sides", x: 0.2, y: 0.1), .controls)
        XCTAssertEqual(ReaderDocument.tapAction(zone: "left_right", x: 0.39, y: 0.8, inversion: "left_right"), .next)
        for x in [0.0, 0.1, 0.5, 0.9, 1.0] { XCTAssertEqual(ReaderDocument.tapAction(zone: "disabled", x: x, y: x), .controls) }
        XCTAssertEqual(ReaderDocument.fonts.count, 7)
        XCTAssertEqual(ReaderDocument.tapZones.count, 6)
    }
    func testPageGeometryAndCancelledCardNeverCommit() {
        XCTAssertEqual(ReaderDocument.pageCount(extent: 1170, viewport: 390), 3)
        XCTAssertEqual(ReaderDocument.pageCount(extent: 390.1, viewport: 390), 1)
        XCTAssertEqual(ReaderDocument.pageIndex(offset: 789, viewport: 390, count: 3), 2)
        XCTAssertEqual(ReaderDocument.pageIndex(offset: -100, viewport: 390, count: 3), 0)
        XCTAssertFalse(ReaderDocument.shouldCommitDrag(translation: 500, width: 390, cancelled: true))
        XCTAssertFalse(ReaderDocument.shouldCommitDrag(translation: 96, width: 390, cancelled: false))
        XCTAssertTrue(ReaderDocument.shouldCommitDrag(translation: -100, width: 390, cancelled: false))
    }
    func testSourceMarkupIsConvertedToInertTextAndRasterImages() {
        let source = #"<script>steal()</script><style>body{display:none}</style><p onclick='steal()'>A &amp; B<img src='javascript:steal()'><img src='//cdn.example.org/a.png' onerror='steal()' width='640' height='900'>终章 &lt;script&gt;</p><iframe src='https://evil.example/'>secret</iframe><p>下一段</p>"#
        let blocks = ReaderDocument.blocks(html: source, text: "")
        XCTAssertEqual(blocks.count, 4)
        XCTAssertEqual(blocks[0].text, "A & B")
        XCTAssertEqual(blocks[1].kind, .image)
        XCTAssertEqual(blocks[1].text, "https://cdn.example.org/a.png")
        XCTAssertEqual(blocks[1].width, 640)
        XCTAssertEqual(blocks[2].text, "终章 <script>")
        XCTAssertEqual(blocks[3].text, "下一段")
        XCTAssertFalse(blocks.contains { $0.text.contains("steal()") || $0.text == "secret" })
    }
    func testEntityParsingAndUnsafeImageSchemes() {
        XCTAssertEqual(ReaderDocument.decoded("&#x1F680;&#20013;&quot;&amp;"), "🚀中\"&")
        XCTAssertNil(ReaderDocument.imageURL("javascript:alert(1)"))
        XCTAssertNil(ReaderDocument.imageURL("file:///private/data.png"))
        XCTAssertNil(ReaderDocument.imageURL("data:image/svg+xml;base64,PHN2Zz4="))
        XCTAssertNil(ReaderDocument.imageURL("https://user:pass@example.org/a.png"))
        XCTAssertEqual(ReaderDocument.imageURL("http://example.org/a.png")?.scheme, "https")
        XCTAssertNotNil(ReaderDocument.imageURL("data:image/png;base64,iVBORw=="))
    }
    func testWOFF2RemainsEmbeddedAndSourceScriptsCannotRun() {
        let chapter = Chapter(key: ResourceKey(.shelf, "c"), novelKey: ResourceKey(.shelf, "n"), volumeKey: ResourceKey(.shelf, "v"), title: "<标题>")
        let content = ChapterContent(chapter: chapter, novelTitle: "书", volumeTitle: "卷", bodyText: "正文")
        let font = Data("wOF2actual-font-bytes".utf8)
        let html = ReaderDocument.html(content: content, blocks: ReaderDocument.blocks(html: "", text: "正文"), settings: ReaderSettings(), font: font, mandatory: true)
        XCTAssertTrue(html.contains("data:font/woff2;base64," + font.base64EncodedString()))
        XCTAssertTrue(html.contains("script-src 'none'"))
        XCTAssertTrue(html.contains("frame-src 'none'"))
        XCTAssertTrue(html.contains("data-mandatory=\"yes\""))
        XCTAssertTrue(html.contains("&lt;标题&gt;"))
        XCTAssertFalse(html.contains("<script"))
    }
    func testEmptyChapterDoesNotInventBody() {
        XCTAssertTrue(ReaderDocument.blocks(html: "<script>bad()</script>", text: "").isEmpty)
        XCTAssertEqual(ReaderDocument.blocks(html: "", text: "第一段\n\n第二段").map(\.text), ["第一段", "第二段"])
    }
}
