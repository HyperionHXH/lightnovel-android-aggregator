import Foundation
import zlib

@MainActor final class ShelfRateLimiter {
    private var starts: [TimeInterval] = []
    private let now: () -> TimeInterval
    private let sleep: (TimeInterval) async throws -> Void
    init(now: @escaping () -> TimeInterval = { ProcessInfo.processInfo.systemUptime },
         sleep: @escaping (TimeInterval) async throws -> Void = { try await Task.sleep(nanoseconds: UInt64(max(0, $0) * 1_000_000_000)) }) {
        self.now = now
        self.sleep = sleep
    }
    func acquire() async throws {
        while true {
            try Task.checkCancellation()
            let time = now()
            starts.removeAll { time - $0 >= 5.5 }
            let gap = starts.last.map { max(0, 0.5 - (time - $0)) } ?? 0
            let window = starts.count >= 9 ? max(0.001, 5.5 - (time - starts[0])) : 0
            let delay = max(gap, window)
            if delay <= 0 { starts.append(time); return }
            try await sleep(delay)
        }
    }
}

protocol ShelfSocket: AnyObject {
    func resume()
    func send(_ text: String) async throws
    func receive() async throws -> Data
    func cancel()
}

private final class ShelfURLSocket: ShelfSocket {
    private let task: URLSessionWebSocketTask
    init(_ task: URLSessionWebSocketTask) { self.task = task }
    func resume() { task.resume() }
    func send(_ text: String) async throws {
        let result = ShelfSocketSend()
        try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                guard result.install(continuation) else { return }
                task.send(.string(text)) { error in
                    if let error = error { result.finish(.failure(error)) }
                    else { result.finish(.success(())) }
                }
            }
            try Task.checkCancellation()
        }, onCancel: { result.finish(.failure(CancellationError())) })
    }
    func receive() async throws -> Data {
        switch try await task.receive() {
        case .string(let text): return Data(text.utf8)
        case .data(let data): return data
        @unknown default: throw SourceFailure.parsing("轻书架返回了未知的 WebSocket 消息")
        }
    }
    func cancel() { task.cancel(with: .goingAway, reason: nil) }
    deinit { task.cancel(with: .goingAway, reason: nil) }
}

private final class ShelfSocketSend {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Void, Error>?
    private var result: Result<Void, Error>?
    func install(_ continuation: CheckedContinuation<Void, Error>) -> Bool {
        lock.lock()
        if let result = result { lock.unlock(); continuation.resume(with: result); return false }
        self.continuation = continuation
        lock.unlock()
        return true
    }
    func finish(_ result: Result<Void, Error>) {
        lock.lock()
        guard self.result == nil else { lock.unlock(); return }
        self.result = result
        let continuation = self.continuation
        self.continuation = nil
        lock.unlock()
        continuation?.resume(with: result)
    }
}

/// SignalR's JSON record separator is independent of WebSocket message boundaries.
struct ShelfSignalRFrames {
    private var buffer = Data()
    mutating func append(_ data: Data) throws -> [JSONObject] {
        buffer.append(data)
        guard buffer.count <= 20 * 1024 * 1024 else { throw SourceFailure.parsing("轻书架消息过大") }
        var frames: [JSONObject] = []
        while let end = buffer.firstIndex(of: 0x1e) {
            let frame = Data(buffer[..<end])
            buffer.removeSubrange(...end)
            if frame.isEmpty { continue }
            let value: JSONValue
            do { value = try JSONDecoder().decode(JSONValue.self, from: frame) }
            catch { throw SourceFailure.parsing("轻书架 SignalR 消息不是有效 JSON") }
            guard case .object(let object) = value else { throw SourceFailure.parsing("轻书架 SignalR 消息无效") }
            frames.append(object)
        }
        return frames
    }
}

@MainActor final class ShelfSignalR {
    private struct Pending {
        let continuation: CheckedContinuation<JSONValue, Error>
        let timeout: Task<Void, Never>
        let sender: Task<Void, Never>
    }
    private let http: HTTPClient
    private let limiter: ShelfRateLimiter
    private let socketFactory: (URLRequest) -> ShelfSocket
    private let origins: [URL]
    private let handshakeTimeout: TimeInterval
    private let invocationTimeout: TimeInterval
    private var socket: ShelfSocket?
    private var connectionTask: Task<Void, Never>?
    private var receiver: Task<Void, Never>?
    private var heartbeat: Task<Void, Never>?
    private var handshakeTimer: Task<Void, Never>?
    private var ready: [UUID: CheckedContinuation<Void, Error>] = [:]
    private var pending: [String: Pending] = [:]
    private var parser = ShelfSignalRFrames()
    private var epoch: UInt64 = 0
    private var connected = false
    private var boundToken: String?
    private var boundOrigin: URL?
    private var sequence: UInt64 = 0

    init(http: HTTPClient, limiter: ShelfRateLimiter,
         origins: [URL] = [URL(string: "https://api.lightnovel.life")!, URL(string: "https://cf-api.lightnovel.life")!],
         handshakeTimeout: TimeInterval = 15, invocationTimeout: TimeInterval = 30,
         socketFactory: ((URLRequest) -> ShelfSocket)? = nil) {
        self.http = http
        self.limiter = limiter
        self.origins = origins
        self.handshakeTimeout = handshakeTimeout
        self.invocationTimeout = invocationTimeout
        self.socketFactory = socketFactory ?? { ShelfURLSocket(http.session.webSocketTask(with: $0)) }
    }

    func invoke(_ target: String, params: JSONValue, token: String?) async throws -> JSONValue {
        guard !origins.isEmpty else { throw SourceFailure.network("轻书架服务器未配置") }
        var lastError: Error = SourceFailure.network("无法连接轻书架")
        let endpoints: [URL]
        if boundToken == token, let current = boundOrigin, origins.contains(current) {
            endpoints = [current] + origins.filter { $0 != current }
        } else { endpoints = origins }
        for (index, origin) in endpoints.enumerated() {
            do {
                try Task.checkCancellation()
                try await connect(origin: origin, token: token)
                try await limiter.acquire()
                try Task.checkCancellation()
                return try await sendInvocation(target, params: params)
            } catch is CancellationError { throw CancellationError() }
            catch {
                try Task.checkCancellation()
                lastError = error
                guard index + 1 < endpoints.count, Self.canFallback(error) else { throw error }
                // An explicit server error is never replayed on the backup endpoint.
                reset(error: SourceFailure.network("轻书架切换连接"))
            }
        }
        throw lastError
    }

    func reset(error: Error = CancellationError()) {
        epoch &+= 1
        teardown(error)
    }

    private static func canFallback(_ error: Error) -> Bool {
        if let failure = error as? SourceFailure {
            switch failure { case .network, .timeout: return true; default: return false }
        }
        if let error = error as? URLError { return error.code != .cancelled }
        return false
    }

    private func connect(origin: URL, token: String?) async throws {
        try Task.checkCancellation()
        if (socket != nil || connectionTask != nil), (boundToken != token || boundOrigin != origin) {
            reset(error: SourceFailure.network("轻书架会话已切换"))
        }
        if connected { return }
        let id = UUID()
        try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                guard !Task.isCancelled else { continuation.resume(throwing: CancellationError()); return }
                ready[id] = continuation
                if connectionTask == nil && socket == nil { startConnection(origin: origin, token: token) }
            }
        }, onCancel: { [weak self] in
            Task { @MainActor in self?.cancelConnectionWaiter(id) }
        })
        try Task.checkCancellation()
    }

    private func cancelConnectionWaiter(_ id: UUID) {
        guard let waiter = ready.removeValue(forKey: id) else { return }
        waiter.resume(throwing: CancellationError())
        if ready.isEmpty && !connected { reset() }
    }

    private func startConnection(origin: URL, token: String?) {
        epoch &+= 1
        let generation = epoch
        boundOrigin = origin
        boundToken = token
        handshakeTimer = Task { [weak self] in
            guard let delay = self?.handshakeTimeout else { return }
            do { try await Task.sleep(nanoseconds: UInt64(max(0, delay) * 1_000_000_000)) }
            catch { return }
            self?.fail(SourceFailure.timeout, epoch: generation)
        }
        connectionTask = Task { [weak self] in
            guard let self = self else { return }
            do {
                try await self.limiter.acquire()
                var request = URLRequest(url: origin.appendingPathComponent("hub/api/negotiate").appending(queryItems: [URLQueryItem(name: "negotiateVersion", value: "1")]))
                request.httpMethod = "POST"
                request.httpBody = Data()
                request.setValue("https://www.lightnovel.life", forHTTPHeaderField: "Origin")
                if let token = token, !token.isEmpty { request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }
                let response = try await self.http.send(request)
                try Task.checkCancellation()
                guard generation == self.epoch else { return }
                if response.status == 401 || response.status == 403 { throw SourceFailure.authentication("轻书架登录已失效") }
                if response.status == 429 { throw SourceFailure.rateLimited }
                guard (200..<300).contains(response.status) else { throw SourceFailure.network("轻书架协商连接失败（\(response.status)）") }
                guard let root = try? JSONDecoder().decode(JSONValue.self, from: response.data), case .object(let negotiation) = root else { throw SourceFailure.parsing("轻书架协商响应无效") }
                let error = negotiation.string("error", "Error")
                if !error.isEmpty { throw Self.serverError(error) }
                let connectionToken = negotiation.string("connectionToken", "connectionId")
                guard !connectionToken.isEmpty else { throw SourceFailure.parsing("轻书架协商响应缺少连接令牌") }
                if let transports = negotiation["availableTransports"], !transports.array.contains(where: { $0.object.string("transport") == "WebSockets" }) {
                    throw SourceFailure.network("轻书架未提供 WebSocket 传输")
                }
                var components = URLComponents(url: origin.appendingPathComponent("hub/api"), resolvingAgainstBaseURL: false)!
                components.scheme = "wss"
                components.queryItems = [URLQueryItem(name: "id", value: connectionToken)]
                if let token = token, !token.isEmpty { components.queryItems?.append(URLQueryItem(name: "access_token", value: token)) }
                guard let url = components.url else { throw SourceFailure.parsing("轻书架连接地址无效") }
                var socketRequest = URLRequest(url: url)
                socketRequest.setValue("https://www.lightnovel.life", forHTTPHeaderField: "Origin")
                if let token = token, !token.isEmpty { socketRequest.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }
                let socket = self.socketFactory(socketRequest)
                self.socket = socket
                self.parser = ShelfSignalRFrames()
                socket.resume()
                self.startReceiver(socket, epoch: generation)
                try await socket.send("{\"protocol\":\"json\",\"version\":1}\u{1e}")
                try Task.checkCancellation()
                if self.epoch == generation { self.connectionTask = nil }
            } catch is CancellationError {
                self.fail(CancellationError(), epoch: generation)
            } catch {
                self.fail(Self.transportError(error), epoch: generation)
            }
        }
    }

    private func startReceiver(_ socket: ShelfSocket, epoch generation: UInt64) {
        receiver = Task { [weak self, socket] in
            do {
                while !Task.isCancelled {
                    let data = try await socket.receive()
                    try Task.checkCancellation()
                    guard let self = self, self.epoch == generation else { return }
                    let frames = try self.parser.append(data)
                    for frame in frames {
                        guard self.epoch == generation else { return }
                        self.handle(frame, epoch: generation)
                    }
                }
            } catch is CancellationError { return }
            catch { self?.fail(Self.transportError(error), epoch: generation) }
        }
    }

    private func handle(_ message: JSONObject, epoch generation: UInt64) {
        if !connected {
            let error = message.string("error")
            if !error.isEmpty { fail(Self.serverError(error), epoch: generation); return }
            guard message["type"] == nil else { fail(SourceFailure.parsing("轻书架未确认 SignalR 握手"), epoch: generation); return }
            connected = true
            handshakeTimer?.cancel(); handshakeTimer = nil
            let continuations = Array(ready.values); ready.removeAll()
            for continuation in continuations { continuation.resume() }
            startHeartbeat(epoch: generation)
            return
        }
        switch message.int("type") {
        case 3:
            let id = message.string("invocationId")
            guard !id.isEmpty else { return }
            let error = message.string("error")
            if error.isEmpty { complete(id, result: .success(message["result"] ?? .null)) }
            else { complete(id, result: .failure(Self.serverError(error))) }
        case 7:
            let reason = message.string("error")
            fail(reason.isEmpty ? SourceFailure.network("轻书架关闭了连接") : Self.serverError(reason), epoch: generation)
        default: break
        }
    }

    private func startHeartbeat(epoch generation: UInt64) {
        heartbeat?.cancel()
        heartbeat = Task { [weak self] in
            while !Task.isCancelled {
                do { try await Task.sleep(nanoseconds: 15_000_000_000) } catch { return }
                guard let socket = self?.socket, self?.epoch == generation, self?.connected == true else { return }
                do { try await socket.send("{\"type\":6}\u{1e}") }
                catch { self?.fail(Self.transportError(error), epoch: generation); return }
            }
        }
    }

    private func sendInvocation(_ target: String, params: JSONValue) async throws -> JSONValue {
        try Task.checkCancellation()
        guard connected, let socket = socket else { throw SourceFailure.network("轻书架连接尚未建立") }
        sequence &+= 1
        let id = String(sequence)
        let message: JSONObject = ["type": 1, "invocationId": j(id), "target": j(target), "arguments": .array([params, .object(["UseGzip": true])])]
        let data = try JSONEncoder().encode(JSONValue.object(message))
        guard let encoded = String(data: data, encoding: .utf8) else { throw SourceFailure.parsing("无法编码轻书架请求") }
        return try await withTaskCancellationHandler(operation: {
            let value = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<JSONValue, Error>) in
                guard !Task.isCancelled else { continuation.resume(throwing: CancellationError()); return }
                let timer = Task { [weak self] in
                    guard let delay = self?.invocationTimeout else { return }
                    do { try await Task.sleep(nanoseconds: UInt64(max(0, delay) * 1_000_000_000)) } catch { return }
                    self?.complete(id, result: .failure(SourceFailure.timeout))
                }
                let sender = Task { [weak self, socket] in
                    do { try Task.checkCancellation(); try await socket.send(encoded + "\u{1e}") }
                    catch is CancellationError { self?.complete(id, result: .failure(CancellationError())) }
                    catch { self?.complete(id, result: .failure(Self.transportError(error))) }
                }
                pending[id] = Pending(continuation: continuation, timeout: timer, sender: sender)
            }
            try Task.checkCancellation()
            return value
        }, onCancel: { [weak self] in
            Task { @MainActor in self?.complete(id, result: .failure(CancellationError())) }
        })
    }

    private func complete(_ id: String, result: Result<JSONValue, Error>) {
        guard let request = pending.removeValue(forKey: id) else { return }
        request.timeout.cancel()
        request.sender.cancel()
        request.continuation.resume(with: result)
    }

    private func fail(_ error: Error, epoch generation: UInt64) {
        guard epoch == generation else { return }
        epoch &+= 1
        teardown(error)
    }

    private func teardown(_ error: Error) {
        connectionTask?.cancel(); connectionTask = nil
        receiver?.cancel(); receiver = nil
        heartbeat?.cancel(); heartbeat = nil
        handshakeTimer?.cancel(); handshakeTimer = nil
        socket?.cancel(); socket = nil
        connected = false
        boundToken = nil; boundOrigin = nil
        parser = ShelfSignalRFrames()
        let waiters = Array(ready.values); ready.removeAll()
        for waiter in waiters { waiter.resume(throwing: error) }
        for id in Array(pending.keys) { complete(id, result: .failure(error)) }
    }

    static func serverError(_ message: String) -> SourceFailure {
        let lower = message.lowercased()
        if lower.contains("unauthorized") || lower.contains("401") { return .authentication(message) }
        return .server(message)
    }

    private static func transportError(_ error: Error) -> Error {
        if error is CancellationError { return CancellationError() }
        if let error = error as? SourceFailure { return error }
        if let error = error as? URLError {
            if error.code == .cancelled { return CancellationError() }
            if error.code == .timedOut { return SourceFailure.timeout }
        }
        return SourceFailure.network("轻书架连接失败：" + error.localizedDescription)
    }

    deinit {
        connectionTask?.cancel(); receiver?.cancel(); heartbeat?.cancel(); handshakeTimer?.cancel()
        socket?.cancel()
        for continuation in ready.values { continuation.resume(throwing: CancellationError()) }
        for request in pending.values {
            request.timeout.cancel(); request.sender.cancel()
            request.continuation.resume(throwing: CancellationError())
        }
    }
}

private extension URL {
    func appending(queryItems: [URLQueryItem]) -> URL {
        var components = URLComponents(url: self, resolvingAgainstBaseURL: false)!
        components.queryItems = queryItems
        return components.url!
    }
}

enum ShelfResponseDecoder {
    static func unwrap(_ value: JSONValue) throws -> JSONValue {
        guard case .object(let envelope) = value, let success = envelope.bool("Success", "success") else {
            throw SourceFailure.parsing("轻书架响应缺少成功状态")
        }
        if !success { try throwFailure(envelope) }
        let response = envelope["Response"] ?? envelope["response"] ?? .string("")
        guard case .string(let encoded) = response, let compressed = Data(base64Encoded: encoded), compressed.starts(with: [0x1f, 0x8b]) else { return response }
        let json = try gunzip(compressed)
        do { return try JSONDecoder().decode(JSONValue.self, from: json) }
        catch { throw SourceFailure.parsing("轻书架压缩响应不是有效 JSON") }
    }

    static func throwFailure(_ object: JSONObject, authenticationContext: Bool = false) throws {
        guard object.bool("Success", "success") == false else { return }
        let message = object.string("Msg", "msg", "Message", "message", "Error", "error")
        let text = message.isEmpty ? "轻书架请求失败" : message
        if authenticationContext || [401, -100, 1001].contains(object.int("Status", "status")) { throw SourceFailure.authentication(text) }
        throw SourceFailure.server(text)
    }

    private static func gunzip(_ data: Data) throws -> Data {
        var stream = z_stream()
        let initialized = inflateInit2_(&stream, MAX_WBITS + 16, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size))
        guard initialized == Z_OK else { throw SourceFailure.parsing("无法解压轻书架响应") }
        defer { inflateEnd(&stream) }
        return try data.withUnsafeBytes { input in
            guard let base = input.bindMemory(to: Bytef.self).baseAddress else { throw SourceFailure.parsing("轻书架压缩响应为空") }
            stream.next_in = UnsafeMutablePointer(mutating: base)
            stream.avail_in = uInt(data.count)
            var result = Data()
            var status: Int32 = Z_OK
            repeat {
                var chunk = [UInt8](repeating: 0, count: 32 * 1024)
                let produced = chunk.withUnsafeMutableBytes { output -> Int in
                    stream.next_out = output.bindMemory(to: Bytef.self).baseAddress
                    stream.avail_out = uInt(output.count)
                    status = inflate(&stream, Z_NO_FLUSH)
                    return output.count - Int(stream.avail_out)
                }
                guard status == Z_OK || status == Z_STREAM_END, produced > 0 || status == Z_STREAM_END else {
                    throw SourceFailure.parsing("轻书架返回了无效的压缩响应")
                }
                result.append(contentsOf: chunk.prefix(produced))
                guard result.count <= 20 * 1024 * 1024 else { throw SourceFailure.parsing("轻书架解压响应过大") }
            } while status != Z_STREAM_END
            return result
        }
    }
}
