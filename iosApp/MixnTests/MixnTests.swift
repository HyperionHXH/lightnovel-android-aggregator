import XCTest
@testable import Mixn

final class MixnTests: XCTestCase {
    func testSourcesHaveStableDisplayNames() {
        XCTAssertEqual(MixnSource.kingdom.rawValue, "kingdom")
        XCTAssertEqual(MixnSource.kingdom.displayName, "轻之国度")
        XCTAssertEqual(MixnSource.shelf.displayName, "轻书架")
    }

    func testBookIdentityIncludesSourceAndRemoteID() {
        let book = MixnBook(
            id: "shelf:42",
            source: .shelf,
            remoteID: "42",
            title: "测试书",
            author: "作者",
            summary: "简介",
            coverURL: nil,
            defaultChapterID: 1,
        )
        XCTAssertEqual(book.id, "shelf:42")
        XCTAssertEqual(book.source, .shelf)
        XCTAssertEqual(book.remoteID, "42")
    }
}
