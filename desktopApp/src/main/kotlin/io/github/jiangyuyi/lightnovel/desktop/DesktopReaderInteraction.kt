package io.github.jiangyuyi.lightnovel.desktop

internal enum class DesktopTapAction {
    PREVIOUS,
    NEXT,
    CONTROLS,
}

/** Keeps desktop tap behavior aligned with the Android reader's normalized zones. */
internal fun desktopTapAction(
    zone: String,
    xFraction: Double,
    yFraction: Double,
    inversion: String = "none",
): DesktopTapAction {
    var x = xFraction.coerceIn(0.0, 1.0)
    var y = yFraction.coerceIn(0.0, 1.0)
    if (inversion == "left_right" || inversion == "all") x = 1.0 - x
    if (inversion == "up_down" || inversion == "all") y = 1.0 - y
    return when (zone) {
        "default" -> when {
            x < 0.25 -> DesktopTapAction.PREVIOUS
            x > 0.75 -> DesktopTapAction.NEXT
            else -> DesktopTapAction.CONTROLS
        }
        "l_shape" -> when {
            x < 0.34 && (y < 0.34 || y > 0.66) -> DesktopTapAction.PREVIOUS
            x > 0.66 && (y < 0.34 || y > 0.66) -> DesktopTapAction.NEXT
            else -> DesktopTapAction.CONTROLS
        }
        "kindle" -> when {
            x < 0.32 -> DesktopTapAction.PREVIOUS
            x > 0.68 -> DesktopTapAction.NEXT
            else -> DesktopTapAction.CONTROLS
        }
        "both_sides" -> when {
            x < 0.18 -> DesktopTapAction.PREVIOUS
            x > 0.82 -> DesktopTapAction.NEXT
            else -> DesktopTapAction.CONTROLS
        }
        "left_right" -> when {
            x < 0.40 -> DesktopTapAction.PREVIOUS
            x > 0.60 -> DesktopTapAction.NEXT
            else -> DesktopTapAction.CONTROLS
        }
        else -> DesktopTapAction.CONTROLS
    }
}

internal fun desktopImageCss(scale: String): String = when (scale) {
    "fill" -> "width:100%;height:100%;object-fit:fill;"
    "fit_width" -> "width:100%;height:auto;"
    "fit_height" -> "width:auto;height:100%;max-width:100%;"
    "original" -> "width:auto;height:auto;max-width:none;"
    "smart" -> "width:100%;height:auto;object-fit:cover;"
    else -> "max-width:100%;height:auto;"
}
