package com.mardous.booming.ui.overlay

import android.graphics.Color
import com.mardous.booming.data.model.lyrics.SyncedLyrics

internal data class LyricRenderText(
    val primary: CharSequence,
    val secondary: CharSequence?,
    val key: String,
    val content: SyncedLyrics.TextContent? = null,
    val position: Long = 0L,
    val highlightColor: Int = Color.WHITE,
    val animated: Boolean = false
) {
    companion object {
        val Empty = LyricRenderText("", null, "empty")
    }
}


