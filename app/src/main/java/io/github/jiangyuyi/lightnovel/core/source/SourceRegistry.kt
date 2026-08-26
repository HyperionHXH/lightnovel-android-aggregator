package io.github.jiangyuyi.lightnovel.core.source

class SourceRegistry(sources: Iterable<NovelSource>) {
    private val sourcesById: Map<String, NovelSource>

    init {
        val sourceList = sources.toList()
        val duplicateIds = sourceList
            .groupingBy { it.descriptor.id }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        require(duplicateIds.isEmpty()) {
            "duplicate source ids: ${duplicateIds.sorted().joinToString()}"
        }
        sourcesById = sourceList.associateBy { it.descriptor.id }
    }

    fun all(): List<NovelSource> = sourcesById.values.toList()

    fun get(sourceId: String): NovelSource? = sourcesById[sourceId]

    fun discoverProviders(): List<DiscoverProvider> = all().filterIsInstance<DiscoverProvider>()

    fun searchProviders(): List<SearchProvider> = all().filterIsInstance<SearchProvider>()

    fun accountProviders(): List<AccountProvider> = all().filterIsInstance<AccountProvider>()

    fun rewardProviders(): List<RewardProvider> = all().filterIsInstance<RewardProvider>()

    fun profileProvider(sourceId: String): SourceProfileProvider? = get(sourceId) as? SourceProfileProvider

    fun shelfProviders(): List<ShelfProvider> = all().filterIsInstance<ShelfProvider>()

    fun historyProviders(): List<HistoryProvider> = all().filterIsInstance<HistoryProvider>()

    fun detailProvider(sourceId: String): DetailProvider? = get(sourceId) as? DetailProvider

    fun readerProvider(sourceId: String): ReaderProvider? = get(sourceId) as? ReaderProvider

    fun discoverProvider(sourceId: String): DiscoverProvider? = get(sourceId) as? DiscoverProvider

    fun accountProvider(sourceId: String): AccountProvider? = get(sourceId) as? AccountProvider

    fun shelfProvider(sourceId: String): ShelfProvider? = get(sourceId) as? ShelfProvider

    fun historyProvider(sourceId: String): HistoryProvider? = get(sourceId) as? HistoryProvider

    fun historyMutationProvider(sourceId: String): HistoryMutationProvider? =
        get(sourceId) as? HistoryMutationProvider

    fun readingProgressSyncProvider(sourceId: String): ReadingProgressSyncProvider? =
        get(sourceId) as? ReadingProgressSyncProvider

    fun rewardProvider(sourceId: String): RewardProvider? = get(sourceId) as? RewardProvider
}
