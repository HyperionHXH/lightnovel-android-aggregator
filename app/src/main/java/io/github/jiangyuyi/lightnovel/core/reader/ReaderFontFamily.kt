package io.github.jiangyuyi.lightnovel.core.reader

import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import io.github.jiangyuyi.lightnovel.core.model.ReaderFont
import io.github.jiangyuyi.lightnovel.core.model.ReaderPreferences

/** Maps the reader's stable font choices to Android families in one place. */
fun ReaderFont.fontFamily(): FontFamily = when (this) {
    ReaderFont.DEFAULT -> FontFamily.Default
    ReaderFont.SANS -> FontFamily.SansSerif
    ReaderFont.SERIF -> FontFamily.Serif
    ReaderFont.MONO -> FontFamily.Monospace
    ReaderFont.CURSIVE -> FontFamily.Cursive
    ReaderFont.CONDENSED -> FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL))
    ReaderFont.ROUNDED -> FontFamily(Typeface.create("sans-serif-rounded", Typeface.NORMAL))
}

/** Returns a user-facing label for either a built-in or downloaded reader font. */
fun ReaderPreferences.fontLabel(): String = customFontId
    ?.let { id -> UserFontRepository.catalog.firstOrNull { it.id == id }?.name }
    ?: font.label

const val READER_FONT_PREVIEW = "春风拂过窗棂，檐下灯影轻晃。她翻过一页书，低声读道：‘山川入梦，灯火可亲。’"
