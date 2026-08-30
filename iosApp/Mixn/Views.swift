import SwiftUI

struct RootView: View {
    @ObservedObject var model: MixnAppModel

    var body: some View {
        TabView {
            DiscoverView(model: model)
                .tabItem { Label("发现", systemImage: "sparkles") }
            BookshelfView(model: model)
                .tabItem { Label("书架", systemImage: "books.vertical") }
            ProfileView(model: model)
                .tabItem { Label("我的", systemImage: "person.crop.circle") }
        }
        .tint(.teal)
    }
}

struct DiscoverView: View {
    @ObservedObject var model: MixnAppModel
    @State private var query = ""

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("来源", selection: $model.selectedSource) {
                    ForEach(MixnSource.allCases) { source in
                        Text(source.displayName).tag(source)
                    }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)
                .padding(.top, 8)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(MixnFeed.allCases.filter { model.selectedSource == .kingdom || ($0 != .original && $0 != .fanfic) }) { feed in
                            Button(feed.label) {
                                model.selectedFeed = feed
                                Task { await model.loadDiscover() }
                            }
                            .buttonStyle(.bordered)
                            .tint(model.selectedFeed == feed ? .teal : .gray)
                        }
                    }
                    .padding(.horizontal)
                    .padding(.vertical, 8)
                }
                if model.isLoading {
                    ProgressView().padding(.top, 20)
                }
                if let message = model.message, model.books.isEmpty {
                    ContentUnavailableView(message, systemImage: "wifi.exclamationmark")
                } else {
                    List(model.books) { book in
                        NavigationLink(value: book) { BookRow(book: book) }
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle("发现")
            .searchable(text: $query, prompt: "搜索书名或作者")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { Task { await model.loadDiscover() } } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .accessibilityLabel("刷新")
                }
            }
            .navigationDestination(for: MixnBook.self) { book in
                BookDetailView(book: book, model: model)
            }
            .task { await model.loadDiscover() }
            .task(id: query) {
                guard !query.isEmpty else { return }
                try? await Task.sleep(for: .milliseconds(400))
                guard !Task.isCancelled else { return }
                await model.search(query)
            }
            .onChange(of: model.selectedSource) { _, source in
                if source == .shelf && (model.selectedFeed == .original || model.selectedFeed == .fanfic) {
                    model.selectedFeed = .popular
                }
                Task { await model.loadDiscover() }
            }
        }
    }
}

struct BookshelfView: View {
    @ObservedObject var model: MixnAppModel

    var body: some View {
        NavigationStack {
            ContentUnavailableView("统一书架", systemImage: "books.vertical", description: Text("书架同步接口正在接入。"))
            .navigationTitle("书架")
        }
    }
}

struct ProfileView: View {
    @ObservedObject var model: MixnAppModel
    @State private var loginSource: MixnSource?

    var body: some View {
        NavigationStack {
            List {
                Section("来源账号") {
                    AccountRow(
                        source: .kingdom,
                        signedIn: model.kingdomLoggedIn,
                        action: { loginSource = .kingdom },
                    )
                    AccountRow(
                        source: .shelf,
                        signedIn: model.shelfLoggedIn,
                        action: { loginSource = .shelf },
                    )
                }
                Section("关于") {
                    LabeledContent("应用", value: "Mixn iOS")
                    LabeledContent("版本", value: "0.1 开发预览")
                }
            }
            .navigationTitle("我的")
            .sheet(item: $loginSource) { source in
                LoginView(source: source, model: model)
            }
        }
    }
}

private struct AccountRow: View {
    let source: MixnSource
    let signedIn: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                Image(systemName: source == .kingdom ? "crown" : "books.vertical")
                    .frame(width: 28)
                VStack(alignment: .leading) {
                    Text(source.displayName)
                    Text(signedIn ? "已登录" : "未登录")
                        .font(.caption)
                        .foregroundStyle(signedIn ? .green : .secondary)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }
        }
        .foregroundStyle(.primary)
    }
}

struct LoginView: View {
    let source: MixnSource
    @ObservedObject var model: MixnAppModel
    @Environment(\.dismiss) private var dismiss
    @State private var identifier = ""
    @State private var password = ""
    @State private var isSubmitting = false

    var body: some View {
        NavigationStack {
            Form {
                Section(source.displayName) {
                    TextField(source == .kingdom ? "用户名或邮箱" : "邮箱", text: $identifier)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("密码", text: $password)
                }
                Section {
                    Button {
                        isSubmitting = true
                        Task {
                            let success = await model.login(source: source, identifier: identifier, password: password)
                            isSubmitting = false
                            if success { dismiss() }
                        }
                    } label: {
                        HStack {
                            Spacer()
                            if isSubmitting { ProgressView() } else { Text("登录") }
                            Spacer()
                        }
                    }
                    .disabled(identifier.isEmpty || password.isEmpty || isSubmitting)
                }
                Text("密码只在登录请求期间使用，会话令牌保存在 iOS 钥匙串中。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .navigationTitle("登录")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
            }
        }
    }
}

struct BookDetailView: View {
    let book: MixnBook
    @ObservedObject var model: MixnAppModel
    @State private var chapterText: String?
    @State private var error: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack(alignment: .top, spacing: 16) {
                    AsyncImage(url: book.coverURL) { image in
                        image.resizable().scaledToFill()
                    } placeholder: {
                        RoundedRectangle(cornerRadius: 8).fill(.quaternary)
                    }
                    .frame(width: 96, height: 136)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    VStack(alignment: .leading, spacing: 6) {
                        Text(book.title).font(.title3.bold())
                        Text(book.sourceLabel).font(.caption).foregroundStyle(.teal)
                        if !book.author.isEmpty { Text(book.author).foregroundStyle(.secondary) }
                    }
                }
                if !book.summary.isEmpty { Text(book.summary).font(.body) }
                NavigationLink("开始阅读") {
                    ReaderView(book: book, model: model)
                }
                .buttonStyle(.borderedProminent)
                if let error { Text(error).foregroundStyle(.red) }
            }
            .padding()
        }
        .navigationTitle("书籍详情")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct ReaderView: View {
    let book: MixnBook
    @ObservedObject var model: MixnAppModel
    @State private var text = "正在加载正文..."

    var body: some View {
        ScrollView {
            Text(text)
                .frame(maxWidth: .infinity, alignment: .leading)
                .font(.system(size: 19, design: .serif))
                .lineSpacing(10)
                .padding()
        }
        .navigationTitle(book.title)
        .task {
            do {
                switch book.source {
                case .kingdom:
                    text = try await model.api.kingdomChapter(book: book)
                case .shelf:
                    text = try await model.api.shelfChapter(book: book)
                }
            } catch {
                text = error.localizedDescription
            }
        }
    }
}

private struct BookRow: View {
    let book: MixnBook

    var body: some View {
        HStack(spacing: 12) {
            AsyncImage(url: book.coverURL) { image in
                image.resizable().scaledToFill()
            } placeholder: {
                RoundedRectangle(cornerRadius: 6).fill(.quaternary)
            }
            .frame(width: 52, height: 72)
            .clipShape(RoundedRectangle(cornerRadius: 6))
            VStack(alignment: .leading, spacing: 4) {
                Text(book.title).font(.headline).lineLimit(2)
                if !book.author.isEmpty { Text(book.author).font(.subheadline).foregroundStyle(.secondary) }
                Text(book.sourceLabel).font(.caption).foregroundStyle(.teal)
            }
        }
        .padding(.vertical, 4)
    }
}
