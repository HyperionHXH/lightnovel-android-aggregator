import XCTest
import Foundation

final class MixnUITourTests: XCTestCase {
    private var app: XCUIApplication!
    private let runID = UUID().uuidString

    override func setUpWithError() throws { continueAfterFailure = false }
    override func tearDownWithError() throws {
        if testRun?.hasSucceeded == false, app != nil {
            print("Native failure accessibility tree:\n" + app.debugDescription)
            let attachment = XCTAttachment(screenshot: app.screenshot())
            attachment.name = "failure-surface"; attachment.lifetime = .keepAlways; add(attachment)
            let complete = expectation(description: "Capture failed native surface before app teardown")
            URLSession.shared.dataTask(with: URL(string: "http://127.0.0.1:18764/failure")!) { _, _, _ in complete.fulfill() }.resume()
            wait(for: [complete], timeout: 35)
        }
        try super.tearDownWithError()
    }
    private func launch(onboarding: Bool = false, error: Bool = false, dark: Bool = false, large: Bool = false) {
        app = XCUIApplication()
        app.launchEnvironment["MIXN_UI_TEST"] = "1"
        app.launchEnvironment["MIXN_UI_RUN"] = runID + (error ? "-error" : "") + (dark ? "-dark" : "") + (large ? "-large" : "")
        app.launchEnvironment["MIXN_UI_ONBOARDING"] = onboarding ? "1" : "0"
        app.launchEnvironment["MIXN_UI_DARK"] = dark ? "1" : "0"
        app.launchEnvironment["MIXN_UI_ERROR"] = error ? "1" : "0"
        if let runtimePath = ProcessInfo.processInfo.environment["DYLD_FALLBACK_LIBRARY_PATH"] {
            app.launchEnvironment["DYLD_FALLBACK_LIBRARY_PATH"] = runtimePath
        }
        app.launchArguments = ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        if dark { app.launchArguments += ["-AppleInterfaceStyle", "Dark"] }
        if large { app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"] }
        app.launch()
    }
    private func element(_ id: String) -> XCUIElement { app.descendants(matching: .any).matching(identifier: id).firstMatch }
    private func open(_ id: String, file: StaticString = #filePath, line: UInt = #line) {
        let target = element(id)
        for _ in 0..<10 {
            if target.waitForExistence(timeout: 1), target.isHittable { target.tap(); return }
            app.swipeUp()
        }
        XCTFail("Control unavailable: \(id)", file: file, line: line)
    }
    private func wait(_ id: String, timeout: TimeInterval = 20, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(element(id).waitForExistence(timeout: timeout), "Missing \(id)", file: file, line: line)
    }
    private func text(_ label: String) { XCTAssertTrue(app.staticTexts[label].firstMatch.waitForExistence(timeout: 15), "Missing text \(label)") }
    private func back() {
        let button = app.navigationBars.buttons.element(boundBy: 0)
        XCTAssertTrue(button.waitForExistence(timeout: 5))
        button.tap()
    }
    private func capture(_ name: String) throws {
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 5))
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
        let url = URL(string: "http://127.0.0.1:18764/capture?name=" + name)!
        let complete = expectation(description: "Native host screenshot " + name)
        let task = URLSession.shared.dataTask(with: url) { _, response, error in
            XCTAssertNil(error)
            XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
            complete.fulfill()
        }
        task.resume(); wait(for: [complete], timeout: 40)
    }
    private func finishSheet() { app.buttons["完成"].firstMatch.tap() }
    private func waitForReader() {
        wait("reader.document")
        let ready = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: element("reader.loading"))
        wait(for: [ready], timeout: 30)
        XCTAssertTrue(app.webViews.firstMatch.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "这是原创测试插图")).firstMatch.waitForExistence(timeout: 10))
        XCTAssertFalse(app.tabBars.firstMatch.isHittable, "Reader must not leave the root tabs over its content")
    }
    private func setReaderMode(_ label: String) {
        open("reader.settings")
        open("reader.mode")
        let option = app.buttons[label].firstMatch
        if option.waitForExistence(timeout: 4) { option.tap() } else { app.staticTexts[label].firstMatch.tap() }
        if !app.buttons["完成"].firstMatch.exists { back() }
        finishSheet()
        wait("reader.document")
    }

    private func waitForSystemShare() {
        let share = app.descendants(matching: .any).matching(identifier: "ActivityListView").firstMatch
        XCTAssertTrue(share.waitForExistence(timeout: 30), "Expected the real iOS activity sheet")
        XCTAssertTrue(app.buttons["header.closeButton"].waitForExistence(timeout: 10), "System share sheet must be dismissible")
    }

    func testColdCatalogChapterCanBeReadAndDismissedForEachSource() throws {
        launch()
        for source in ["light_novel_kingdom", "light_novel_shelf"] {
            if source == "light_novel_shelf" { open("discover.source." + source) }
            open("book.\(source):2")
            wait("detail.title")
            XCTAssertEqual(element("detail.read").label, "开始阅读", "This route must start with an unread book")
            app.segmentedControls.buttons["章节"].tap()
            open("volume.\(source):v1")
            open("chapter.\(source):2")
            waitForReader()
            open("reader.menu")
            text("第2章 · 稳定阅读")
            app.buttons["返回"].firstMatch.tap()
            wait("detail.title")
            open("chapter.\(source):1")
            waitForReader()
            open("reader.menu")
            text("第1章 · 稳定阅读")
            app.buttons["返回"].firstMatch.tap()
            wait("detail.title")
            back()
        }
        app.terminate()
    }

    func testProductionRoutesAndReadingStates() throws {
        launch(onboarding: true)
        text("欢迎使用 Mixn")
        try capture("01_onboarding")
        for _ in 0..<3 { open("onboarding.next") }
        open("onboarding.finish")
        wait("book.light_novel_kingdom:1")
        text("测试作者甲")
        try capture("02_discover")
        open("discover.source.light_novel_shelf")
        wait("book.light_novel_shelf:1")
        text("测试作者乙")
        try capture("03_source")
        open("book.light_novel_shelf:1")
        wait("detail.title")
        XCTAssertFalse(element("detail.comments").exists, "Shelf must not advertise an unsupported comment API")
        open("detail.read")
        waitForReader()
        try capture("03a_mandatory_woff2")
        open("reader.menu")
        app.buttons["返回"].firstMatch.tap()
        wait("detail.title")
        back()
        open("discover.search")
        wait("search.field")
        let field = element("search.field"); field.tap(); field.typeText("跨端")
        XCTAssertEqual(field.value as? String, "跨端", "Native typing must reach the actual search field")
        open("search.submit")
        XCTAssertEqual(field.value as? String, "跨端", "Submitting must not also activate the clear button")
        wait("book.light_novel_kingdom:1")
        let keyboardDismissed = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: app.keyboards.firstMatch)
        wait(for: [keyboardDismissed], timeout: 5)
        try capture("04_search")
        open("book.light_novel_kingdom:1")
        wait("detail.title")
        text("跨端阅读验收")
        try capture("05_detail")
        app.segmentedControls.buttons["章节"].tap()
        open("volume.light_novel_kingdom:v1")
        wait("chapter.light_novel_kingdom:1")
        try capture("06_catalog")
        app.swipeDown()
        open("detail.comments")
        text("测试书评正文。表情与回复不会挡住评分。")
        open("comments.replies.root")
        text("回复内容与主评论保持层级。")
        text("第4条折叠回复")
        try capture("07_comments")
        back()
        open("detail.read")
        waitForReader()
        try capture("08_reader_paged")
        let inlineImage = app.webViews.firstMatch.images.firstMatch
        XCTAssertTrue(inlineImage.waitForExistence(timeout: 10))
        inlineImage.tap()
        wait("reader.image.preview")
        try capture("08a_reader_image")
        element("reader.image.preview").press(forDuration: 1)
        open("reader.image.save")
        text("已保存到相册")
        try capture("08b_reader_image_actions")
        open("reader.image.close")
        open("reader.menu")
        let oldPosition = element("reader.position").label
        open("reader.next")
        let advanced = expectation(for: NSPredicate(format: "label != %@", oldPosition), evaluatedWith: element("reader.position"))
        wait(for: [advanced], timeout: 10)
        open("reader.menu")
        try capture("09_reader_menu")
        open("reader.menu.settings")
        wait("reader.mode")
        try capture("10_reader_settings")
        finishSheet()
        setReaderMode("上下滚动")
        element("reader.document").swipeUp()
        try capture("11_reader_scroll")
        setReaderMode("卡片翻页")
        element("reader.document").swipeLeft()
        try capture("12_reader_card")
        app.buttons["返回"].firstMatch.tap()
        wait("detail.title")
        app.swipeDown(); app.swipeDown()
        open("detail.download")
        back(); back()
        app.tabBars.buttons["书架"].tap()
        text("书架")
        try capture("13_shelf")
        app.tabBars.buttons["我的"].tap()
        open("profile.downloads")
        let wifi = element("download.wifiOnly")
        if wifi.value as? String == "1" { wifi.tap() }
        XCTAssertTrue(app.staticTexts["下载完成"].firstMatch.waitForExistence(timeout: 45))
        text("4/4 章")
        try capture("14_downloads")
        for (name, action) in [("14a_epub_export", "导出 EPUB（含字体）"), ("14b_txt_export", "导出 TXT")] {
            open("download.export")
            app.buttons[action].firstMatch.tap()
            waitForSystemShare()
            try capture(name)
            app.buttons["header.closeButton"].tap()
            wait("download.export")
        }
        back()
        open("profile.history")
        text("跨端阅读验收")
        try capture("15_history")
        back()
        try capture("16_profile")
        open("profile.account.light_novel_shelf")
        wait("account.identifier")
        XCTAssertTrue(element("account.password").exists)
        try capture("17_accounts")
        let account = element("account.identifier")
        account.tap(); account.typeText("fixture@example.test")
        let password = element("account.password")
        password.tap(); password.typeText("synthetic-password")
        open("account.login")
        text("UID fixture")
        text("测试账号")
        try capture("17a_signed_in")
        back()
        open("profile.settings")
        try capture("18_settings")
        open("settings.fonts")
        text("字体")
        try capture("19_fonts")
        back(); back()
        open("profile.data")
        text("数据与统计")
        try capture("20_storage")
        back()
        open("profile.about")
        try capture("21_about")
        app.terminate()
        launch(error: true)
        text("隔离测试网络错误")
        try capture("22_error")
        app.terminate()
        launch(dark: true)
        wait("book.light_novel_kingdom:1")
        try capture("23_dark")
        app.terminate()
        launch(large: true)
        wait("book.light_novel_kingdom:1")
        try capture("24_large_text")
    }
}
