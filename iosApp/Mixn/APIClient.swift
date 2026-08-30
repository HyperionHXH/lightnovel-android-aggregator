import CryptoKit
import Foundation
import Security

actor MixnAPIClient {
    private let urlSession: URLSession
    private let kingdomOrigin = URL(string: "https://www.lightnovel.fun/api/pc-proxy/")!
    private let shelfOrigin = URL(string: "https://api.lightnovel.life")!
    private let kingdomKeyName = "mixn.kingdom.security-key"
    private let shelfAccessName = "mixn.shelf.access-token"
    private let shelfRefreshName = "mixn.shelf.refresh-token"

    init(urlSession: URLSession = .shared) {
        self.urlSession = urlSession
    }

    func kingdomHasSession() -> Bool {
        KeychainStore.read(kingdomKeyName) != nil
    }

    func shelfHasSession() -> Bool {
        KeychainStore.read(shelfAccessName) != nil && KeychainStore.read(shelfRefreshName) != nil
    }

    func kingdomLogin(identifier: String, password: String) async throws {
        let data = try await kingdomPost(
            path: "api/bff/auth-password-login-v1",
            body: ["username": identifier.trimmingCharacters(in: .whitespacesAndNewlines), "password": password],
        )
        let key = firstString(in: data, keys: ["security_key", "securityKey", "token"])
        guard let key, !key.isEmpty else { throw MixnAPIError.invalidResponse }
        KeychainStore.save(key, named: kingdomKeyName)
    }

    func shelfLogin(email: String, password: String) async throws {
        let digest = SHA256.hash(data: Data(password.utf8))
        let hash = digest.map { String(format: "%02x", $0) }.joined()
        let payload = try await shelfPost(
            path: "/api/user/login",
            body: ["email": email.trimmingCharacters(in: .whitespacesAndNewlines), "password": hash],
            bearer: nil,
        )
        guard let access = firstString(in: payload, keys: ["Token", "token"]),
              let refresh = firstString(in: payload, keys: ["RefreshToken", "refreshToken"])
        else { throw MixnAPIError.invalidResponse }
        KeychainStore.save(access, named: shelfAccessName)
        KeychainStore.save(refresh, named: shelfRefreshName)
    }

    func shelfDiscover(feed: MixnFeed, page: Int = 1, pageSize: Int = 20) async throws -> [MixnBook] {
        guard let token = KeychainStore.read(shelfAccessName), !token.isEmpty else {
            throw MixnAPIError.unsupported("请先登录轻书架")
        }
        let response: Any
        switch feed {
        case .popular:
            response = try await shelfInvoke(
                target: "GetBookList",
                params: ["Page": page, "Size": pageSize, "Order": "view", "IgnoreJapanese": true, "IgnoreAI": true],
                token: token,
            )
        case .rank:
            response = try await shelfInvoke(target: "GetRank", params: ["Days": 7], token: token)
        case .newest:
            response = try await shelfInvoke(
                target: "GetBookList",
                params: ["Page": page, "Size": pageSize, "Order": "new", "IgnoreJapanese": true, "IgnoreAI": true],
                token: token,
            )
        case .updated:
            response = try await shelfInvoke(
                target: "GetBookList",
                params: ["Page": page, "Size": pageSize, "Order": "latest", "IgnoreJapanese": true, "IgnoreAI": true],
                token: token,
            )
        case .original, .fanfic:
            throw MixnAPIError.unsupported("轻书架没有对应的分类榜单")
        }
        return try parseBooks(response, source: .shelf)
    }

    func shelfSearch(query: String, page: Int = 1, pageSize: Int = 30) async throws -> [MixnBook] {
        guard let token = KeychainStore.read(shelfAccessName), !token.isEmpty else {
            throw MixnAPIError.unsupported("请先登录轻书架")
        }
        let response = try await shelfInvoke(
            target: "GetBookList",
            params: [
                "KeyWords": query.trimmingCharacters(in: .whitespacesAndNewlines),
                "Page": page,
                "Size": pageSize,
                "IgnoreJapanese": false,
                "IgnoreAI": false,
            ],
            token: token,
        )
        return try parseBooks(response, source: .shelf)
    }

    func shelfChapter(book: MixnBook, sortNumber: Int = 1) async throws -> String {
        guard book.source == .shelf, let token = KeychainStore.read(shelfAccessName), !token.isEmpty else {
            throw MixnAPIError.unsupported("请先登录轻书架")
        }
        let response = try await shelfInvoke(
            target: "GetNovelContent",
            params: ["Bid": Int64(book.remoteID) ?? 0, "SortNum": sortNumber],
            token: token,
        )
        let html = firstString(in: response as? [String: Any] ?? [:], keys: ["Content", "content", "html", "bodyText"]) ?? ""
        return stripHTML(html)
    }

    func kingdomDiscover(feed: MixnFeed, page: Int = 1, pageSize: Int = 20) async throws -> [MixnBook] {
        let path: String
        var body: [String: Any] = [
            "page": page,
            "page_size": pageSize,
            "pageSize": pageSize,
            "read_filter": "all",
            "status_filter": "all",
            "category_filter": "all",
        ]
        switch feed {
        case .popular:
            path = "api/bff/home-feed-v1"
        case .rank:
            path = "api/bff/book-rank-list-v1"
            body = ["rank_scene": "weekly_hot", "page": page, "page_size": pageSize, "pageSize": pageSize]
        case .newest:
            path = "api/bff/book-rank-list-v1"
            body = ["rank_scene": "daily_fresh", "page": page, "page_size": pageSize, "pageSize": pageSize]
        case .original:
            path = "api/bff/home-original-feed-v1"
        case .fanfic:
            path = "api/bff/home-fanfic-feed-v1"
        case .updated:
            path = "api/bff/home-recent-updates-feed-v1"
        }
        return try parseBooks(try await kingdomPost(path: path, body: body), source: .kingdom)
    }

    func kingdomSearch(query: String) async throws -> [MixnBook] {
        let body: [String: Any] = [
            "q": query.trimmingCharacters(in: .whitespacesAndNewlines),
            "scope": "",
            "source": "",
            "primary_tag": "",
            "channel_code": "",
            "work_type": "",
            "preset": "",
            "source_type": "",
            "filters": [:] as [String: Any],
            "word_count_bucket": "",
            "status_bucket": "",
            "page": 0,
            "pageSize": 30,
            "sort": "relevance",
        ]
        return try parseBooks(
            try await kingdomPost(path: "api/bff/apk-search-result-v1", body: body),
            source: .kingdom,
        )
    }

    func kingdomChapter(book: MixnBook) async throws -> String {
        guard book.source == .kingdom, let chapterID = book.defaultChapterID else {
            throw MixnAPIError.unsupported("这本书暂无可读章节")
        }
        let data = try await kingdomPost(
            path: "api/new-content-read/get-chapter-detail",
            body: ["book_id": Int64(book.remoteID) ?? 0, "chapter_id": chapterID],
        )
        let html = firstString(in: data, keys: ["body_html", "bodyHtml", "content", "body_text", "bodyText"]) ?? ""
        return stripHTML(html)
    }

    private func shelfInvoke(target: String, params: [String: Any], token: String) async throws -> Any {
        var components = URLComponents(string: "wss://api.lightnovel.life/hub/api")!
        components.queryItems = [URLQueryItem(name: "access_token", value: token)]
        guard let url = components.url else { throw MixnAPIError.invalidResponse }
        var request = URLRequest(url: url, timeoutInterval: 30)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let socket = urlSession.webSocketTask(with: request)
        socket.resume()
        defer { socket.cancel(with: .goingAway, reason: nil) }

        let separator = "\u{001e}"
        let handshake = try JSONSerialization.data(withJSONObject: ["protocol": "json", "version": 1])
        try await socket.send(.string(String(data: handshake, encoding: .utf8)! + separator))
        let handshakeFrame = try await receiveWebSocketText(socket)
            .components(separatedBy: separator)
            .first(where: { !$0.isEmpty })
        if let handshakeFrame,
           let handshakeObject = try JSONSerialization.jsonObject(with: Data(handshakeFrame.utf8)) as? [String: Any],
           let error = handshakeObject["error"] as? String {
            throw MixnAPIError.server(error)
        }

        let invocation: [String: Any] = [
            "type": 1,
            "invocationId": "1",
            "target": target,
            "arguments": [params, ["UseGzip": false]],
        ]
        let invocationData = try JSONSerialization.data(withJSONObject: invocation)
        try await socket.send(.string(String(data: invocationData, encoding: .utf8)! + separator))

        while true {
            let payload = try await receiveWebSocketText(socket)
            for frame in payload.components(separatedBy: separator) where !frame.isEmpty {
                guard let object = try JSONSerialization.jsonObject(with: Data(frame.utf8)) as? [String: Any] else { continue }
                if let error = object["error"] as? String { throw MixnAPIError.server(error) }
                if (object["type"] as? NSNumber)?.intValue == 3 {
                    let result = object["result"] ?? NSNull()
                    return try unwrapShelfResponse(result)
                }
                if (object["type"] as? NSNumber)?.intValue == 7 {
                    throw MixnAPIError.server((object["error"] as? String) ?? "轻书架关闭了连接")
                }
            }
        }
    }

    private func receiveWebSocketText(_ socket: URLSessionWebSocketTask) async throws -> String {
        switch try await socket.receive() {
        case .string(let text): return text
        case .data(let data):
            guard let text = String(data: data, encoding: .utf8) else { throw MixnAPIError.invalidResponse }
            return text
        @unknown default: throw MixnAPIError.invalidResponse
        }
    }

    private func unwrapShelfResponse(_ value: Any) throws -> Any {
        guard let envelope = value as? [String: Any] else { throw MixnAPIError.invalidResponse }
        if let success = envelope["Success"] as? NSNumber, !success.boolValue {
            throw MixnAPIError.server(firstString(in: envelope, keys: ["Msg", "msg", "message"]) ?? "轻书架请求失败")
        }
        return envelope["Response"] ?? envelope["response"] ?? envelope
    }

    private func kingdomPost(path: String, body: [String: Any]) async throws -> [String: Any] {
        var enriched = body
        if let key = KeychainStore.read(kingdomKeyName) { enriched["security_key"] = key }
        guard let url = URL(string: path, relativeTo: kingdomOrigin) else { throw MixnAPIError.invalidResponse }
        return try await post(url: url, body: enriched, headers: [
            "Origin": "https://www.lightnovel.fun",
            "Referer": "https://www.lightnovel.fun/",
        ])
    }

    private func shelfPost(path: String, body: [String: Any], bearer: String?) async throws -> [String: Any] {
        guard let url = URL(string: path, relativeTo: shelfOrigin) else { throw MixnAPIError.invalidResponse }
        var headers = ["Origin": "https://lightnovel.life", "Referer": "https://lightnovel.life/"]
        if let bearer { headers["Authorization"] = "Bearer \(bearer)" }
        return try await post(url: url, body: body, headers: headers)
    }

    private func post(url: URL, body: [String: Any], headers: [String: String]) async throws -> [String: Any] {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        headers.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
        let (data, response) = try await urlSession.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw MixnAPIError.invalidResponse }
        guard (200...299).contains(http.statusCode) else {
            throw MixnAPIError.server("服务器返回 \(http.statusCode)")
        }
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw MixnAPIError.invalidResponse
        }
        if let code = root["code"] as? NSNumber, code.intValue != 0 {
            let message = firstString(in: root, keys: ["message", "msg", "error"]) ?? "请求失败"
            throw MixnAPIError.server(message)
        }
        if let nested = root["data"] as? [String: Any] { return nested }
        return root
    }

    private func parseBooks(_ root: Any, source: MixnSource) throws -> [MixnBook] {
        let dictionaries = collectDictionaries(root)
        var seen = Set<String>()
        return dictionaries.compactMap { dictionary in
            guard let id = firstString(in: dictionary, keys: ["id", "book_id", "bookId"]),
                  let title = firstString(in: dictionary, keys: ["title", "name"]),
                  !id.isEmpty, !title.isEmpty
            else { return nil }
            let key = "\(source.rawValue):\(id)"
            guard seen.insert(key).inserted else { return nil }
            let cover = firstString(in: dictionary, keys: ["cover_url", "coverUrl", "cover", "Cover"])
            let chapter = firstString(in: dictionary, keys: ["default_chapter_id", "defaultChapterId", "SortNum", "sortNum"])
            return MixnBook(
                id: key,
                source: source,
                remoteID: id,
                title: title,
                author: firstString(in: dictionary, keys: ["author", "author_name", "UserName"]) ?? "",
                summary: firstString(in: dictionary, keys: ["summary", "introduction", "Introduction"]) ?? "",
                coverURL: cover.flatMap(URL.init(string:)),
                defaultChapterID: chapter.flatMap(Int64.init),
            )
        }
    }

    private func collectDictionaries(_ value: Any) -> [[String: Any]] {
        if let dictionary = value as? [String: Any] {
            return [dictionary] + dictionary.values.flatMap(collectDictionaries)
        }
        if let array = value as? [Any] {
            return array.flatMap(collectDictionaries)
        }
        return []
    }

    private func firstString(in dictionary: [String: Any], keys: [String]) -> String? {
        for key in keys {
            if let value = dictionary[key] as? String, !value.isEmpty { return value }
            if let value = dictionary[key] as? NSNumber { return value.stringValue }
        }
        for value in dictionary.values {
            if let nested = value as? [String: Any], let found = firstString(in: nested, keys: keys) { return found }
            if let nested = value as? [Any] {
                for item in nested {
                    if let nested = item as? [String: Any], let found = firstString(in: nested, keys: keys) { return found }
                }
            }
        }
        return nil
    }

    private func stripHTML(_ value: String) -> String {
        value
            .replacingOccurrences(of: "<br\\s*/?>", with: "\n", options: .regularExpression)
            .replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
            .replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "&amp;", with: "&")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

private enum KeychainStore {
    static func save(_ value: String, named name: String) {
        let data = Data(value.utf8)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: name,
        ]
        SecItemDelete(query as CFDictionary)
        var item = query
        item[kSecValueData as String] = data
        SecItemAdd(item as CFDictionary, nil)
    }

    static func read(_ name: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: name,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data
        else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
