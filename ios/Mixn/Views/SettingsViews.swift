import SwiftUI
import UIKit
import WebKit
import UserNotifications

struct ProfileView: View {
    @EnvironmentObject private var app: AppStore
    @State private var unread = 0
    @State private var summaryError: String?
    var body: some View {
        List {
            Section(header: Text("账号与来源")) {
                account(.kingdom)
                account(.shelf)
            }
            if app.sessions[.kingdom]?.loggedIn == true {
                Section(header: Text("轻之国度")) {
                    NavigationLink(destination: CommunityView(kind: .messages)) {
                        HStack { Label("消息中心", systemImage: "envelope"); Spacer(); if unread > 0 { Text("\(unread)").foregroundColor(.red) } }
                    }.accessibilityIdentifier("profile.messages")
                    NavigationLink("关注与粉丝", destination: CommunityView(kind: .following)).accessibilityIdentifier("profile.following")
                    NavigationLink("发布管理", destination: CommunityView(kind: .publishing)).accessibilityIdentifier("profile.publishing")
                    if let error = summaryError { Text(error).font(.caption).foregroundColor(.red) }
                }
            }
            Section(header: Text("聚合功能")) {
                NavigationLink(destination: HistoryView()) { Label("阅读记录", systemImage: "clock") }.accessibilityIdentifier("profile.history")
                NavigationLink(destination: ShelfView(downloadedOnly: true)) { Label("下载与导出", systemImage: "arrow.down.circle") }.accessibilityIdentifier("profile.downloads")
                NavigationLink(destination: DataStatsView()) { Label("数据与统计", systemImage: "chart.bar") }.accessibilityIdentifier("profile.data")
            }
            Section {
                NavigationLink(destination: SettingsView()) { Label("设置", systemImage: "gearshape") }.accessibilityIdentifier("profile.settings")
                NavigationLink(destination: AboutView()) { Label("关于 Mixn", systemImage: "info.circle") }.accessibilityIdentifier("profile.about")
            }
        }.listStyle(.insetGrouped).navigationTitle("我的")
            .task(id: "\(app.sessions[.kingdom]?.accountID ?? ""):\(app.sessions[.kingdom]?.loggedIn == true):\(app.accountRevision(.kingdom)):\(app.sessions[.shelf]?.accountID ?? ""):\(app.sessions[.shelf]?.loggedIn == true):\(app.accountRevision(.shelf))") { await refresh() }
            .refreshable { await refresh() }
    }
    private func account(_ id: SourceID) -> some View {
        NavigationLink(destination: AccountView(sourceID: id)) {
            VStack(alignment: .leading, spacing: 10) {
                AccountIdentity(sourceID: id, profile: app.profiles[id], session: app.sessions[id] ?? AccountSession())
                if app.sessions[id]?.loggedIn == true, let profile = app.profiles[id] {
                    AccountExperienceView(profile: profile)
                } else { Text("登录后查看资料、轻币和来源专属功能").font(.caption).foregroundColor(.secondary) }
                if let error = app.sourceErrors[id] { Text(error).font(.caption).foregroundColor(.red) }
            }.padding(.vertical, 6)
        }.accessibilityIdentifier("profile.account.\(id.rawValue)")
    }
    @MainActor private func refresh() async {
        for id in SourceID.allCases where app.sessions[id]?.loggedIn == true { await app.refreshProfile(id) }
        let session = app.sessions[.kingdom], revision = app.accountRevision(.kingdom)
        unread = 0; summaryError = nil
        guard session?.loggedIn == true, let source = app.source(.kingdom) as? KingdomSource else { return }
        do {
            let result = try await source.request(path: "api/bff/message-unread-v1")
            try Task.checkCancellation()
            guard app.sessions[.kingdom] == session, app.accountRevision(.kingdom) == revision else { return }
            let nested = result.object("summary", "unread")
            let summary = nested.isEmpty ? result : nested
            guard let count = summary.optionalInt("unread_count", "unreadCount", "total_unread") else { throw SourceFailure.parsing("来源未返回消息未读总数") }
            unread = max(0, count)
        } catch is CancellationError {} catch {
            if app.sessions[.kingdom] == session, app.accountRevision(.kingdom) == revision { summaryError = "消息未读数：" + error.localizedDescription }
        }
    }
}

private struct AccountIdentity: View {
    let sourceID: SourceID
    let profile: SourceProfile?
    let session: AccountSession
    var body: some View {
        HStack(spacing: 12) {
            AsyncImage(url: profile?.avatarURL) { image in image.resizable().scaledToFill() } placeholder: { Image(systemName: "person.crop.circle.fill").resizable().scaledToFit().foregroundColor(.secondary) }
                .frame(width: 50, height: 50).clipShape(Circle()).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(sourceID.title + "账号").font(.headline)
                Text(session.loggedIn ? (profile?.name.isEmpty == false ? profile!.name : session.displayName ?? session.accountID ?? "已登录") : "未登录").foregroundColor(.secondary)
                if let account = profile?.accountID { Text("UID \(account)").font(.caption).foregroundColor(.secondary) }
            }
        }
    }
}

struct AccountExperienceView: View {
    let profile: SourceProfile
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let balance = profile.balance { Text("轻币 \(balance)").fontWeight(.semibold) }
            if let level = profile.level {
                Text("\(profile.source == .shelf ? "成长等级" : "等级") \(level)\(profile.levelLabel.map { " · " + $0 } ?? "")")
                if profile.source == .kingdom, let maximum = profile.maximumLevel, maximum > 1 {
                    ProgressView(value: Double(max(0, level - 1)), total: Double(maximum - 1))
                    Text("等级进度（共 \(maximum) 档），不是经验进度").font(.caption).foregroundColor(.secondary)
                }
            } else if let label = profile.levelLabel { Text(label) }
            if let exp = profile.experience {
                Text("累计经验 \(exp.total)")
                if let fraction = exp.fraction { ProgressView(value: fraction) }
                if exp.maxLevel { Text("已达到最高等级").font(.caption).foregroundColor(.secondary) }
                else if let start = exp.levelStart, let next = exp.nextLevel {
                    Text("本级门槛 \(start) · 下级门槛 \(next)").font(.caption).foregroundColor(.secondary)
                } else { Text("来源未提供升级门槛").font(.caption).foregroundColor(.secondary) }
            }
            ForEach(profile.extra.keys.sorted(), id: \.self) { key in
                Text("\(key)：\(profile.extra[key] ?? "")").font(.caption).foregroundColor(.secondary)
            }
        }
    }
}

struct AccountView: View {
    let sourceID: SourceID
    @EnvironmentObject private var app: AppStore
    @State private var identifier = ""
    @State private var password = ""
    @State private var revealed = false
    @State private var busy = false
    @State private var loadingRewards = false
    @State private var reward: RewardStatus?
    @State private var error: String?
    @State private var notice: String?
    @State private var showRegistration = false
    @State private var confirmLogout = false
    @State private var operation: Task<Void, Never>?
    @State private var epoch = UUID()
    private var session: AccountSession { app.sessions[sourceID] ?? app.source(sourceID).session }
    var body: some View {
        Form {
            Section { AccountIdentity(sourceID: sourceID, profile: app.profiles[sourceID], session: session) }
            if let error = error { Section { Text(error).foregroundColor(.red).accessibilityIdentifier("account.error") } }
            if let error = app.sourceErrors[sourceID] { Section { Text(error).foregroundColor(.red) } }
            if let notice = notice { Section { Text(notice).foregroundColor(.accentColor) } }
            if session.loggedIn {
                if let profile = app.profiles[sourceID] { Section(header: Text("等级与经验")) { AccountExperienceView(profile: profile) } }
                Section(header: Text("签到")) {
                    if loadingRewards { ProgressView("读取签到状态…") }
                    if let reward = reward {
                        if let streak = reward.streak { Text("连续签到 \(streak) 天") }
                        if let balance = reward.balance { Text("来源余额 \(balance)") }
                        ForEach(reward.days) { day in
                            HStack { Text("第 \(day.day) 天"); Spacer(); Text(day.reward); if day.claimed { Image(systemName: "checkmark.circle.fill").foregroundColor(.green) } }
                        }
                        Button(reward.claimedToday ? "今日已签到" : "领取今日签到") { run { await claim() } }
                            .disabled(reward.claimedToday || busy || loadingRewards).accessibilityIdentifier("account.reward")
                    } else if !loadingRewards { Button("重新读取签到状态") { run { await loadRewards() } }.disabled(busy) }
                }
                Section {
                    Button("刷新来源资料与签到") { run { await refresh() } }.disabled(busy).accessibilityIdentifier("account.refresh")
                    Button("退出该来源", role: .destructive) { confirmLogout = true }.disabled(busy).accessibilityIdentifier("account.logout")
                }
            } else {
                Section(header: Text("仅登录此来源"), footer: Text("两站账号独立。密码仅用于本次请求；会话保存在系统钥匙串。")) {
                    TextField(sourceID == .shelf ? "邮箱" : "用户名 / 邮箱", text: $identifier)
                        .textContentType(sourceID == .shelf ? .emailAddress : .username).keyboardType(sourceID == .shelf ? .emailAddress : .default)
                        .textInputAutocapitalization(.never).disableAutocorrection(true).accessibilityIdentifier("account.identifier")
                    HStack {
                        Group {
                            if revealed { TextField("密码", text: $password) }
                            else { SecureField("密码", text: $password) }
                        }.textContentType(.password).textInputAutocapitalization(.never).disableAutocorrection(true).accessibilityIdentifier("account.password")
                        Button(revealed ? "隐藏" : "显示") { revealed.toggle() }.accessibilityIdentifier("account.reveal")
                    }
                    Button { login() } label: { HStack { Text("登录"); if busy { Spacer(); ProgressView() } } }
                        .disabled(busy || identifier.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || password.isEmpty).accessibilityIdentifier("account.login")
                }
                Section(header: Text("注册与密码")) {
                    if sourceID == .kingdom {
                        Button("邮箱注册（轻之国度）") { showRegistration = true }.disabled(busy).accessibilityIdentifier("account.register.native")
                        Link("浏览器注册 / 找回密码", destination: sourceID.website)
                    } else {
                        Link("浏览器注册", destination: URL(string: "https://www.lightnovel.life/register")!)
                        Link("浏览器找回 / 修改密码", destination: URL(string: "https://www.lightnovel.life/forgot-password")!)
                    }
                    Link("打开来源官网", destination: sourceID.website)
                }
            }
        }.navigationTitle(sourceID.title + "账号").navigationBarTitleDisplayMode(.inline)
            .task(id: "\(sourceID.rawValue):\(session.accountID ?? ""): \(session.loggedIn):\(app.accountRevision(sourceID))") { epoch = UUID(); reward = nil; if session.loggedIn { await refresh() } }
            .refreshable { await refresh() }
            .onDisappear { operation?.cancel(); epoch = UUID(); password = ""; revealed = false }
            .sheet(isPresented: $showRegistration) { NavigationView { KingdomRegistrationView() }.navigationViewStyle(.stack).environmentObject(app) }
            .alert("退出\(sourceID.title)账号？", isPresented: $confirmLogout) {
                Button("退出", role: .destructive) { run { do { try await app.logout(sourceID); reward = nil; notice = nil } catch { self.error = error.localizedDescription } } }
                Button("取消", role: .cancel) {}
            } message: { Text("另一来源的账号、下载和本地阅读进度会保留。") }
    }
    @MainActor private func run(_ action: @escaping @MainActor () async -> Void) {
        guard !busy else { return }; busy = true; error = nil; notice = nil
        operation = Task { await action(); busy = false }
    }
    @MainActor private func login() {
        let secret = password, loginIdentifier = identifier.trimmingCharacters(in: .whitespacesAndNewlines)
        password = ""; revealed = false
        run {
            let revision = app.accountRevision(sourceID) + 1
            do { try await app.login(sourceID, identifier: loginIdentifier, password: secret) }
            catch is CancellationError {} catch { if app.accountRevision(sourceID) == revision { self.error = error.localizedDescription } }
        }
    }
    @MainActor private func refresh() async {
        guard session.loggedIn else { return }
        await app.refreshProfile(sourceID)
        guard !Task.isCancelled else { return }
        await loadRewards()
    }
    @MainActor private func loadRewards() async {
        let captured = session, token = epoch, revision = app.accountRevision(sourceID)
        guard captured.loggedIn else { return }
        loadingRewards = true
        defer { if epoch == token { loadingRewards = false } }
        do {
            let status = try await app.source(sourceID).rewards()
            try Task.checkCancellation()
            guard epoch == token, session == captured, app.accountRevision(sourceID) == revision else { return }
            reward = status
        } catch is CancellationError {} catch { if epoch == token, session == captured, app.accountRevision(sourceID) == revision { self.error = error.localizedDescription } }
    }
    @MainActor private func claim() async {
        let captured = session, token = epoch, revision = app.accountRevision(sourceID)
        do {
            _ = try await app.source(sourceID).claimReward()
            try Task.checkCancellation()
            guard epoch == token, session == captured, app.accountRevision(sourceID) == revision else { return }
            notice = "签到成功，正在读取来源最新资料。"
            await refresh()
        } catch is CancellationError {} catch { if epoch == token, session == captured, app.accountRevision(sourceID) == revision { self.error = error.localizedDescription } }
    }
}

private struct KingdomRegistrationView: View {
    @EnvironmentObject private var app: AppStore
    @Environment(\.presentationMode) private var presentation
    @State private var email = ""
    @State private var code = ""
    @State private var nickname = ""
    @State private var password = ""
    @State private var reveal = false
    @State private var busy = false
    @State private var error: String?
    @State private var notice: String?
    @State private var job: Task<Void, Never>?
    var body: some View {
        Form {
            Section(header: Text("轻之国度邮箱注册")) {
                TextField("邮箱", text: $email).keyboardType(.emailAddress).textContentType(.emailAddress).textInputAutocapitalization(.never).disableAutocorrection(true)
                Button("发送邮箱验证码") { perform(sendCode: true) }.disabled(busy || email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                TextField("验证码", text: $code).textContentType(.oneTimeCode).keyboardType(.asciiCapable)
                TextField("昵称", text: $nickname)
                HStack {
                    Group { if reveal { TextField("密码", text: $password) } else { SecureField("密码", text: $password) } }.textContentType(.newPassword).textInputAutocapitalization(.never).disableAutocorrection(true)
                    Button(reveal ? "隐藏" : "显示") { reveal.toggle() }
                }
                Button("注册并登录") { perform(sendCode: false) }.disabled(busy || email.isEmpty || code.isEmpty || nickname.isEmpty || password.isEmpty)
                if busy { ProgressView() }
                if let error = error { Text(error).foregroundColor(.red) }
                if let notice = notice { Text(notice).foregroundColor(.accentColor) }
            }
            Section { Link("在浏览器查看官网与注册条款", destination: SourceID.kingdom.website) }
        }.navigationTitle("注册账号").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { presentation.wrappedValue.dismiss() }.disabled(busy) } }
            .interactiveDismissDisabled(busy)
            .onDisappear { job?.cancel(); password = "" }
    }
    @MainActor private func perform(sendCode: Bool) {
        guard !busy else { return }
        let secret = password
        if !sendCode { password = ""; reveal = false }
        busy = true; error = nil; notice = nil
        job = Task {
            defer { busy = false }
            do {
                guard let source = app.source(.kingdom) as? KingdomSource else { throw SourceFailure.unsupported("此来源实例不提供邮箱注册") }
                if sendCode {
                    try await source.sendRegistrationCode(email: email)
                    try Task.checkCancellation()
                    notice = "验证码已发送，请查看邮箱。"
                } else {
                    try await app.registerKingdom(email: email, captcha: code, nickname: nickname, password: secret)
                    try Task.checkCancellation()
                    presentation.wrappedValue.dismiss()
                }
            } catch is CancellationError {} catch { self.error = error.localizedDescription }
        }
    }
}

struct SettingsView: View {
    @EnvironmentObject private var app: AppStore
    var body: some View { SettingsContent(preferences: app.preferences, library: app.library) }
}
private struct SettingsContent: View {
    @EnvironmentObject private var app: AppStore
    @ObservedObject var preferences: PreferencesStore
    @ObservedObject var library: LibraryStore
    @Environment(\.scenePhase) private var scenePhase
    @State private var choosingDirectory = false
    @State private var error: String?
    @State private var enablingNotifications = false
    @State private var reset: String?
    @State private var showOnboarding = false
    var body: some View {
        Form {
            Section(header: Text("阅读 · 文字排版")) {
                NavigationLink("字体预览与选择", destination: FontSelectionView()).accessibilityIdentifier("settings.fonts")
                numericSlider("字号", value: $preferences.reader.fontSize, range: 14...32, step: 1)
                numericSlider("行高", value: $preferences.reader.lineHeight, range: 1.2...2.2, step: 0.1)
                numericSlider("页边距", value: $preferences.reader.horizontalPadding, range: 12...40, step: 2)
            }
            Section(header: Text("阅读 · 交互")) {
                Group {
                    Picker("阅读方式", selection: $preferences.reader.mode) { ForEach(ReadingMode.allCases) { Text($0.title).tag($0) } }
                    Picker("阅读背景", selection: $preferences.reader.theme) { ForEach(ReadingTheme.allCases) { Text($0.title).tag($0) } }
                    choices("点击区域", value: $preferences.reader.tapZone, items: [("default","默认"),("l_shape","L 形"),("kindle","Kindle"),("both_sides","两侧翻页"),("left_right","左右"),("disabled","禁用点击翻页")])
                    choices("反转点击区域", value: $preferences.reader.tapInversion, items: [("none","无"),("left_right","左右"),("up_down","上下"),("all","全部")])
                    choices("屏幕方向", value: $preferences.reader.orientation, items: [("default","跟随系统"),("portrait","竖屏"),("landscape","横屏")])
                    choices("图片缩放", value: $preferences.reader.imageScale, items: [("fit","适应屏幕"),("fill","拉伸"),("fit_width","适应宽度"),("fit_height","适应高度"),("original","原始大小"),("smart","智能")])
                }
                Text("iOS 不提供拦截并屏蔽硬件音量键的公开接口；音量键保持系统音量功能，请用点击或滑动翻页。").font(.caption).foregroundColor(.secondary)
                Toggle("保持屏幕常亮", isOn: $preferences.reader.keepScreenOn)
                Toggle("显示阅读进度条", isOn: $preferences.reader.showProgressBar)
                Button("恢复阅读默认设置", role: .destructive) { reset = "reader" }.accessibilityIdentifier("settings.reset.reader")
            }
            Section(header: Text("外观")) {
                choices("主题", value: $preferences.appearance.theme, items: [("system","跟随系统"),("light","浅色"),("dark","深色")])
                Picker("界面字号", selection: $preferences.appearance.uiScale) { Text("小").tag(0.9); Text("标准").tag(1.0); Text("大").tag(1.12) }
                Picker("图标大小", selection: $preferences.appearance.iconScale) { Text("小").tag(0.9); Text("标准").tag(1.0); Text("大").tag(1.12) }
                HStack { Image(systemName: "book.fill").font(.system(size: 24 * preferences.appearance.iconScale)); Text("字号与图标预览").font(.system(size: 17 * preferences.appearance.uiScale)) }
                Button("恢复外观默认设置", role: .destructive) { reset = "appearance" }
            }
            Section(header: Text("下载与导出"), footer: Text("离线原始数据保存在应用专用目录。选择的文件夹用于保存导出书籍，不会因清理缓存或重设目录自动删除。")) {
                Toggle("仅使用 Wi-Fi 下载", isOn: Binding(get: { library.wifiOnly }, set: { value in do { try library.setWifiOnly(value) } catch { self.error = error.localizedDescription } }))
                VStack(alignment: .leading, spacing: 4) { Text("下载 / 导出目录"); Text(directoryLabel).font(.caption).foregroundColor(.secondary) }
                Button("选择文件夹") { choosingDirectory = true }.accessibilityIdentifier("settings.directory.choose")
                if preferences.exportDirectoryBookmark != nil { Button("恢复应用专用目录") { preferences.exportDirectoryBookmark = nil }.accessibilityIdentifier("settings.directory.reset") }
                Button("恢复下载默认设置", role: .destructive) { reset = "downloads" }
            }
            Section(header: Text("更新提醒"), footer: Text("通过 iOS 后台 App 刷新检查来源书架，最早间隔为 6 小时。实际时机与频率由系统决定；关闭后台刷新、低电量或长期未打开应用时可能不运行。")) {
                Toggle("后台更新通知", isOn: Binding(get: { preferences.updateNotifications }, set: setNotifications)).disabled(enablingNotifications).accessibilityIdentifier("settings.notifications")
                if enablingNotifications { ProgressView("请求通知权限…") }
                Button("打开系统通知与后台刷新设置") { if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) } }
            }
            Section(header: Text("其他")) {
                NavigationLink("数据与统计", destination: DataStatsView())
                Button("重新查看引导") { showOnboarding = true }.accessibilityIdentifier("settings.onboarding")
                NavigationLink("关于与更新", destination: AboutView()).accessibilityIdentifier("settings.about")
            }
            if let error = error { Section { Text(error).foregroundColor(.red).accessibilityIdentifier("settings.error") } }
        }.navigationTitle("设置").navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: $choosingDirectory) { DirectoryPicker { url in do { preferences.exportDirectoryBookmark = try DownloadDirectory.bookmark(url) } catch { self.error = error.localizedDescription } } }
            .sheet(isPresented: $showOnboarding) { OnboardingView(revisiting: true).environmentObject(app) }
            .task { await synchronizeNotificationPermission() }
            .onChange(of: scenePhase) { phase in if phase == .active { Task { await synchronizeNotificationPermission() } } }
            .alert("恢复默认设置？", isPresented: Binding(get: { reset != nil }, set: { if !$0 { reset = nil } })) {
                Button("恢复", role: .destructive) {
                    switch reset {
                    case "reader": preferences.reader = ReaderSettings()
                    case "appearance": preferences.appearance = AppearanceSettings()
                    case "downloads": do { try library.setWifiOnly(true); preferences.exportDirectoryBookmark = nil } catch { self.error = error.localizedDescription }
                    default: break
                    }
                    reset = nil
                }
                Button("取消", role: .cancel) { reset = nil }
            } message: { Text("只重设该组偏好，不删除账号、下载、字体或阅读进度。") }
    }
    private var directoryLabel: String {
        guard let data = preferences.exportDirectoryBookmark else { return "应用专用目录 · 离线书籍与导出" }
        do { let result = try DownloadDirectory.resolve(data); return result.stale ? "目录授权已过期，请重新选择" : result.url.lastPathComponent }
        catch { return "无法读取目录授权，请重新选择" }
    }
    @MainActor private func synchronizeNotificationPermission() async {
        guard preferences.updateNotifications, !enablingNotifications else { return }
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        if settings.authorizationStatus == .denied {
            preferences.updateNotifications = false
            BackgroundUpdateScheduler.shared.cancel()
            error = "通知权限已在系统中关闭，后台更新提醒已停用。"
        }
    }
    private func numericSlider(_ title: String, value: Binding<Double>, range: ClosedRange<Double>, step: Double) -> some View {
        VStack(alignment: .leading) { Text("\(title) \(value.wrappedValue, specifier: step < 1 ? "%.1f" : "%.0f")"); Slider(value: value, in: range, step: step) }
    }
    private func choices(_ title: String, value: Binding<String>, items: [(String,String)]) -> some View {
        Picker(title, selection: value) { ForEach(items.indices, id: \.self) { index in Text(items[index].1).tag(items[index].0) } }
    }
    private func setNotifications(_ enabled: Bool) {
        guard enabled else { preferences.updateNotifications = false; BackgroundUpdateScheduler.shared.cancel(); return }
        enablingNotifications = true; error = nil
        Task { @MainActor in
            defer { enablingNotifications = false }
            do {
                let granted = try await BackgroundUpdateScheduler.shared.enable(app: app)
                if !granted { error = "通知权限未开启，请在系统设置中允许通知。" }
            } catch { self.error = error.localizedDescription }
        }
    }
}

struct OnboardingView: View {
    var revisiting = false
    @EnvironmentObject private var app: AppStore
    @Environment(\.presentationMode) private var presentation
    @State private var step = 0
    @State private var folderPicker = false
    @State private var error: String?
    var body: some View {
        NavigationView {
            VStack(spacing: 18) {
                ProgressView(value: Double(step + 1), total: 4).padding(.horizontal)
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        pageContent
                        if let error = error { Text(error).foregroundColor(.red) }
                    }.frame(maxWidth: .infinity, alignment: .leading).padding(24)
                }
                HStack {
                    if step > 0 { Button("上一步") { step -= 1 } }
                    Spacer()
                    Button(step == 3 ? "开始使用" : "下一步") {
                        if step < 3 { step += 1 }
                        else { app.preferences.onboardingComplete = true; if revisiting { presentation.wrappedValue.dismiss() } }
                    }.buttonStyle(.borderedProminent).accessibilityIdentifier(step == 3 ? "onboarding.finish" : "onboarding.next")
                }.padding()
            }.navigationTitle("使用引导").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        if revisiting { Button("关闭") { presentation.wrappedValue.dismiss() } }
                    }
                }
        }.navigationViewStyle(.stack)
            .sheet(isPresented: $folderPicker) { DirectoryPicker { url in do { app.preferences.exportDirectoryBookmark = try DownloadDirectory.bookmark(url) } catch { self.error = error.localizedDescription } } }
    }
    @ViewBuilder private var pageContent: some View {
        switch step {
        case 0:
            Text("欢迎使用 Mixn").font(.largeTitle.bold())
            Text("聚合轻之国度与轻书架。来源账号独立，登录、书架与进度只发送给对应来源。")
            Label("发现、搜索、在线阅读与离线导出", systemImage: "books.vertical")
            Label("密码登录使用系统钥匙串保存会话", systemImage: "lock.shield")
        case 1:
            Text("连接来源账号").font(.title.bold())
            Text("可分别登录，也可稍后在“我的”中登录。已有登录状态会保留。")
            ForEach(SourceID.allCases) { id in accountRow(id) }
        case 2:
            Text("离线与导出目录").font(.title.bold())
            Text("离线内容保存在应用专用存储。可授权一个文件夹保存 EPUB / TXT 导出；换目录不会删除旧文件。")
            Text(app.preferences.exportDirectoryBookmark == nil ? "当前：应用专用目录" : "当前：已保留选择的文件夹")
            Button("选择导出文件夹") { folderPicker = true }.buttonStyle(.bordered).accessibilityIdentifier("onboarding.directory")
            Text("可继续使用当前目录。缓存清理不会删除已下载的书籍、必需字体或阅读进度。").font(.caption).foregroundColor(.secondary)
        default:
            Text("准备开始阅读").font(.title.bold())
            Text("在设置中调整字体、翻页方式、阅读背景和通知。iOS 后台更新由系统调度，无法保证固定时间运行。")
            Text("现有账号、阅读偏好与下载目录均会保留。")
        }
    }
    private func accountRow(_ id: SourceID) -> some View {
        NavigationLink(destination: AccountView(sourceID: id)) {
            HStack {
                Text(id.title + "账号")
                Spacer()
                Text(app.sessions[id]?.loggedIn == true ? "已登录" : "登录").foregroundColor(.secondary)
            }
        }.padding(.vertical, 10)
    }
}

struct FontSelectionView: View {
    @EnvironmentObject private var app: AppStore
    var body: some View { FontSelectionContent(fonts: app.fonts, preferences: app.preferences) }
}
private struct FontSelectionContent: View {
    @ObservedObject var fonts: FontStore
    @ObservedObject var preferences: PreferencesStore
    @State private var installing: String?
    @State private var error: String?
    @State private var previewData: Data?
    @State private var previewName = "系统字体"
    @State private var fontToDelete: InstalledFont?
    private let builtins = [("default","系统默认"),("serif","衬线"),("sans","无衬线"),("mono","等宽"),("cursive","手写"),("condensed","窄体"),("rounded","圆体")]
    var body: some View {
        List {
            Section(header: Text("当前字体预览 · \(previewName)")) {
                FontSampleView(data: previewData, family: preferences.reader.font).frame(height: 160)
                Text("来源章节要求的专用字体会优先用于正文，避免显示乱码。").font(.caption).foregroundColor(.secondary)
            }
            Section(header: Text("系统字体")) {
                ForEach(builtins.indices, id: \.self) { index in
                    let font = builtins[index]
                    Button { preferences.reader.font = font.0; preferences.reader.customFontID = nil } label: {
                        HStack { Text(font.1); Spacer(); if preferences.reader.customFontID == nil && preferences.reader.font == font.0 { Image(systemName: "checkmark") } }
                    }
                }
            }
            Section(header: Text("已安装字体")) {
                if fonts.installed.isEmpty { Text("尚未安装字体").foregroundColor(.secondary) }
                ForEach(fonts.installed) { font in
                    Button { preferences.reader.customFontID = font.id } label: {
                        HStack { Text(font.name); Spacer(); if preferences.reader.customFontID == font.id { Image(systemName: "checkmark") } }
                    }.swipeActions { Button("删除", role: .destructive) { fontToDelete = font } }
                }
            }
            Section(header: Text("下载字体"), footer: Text("使用固定版本源文件并核验 SHA-256。下载失败或校验不符时不会安装。手动安装的字体不会随临时缓存清理而删除。")) {
                ForEach(FontStore.catalog) { definition in
                    HStack {
                        VStack(alignment: .leading, spacing: 3) { Text(definition.name); if let hash = definition.sha256 { Text("SHA-256 " + String(hash.prefix(12)) + "…").font(.caption2).foregroundColor(.secondary) } }
                        Spacer()
                        if installing == definition.id { ProgressView() }
                        else if fonts.installed.contains(where: { $0.id == definition.id }) { Text("已安装").font(.caption).foregroundColor(.secondary) }
                        else { Button("下载") { install(definition) }.disabled(installing != nil) }
                    }
                }
            }
            if let error = error { Section { Text(error).foregroundColor(.red) } }
            if let error = fonts.storageError { Section { Text(error).foregroundColor(.red) } }
        }.navigationTitle("字体").navigationBarTitleDisplayMode(.inline)
            .task(id: "\(preferences.reader.customFontID ?? "system"):\(preferences.reader.font)") { await preview() }
            .alert("删除字体？", isPresented: Binding(get: { fontToDelete != nil }, set: { if !$0 { fontToDelete = nil } })) {
                Button("删除", role: .destructive) {
                    guard let font = fontToDelete else { return }
                    do { try fonts.remove(font.id); if preferences.reader.customFontID == font.id { preferences.reader.customFontID = nil } } catch { self.error = error.localizedDescription }
                    fontToDelete = nil
                }
                Button("取消", role: .cancel) { fontToDelete = nil }
            } message: { Text("当前字体会恢复为系统字体；离线章节必需字体仍由下载内容保留。") }
    }
    private func install(_ definition: FontDefinition) {
        installing = definition.id; error = nil
        Task { @MainActor in
            defer { installing = nil }
            do { try await fonts.install(definition); preferences.reader.customFontID = definition.id }
            catch is CancellationError {} catch { self.error = error.localizedDescription }
        }
    }
    @MainActor private func preview() async {
        let id = preferences.reader.customFontID
        previewData = nil; previewName = builtins.first { $0.0 == preferences.reader.font }?.1 ?? "系统字体"
        guard let id = id, let font = fonts.installed.first(where: { $0.id == id }) else { return }
        do {
            let data = try await fonts.fontData(font.url)
            try Task.checkCancellation()
            guard preferences.reader.customFontID == id else { return }
            previewData = data; previewName = font.name
        } catch is CancellationError {} catch { self.error = error.localizedDescription }
    }
}
private struct FontSampleView: UIViewRepresentable {
    let data: Data?
    let family: String
    func makeUIView(context: Context) -> WKWebView {
        let view = WKWebView(frame: .zero); view.isOpaque = false; view.backgroundColor = .clear; view.scrollView.backgroundColor = .clear
        return view
    }
    func updateUIView(_ view: WKWebView, context: Context) {
        let cssFamily = ["default":"-apple-system","sans":"sans-serif","serif":"serif","mono":"monospace","cursive":"cursive","condensed":"sans-serif-condensed","rounded":"ui-rounded"][family] ?? "serif"
        let custom = data.map { bytes -> String in
            let media = (try? FontPayload.format(bytes).media) ?? "application/octet-stream"
            return "@font-face{font-family:MixnPreview;src:url(data:\(media);base64,\(bytes.base64EncodedString()))} "
        } ?? ""
        let actualFamily = data == nil ? cssFamily : "MixnPreview"
        view.loadHTMLString("<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'><style>:root{color-scheme:light dark}body{margin:10px;font:22px/1.8 \(actualFamily)}\(custom)</style><p>春日的微风吹过书页。<br>故事从这里开始，与你相遇。<br>Mixn · 0123456789</p>", baseURL: nil)
    }
}

struct DataStatsView: View {
    @EnvironmentObject private var app: AppStore
    var body: some View { DataStatsContent(library: app.library, fonts: app.fonts) }
}
private struct DataStatsContent: View {
    @EnvironmentObject private var app: AppStore
    @ObservedObject var library: LibraryStore
    @ObservedObject var fonts: FontStore
    @State private var disk: Int64 = 0
    @State private var memory: Int64 = 0
    @State private var temporaryFonts: Int64 = 0
    @State private var durableFonts: Int64 = 0
    @State private var offline: Int64 = 0
    @State private var packages: Int64 = 0
    @State private var clearing = false
    @State private var confirmCache = false
    @State private var confirmPackages = false
    @State private var error: String?
    @State private var notice: String?
    var body: some View {
        List {
            Section(header: Text("本地阅读统计")) {
                metric("阅读过的书籍", "\(library.localHistory.count)")
                metric("保存阅读位置", "\(library.progress.count)")
                metric("离线书籍", "\(library.records.count)")
                metric("已下载章节", "\(library.records.reduce(0) { $0 + $1.completed })")
                metric("书架更新提示", "\(library.updated.count)")
            }
            Section(header: Text("持久内容"), footer: Text("离线书籍、导出、手动安装字体、账号和阅读进度不属于临时缓存；请在下载管理中主动删除书籍。")) {
                metric("离线书籍与导出", bytes(offline))
                metric("离线必需 / 手动安装字体", bytes(durableFonts))
                NavigationLink("管理离线下载", destination: ShelfView(downloadedOnly: true))
            }
            Section(header: Text("可再获取的缓存")) {
                metric("磁盘内容缓存", bytes(disk)); metric("内存内容缓存", bytes(memory)); metric("临时章节字体", bytes(temporaryFonts))
                Button("清理临时缓存", role: .destructive) { confirmCache = true }.disabled(clearing).accessibilityIdentifier("data.clear.cache")
                if clearing { ProgressView("正在清理…") }
            }
            Section(header: Text("iOS 更新包")) {
                metric("已下载 IPA 更新包", bytes(packages))
                Button("单独删除更新包", role: .destructive) { confirmPackages = true }.disabled(clearing || packages == 0).accessibilityIdentifier("data.clear.packages")
                NavigationLink("关于与更新", destination: AboutView())
            }
            if let error = error { Section { Text(error).foregroundColor(.red) } }
            if let notice = notice { Section { Text(notice).foregroundColor(.accentColor) } }
            if let error = library.storageError { Section { Text(error).foregroundColor(.red) } }
        }.navigationTitle("数据与统计").navigationBarTitleDisplayMode(.inline)
            .task { await refresh() }.refreshable { await refresh() }
            .alert("清理临时缓存？", isPresented: $confirmCache) {
                Button("清理", role: .destructive) { clear(packagesOnly: false) }; Button("取消", role: .cancel) {}
            } message: { Text("仅通过缓存所有者清理可再获取的数据；不删除离线书籍、导出、安装字体、更新包、账号或进度。") }
            .alert("删除已下载的 IPA 更新包？", isPresented: $confirmPackages) {
                Button("删除", role: .destructive) { clear(packagesOnly: true) }; Button("取消", role: .cancel) {}
            } message: { Text("不会影响当前安装的应用或阅读内容。") }
    }
    private func metric(_ title: String, _ value: String) -> some View { HStack { Text(title); Spacer(); Text(value).foregroundColor(.secondary) } }
    private func bytes(_ value: Int64) -> String { ByteCountFormatter.string(fromByteCount: value, countStyle: .file) }
    @MainActor private func refresh() async {
        let cache = await app.cache.usage(); disk = cache.disk; memory = cache.memory
        let fontUsage = fonts.usage(); temporaryFonts = fontUsage.temporary; durableFonts = fontUsage.offline
        offline = library.usage()
        do { packages = try UpdatePackageStorage.usage() } catch { self.error = error.localizedDescription }
    }
    private func clear(packagesOnly: Bool) {
        clearing = true; error = nil; notice = nil
        Task { @MainActor in
            defer { clearing = false }
            do {
                if packagesOnly { try UpdatePackageStorage.clear() } else { try await app.clearTemporaryCache() }
                notice = packagesOnly ? "已删除更新包。" : "已清理临时缓存，离线内容与账号均已保留。"
                await refresh()
            } catch { self.error = error.localizedDescription }
        }
    }
}

private enum UpdatePackageStorage {
    static var directory: URL { FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Mixn/Updates", isDirectory: true) }
    static func usage() throws -> Int64 {
        guard FileManager.default.fileExists(atPath: directory.path) else { return 0 }
        return try files().reduce(Int64(0)) { sum, url in sum + Int64(try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) }
    }
    static func files() throws -> [URL] {
        try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey], options: [.skipsHiddenFiles]).filter { try $0.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile == true && $0.pathExtension.lowercased() == "ipa" }
    }
    static func clear() throws {
        guard FileManager.default.fileExists(atPath: directory.path) else { return }
        for file in try files() { try FileManager.default.removeItem(at: file) }
    }
}

struct AboutView: View {
    @State private var release: JSONObject?
    @State private var checking = false
    @State private var downloading = false
    @State private var error: String?
    @State private var downloaded: URL?
    @State private var share = false
    @State private var job: Task<Void, Never>?
    private let repo = "https://github.com/HyperionHXH/lightnovel-android-aggregator"
    private var version: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "未知" }
    private var build: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "未知" }
    private var ipa: JSONObject? { release?.array("assets").map(\.object).first { $0.string("name").lowercased().hasSuffix(".ipa") && webURL($0.string("browser_download_url")) != nil } }
    var body: some View {
        List {
            Section {
                HStack { Image(systemName: "books.vertical.fill").font(.largeTitle).foregroundColor(.accentColor); VStack(alignment: .leading) { Text("Mixn").font(.title.bold()); Text("iOS \(version) (\(build))").foregroundColor(.secondary) } }
                Text("轻小说聚合阅读器 · 原生 iOS").font(.subheadline)
            }
            Section(header: Text("版本与更新"), footer: Text("iOS 使用 IPA 更新包。应用无法自行安装或绕过系统签名；未签名 IPA 需使用你信任的签名与安装流程。不会下载或安装 Android APK。")) {
                Button("检查更新") { check() }.disabled(checking || downloading).accessibilityIdentifier("about.check")
                if checking { ProgressView("读取 GitHub Release…") }
                if let release = release {
                    Text("发布版本：" + release.string("tag_name")).font(.headline)
                    let comparison = Self.compare(release.string("tag_name"), version)
                    Text(comparison > 0 ? "有新版本" : comparison == 0 ? "当前为该版本" : "当前版本高于此发布版本").foregroundColor(.secondary)
                    if !release.string("published_at").isEmpty { Text(release.string("published_at")).font(.caption).foregroundColor(.secondary) }
                    if !release.string("body").isEmpty { Text(release.string("body")).textSelection(.enabled) }
                    if let url = webURL(release.string("html_url")) { Link("打开此版本发布页", destination: url) }
                    if let ipa = ipa, let url = webURL(ipa.string("browser_download_url")) {
                        Link("在浏览器下载 iOS IPA", destination: url)
                        Button("保存 IPA 到应用") { download(ipa) }.disabled(downloading).accessibilityIdentifier("about.download.ipa")
                        Text(ByteCountFormatter.string(fromByteCount: Int64(ipa.int("size")), countStyle: .file)).font(.caption).foregroundColor(.secondary)
                    } else { Text("此 Release 尚未提供 iOS IPA；请到发布页查看可用资源。").font(.caption).foregroundColor(.secondary) }
                }
                if downloading { HStack { ProgressView("下载 iOS IPA…"); Button("取消") { job?.cancel() } } }
                if let file = downloaded { Button("分享已下载的 IPA") { share = true }; Text(file.lastPathComponent).font(.caption) }
                if let error = error { Text(error).foregroundColor(.red).accessibilityIdentifier("about.error") }
            }
            Section(header: Text("项目")) {
                Link("源代码", destination: URL(string: repo)!)
                Link("所有版本", destination: URL(string: repo + "/releases")!)
                Link("反馈问题", destination: URL(string: repo + "/issues")!)
                Link("MIT 开源许可", destination: URL(string: repo + "/blob/main/LICENSE")!)
            }
            Section(header: Text("内容来源")) { ForEach(SourceID.allCases) { id in Link(id.title, destination: id.website) } }
        }.navigationTitle("关于 Mixn").navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: $share) { if let file = downloaded { ShareSheet(items: [file]) } }
            .onDisappear { job?.cancel() }
    }
    private func check() {
        checking = true; error = nil
        job = Task { @MainActor in
            defer { checking = false }
            do {
                var request = URLRequest(url: URL(string: "https://api.github.com/repos/HyperionHXH/lightnovel-android-aggregator/releases/latest")!)
                request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
                request.setValue("Mixn-iOS", forHTTPHeaderField: "User-Agent")
                let result = try await HTTPClient().send(request)
                guard (200..<300).contains(result.status) else { throw SourceFailure.server("GitHub 返回 HTTP \(result.status)") }
                let object = try JSONDecoder().decode(JSONValue.self, from: result.data).object
                guard !object.string("tag_name").isEmpty else { throw SourceFailure.parsing("Release 没有版本号") }
                try Task.checkCancellation(); release = object
            } catch is CancellationError {} catch { self.error = error.localizedDescription }
        }
    }
    private func download(_ asset: JSONObject) {
        guard let url = webURL(asset.string("browser_download_url")), asset.string("name").lowercased().hasSuffix(".ipa") else { return }
        downloading = true; error = nil
        job = Task { @MainActor in
            defer { downloading = false }
            do {
                let (temporary, response) = try await URLSession.shared.download(for: URLRequest(url: url))
                defer { try? FileManager.default.removeItem(at: temporary) }
                guard let response = response as? HTTPURLResponse, (200..<300).contains(response.statusCode) else { throw SourceFailure.server("IPA 下载失败") }
                try Task.checkCancellation()
                let expected = asset.int("size")
                let actual = try temporary.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                guard actual > 0, expected <= 0 || actual == expected else { throw SourceFailure.parsing("IPA 大小与 Release 资源不符") }
                if let digest = asset.string("digest").split(separator: ":").last,
                   asset.string("digest").lowercased().hasPrefix("sha256:") {
                    let actualDigest = try await Task.detached(priority: .utility) { sha256(try Data(contentsOf: temporary, options: .mappedIfSafe)) }.value
                    try Task.checkCancellation()
                    guard actualDigest.caseInsensitiveCompare(String(digest)) == .orderedSame else { throw SourceFailure.parsing("IPA SHA-256 与 Release 资源不符") }
                }
                try FileManager.default.createDirectory(at: UpdatePackageStorage.directory, withIntermediateDirectories: true)
                let destination = UpdatePackageStorage.directory.appendingPathComponent(String(sha256(url.absoluteString).prefix(12)) + ".ipa")
                if FileManager.default.fileExists(atPath: destination.path) { _ = try FileManager.default.replaceItemAt(destination, withItemAt: temporary) }
                else { try FileManager.default.moveItem(at: temporary, to: destination) }
                downloaded = destination
            } catch is CancellationError {} catch { self.error = error.localizedDescription }
        }
    }
    static func compare(_ left: String, _ right: String) -> Int {
        func parts(_ value: String) -> [Int] { value.trimmingCharacters(in: CharacterSet(charactersIn: "vV")).split(separator: "-").first.map { $0.split(separator: ".").map { Int($0) ?? 0 } } ?? [] }
        let a = parts(left), b = parts(right)
        for index in 0..<max(a.count, b.count) { let x = index < a.count ? a[index] : 0, y = index < b.count ? b[index] : 0; if x != y { return x > y ? 1 : -1 } }
        return 0
    }
}
