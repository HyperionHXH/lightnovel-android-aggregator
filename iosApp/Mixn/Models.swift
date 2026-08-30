import Foundation

enum MixnSource: String, CaseIterable, Identifiable, Hashable {
    case kingdom
    case shelf

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .kingdom: return "轻之国度"
        case .shelf: return "轻书架"
        }
    }

    var shortName: String {
        switch self {
        case .kingdom: return "LK"
        case .shelf: return "书架"
        }
    }
}

struct MixnBook: Identifiable, Hashable {
    let id: String
    let source: MixnSource
    let remoteID: String
    let title: String
    let author: String
    let summary: String
    let coverURL: URL?
    let defaultChapterID: Int64?

    var sourceLabel: String { source.displayName }
}

enum MixnFeed: String, CaseIterable, Identifiable {
    case popular
    case rank
    case newest
    case original
    case fanfic
    case updated

    var id: String { rawValue }

    var label: String {
        switch self {
        case .popular: return "热门"
        case .rank: return "排行"
        case .newest: return "新书"
        case .original: return "原创"
        case .fanfic: return "同人"
        case .updated: return "最近更新"
        }
    }
}

enum MixnAPIError: LocalizedError {
    case invalidResponse
    case server(String)
    case unsupported(String)

    var errorDescription: String? {
        switch self {
        case .invalidResponse: return "服务器返回了无法识别的数据"
        case .server(let message): return message
        case .unsupported(let message): return message
        }
    }
}
