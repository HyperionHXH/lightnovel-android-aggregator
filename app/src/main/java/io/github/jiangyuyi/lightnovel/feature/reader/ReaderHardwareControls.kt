package io.github.jiangyuyi.lightnovel.feature.reader

import android.view.KeyEvent
import android.view.View
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import io.github.jiangyuyi.lightnovel.core.model.ReaderOrientation

internal interface ReaderVolumeKeyHost {
    fun setReaderVolumeKeyHandler(handler: ((keyCode: Int, action: Int, repeatCount: Int) -> Boolean)?)
}

internal enum class ReaderVolumeKeyDirection {
    PREVIOUS,
    NEXT,
}

/** Returns the reader action for a volume event, or null when it is not a volume key. */
internal fun readerVolumeKeyDirection(keyCode: Int): ReaderVolumeKeyDirection? = when (keyCode) {
    KeyEvent.KEYCODE_VOLUME_UP -> ReaderVolumeKeyDirection.PREVIOUS
    KeyEvent.KEYCODE_VOLUME_DOWN -> ReaderVolumeKeyDirection.NEXT
    else -> null
}

/** Consumes volume events while enabled and emits one page turn per physical key press. */
internal fun consumeReaderVolumeKey(
    keyCode: Int,
    action: Int,
    repeatCount: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
): Boolean {
    val direction = readerVolumeKeyDirection(keyCode) ?: return false
    if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) {
        when (direction) {
            ReaderVolumeKeyDirection.PREVIOUS -> onPrevious()
            ReaderVolumeKeyDirection.NEXT -> onNext()
        }
    }
    return true
}

@Composable
internal fun ReaderKeepScreenOnEffect(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        val previous = view.keepScreenOn
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = previous }
    }
}

@Composable
internal fun ReaderOrientationEffect(orientation: ReaderOrientation) {
    val context = LocalContext.current
    DisposableEffect(context, orientation) {
        val activity = context.findReaderActivity()
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = when (orientation) {
            ReaderOrientation.DEFAULT -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ReaderOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            ReaderOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose {
            previous?.let { activity?.requestedOrientation = it }
        }
    }
}

/** Consumes volume keys only while enabled; otherwise Android keeps normal volume behavior. */
@Composable
internal fun ReaderVolumeKeyEffect(
    enabled: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val view = LocalView.current
    val activity = view.context.findReaderActivity()
    val host = activity as? ReaderVolumeKeyHost
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    DisposableEffect(view, activity, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val listener = View.OnKeyListener { _, keyCode, event ->
            consumeReaderVolumeKey(keyCode, event.action, event.repeatCount, previous, next)
        }
        val handler: (Int, Int, Int) -> Boolean = { keyCode, action, repeatCount ->
            consumeReaderVolumeKey(keyCode, action, repeatCount, previous, next)
        }
        if (host != null) {
            host.setReaderVolumeKeyHandler(handler)
            onDispose {
                host.setReaderVolumeKeyHandler(null)
            }
        } else {
            val hadFocus = view.hasFocus()
            view.isFocusableInTouchMode = true
            view.requestFocus()
            view.setOnKeyListener(listener)
            onDispose {
                view.setOnKeyListener(null)
                if (!hadFocus) view.clearFocus()
            }
        }
    }
}

private tailrec fun Context.findReaderActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is ContextWrapper -> baseContext.findReaderActivity()
    else -> null
}
