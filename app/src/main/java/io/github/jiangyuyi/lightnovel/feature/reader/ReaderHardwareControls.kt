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
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    DisposableEffect(view, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val listener = View.OnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
                return@OnKeyListener false
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) previous() else next()
            }
            true
        }
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

private tailrec fun Context.findReaderActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is ContextWrapper -> baseContext.findReaderActivity()
    else -> null
}
