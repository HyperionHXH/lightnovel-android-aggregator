package io.github.jiangyuyi.lightnovel.feature.sources

import io.github.jiangyuyi.lightnovel.core.source.RewardTask

internal enum class RewardTaskDestination {
    DISCOVER,
    OFFICIAL_CLIENT,
    NONE,
}

internal data class RewardTaskGuidance(
    val instruction: String,
    val destination: RewardTaskDestination,
)

internal fun RewardTask.completionGuidance(): RewardTaskGuidance = rewardTaskGuidance(
    key = key,
    title = title,
    subtitle = subtitle,
    claimed = claimed,
)

internal fun rewardTaskGuidance(
    key: String,
    title: String,
    subtitle: String,
    claimed: Boolean = false,
): RewardTaskGuidance {
    if (claimed) {
        return RewardTaskGuidance("任务已完成，奖励已经领取", RewardTaskDestination.NONE)
    }
    val task = "$key $title $subtitle".lowercase()
    return when {
        listOf("广告", "看视频", "观看视频", "激励", "advert", "video").any(task::contains) ->
            RewardTaskGuidance("Mixn 不接入广告任务，请在 LK 官方客户端查看", RewardTaskDestination.OFFICIAL_CLIENT)

        listOf("睡觉", "休息", "sleep", "rest", "分享", "share").any(task::contains) ->
            RewardTaskGuidance("该任务依赖 LK 官方客户端，请在官方客户端完成", RewardTaskDestination.OFFICIAL_CLIENT)

        listOf("收藏", "加入书架", "书架", "favorite", "collect", "bookshelf").any(task::contains) ->
            RewardTaskGuidance("前往 LK 作品详情，将作品加入书架后返回领取", RewardTaskDestination.DISCOVER)

        listOf("阅读", "浏览", "read", "browse", "view").any(task::contains) ->
            RewardTaskGuidance("正常浏览或阅读 LK 作品，达到要求后返回领取", RewardTaskDestination.DISCOVER)

        listOf("评论", "评价", "comment", "review").any(task::contains) ->
            RewardTaskGuidance("前往 LK 作品评论页完成评论后返回领取", RewardTaskDestination.DISCOVER)

        subtitle.isNotBlank() ->
            RewardTaskGuidance("按上方任务要求完成后返回领取", RewardTaskDestination.NONE)

        else -> RewardTaskGuidance(
            "请按轻之国度任务要求完成后返回领取",
            RewardTaskDestination.OFFICIAL_CLIENT,
        )
    }
}
