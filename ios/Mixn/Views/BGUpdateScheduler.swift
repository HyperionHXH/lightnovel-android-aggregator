import Foundation
import BackgroundTasks
import UserNotifications

/// Launch registration belongs in the application delegate before didFinishLaunching returns.
@MainActor final class BackgroundUpdateScheduler {
    static let shared = BackgroundUpdateScheduler()
    static let identifier = "io.github.hyperionhxh.mixn.refresh"
    private weak var app: AppStore?
    private var registered = false
    private var running: Task<Void, Never>?

    func register(app: AppStore) {
        self.app = app
        guard !registered else { return }
        registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.identifier, using: nil) { [weak self] task in
            Task { @MainActor in
                guard let self = self, let refresh = task as? BGAppRefreshTask else {
                    task.setTaskCompleted(success: false)
                    return
                }
                self.perform(refresh)
            }
        }
    }

    func schedule() throws {
        guard app?.preferences.updateNotifications == true else { return }
        guard registered else { throw SourceFailure.unsupported("后台更新任务未能注册，请重新启动应用") }
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.identifier)
        let request = BGAppRefreshTaskRequest(identifier: Self.identifier)
        // This is an earliest start, never a promise of periodic background execution.
        request.earliestBeginDate = Date(timeIntervalSinceNow: 6 * 60 * 60)
        try BGTaskScheduler.shared.submit(request)
    }

    func cancel() {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.identifier)
        running?.cancel()
        running = nil
    }

    func enable(app: AppStore) async throws -> Bool {
        let granted = try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
        guard granted else { return false }
        self.app = app
        app.preferences.updateNotifications = true
        do { try schedule() }
        catch { app.preferences.updateNotifications = false; throw error }
        return true
    }

    private func perform(_ task: BGAppRefreshTask) {
        guard let app = app, app.preferences.updateNotifications else {
            task.setTaskCompleted(success: false)
            return
        }
        let completion = BackgroundTaskCompletion(task)
        running?.cancel()
        let operation = Task { @MainActor [weak self, weak app] in
            guard let self = self, let app = app else { completion.finish(false); return }
            do {
                try self.schedule()
                let before = app.library.updated
                await app.restoreAccounts()
                try Task.checkCancellation()
                let sessions = app.sessions
                let revisions = Dictionary(uniqueKeysWithValues: SourceID.allCases.map { ($0, app.accountRevision($0)) })
                await app.refreshUpdates()
                try Task.checkCancellation()
                guard app.preferences.updateNotifications, sessions == app.sessions,
                      revisions.allSatisfy({ app.accountRevision($0.key) == $0.value }) else { completion.finish(false); return }
                let added = app.library.updated.subtracting(before)
                if !added.isEmpty {
                    let content = UNMutableNotificationContent()
                    content.title = "书架有新章节"
                    content.body = "\(added.count) 本书有更新，打开 Mixn 查看书架。"
                    content.sound = .default
                    content.userInfo = ["destination": "shelf"]
                    try await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "mixn.shelf.update", content: content, trigger: nil))
                }
                let failed = app.sessions.filter { $0.value.loggedIn }.keys.contains { app.sourceErrors[$0] != nil }
                completion.finish(!failed)
            } catch is CancellationError { completion.finish(false) }
            catch { completion.finish(false) }
            self.running = nil
        }
        running = operation
        task.expirationHandler = {
            operation.cancel()
            completion.finish(false)
        }
    }
}

/// Expiration and asynchronous completion can race; BackgroundTasks requires exactly one result.
private final class BackgroundTaskCompletion {
    private let lock = NSLock()
    private var completed = false
    private let task: BGTask
    init(_ task: BGTask) { self.task = task }
    func finish(_ success: Bool) {
        lock.lock()
        guard !completed else { lock.unlock(); return }
        completed = true
        lock.unlock()
        task.setTaskCompleted(success: success)
    }
}
