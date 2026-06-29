package com.mardous.booming.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.media3.session.MediaController
import com.mardous.booming.R
import com.mardous.booming.util.DESKTOP_LYRICS_LOCKED
import com.mardous.booming.util.DESKTOP_LYRICS_TEXT_COLOR
import com.mardous.booming.util.DESKTOP_LYRICS_TEXT_SIZE
import com.mardous.booming.util.Preferences
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
internal class DesktopLyricsCardView(
    context: Context,
    private val preferences: SharedPreferences,
    private val controllerProvider: () -> MediaController?,
    private val dispatchPlaybackKey: (Int) -> Unit
) : LinearLayout(context) {
    private val viewContext = context
    private val titleView = TextView(context)
    private val artistView = TextView(context)
    private val primaryLyricView = PipelineLyricView(context)

    // 统一后的主控制卡片
    private val controlCard = LinearLayout(context)
    private val songInfoColumn = LinearLayout(context)
    private val controlsRow = LinearLayout(context)
    private val configRow = LinearLayout(context)
    private val colorRow = LinearLayout(context)
    private val sizeRow = LinearLayout(context)
    private val sizeValueView = TextView(context)

    private val playPauseButton = iconButton(R.drawable.ic_pause_24dp, R.string.action_play_pause) {
        controllerProvider()?.let { mediaController ->
            if (mediaController.isPlaying) {
                mediaController.pause()
            } else {
                mediaController.play()
            }
        }
        applyMetadata(
            title = controllerProvider()?.mediaMetadata?.title?.toString().orEmpty(),
            artist = controllerProvider()?.mediaMetadata?.artist?.toString().orEmpty(),
            isPlaying = controllerProvider()?.isPlaying == true
        )
    }
    private var expanded = false
    private var textColor = Color.WHITE
    private var textSp = 22

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(4), dp(16), dp(4))
        clipToPadding = false

        titleView.apply {
            includeFontPadding = false
            gravity = Gravity.CENTER
            maxLines = 1
            maxWidth = desktopPanelMaxWidth()
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xE6000000.toInt()) // 稍作微调，避免纯黑
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }

        artistView.apply {
            includeFontPadding = false
            gravity = Gravity.CENTER
            maxLines = 1
            maxWidth = desktopPanelMaxWidth()
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(0x8A000000.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }

        primaryLyricView.configure(
            textSizeSp = textSp,
            primaryColor = Color.WHITE,
            backgroundColor = Color.WHITE,
            highlightColor = textColor,
            secondaryColor = Color.WHITE,
            outline = true,
            outlineWidth = 1.25f,
            centerWhenFits = true,
            charMotion = false,
            scrollOnly = false,
            drawSecondary = true
        )
        primaryLyricView.background = null
        primaryLyricView.setPadding(0, dp(2), 0, dp(2))

        controlsRow.apply {
            gravity = Gravity.CENTER
            orientation = HORIZONTAL
            addView(iconButton(R.drawable.ic_lock_24dp, R.string.desktop_lyrics_lock_title) {
                setExpanded(false)
                preferences.edit { putBoolean(DESKTOP_LYRICS_LOCKED, true) }
            })
            addView(iconButton(R.drawable.ic_previous_24dp, R.string.action_previous) {
                dispatchPlaybackKey.invoke(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            })
            addView(playPauseButton)
            addView(iconButton(R.drawable.ic_next_24dp, R.string.action_next) {
                dispatchPlaybackKey.invoke(KeyEvent.KEYCODE_MEDIA_NEXT)
            })
            addView(iconButton(R.drawable.ic_close_24dp, R.string.action_cancel) {
                setExpanded(false)
            })
        }

        songInfoColumn.apply {
            gravity = Gravity.CENTER
            orientation = VERTICAL
            addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(artistView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
            })
        }

        colorRow.apply {
            gravity = Gravity.CENTER
            orientation = HORIZONTAL
        }

        sizeValueView.apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xCC000000.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }

        sizeRow.apply {
            gravity = Gravity.CENTER
            orientation = HORIZONTAL
            addView(textButton("T-", 30, 26) {
                changeDesktopTextSize(-2)
            })
            addView(sizeValueView, LayoutParams(dp(30), dp(26)).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            })
            addView(textButton("T+", 30, 26) {
                changeDesktopTextSize(2)
            })
        }

        configRow.apply {
            gravity = Gravity.CENTER
            orientation = HORIZONTAL
            addView(colorRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(sizeRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(16) // 适当拉开间距
            })
        }

        // 统一在 controlCard 内呈现，减少层级嵌套与背景叠加
        controlCard.apply {
            gravity = Gravity.CENTER
            orientation = VERTICAL
            background = cardBackground()
            setPadding(dp(16), dp(12), dp(16), dp(12))

            addView(songInfoColumn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(controlsRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
            addView(configRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            })
        }

        addView(primaryLyricView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(30)
            marginEnd = dp(30)
        })
        addView(controlCard, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        rebuildColorRow()
        setExpanded(false)
    }

    fun configure(color: Int, textSize: Int, locked: Boolean) {
        textColor = color
        textSp = textSize
        primaryLyricView.configure(
            textSizeSp = textSp,
            primaryColor = Color.WHITE,
            backgroundColor = Color.WHITE,
            highlightColor = textColor,
            secondaryColor = Color.WHITE,
            outline = true,
            outlineWidth = 1.25f,
            centerWhenFits = true,
            charMotion = false,
            scrollOnly = false,
            drawSecondary = true
        )
        sizeValueView.text = textSize.toString()
        rebuildColorRow()
        if (locked) {
            setExpanded(false)
        }
    }

    fun applyText(text: LyricRenderText) {
        primaryLyricView.applyText(text)
        visibility = View.VISIBLE
    }

    fun applyMetadata(title: String, artist: String, isPlaying: Boolean) {
        titleView.text = title.ifBlank { viewContext.getString(R.string.now_playing) }
        artistView.text = artist
        artistView.visibility = if (artist.isBlank() || !expanded) View.GONE else View.VISIBLE
        playPauseButton.setImageResource(
            if (isPlaying) R.drawable.ic_pause_24dp else R.drawable.ic_play_24dp
        )
    }

    fun toggleExpanded() {
        if (!Preferences.desktopLyricsLocked) {
            setExpanded(!expanded)
        }
    }

    private fun setExpanded(value: Boolean) {
        expanded = value
        // 简化：直接控制最外层控制面板的显隐即可
        controlCard.visibility = if (expanded) View.VISIBLE else View.GONE
        artistView.visibility = if (expanded && artistView.text.isNotBlank()) View.VISIBLE else View.GONE
        background = null
        setPadding(dp(16), dp(4), dp(16), dp(4))
    }

    private fun rebuildColorRow() {
        val colors = listOf("#FFE57373", "#FFE79A61", "#FFFFD54F", "#FF80CBC4", "#FF90CAF9", "#FFF48FB1", "#FFFFFFFF")
        colorRow.removeAllViews()
        colors.forEach { colorValue ->
            val color = colorValue.toColorInt()
            val isSelected = color == textColor
            colorRow.addView(View(viewContext).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    // 选中的圈高亮加粗
                    setStroke(
                        dp(if (isSelected) 2 else 1),
                        if (isSelected) 0xDE000000.toInt() else 0x26000000
                    )
                }
                setOverlayClickAction {
                    preferences.edit { putString(DESKTOP_LYRICS_TEXT_COLOR, colorValue) }
                }
            }, LayoutParams(dp(20), dp(20)).apply { // 略微缩小色块，使其单行排版更精致
                marginStart = dp(3)
                marginEnd = dp(3)
            })
        }
    }

    private fun iconButton(icon: Int, description: Int, onClick: () -> Unit): ImageButton {
        return ImageButton(viewContext).apply {
            setImageResource(icon)
            setColorFilter(0xDE000000.toInt())
            background = null
            contentDescription = viewContext.getString(description)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOverlayClickAction(onClick)
        }.also { button ->
            button.layoutParams = LayoutParams(dp(32), dp(32)).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            }
        }
    }

    private fun textButton(label: String, widthDp: Int, heightDp: Int, onClick: () -> Unit): TextView {
        return TextView(viewContext).apply {
            text = label
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xDE000000.toInt())
            includeFontPadding = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = null
            setOverlayClickAction(onClick)
        }.also { view ->
            view.layoutParams = LayoutParams(dp(widthDp), dp(heightDp)).apply {
                marginStart = dp(1)
                marginEnd = dp(1)
            }
        }
    }

    private fun cardBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(0xF2FFFFFF.toInt()) // 微带透明度的白色，使悬浮窗质感更好
            setStroke(dp(1), 0x1A000000)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun desktopPanelMaxWidth(): Int {
        val availableWidth = (resources.displayMetrics.widthPixels - dp(72)).coerceAtLeast(dp(180))
        return minOf(availableWidth, dp(360))
    }

    private fun changeDesktopTextSize(delta: Int) {
        val newSize = (preferences.getInt(DESKTOP_LYRICS_TEXT_SIZE, 22) + delta).coerceIn(12, 48)
        preferences.edit { putInt(DESKTOP_LYRICS_TEXT_SIZE, newSize) }
    }

    private fun View.setOverlayClickAction(onClick: () -> Unit) {
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    onClick()
                    view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    true
                }
                else -> true
            }
        }
        setOnClickListener(null)
    }
}