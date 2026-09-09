package io.github.jiangyuyi.lightnovel.feature.reader

import android.view.KeyEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView

@Composable
internal fun ReaderKeepScreenOnEffect(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        val previous = view.keepScreenOn
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = previous }
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
