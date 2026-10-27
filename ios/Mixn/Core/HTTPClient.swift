import Foundation
import Security
import CryptoKit

struct HTTPResult { var status: Int; var data: Data; var headers: [AnyHashable: Any] }
final class HTTPClient {
    let session: URLSession
    init(session: URLSession? = nil) {
        if let session = session { self.session = session; return }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 45
        configuration.httpCookieStorage = nil
        configuration.urlCache = nil
        self.session = URLSession(configuration: configuration)
    }
    func send(_ request: URLRequest) async throws -> HTTPResult {
        do {
            let (data, response) = try await session.data(for: request)
            try Task.checkCancellation()
            guard let response = response as? HTTPURLResponse else { throw SourceFailure.parsing("服务器未返回 HTTP 响应") }
            return HTTPResult(status: response.statusCode, data: data, headers: response.allHeaderFields)
        } catch let error as URLError {
            if error.code == .cancelled { throw CancellationError() }
            if error.code == .timedOut { throw SourceFailure.timeout }
            throw SourceFailure.network(error.localizedDescription)
        }
    }
    func json(url: URL, body: JSONObject, headers: [String: String] = [:]) async throws -> (Int, JSONValue) {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = try JSONEncoder().encode(JSONValue.object(body))
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        for (key, value) in headers { request.setValue(value, forHTTPHeaderField: key) }
        let result = try await send(request)
        guard let json = try? JSONDecoder().decode(JSONValue.self, from: result.data) else {
            throw SourceFailure.server("来源返回 HTTP \(result.status)，响应不是 JSON")
        }
        return (result.status, json)
    }
    func bytes(_ url: URL) async throws -> Data {
        let result = try await send(URLRequest(url: url))
        guard (200..<300).contains(result.status) else { throw SourceFailure.server("资源服务器返回 \(result.status)") }
        return result.data
    }
}
protocol SecretStore {
    func read(_ key: String) throws -> String?
    func write(_ key: String, value: String?) throws
}
struct KeychainStore: SecretStore {
    let service: String
    init(service: String = "io.github.hyperionhxh.mixn.sources") { self.service = service }
    private func query(_ key: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: key]
    }
    func read(_ key: String) throws -> String? {
        var q = query(key)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw SourceFailure.server("无法读取系统钥匙串（\(status)）") }
        return String(data: data, encoding: .utf8)
    }
    func write(_ key: String, value: String?) throws {
        let q = query(key)
        guard let value = value else {
            let status = SecItemDelete(q as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else { throw SourceFailure.server("钥匙串删除失败（\(status)）") }
            return
        }
        let data = Data(value.utf8)
        let status = SecItemUpdate(q as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var added = q
            added[kSecValueData as String] = data
            added[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            let insert = SecItemAdd(added as CFDictionary, nil)
            guard insert == errSecSuccess else { throw SourceFailure.server("钥匙串写入失败（\(insert)）") }
        } else if status != errSecSuccess { throw SourceFailure.server("钥匙串更新失败（\(status)）") }
    }
}
func sha256(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
func sha256(_ string: String) -> String { sha256(Data(string.utf8)) }
