import XCTest
import Foundation

final class MixnUITourTests: XCTestCase {
    private var app: XCUIApplication!
    private let runID = UUID().uuidString

    override func setUpWithError() throws { continueAfterFailure = false }
    private func launch(onboarding: Bool = false, error: Bool = false, dark: Bool = false, large: Bool = false) {
        app = XCUIApplication()
        app.launchEnvironment["MIXN_UI_TEST"] = "1"
        app.launchEnvironment["MIXN_UI_RUN"] = runID + (error ? "-error" : "") + (dark ? "-dark" : "") + (large ? "-large" : "")
        app.launchEnvironment["MIXN_UI_ONBOARDING"] = onboarding ? "1" : "0"
        app.launchEnvironment["MIXN_UI_ERROR"] = error ? "1" : "0"
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
        open("discover.search")
        wait("search.field")
        let field = element("search.field"); field.tap(); field.typeText("跨端")
        open("search.submit")
        wait("book.light_novel_kingdom:1")
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
        open("reader.settings")
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
        back(); back()
        app.tabBars.buttons["书架"].tap()
        text("书架")
        try capture("13_shelf")
        app.tabBars.buttons["我的"].tap()
        open("profile.downloads")
        try capture("14_downloads")
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
