import XCTest
import UIKit
import WebKit
@testable import Mixn

final class ReaderWebViewTests: XCTestCase {
    @MainActor func testRepeatedPresentationsRestoreWithoutAnimationFrameCallbacks() async throws {
        // WebKit can suspend animation callbacks while a new controller is covered or
        // entering its window. Resource readiness must not depend on its render clock.
        for presentation in 0..<6 {
            let settled = expectation(description: "Presentation \(presentation) restored")
            let controller = ReaderWebController()
            controller.loadViewIfNeeded()
            controller.webView.configuration.userContentController.addUserScript(WKUserScript(
                source: "window.requestAnimationFrame = () => 1;",
                injectionTime: .atDocumentStart, forMainFrameOnly: true, in: .defaultClient))
            controller.owner = reader(html: document(), onSettled: { position in
                XCTAssertEqual(position.paragraph, 6)
                settled.fulfill()
            })
            let window = show(controller)
            controller.loadDocument()
            await fulfillment(of: [settled], timeout: 8)
            let visibility = try await evaluate("getComputedStyle(document.body).visibility", in: controller)
            XCTAssertEqual(visibility as? String, "visible")
            let restored = try await evaluate("MixnReader.state().paragraph", in: controller)
            XCTAssertEqual(restored as? Int, 6)
            let imageLoaded = try await evaluate("Array.from(document.images).every(image => image.complete && image.naturalWidth === 1)", in: controller)
            XCTAssertEqual(imageLoaded as? Bool, true)
            controller.tearDown()
            window.isHidden = true
            window.rootViewController = nil
        }
    }

    @MainActor func testOffscreenResourceCompletionWaitsForVisibleNativeRestore() async throws {
        let controller = ReaderWebController()
        controller.loadViewIfNeeded()
        let premature = expectation(description: "Detached document must not settle")
        premature.isInverted = true
        let settled = expectation(description: "Attached document restores its real viewport")
        controller.owner = reader(html: document(), onSettled: { [weak controller] position in
            if controller?.view.window == nil { premature.fulfill(); return }
            XCTAssertEqual(position.paragraph, 6)
            settled.fulfill()
        })
        controller.loadDocument()
        let deadline = Date().addingTimeInterval(8)
        var resourcesReady = false
        while Date() < deadline {
            if let value = try? await evaluate("document.body?.classList.contains('ready') === true", in: controller),
               value as? Bool == true {
                resourcesReady = true
                break
            }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        XCTAssertTrue(resourcesReady, "Actual document did not finish its resource gates")
        await fulfillment(of: [premature], timeout: 0.2)
        let window = show(controller)
        defer {
            controller.tearDown()
            window.isHidden = true
            window.rootViewController = nil
        }
        await fulfillment(of: [settled], timeout: 8)
    }

    @MainActor func testReplacedDocumentRejectsOldReadyAndInvalidEmbeddedFont() async throws {
        let controller = ReaderWebController()
        controller.loadViewIfNeeded()
        let initial = expectation(description: "Initial document restored")
        controller.owner = reader(html: document(), onSettled: { _ in initial.fulfill() })
        let window = show(controller)
        defer {
            controller.tearDown()
            window.isHidden = true
            window.rootViewController = nil
        }
        controller.loadDocument()
        await fulfillment(of: [initial], timeout: 8)
        let tokenValue = try await evaluate("document.body.dataset.token", in: controller)
        let oldToken = try XCTUnwrap(tokenValue as? String)
        // Deliver a real isolated-world bridge message from the previous generation
        // while the replacement is still processing its mandatory embedded font.
        controller.webView.configuration.userContentController.addUserScript(WKUserScript(
            source: """
            document.addEventListener('DOMContentLoaded', () => {
              webkit.messageHandlers.reader.postMessage({kind:'ready',token:'\(oldToken)'});
            }, {once:true});
            """,
            injectionTime: .atDocumentStart, forMainFrameOnly: true, in: .defaultClient))
        let rejectedFont = expectation(description: "Actual WebKit font decoding rejects corrupt bytes")
        let incorrectlySettled = expectation(description: "Unready replacement must not settle")
        incorrectlySettled.isInverted = true
        controller.owner = reader(html: document(font: Data("not-a-font".utf8)),
                                  onSettled: { _ in incorrectlySettled.fulfill() },
                                  onError: { message in
                                      XCTAssertFalse(message.contains("排版超时"))
                                      rejectedFont.fulfill()
                                  })
        controller.reloadForDocumentChange()
        await fulfillment(of: [rejectedFont], timeout: 8)
        await fulfillment(of: [incorrectlySettled], timeout: 0.3)
        let visibility = try await evaluate("getComputedStyle(document.body).visibility", in: controller)
        XCTAssertEqual(visibility as? String, "hidden")

        let replacement = expectation(description: "Next valid document restores normally")
        controller.owner = reader(html: document(), onSettled: { position in
            XCTAssertEqual(position.paragraph, 6)
            replacement.fulfill()
        })
        controller.reloadForDocumentChange()
        await fulfillment(of: [replacement], timeout: 8)
        let newToken = try await evaluate("document.body.dataset.token", in: controller) as? String
        XCTAssertNotEqual(newToken, oldToken)
    }

    @MainActor private func reader(html: String, onSettled: @escaping (ReaderViewport) -> Void,
                                   onError: @escaping (String) -> Void = { XCTFail($0) }) -> ReaderWebView {
        var settings = ReaderSettings()
        settings.mode = .scroll
        return ReaderWebView(documentID: UUID(), html: html, settings: settings,
                             restoreParagraph: 6, restorePercent: 0, restoreAtEnd: false, command: nil,
                             onSettled: onSettled, onControls: {}, onBoundary: { _ in },
                             onImage: { _, _ in }, onError: onError)
    }

    @MainActor private func document(font: Data? = nil) -> String {
        let chapter = Chapter(key: ResourceKey(.kingdom, "reader-test"), novelKey: ResourceKey(.kingdom, "book"),
                              volumeKey: ResourceKey(.kingdom, "volume"), title: "真实 WebKit 阅读排版")
        let text = (0..<30).map { "第\($0)段：" + String(repeating: "用于检查恢复后的真实正文位置。", count: 12) }.joined(separator: "\n")
        let content = ChapterContent(chapter: chapter, novelTitle: "书", volumeTitle: "卷", bodyText: text)
        var settings = ReaderSettings()
        settings.mode = .scroll
        let image = UIGraphicsImageRenderer(size: CGSize(width: 1, height: 1)).pngData { context in
            UIColor.red.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 1, height: 1))
        }
        var blocks = ReaderDocument.blocks(html: "", text: text)
        blocks.append(ReaderDocument.Block(kind: .image, text: "data:image/png;base64," + image.base64EncodedString()))
        return ReaderDocument.html(content: content, blocks: blocks, settings: settings,
                                   font: font, mandatory: font != nil)
    }

    @MainActor private func show(_ controller: ReaderWebController) -> UIWindow {
        let window: UIWindow
        if let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first {
            window = UIWindow(windowScene: scene)
        } else {
            window = UIWindow(frame: UIScreen.main.bounds)
        }
        window.frame = UIScreen.main.bounds
        window.rootViewController = controller
        window.makeKeyAndVisible()
        window.layoutIfNeeded()
        controller.view.layoutIfNeeded()
        return window
    }

    @MainActor private func evaluate(_ source: String, in controller: ReaderWebController) async throws -> Any? {
        try await withCheckedThrowingContinuation { continuation in
            controller.webView.evaluateJavaScript(source, in: nil, in: .defaultClient) { result in
                switch result {
                case .success(let value): continuation.resume(returning: value)
                case .failure(let error): continuation.resume(throwing: error)
                }
            }
        }
    }
}
