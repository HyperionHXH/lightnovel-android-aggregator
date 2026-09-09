package io.github.jiangyuyi.lightnovel.feature.reader

import io.github.jiangyuyi.lightnovel.core.model.ReaderTapZone
import io.github.jiangyuyi.lightnovel.core.model.ReaderTapInversion

internal enum class ReaderTapAction {
    PREVIOUS,
    NEXT,
    CONTROLS,
    NONE,
}

/** Maps a normalized tap position to a reader action without touching UI state. */
internal fun readerTapAction(
    zone: ReaderTapZone,
    xFraction: Float,
    yFraction: Float,
    inversion: ReaderTapInversion = ReaderTapInversion.NONE,
): ReaderTapAction {
    var x = xFraction.coerceIn(0f, 1f)
    var y = yFraction.coerceIn(0f, 1f)
    if (inversion == ReaderTapInversion.LEFT_RIGHT || inversion == ReaderTapInversion.ALL) x = 1f - x
    if (inversion == ReaderTapInversion.UP_DOWN || inversion == ReaderTapInversion.ALL) y = 1f - y
    return when (zone) {
        ReaderTapZone.DEFAULT -> when {
            x < 0.25f -> ReaderTapAction.PREVIOUS
            x > 0.75f -> ReaderTapAction.NEXT
            else -> ReaderTapAction.CONTROLS
        }
        ReaderTapZone.L_SHAPE -> when {
            x < 0.34f && (y < 0.34f || y > 0.66f) -> ReaderTapAction.PREVIOUS
            x > 0.66f && (y < 0.34f || y > 0.66f) -> ReaderTapAction.NEXT
            else -> ReaderTapAction.CONTROLS
        }
        ReaderTapZone.KINDLE -> when {
            x < 0.32f -> ReaderTapAction.PREVIOUS
            x > 0.68f -> ReaderTapAction.NEXT
            else -> ReaderTapAction.CONTROLS
        }
        ReaderTapZone.BOTH_SIDES -> when {
            x < 0.18f -> ReaderTapAction.PREVIOUS
            x > 0.82f -> ReaderTapAction.NEXT
            else -> ReaderTapAction.CONTROLS
        }
        ReaderTapZone.LEFT_RIGHT -> when {
            x < 0.40f -> ReaderTapAction.PREVIOUS
            x > 0.60f -> ReaderTapAction.NEXT
            else -> ReaderTapAction.CONTROLS
        }
        ReaderTapZone.DISABLED -> ReaderTapAction.CONTROLS
    }
}
