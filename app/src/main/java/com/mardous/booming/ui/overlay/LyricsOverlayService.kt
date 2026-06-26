package com.mardous.booming.ui.overlay

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.InputFilter
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextPaint
import android.text.style.ReplacementSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.Display
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.mardous.booming.R
import com.mardous.booming.core.model.lyrics.LyricsViewSettings
import com.mardous.booming.data.local.repository.LyricsRepository
import com.mardous.booming.data.local.repository.Repository
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.lyrics.LyricsSource
import com.mardous.booming.data.model.lyrics.SyncedLyrics
import com.mardous.booming.playback.PlaybackService
import com.mardous.booming.playback.progress.ProgressObserver
import com.mardous.booming.util.DESKTOP_LYRICS
import com.mardous.booming.util.DESKTOP_LYRICS_LOCKED
import com.mardous.booming.util.DESKTOP_LYRICS_SHOW_NEXT_LINE
import com.mardous.booming.util.DESKTOP_LYRICS_TEXT_COLOR
import com.mardous.booming.util.DESKTOP_LYRICS_TEXT_SIZE
import com.mardous.booming.util.DESKTOP_LYRICS_X
import com.mardous.booming.util.DESKTOP_LYRICS_Y
import com.mardous.booming.util.Preferences
import com.mardous.booming.util.STATUS_BAR_LYRICS
import com.mardous.booming.util.STATUS_BAR_LYRICS_TEXT_COLOR
import com.mardous.booming.util.STATUS_BAR_LYRICS_TEXT_SIZE
import com.mardous.booming.util.STATUS_BAR_LYRICS_WIDTH
import com.mardous.booming.util.STATUS_BAR_LYRICS_X
import com.mardous.booming.util.STATUS_BAR_LYRICS_Y
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import kotlin.math.ceil
import kotlin.math.roundToInt

class LyricsOverlayService : Service(), Player.Listener, SharedPreferences.OnSharedPreferenceChangeListener {

    private val preferences: SharedPreferences by inject()
    private val repository: Repository by inject()
    private val lyricsRepository: LyricsRepository by inject()

    private val serviceScope = CoroutineScope(Job() + Main)
    private val progressObserver = ProgressObserver(120)

    private lateinit var overlayContext: Context
    private lateinit var windowManager: WindowManager

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var desktopWindow: OverlayWindow? = null
    private var statusWindow: OverlayWindow? = null
    private var lyricsJob: Job? = null
    private var overlayLyrics: OverlayLyrics = OverlayLyrics.Empty
    private var currentSongId: Long = Song.emptySong.id
    private var lastDesktopText: String = ""
    private var lastStatusText: String = ""

    override fun onCreate() {
        super.onCreate()
        overlayContext = createOverlayContext()
        windowManager = overlayContext.getSystemService(WINDOW_SERVICE) as WindowManager
        preferences.registerOnSharedPreferenceChangeListener(this)
        connectController()
        updateWindows()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!shouldRun(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        updateWindows()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        progressObserver.stop()
        lyricsJob?.cancel()
        removeWindow(desktopWindow)
        removeWindow(statusWindow)
        desktopWindow = null
        statusWindow = null
        controller?.removeListener(this)
        controllerFuture?.let(MediaController::releaseFuture)
        preferences.unregisterOnSharedPreferenceChangeListener(this)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        loadCurrentSong()
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.contains(Player.EVENT_TIMELINE_CHANGED) ||
            events.contains(Player.EVENT_MEDIA_METADATA_CHANGED) ||
            events.contains(Player.EVENT_IS_PLAYING_CHANGED)
        ) {
            if (events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                events.contains(Player.EVENT_MEDIA_METADATA_CHANGED)
            ) {
                loadCurrentSong()
            }
            updateLyricText(force = events.contains(Player.EVENT_IS_PLAYING_CHANGED))
        } else {
            updateLyricText()
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        when (key) {
            DESKTOP_LYRICS,
            STATUS_BAR_LYRICS -> {
                if (!shouldRun(this)) {
                    stopSelf()
                } else {
                    updateWindows()
                }
            }
            DESKTOP_LYRICS_LOCKED,
            DESKTOP_LYRICS_SHOW_NEXT_LINE,
            DESKTOP_LYRICS_TEXT_SIZE,
            DESKTOP_LYRICS_TEXT_COLOR,
            STATUS_BAR_LYRICS_TEXT_SIZE,
            STATUS_BAR_LYRICS_TEXT_COLOR -> {
                updateWindowStyle()
                updateLyricText(force = true)
            }
            STATUS_BAR_LYRICS_X,
            STATUS_BAR_LYRICS_Y,
            STATUS_BAR_LYRICS_WIDTH -> updateWindowStyle()
            LyricsViewSettings.Key.ENABLE_SYLLABLE_LYRICS,
            LyricsViewSettings.Key.ENABLE_KARAOKE_STYLE,
            LyricsViewSettings.Key.SHOW_TRANSLATION,
            LyricsViewSettings.Key.SHOW_TRANSLITERATION -> updateLyricText(force = true)
        }
    }

    private fun connectController() {
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener({
            runCatching {
                controller = future.get().also { mediaController ->
                    mediaController.addListener(this)
                }
                loadCurrentSong()
                progressObserver.start { updateLyricText() }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateWindows() {
        if (!Settings.canDrawOverlays(this)) {
            removeWindow(desktopWindow)
            removeWindow(statusWindow)
            desktopWindow = null
            statusWindow = null
            return
        }

        if (Preferences.desktopLyricsEnabled) {
            if (desktopWindow == null) {
                desktopWindow = createWindow(OverlayKind.Desktop).also { window ->
                    windowManager.addView(window.view, window.params)
                    window.view.post {
                        clampWindowToDisplay(window)
                        updateWindow(window)
                    }
                }
            }
        } else {
            removeWindow(desktopWindow)
            desktopWindow = null
        }

        if (Preferences.statusBarLyricsEnabled) {
            if (statusWindow == null) {
                statusWindow = createWindow(OverlayKind.StatusBar).also { window ->
                    windowManager.addView(window.view, window.params)
                    window.view.post {
                        clampWindowToDisplay(window)
                        updateWindow(window)
                    }
                }
            }
        } else {
            removeWindow(statusWindow)
            statusWindow = null
        }
        updateWindowStyle()
        updateLyricText(force = true)
    }

    private fun createWindow(kind: OverlayKind): OverlayWindow {
        val view = when (kind) {
            OverlayKind.Desktop -> DesktopLyricsCardView(overlayContext)
            OverlayKind.StatusBar -> PipelineLyricView(overlayContext).apply {
                val lyricColor = preferences.color(STATUS_BAR_LYRICS_TEXT_COLOR)
                val backgroundColor = statusBarLyricsBackgroundColor(lyricColor)
                configure(
                    textSizeSp = preferences.getInt(STATUS_BAR_LYRICS_TEXT_SIZE, 14),
                    primaryColor = lyricColor,
                    backgroundColor = backgroundColor,
                    highlightColor = lyricColor,
                    secondaryColor = backgroundColor,
                    outline = false,
                    outlineWidth = 0f,
                    centerWhenFits = true,
                    charMotion = false,
                    scrollOnly = false,
                    drawSecondary = true
                )
            }
        }
        val params = createLayoutParams(kind)
        val window = OverlayWindow(kind, view, params)
        if (kind == OverlayKind.Desktop) {
            view.setOnTouchListener(DragTouchListener(window))
        }
        return window
    }

    private fun createLayoutParams(kind: OverlayKind): WindowManager.LayoutParams {
        val flags = baseFlags().withTouchable(when (kind) {
            OverlayKind.Desktop -> !Preferences.desktopLyricsLocked
            OverlayKind.StatusBar -> false
        })
        val statusWidth = statusBarLyricsWidth()
        return WindowManager.LayoutParams(
            if (kind == OverlayKind.Desktop) {
                WindowManager.LayoutParams.MATCH_PARENT
            } else if (kind == OverlayKind.StatusBar) {
                statusWidth
            } else {
                WindowManager.LayoutParams.WRAP_CONTENT
            },
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = preferences.getInt(kind.xKey, kind.defaultX())
            y = preferences.getInt(kind.yKey, kind.defaultY())
            if (kind == OverlayKind.Desktop) {
                x = 0
            } else if (kind == OverlayKind.StatusBar) {
                width = statusWidth
                x = statusBarLyricsX(statusWidth)
                y = statusBarLyricsY()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun updateWindowStyle() {
        desktopWindow?.let { window ->
            val locked = Preferences.desktopLyricsLocked
            window.params.flags = baseFlags().withTouchable(!locked)
            window.params.width = WindowManager.LayoutParams.MATCH_PARENT
            window.params.x = 0
            (window.view as? DesktopLyricsCardView)?.configure(
                color = preferences.color(DESKTOP_LYRICS_TEXT_COLOR),
                textSize = preferences.getInt(DESKTOP_LYRICS_TEXT_SIZE, 22),
                locked = locked
            )
            clampWindowToDisplay(window)
            updateWindow(window)
        }
        statusWindow?.let { window ->
            val statusWidth = statusBarLyricsWidth()
            val lyricColor = preferences.color(STATUS_BAR_LYRICS_TEXT_COLOR)
            val backgroundColor = statusBarLyricsBackgroundColor(lyricColor)
            window.params.flags = baseFlags().withTouchable(false)
            window.params.width = statusWidth
            window.params.x = statusBarLyricsX(statusWidth)
            window.params.y = statusBarLyricsY()
            (window.view as? PipelineLyricView)?.configure(
                textSizeSp = preferences.getInt(STATUS_BAR_LYRICS_TEXT_SIZE, 14),
                primaryColor = lyricColor,
                backgroundColor = backgroundColor,
                highlightColor = lyricColor,
                secondaryColor = backgroundColor,
                outline = false,
                outlineWidth = 0f,
                centerWhenFits = true,
                charMotion = false,
                scrollOnly = false,
                drawSecondary = true
            )
            clampWindowToDisplay(window)
            updateWindow(window)
        }
    }

    private fun loadCurrentSong() {
        val mediaItem = controller?.currentMediaItem
        lyricsJob?.cancel()
        if (mediaItem == null) {
            currentSongId = Song.emptySong.id
            overlayLyrics = OverlayLyrics.Empty
            updateLyricText(force = true)
            return
        }
        lyricsJob = serviceScope.launch {
            val song = withContext(IO) {
                repository.songByMediaItem(mediaItem)
            }
            currentSongId = song.id
            overlayLyrics = withContext(IO) {
                findLyrics(song)
            }
            updateLyricText(force = true)
        }
    }

    private suspend fun findLyrics(song: Song): OverlayLyrics {
        if (song == Song.emptySong) return OverlayLyrics.Empty
        var plainLyrics: String? = null
        for (source in listOf(LyricsSource.File, LyricsSource.Embedded, LyricsSource.Downloaded)) {
            val rawLyrics = when (source) {
                LyricsSource.File -> lyricsRepository.fileLyrics(song)
                LyricsSource.Embedded -> lyricsRepository.embeddedLyrics(song)
                LyricsSource.Downloaded -> lyricsRepository.storedLyrics(song, allowDownload = true)
            } ?: continue

            val syncedLyrics = lyricsRepository.parseRawLyrics(song, rawLyrics)
            if (syncedLyrics?.hasContent == true) {
                return OverlayLyrics(syncedLyrics, emptyList())
            }
            if (plainLyrics.isNullOrBlank()) {
                plainLyrics = rawLyrics.lyrics
            }
        }
        return OverlayLyrics(
            synced = null,
            plainLines = plainLyrics
                ?.lineSequence()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toList()
                .orEmpty()
        )
    }

    private fun updateLyricText(force: Boolean = false) {
        val desktopText = currentDesktopLyricText()
        if (force || desktopText.animated || desktopText.key != lastDesktopText) {
            lastDesktopText = desktopText.key
            (desktopWindow?.view as? DesktopLyricsCardView)?.applyText(desktopText)
        }

        val mediaController = controller
        (desktopWindow?.view as? DesktopLyricsCardView)?.applyMetadata(
            title = mediaController?.mediaMetadata?.title?.toString().orEmpty(),
            artist = mediaController?.mediaMetadata?.artist?.toString().orEmpty(),
            isPlaying = mediaController?.isPlaying == true
        )

        val statusText = currentStatusLyricText()
        val statusView = statusWindow?.view as? PipelineLyricView
        if (force || statusText.key != lastStatusText) {
            lastStatusText = statusText.key
            statusView?.applyText(statusText)
        } else if (statusText.animated) {
            statusView?.applyProgress(statusText.position, statusText.highlightColor)
        }
    }

    private fun currentDesktopLyricText(): LyricRenderText {
        val mediaController = controller ?: return placeholderLyricText(
            prefix = "desktop",
            color = preferences.color(DESKTOP_LYRICS_TEXT_COLOR),
            outlined = true,
            includeArtist = true
        )
        val lyrics = overlayLyrics
        val color = preferences.color(DESKTOP_LYRICS_TEXT_COLOR)
        lyrics.synced?.let { synced ->
            val adjustedPosition = mediaController.currentPosition + synced.offset
            val lineIndex = synced.lineIndexAt(adjustedPosition)
            val line = synced.lines.getOrNull(lineIndex) ?: return placeholderLyricText(
                prefix = "desktop_pending",
                color = color,
                outlined = true,
                includeArtist = true
            )
            val secondary = line.secondaryOverlayText(
                nextLine = synced.lines.getOrNull(lineIndex + 1),
                includeNextLineFallback = Preferences.desktopLyricsShowNextLine,
                singleLine = true
            )?.toOutlinedText(Color.WHITE)
            val animated = line.content.canUseSmoothDesktopKaraoke()
            return LyricRenderText(
                primary = line.toDesktopPrimarySpannable(adjustedPosition, color),
                secondary = secondary,
                key = buildDesktopLyricKey(synced, lineIndex),
                content = line.content.takeIf { animated },
                position = adjustedPosition,
                highlightColor = color,
                animated = animated
            )
        }
        if (lyrics.plainLines.isNotEmpty()) {
            val primary = lyrics.plainLines.first()
            val secondary = lyrics.plainLines.getOrNull(1)
            return LyricRenderText(
                primary = primary.toOutlinedText(Color.WHITE),
                secondary = secondary?.toOutlinedText(Color.WHITE),
                key = "plain:$primary\n$secondary",
                highlightColor = Color.WHITE
            )
        }
        return placeholderLyricText(
            prefix = "desktop_title",
            color = color,
            outlined = true,
            includeArtist = true
        )
    }

    private fun buildDesktopLyricKey(
        synced: SyncedLyrics,
        lineIndex: Int
    ): String {
        val line = synced.lines.getOrNull(lineIndex) ?: return "empty"
        val nextLineText = synced.lines.getOrNull(lineIndex + 1)?.content?.content.orEmpty()
        return buildString {
            append("synced:")
            append(lineIndex)
            append(':')
            append(line.start)
            append(':')
            append(line.end)
            append(':')
            append(line.content.content)
            append(':')
            append(nextLineText)
            if (preferences.getBoolean(LyricsViewSettings.Key.SHOW_TRANSLITERATION, false)) {
                append(":tr:")
                append(line.transliteration?.content.orEmpty())
            }
            if (preferences.getBoolean(LyricsViewSettings.Key.SHOW_TRANSLATION, true)) {
                append(":tl:")
                append(line.translation?.content.orEmpty())
            }
            append(":next:")
            append(Preferences.desktopLyricsShowNextLine)
        }
    }

    private fun SyncedLyrics.TextContent.canUseSmoothDesktopKaraoke(): Boolean =
        preferences.getBoolean(LyricsViewSettings.Key.ENABLE_SYLLABLE_LYRICS, false) && mainSyllables.isNotEmpty()

    private fun SyncedLyrics.TextContent.canUsePipelineKaraoke(): Boolean =
        preferences.getBoolean(LyricsViewSettings.Key.ENABLE_SYLLABLE_LYRICS, false) && mainSyllables.isNotEmpty()

    private fun currentStatusLyricText(): LyricRenderText {
        val mediaController = controller ?: return placeholderLyricText(
            prefix = "status",
            color = preferences.color(STATUS_BAR_LYRICS_TEXT_COLOR),
            outlined = false,
            includeArtist = false
        )
        val lyrics = overlayLyrics
        val position = mediaController.currentPosition
        val color = preferences.color(STATUS_BAR_LYRICS_TEXT_COLOR)
        lyrics.synced?.let { synced ->
            val adjustedPosition = position + synced.offset
            val lineIndex = synced.lineIndexAt(adjustedPosition)
            val line = synced.lines.getOrNull(lineIndex) ?: return placeholderLyricText(
                prefix = "status_pending",
                color = color,
                outlined = false,
                includeArtist = false
            )
            val text = line.content.content
            val secondary = line.secondaryOverlayText(
                nextLine = null,
                includeNextLineFallback = false,
                singleLine = true
            )
            val animated = line.content.canUsePipelineKaraoke()
            return LyricRenderText(
                primary = text,
                secondary = secondary,
                key = "status:$lineIndex:${line.start}:${line.end}:$text:$secondary",
                content = line.content.takeIf { animated },
                position = adjustedPosition,
                highlightColor = color,
                animated = animated
            )
        }
        if (lyrics.plainLines.isNotEmpty()) {
            val text = lyrics.plainLines.first()
            return LyricRenderText(
                primary = text,
                secondary = null,
                key = "status_plain:$text",
                highlightColor = color
            )
        }
        return LyricRenderText(
            primary = currentSongTitle(),
            secondary = null,
            key = "status_title:${currentSongTitle()}",
            highlightColor = color
        )
    }

    private fun placeholderLyricText(
        prefix: String,
        color: Int,
        outlined: Boolean,
        includeArtist: Boolean
    ): LyricRenderText {
        val title = currentSongTitle()
        val artist = currentSongArtist().takeIf { includeArtist && it.isNotBlank() }
        val primary = if (outlined) title.toOutlinedText(Color.WHITE) else title
        val secondary = if (outlined) artist?.toOutlinedText(Color.WHITE) else artist
        return LyricRenderText(
            primary = primary,
            secondary = secondary,
            key = "$prefix:$title:$artist",
            highlightColor = color
        )
    }

    private fun currentSongTitle(): String =
        controller?.mediaMetadata?.title?.toString().orEmpty().ifBlank { getString(R.string.now_playing) }

    private fun currentSongArtist(): String =
        controller?.mediaMetadata?.artist?.toString().orEmpty()

    private fun SyncedLyrics.Line.secondaryOverlayText(
        nextLine: SyncedLyrics.Line?,
        includeNextLineFallback: Boolean,
        singleLine: Boolean = false
    ): String? {
        val parts = mutableListOf<String>()
        if (preferences.getBoolean(LyricsViewSettings.Key.SHOW_TRANSLATION, true)) {
            translation?.content?.takeIf { it.isNotBlank() }?.let(parts::add)
            if (singleLine && parts.isNotEmpty()) return parts.first()
        }
        if (preferences.getBoolean(LyricsViewSettings.Key.SHOW_TRANSLITERATION, false)) {
            transliteration?.content?.takeIf { it.isNotBlank() }?.let(parts::add)
            if (singleLine && parts.isNotEmpty()) return parts.first()
        }
        if (parts.isEmpty() && includeNextLineFallback) {
            nextLine?.content?.content?.takeIf { it.isNotBlank() }?.let(parts::add)
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun SyncedLyrics.Line.toDesktopPrimarySpannable(position: Long, color: Int): CharSequence {
        val builder = SpannableStringBuilder()
        appendDesktopContent(builder, content, position, color)
        return builder
    }

    private fun appendDesktopContent(
        builder: SpannableStringBuilder,
        content: SyncedLyrics.TextContent,
        position: Long,
        color: Int
    ) {
        val syllables = content.mainSyllables
        val enableSyllable = preferences.getBoolean(LyricsViewSettings.Key.ENABLE_SYLLABLE_LYRICS, false)
        if (!enableSyllable || syllables.isEmpty()) {
            val start = builder.length
            builder.append(content.content)
            builder.setSpan(OutlinedTextSpan(color), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return
        }

        val karaokeStyle = preferences.getBoolean(LyricsViewSettings.Key.ENABLE_KARAOKE_STYLE, false)
        syllables.forEach { word ->
            val start = builder.length
            builder.append(word.content)
            val wordColor = when {
                position >= word.end -> color
                position in word.start..word.end -> if (karaokeStyle) color else color.withAlpha(0.95f)
                else -> Color.WHITE
            }
            builder.setSpan(OutlinedTextSpan(wordColor), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun String.toOutlinedText(color: Int): CharSequence {
        val builder = SpannableStringBuilder(this)
        if (isNotEmpty()) {
            builder.setSpan(OutlinedTextSpan(color), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return builder
    }

    private fun SyncedLyrics.lineAt(position: Long): SyncedLyrics.Line? {
        val index = lineIndexAt(position)
        return if (index >= 0) lines.getOrNull(index) else null
    }

    private fun SyncedLyrics.lineIndexAt(position: Long): Int {
        for (i in lines.lastIndex downTo 0) {
            if (position >= lines[i].start) {
                return i
            }
        }
        return -1
    }

    private fun removeWindow(window: OverlayWindow?) {
        if (window == null) return
        runCatching { windowManager.removeView(window.view) }
    }

    private fun updateWindow(window: OverlayWindow) {
        runCatching { windowManager.updateViewLayout(window.view, window.params) }
    }

    private fun clampWindowToDisplay(window: OverlayWindow) {
        val displayWidth = resources.displayMetrics.widthPixels
        val displayHeight = resources.displayMetrics.heightPixels
        val viewWidth = window.view.width.takeIf { it > 0 } ?: when (window.params.width) {
            WindowManager.LayoutParams.MATCH_PARENT -> displayWidth
            WindowManager.LayoutParams.WRAP_CONTENT -> 0
            else -> window.params.width.coerceAtLeast(0)
        }
        val viewHeight = window.view.height.takeIf { it > 0 } ?: when (window.params.height) {
            WindowManager.LayoutParams.MATCH_PARENT -> displayHeight
            WindowManager.LayoutParams.WRAP_CONTENT -> 0
            else -> window.params.height.coerceAtLeast(0)
        }
        val maxX = (displayWidth - viewWidth).coerceAtLeast(0)
        val maxY = (displayHeight - viewHeight).coerceAtLeast(0)
        window.params.x = when (window.kind) {
            OverlayKind.Desktop -> 0
            OverlayKind.StatusBar -> window.params.x.coerceIn(0, maxX)
        }
        window.params.y = window.params.y.coerceIn(0, maxY)
    }

    private fun createOverlayContext(): Context =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
            val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            val displayContext = if (display != null) createDisplayContext(display) else applicationContext
            displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else {
            this
        }

    private fun baseFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

    private fun Int.withTouchable(touchable: Boolean): Int =
        if (touchable) {
            this and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            this or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

    private fun Int.withAlpha(alpha: Float): Int =
        ColorUtils.setAlphaComponent(this, (255 * alpha).roundToInt().coerceIn(0, 255))

    private fun statusBarLyricsBackgroundColor(highlightColor: Int): Int {
        val luminance = ColorUtils.calculateLuminance(highlightColor)
        return if (luminance > 0.82) {
            Color.BLACK.withAlpha(0.50f)
        } else {
            highlightColor.withAlpha(0.52f)
        }
    }

    private fun SharedPreferences.color(key: String): Int =
        runCatching { getString(key, "#FFFFFFFF")!!.toColorInt() }.getOrDefault(Color.WHITE)

    private fun statusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else dp(24)
    }

    private fun statusBarLyricsWidth(): Int {
        val configuredWidth = dp(preferences.getInt(STATUS_BAR_LYRICS_WIDTH, 160))
        return configuredWidth.coerceIn(dp(48), resources.displayMetrics.widthPixels)
    }

    private fun statusBarLyricsX(width: Int): Int {
        val progress = preferences.getInt(STATUS_BAR_LYRICS_X, 50).coerceIn(0, 100)
        val availableWidth = (resources.displayMetrics.widthPixels - width).coerceAtLeast(0)
        return availableWidth * progress / 100
    }

    private fun statusBarLyricsY(): Int {
        return dp(preferences.getInt(STATUS_BAR_LYRICS_Y, 0).coerceIn(0, 80))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private inner class DesktopLyricsCardView(context: Context) : LinearLayout(context) {
        private val viewContext = context
        private val titleView = TextView(context)
        private val artistView = TextView(context)
        private val primaryLyricView = PipelineLyricView(context)
        private val secondaryLyricView = TextView(context)
        private val controlsRow = LinearLayout(context)
        private val configRow = LinearLayout(context)
        private val colorRow = LinearLayout(context)
        private val colorRowTop = LinearLayout(context)
        private val colorRowBottom = LinearLayout(context)
        private val sizeRow = LinearLayout(context)
        private val sizeValueView = TextView(context)
        private val playPauseButton = iconButton(R.drawable.ic_pause_24dp, R.string.action_play_pause) {
            controller?.let { mediaController ->
                if (mediaController.isPlaying) {
                    mediaController.pause()
                } else {
                    mediaController.play()
                }
            }
            applyMetadata(
                title = controller?.mediaMetadata?.title?.toString().orEmpty(),
                artist = controller?.mediaMetadata?.artist?.toString().orEmpty(),
                isPlaying = controller?.isPlaying == true
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
                ellipsize = TextUtils.TruncateAt.END
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
            artistView.apply {
                includeFontPadding = false
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(0xCCFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
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
            secondaryLyricView.apply {
                includeFontPadding = false
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                typeface = Typeface.DEFAULT_BOLD
                filters = arrayOf(InputFilter.LengthFilter(240))
                setTextColor(Color.WHITE)
            }
            listOf(secondaryLyricView).forEach { textView ->
                textView.includeFontPadding = false
                textView.gravity = Gravity.CENTER
                textView.maxLines = 2
                textView.ellipsize = TextUtils.TruncateAt.END
                textView.typeface = Typeface.DEFAULT_BOLD
                textView.filters = arrayOf(InputFilter.LengthFilter(240))
                textView.setTextColor(Color.WHITE)
            }
            secondaryLyricView.setTextSize(TypedValue.COMPLEX_UNIT_SP, (textSp * 0.9f))

            controlsRow.apply {
                gravity = Gravity.CENTER
                orientation = HORIZONTAL
                background = panelBackground(0x26000000)
                setPadding(dp(4), dp(4), dp(4), dp(4))
                addView(iconButton(R.drawable.ic_lock_24dp, R.string.desktop_lyrics_lock_title) {
                    setExpanded(false)
                    preferences.edit { putBoolean(DESKTOP_LYRICS_LOCKED, true) }
                })
                addView(iconButton(R.drawable.ic_previous_24dp, R.string.action_previous) {
                    controller?.seekToPreviousMediaItem()
                })
                addView(playPauseButton)
                addView(iconButton(R.drawable.ic_next_24dp, R.string.action_next) {
                    controller?.seekToNextMediaItem()
                })
                addView(iconButton(R.drawable.ic_close_24dp, R.string.action_cancel) {
                    setExpanded(false)
                })
            }

            colorRow.apply {
                gravity = Gravity.CENTER
                orientation = VERTICAL
                addView(colorRowTop, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
                addView(colorRowBottom, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(8)
                })
            }
            listOf(colorRowTop, colorRowBottom).forEach { row ->
                row.gravity = Gravity.CENTER
                row.orientation = HORIZONTAL
            }

            sizeValueView.apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xE6FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }
            sizeRow.apply {
                gravity = Gravity.CENTER
                orientation = HORIZONTAL
                background = panelBackground(0x1FFFFFFF)
                setPadding(dp(6), dp(4), dp(6), dp(4))
                addView(textButton("T-", 38, 30) {
                    changeDesktopTextSize(-2)
                })
                addView(sizeValueView, LayoutParams(dp(42), dp(30)).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                })
                addView(textButton("T+", 38, 30) {
                    changeDesktopTextSize(2)
                })
            }

            configRow.apply {
                gravity = Gravity.CENTER
                orientation = VERTICAL
                background = panelBackground(0x26000000)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                addView(colorRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
                addView(sizeRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(10)
                })
            }

            addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(artistView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
            })
            addView(primaryLyricView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            })
            addView(controlsRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(14)
            })
            addView(configRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
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
            secondaryLyricView.setTextSize(TypedValue.COMPLEX_UNIT_SP, (textSp * 0.9f))
            sizeValueView.text = textSize.toString()
            rebuildColorRow()
            if (locked) {
                setExpanded(false)
            }
        }

        fun applyText(text: LyricRenderText) {
            primaryLyricView.applyText(text)
            secondaryLyricView.visibility = View.GONE
            visibility = View.VISIBLE
        }

        fun applyMetadata(title: String, artist: String, isPlaying: Boolean) {
            titleView.text = title.ifBlank { getString(R.string.now_playing) }
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
            val detailsVisibility = if (expanded) View.VISIBLE else View.GONE
            titleView.visibility = detailsVisibility
            artistView.visibility = if (expanded && artistView.text.isNotBlank()) View.VISIBLE else View.GONE
            controlsRow.visibility = detailsVisibility
            configRow.visibility = detailsVisibility
            background = if (expanded) {
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(0x99000000.toInt())
                }
            } else {
                null
            }
            setPadding(
                dp(16),
                if (expanded) dp(16) else dp(4),
                dp(16),
                if (expanded) dp(18) else dp(4)
            )
        }

        private fun rebuildColorRow() {
            val colors = listOf("#FFE57373", "#FFE79A61", "#FFFFD54F", "#FF80CBC4", "#FF90CAF9", "#FFF48FB1", "#FFFFFFFF")
            colorRowTop.removeAllViews()
            colorRowBottom.removeAllViews()
            colors.forEachIndexed { index, colorValue ->
                val color = colorValue.toColorInt()
                val row = if (index < 4) colorRowTop else colorRowBottom
                row.addView(View(viewContext).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(color)
                        setStroke(dp(2), if (color == textColor) Color.WHITE else 0x33FFFFFF)
                    }
                    setOnClickListener {
                        preferences.edit { putString(DESKTOP_LYRICS_TEXT_COLOR, colorValue) }
                    }
                }, LayoutParams(dp(24), dp(24)).apply {
                    marginStart = dp(5)
                    marginEnd = dp(5)
                })
            }
        }

        private fun iconButton(icon: Int, description: Int, onClick: () -> Unit): ImageButton {
            return ImageButton(viewContext).apply {
                setImageResource(icon)
                setColorFilter(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x18FFFFFF)
                }
                contentDescription = getString(description)
                setPadding(dp(9), dp(9), dp(9), dp(9))
                setOnClickListener { onClick() }
            }.also { button ->
                button.layoutParams = LayoutParams(dp(40), dp(40)).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                }
            }
        }

        private fun textButton(label: String, widthDp: Int, heightDp: Int, onClick: () -> Unit): TextView {
            return TextView(viewContext).apply {
                text = label
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                includeFontPadding = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                background = panelBackground(0x22FFFFFF)
                setOnClickListener { onClick() }
            }.also { view ->
                view.layoutParams = LayoutParams(dp(widthDp), dp(heightDp)).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
            }
        }

        private fun panelBackground(color: Int): GradientDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(color)
            }

        private fun changeDesktopTextSize(delta: Int) {
            val newSize = (preferences.getInt(DESKTOP_LYRICS_TEXT_SIZE, 22) + delta).coerceIn(12, 48)
            preferences.edit { putInt(DESKTOP_LYRICS_TEXT_SIZE, newSize) }
        }
    }

    private inner class PipelineLyricView(context: Context) : View(context), Choreographer.FrameCallback {
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
        }
        private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
        }
        private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = 0xCC000000.toInt()
        }
        private val secondaryPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
            color = Color.WHITE
        }
        private val secondaryStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
            style = Paint.Style.STROKE
            color = 0xCC000000.toInt()
        }
        private val progressAnimator = RenderProgressAnimator()
        private var model = RenderModel.Empty
        private var secondaryModel = RenderModel.Empty
        private var lineState = RenderLineState()
        private var secondaryLineState = RenderLineState()
        private var running = false
        private var lastFrameNanos = 0L
        private var lastPosition = Long.MIN_VALUE
        private var primaryColor = Color.WHITE
        private var backgroundColor = Color.WHITE
        private var highlightColor = Color.WHITE
        private var outline = false
        private var outlineWidth = 0f
        private var centerWhenFits = true
        private var charMotion = true
        private var scrollOnly = false
        private var drawSecondary = false
        private var scrollStarted = false
        private var scrollPendingDelay = false
        private var scrollDelayNanos = 0L
        private var scrollOffset = 0f
        private var secondaryScrollStarted = false
        private var secondaryScrollPendingDelay = false
        private var secondaryScrollDelayNanos = 0L
        private var secondaryScrollOffset = 0f
        private var textHeight = dp(24)
        private var secondaryTextHeight = dp(16)
        private var baselineOffset = 0f
        private var secondaryBaselineOffset = 0f
        private val ghostSpacing get() = dp(40).toFloat()
        private val scrollSpeedPxPerMs get() = dp(40) / 1000f

        init {
            setPadding(0, dp(2), 0, dp(2))
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(10))
        }

        fun configure(
            textSizeSp: Int,
            primaryColor: Int,
            backgroundColor: Int,
            highlightColor: Int,
            secondaryColor: Int,
            outline: Boolean,
            outlineWidth: Float,
            centerWhenFits: Boolean,
            charMotion: Boolean,
            scrollOnly: Boolean,
            drawSecondary: Boolean
        ) {
            val secondaryModeChanged = this.drawSecondary != drawSecondary
            this.primaryColor = primaryColor
            this.backgroundColor = backgroundColor
            this.highlightColor = highlightColor
            this.outline = outline
            this.outlineWidth = outlineWidth
            this.centerWhenFits = centerWhenFits
            this.charMotion = charMotion
            this.scrollOnly = scrollOnly
            this.drawSecondary = drawSecondary
            val textSizePx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                textSizeSp.toFloat(),
                resources.displayMetrics
            )
            listOf(textPaint, basePaint, highlightPaint, strokePaint).forEach {
                it.textSize = textSizePx
                it.typeface = Typeface.DEFAULT_BOLD
            }
            listOf(secondaryPaint, secondaryStrokePaint).forEach {
                it.textSize = textSizePx * 0.72f
                it.typeface = Typeface.DEFAULT_BOLD
            }
            textPaint.color = primaryColor
            basePaint.color = backgroundColor
            highlightPaint.color = highlightColor
            secondaryPaint.color = secondaryColor
            strokePaint.strokeWidth = outlineWidth
            secondaryStrokePaint.strokeWidth = outlineWidth
            updateFontMetrics()
            model.updateSizes(textPaint)
            secondaryModel.updateSizes(secondaryPaint)
            if (secondaryModeChanged) {
                requestLayout()
            }
            requestLayout()
            invalidate()
        }

        fun applyText(text: LyricRenderText) {
            val newModel = RenderModel.from(text, textPaint)
            val newSecondaryModel = if (drawSecondary) {
                RenderModel.fromSecondary(text.secondary?.toString().orEmpty(), secondaryPaint)
            } else {
                RenderModel.Empty
            }
            val changedModel = newModel.identity != model.identity
            val changedSecondary = newSecondaryModel.identity != secondaryModel.identity
            if (changedModel) {
                model = newModel
                model.updateSizes(textPaint)
                lineState.reset()
                progressAnimator.reset()
                scrollStarted = false
                scrollPendingDelay = false
                scrollOffset = 0f
                lastPosition = Long.MIN_VALUE
                requestLayout()
            }
            if (changedSecondary) {
                secondaryModel = newSecondaryModel
                secondaryModel.updateSizes(secondaryPaint)
                secondaryLineState.reset()
                secondaryScrollStarted = false
                secondaryScrollPendingDelay = false
                secondaryScrollDelayNanos = 0L
                secondaryScrollOffset = 0f
                requestLayout()
            }
            visibility = VISIBLE
            highlightColor = text.highlightColor
            highlightPaint.color = highlightColor
            updatePosition(text.position)
            invalidate()
        }

        fun applyProgress(position: Long, highlightColor: Int) {
            this.highlightColor = highlightColor
            highlightPaint.color = highlightColor
            updatePosition(position)
            invalidate()
        }

        private fun updatePosition(position: Long) {
            if (model.isWordSync && !scrollOnly) {
                updateWordProgress(position)
                startAnimator()
            } else {
                startPlainScroll()
            }
            startSecondaryScroll()
        }

        private fun updateWordProgress(position: Long) {
            val target = targetWidth(position)
            if (lastPosition != Long.MIN_VALUE && position < lastPosition) {
                progressAnimator.jumpTo(target)
                lineState.scrollOffset = computeScrollOffset(progressAnimator.currentWidth)
            } else {
                val activeWord = model.words.firstOrNull { position in it.start..it.end }
                if (activeWord != null && progressAnimator.currentWidth == 0f) {
                    activeWord.previous?.let { progressAnimator.jumpTo(it.endPosition) }
                }
                if (target != progressAnimator.targetWidth) {
                    val duration = activeWord?.let { (it.end - position).coerceAtLeast(1) } ?: 1L
                    progressAnimator.animateTo(target, duration)
                }
            }
            lastPosition = position
        }

        private fun targetWidth(position: Long): Float {
            if (model.words.isEmpty()) return model.width
            if (position <= model.begin) return 0f
            if (position >= model.end) return model.width
            val word = model.words.firstOrNull { position <= it.end }
            return when {
                word == null -> model.width
                position < word.start -> word.startPosition
                else -> word.endPosition
            }
        }

        private fun startPlainScroll() {
            if (scrollStarted || model.width <= contentWidth()) return
            scrollStarted = true
            scrollPendingDelay = true
            scrollDelayNanos = 400L * 1_000_000L
            startAnimator()
        }

        private fun startSecondaryScroll() {
            if (!drawSecondary || secondaryModel.text.isBlank()) return
            if (secondaryScrollStarted || secondaryModel.width <= contentWidth()) return
            secondaryScrollStarted = true
            secondaryScrollPendingDelay = true
            secondaryScrollDelayNanos = 400L * 1_000_000L
            startAnimator()
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!running || !isAttachedToWindow) {
                running = false
                return
            }
            val deltaNanos = if (lastFrameNanos == 0L) 0L else frameTimeNanos - lastFrameNanos
            lastFrameNanos = frameTimeNanos
            var changed = false
            if (model.isWordSync && !scrollOnly) {
                changed = progressAnimator.step(deltaNanos)
                if (changed) {
                    lineState.scrollOffset = computeScrollOffset(progressAnimator.currentWidth)
                }
            } else if (model.width > contentWidth()) {
                changed = stepPlainScroll(deltaNanos)
            }
            if (secondaryModel.width > contentWidth()) {
                changed = stepSecondaryScroll(deltaNanos) || changed
            }
            if (changed) postInvalidateOnAnimation()
            if (progressAnimator.isAnimating || model.width > contentWidth() || secondaryModel.width > contentWidth()) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                running = false
            }
        }

        private fun stepPlainScroll(deltaNanos: Long): Boolean {
            if (scrollPendingDelay) {
                scrollDelayNanos -= deltaNanos
                if (scrollDelayNanos > 0L) return false
                scrollPendingDelay = false
            }
            scrollOffset -= scrollSpeedPxPerMs * (deltaNanos / 1_000_000f)
            val unit = model.width + ghostSpacing
            if (-scrollOffset >= unit) {
                scrollOffset += unit
                scrollPendingDelay = true
                scrollDelayNanos = 800L * 1_000_000L
            }
            lineState.scrollOffset = scrollOffset
            return true
        }

        private fun stepSecondaryScroll(deltaNanos: Long): Boolean {
            if (secondaryScrollPendingDelay) {
                secondaryScrollDelayNanos -= deltaNanos
                if (secondaryScrollDelayNanos > 0L) return false
                secondaryScrollPendingDelay = false
            }
            secondaryScrollOffset -= scrollSpeedPxPerMs * (deltaNanos / 1_000_000f)
            val unit = secondaryModel.width + ghostSpacing
            if (-secondaryScrollOffset >= unit) {
                secondaryScrollOffset += unit
                secondaryScrollPendingDelay = true
                secondaryScrollDelayNanos = 800L * 1_000_000L
            }
            secondaryLineState.scrollOffset = secondaryScrollOffset
            return true
        }

        private fun computeScrollOffset(highlightWidth: Float): Float {
            if (model.width <= contentWidth()) return 0f
            val minScroll = -(model.width - contentWidth())
            if (highlightWidth >= model.width) return minScroll
            val halfWidth = contentWidth() / 2f
            return if (highlightWidth > halfWidth) {
                (halfWidth - highlightWidth).coerceIn(minScroll, 0f)
            } else 0f
        }

        override fun onDraw(canvas: Canvas) {
            if (model.text.isBlank()) return
            val hasSecondary = drawSecondary && secondaryModel.text.isNotBlank()
            val primaryBaseline = primaryBaseline(hasSecondary)
            val x = drawStartX()
            if (model.isWordSync && !scrollOnly) {
                val progressWidth = progressAnimator.currentWidth
                if (outline && outlineWidth > 0f) {
                    drawWordLayer(canvas, x, primaryBaseline, strokePaint, 0f, model.width, stroke = true)
                }
                drawWordLayer(canvas, x, primaryBaseline, basePaint, 0f, model.width)
                if (progressWidth > 0f) {
                    drawWordLayer(canvas, x, primaryBaseline, highlightPaint, 0f, progressWidth)
                }
            } else {
                drawPlain(canvas, x, primaryBaseline)
            }
            if (hasSecondary) {
                drawSecondaryLine(canvas, secondaryBaseline())
            }
        }

        private fun drawPlain(canvas: Canvas, startX: Float, baseline: Float) {
            val x = startX + lineState.scrollOffset
            if (outline) canvas.drawText(model.text, x, baseline, strokePaint)
            canvas.drawText(model.text, x, baseline, textPaint)
            if (model.width > contentWidth()) {
                val ghostX = x + model.width + ghostSpacing
                if (ghostX < width) {
                    if (outline) canvas.drawText(model.text, ghostX, baseline, strokePaint)
                    canvas.drawText(model.text, ghostX, baseline, textPaint)
                }
            }
        }

        private fun drawWordLayer(
            canvas: Canvas,
            startX: Float,
            baseline: Float,
            paint: Paint,
            clipStart: Float,
            clipEnd: Float,
            stroke: Boolean = false
        ) {
            if (clipEnd <= clipStart) return
            val offset = startX + lineState.scrollOffset
            val save = canvas.save()
            canvas.clipRect(offset + clipStart, 0f, offset + clipEnd, height.toFloat())
            model.words.forEach { word ->
                if (charMotion && !stroke) {
                    drawMotionWord(canvas, word, offset, baseline, paint, clipStart, clipEnd)
                } else {
                    canvas.drawText(word.text, offset + word.startPosition, baseline, paint)
                }
            }
            canvas.restoreToCount(save)
        }

        private fun drawMotionWord(
            canvas: Canvas,
            word: RenderWord,
            offset: Float,
            baseline: Float,
            paint: Paint,
            clipStart: Float,
            clipEnd: Float
        ) {
            val byChar = word.text.any { it.isCjk() }
            if (!byChar) {
                val lift = computeUnitLift(word.startPosition, word.endPosition, paint.textSize)
                canvas.drawText(word.text, offset + word.startPosition, baseline + lift, paint)
                return
            }
            for (i in word.chars.indices) {
                val charStart = word.charStarts[i]
                val charEnd = word.charEnds[i]
                if (charEnd <= clipStart || charStart >= clipEnd) continue
                val lift = computeUnitLift(charStart, charEnd, paint.textSize)
                canvas.drawText(word.text, i, i + 1, offset + charStart, baseline + lift, paint)
            }
        }

        private fun computeUnitLift(unitStart: Float, unitEnd: Float, textSize: Float): Float {
            val progress = progressAnimator.currentWidth
            val unitCenter = (unitStart + unitEnd) / 2f
            val phase = ((progress - unitCenter) / (textSize * 3.0f)).coerceIn(0f, 1f)
            val inverse = 1f - phase
            val eased = 1f - inverse * inverse * inverse * inverse * inverse
            return -(textSize * 0.06f) * (1f - eased)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val motionPadding = if (charMotion) ceil(textPaint.textSize * 0.08f).toInt() else 0
            val secondaryHeight = if (drawSecondary && secondaryModel.text.isNotBlank()) {
                dp(2) + secondaryTextHeight
            } else {
                0
            }
            val desiredHeight = textHeight + motionPadding + secondaryHeight + paddingTop + paddingBottom
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(desiredHeight, heightMeasureSpec))
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (progressAnimator.isAnimating || model.width > contentWidth() || secondaryModel.width > contentWidth()) startAnimator()
        }

        override fun onDetachedFromWindow() {
            stopAnimator()
            super.onDetachedFromWindow()
        }

        private fun startAnimator() {
            if (!running && isAttachedToWindow) {
                running = true
                lastFrameNanos = 0L
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

        private fun stopAnimator() {
            running = false
            Choreographer.getInstance().removeFrameCallback(this)
            lastFrameNanos = 0L
        }

        private fun drawStartX(): Float {
            val available = contentWidth()
            val centered = centerWhenFits && model.width <= available
            val left = paddingLeft.toFloat()
            return if (centered) {
                left + ((available - model.width) / 2f)
            } else {
                left
            }
        }

        private fun contentWidth(): Float =
            (width - paddingLeft - paddingRight).coerceAtLeast(0).toFloat()

        private fun updateFontMetrics() {
            val metrics = textPaint.fontMetrics
            textHeight = (metrics.descent - metrics.ascent).roundToInt()
            baselineOffset = -(metrics.descent + metrics.ascent) / 2f
            val secondaryMetrics = secondaryPaint.fontMetrics
            secondaryTextHeight = (secondaryMetrics.descent - secondaryMetrics.ascent).roundToInt()
            secondaryBaselineOffset = -(secondaryMetrics.descent + secondaryMetrics.ascent) / 2f
        }

        private fun primaryBaseline(hasSecondary: Boolean): Float {
            if (!hasSecondary) {
                return paddingTop + (height - paddingTop - paddingBottom) / 2f + baselineOffset
            }
            val totalHeight = textHeight + dp(2) + secondaryTextHeight
            val top = paddingTop + (height - paddingTop - paddingBottom - totalHeight) / 2f
            return top + textHeight / 2f + baselineOffset
        }

        private fun secondaryBaseline(): Float {
            val totalHeight = textHeight + dp(2) + secondaryTextHeight
            val top = paddingTop + (height - paddingTop - paddingBottom - totalHeight) / 2f
            return top + textHeight + dp(2) + secondaryTextHeight / 2f + secondaryBaselineOffset
        }

        private fun drawSecondaryLine(canvas: Canvas, baseline: Float) {
            val centered = centerWhenFits && secondaryModel.width <= contentWidth()
            val x = if (centered) {
                paddingLeft + (contentWidth() - secondaryModel.width) / 2f
            } else {
                paddingLeft.toFloat()
            } + secondaryLineState.scrollOffset
            if (outline && outlineWidth > 0f) {
                canvas.drawText(secondaryModel.text, x, baseline, secondaryStrokePaint)
            }
            canvas.drawText(secondaryModel.text, x, baseline, secondaryPaint)
            if (secondaryModel.width > contentWidth()) {
                val ghostX = x + secondaryModel.width + ghostSpacing
                if (ghostX < width) {
                    if (outline && outlineWidth > 0f) {
                        canvas.drawText(secondaryModel.text, ghostX, baseline, secondaryStrokePaint)
                    }
                    canvas.drawText(secondaryModel.text, ghostX, baseline, secondaryPaint)
                }
            }
        }
    }

    private data class RenderLineState(
        var scrollOffset: Float = 0f,
        var isScrollFinished: Boolean = false
    ) {
        fun reset() {
            scrollOffset = 0f
            isScrollFinished = false
        }
    }

    private class RenderProgressAnimator {
        var currentWidth = 0f
            private set
        var targetWidth = 0f
            private set
        var isAnimating = false
            private set
        private var startWidth = 0f
        private var elapsedNanos = 0L
        private var durationNanos = 1L

        fun jumpTo(width: Float) {
            currentWidth = width
            targetWidth = width
            isAnimating = false
        }

        fun animateTo(target: Float, durationMs: Long) {
            if (target == targetWidth && isAnimating) return
            startWidth = currentWidth
            targetWidth = target
            durationNanos = durationMs.coerceAtLeast(1L) * 1_000_000L
            elapsedNanos = 0L
            isAnimating = true
        }

        fun step(deltaNanos: Long): Boolean {
            if (!isAnimating) return false
            elapsedNanos += deltaNanos
            if (elapsedNanos >= durationNanos) {
                currentWidth = targetWidth
                isAnimating = false
                return true
            }
            currentWidth = startWidth + (targetWidth - startWidth) * (elapsedNanos.toFloat() / durationNanos)
            return true
        }

        fun reset() {
            currentWidth = 0f
            targetWidth = 0f
            isAnimating = false
            startWidth = 0f
            elapsedNanos = 0L
            durationNanos = 1L
        }
    }

    private data class RenderModel(
        val identity: String,
        val text: String,
        val begin: Long,
        val end: Long,
        val words: List<RenderWord>
    ) {
        var width: Float = 0f
            private set
        val isWordSync: Boolean get() = words.isNotEmpty()

        fun updateSizes(paint: Paint) {
            width = measureFullWidth(paint, text)
            var x = 0f
            var previous: RenderWord? = null
            words.forEach { word ->
                word.previous = previous
                previous?.next = word
                word.updateSizes(x, paint)
                x = word.endPosition
                previous = word
            }
            if (words.isNotEmpty()) {
                width = words.last().endPosition
            }
        }

        private fun measureFullWidth(paint: Paint, text: String): Float {
            if (text.isBlank()) return 0f
            val measureWidth = paint.measureText(text)
            val bounds = Rect()
            paint.getTextBounds(text, 0, text.length, bounds)
            return if (bounds.right > measureWidth) bounds.right.toFloat() else measureWidth
        }

        companion object {
            val Empty = RenderModel("empty", "", 0L, 0L, emptyList())

            fun from(text: LyricRenderText, paint: Paint): RenderModel {
                val content = text.content
                val model = if (content != null && content.mainSyllables.isNotEmpty()) {
                    RenderModel(
                        identity = text.key,
                        text = content.content,
                        begin = content.mainSyllables.first().start,
                        end = content.mainSyllables.last().end,
                        words = content.mainSyllables.map {
                            RenderWord(
                                text = it.content,
                                start = it.start,
                                end = it.end,
                                duration = it.duration
                            )
                        }
                    )
                } else {
                    RenderModel(text.key, text.primary.toString(), 0L, 0L, emptyList())
                }
                model.updateSizes(paint)
                return model
            }

            fun fromSecondary(text: String, paint: Paint): RenderModel {
                val normalized = text.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() }
                    .orEmpty()
                return RenderModel("secondary:$normalized", normalized, 0L, 0L, emptyList()).also {
                    it.updateSizes(paint)
                }
            }
        }
    }

    private data class RenderWord(
        val text: String,
        val start: Long,
        val end: Long,
        val duration: Long
    ) {
        var previous: RenderWord? = null
        var next: RenderWord? = null
        var startPosition: Float = 0f
            private set
        var endPosition: Float = 0f
            private set
        val chars: CharArray = text.toCharArray()
        val charWidths = FloatArray(text.length)
        val charStarts = FloatArray(text.length)
        val charEnds = FloatArray(text.length)

        fun updateSizes(startX: Float, paint: Paint) {
            startPosition = startX
            if (chars.isNotEmpty()) {
                paint.getTextWidths(chars, 0, chars.size, charWidths)
            }
            var x = startX
            for (i in chars.indices) {
                charStarts[i] = x
                x += charWidths[i]
                charEnds[i] = x
            }
            endPosition = x
        }
    }

    private fun Char.isCjk(): Boolean {
        val block = Character.UnicodeBlock.of(this)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
                block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                block == Character.UnicodeBlock.HIRAGANA ||
                block == Character.UnicodeBlock.KATAKANA ||
                block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
                block == Character.UnicodeBlock.HANGUL_JAMO ||
                block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
    }

    private inner class DragTouchListener(
        private val window: OverlayWindow
    ) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var downRawX = 0f
        private var downRawY = 0f
        private var moved = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            if (window.kind == OverlayKind.Desktop && Preferences.desktopLyricsLocked) {
                return false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = window.params.x
                    startY = window.params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    moved = moved ||
                            kotlin.math.abs(event.rawX - downRawX) > dp(4) ||
                            kotlin.math.abs(event.rawY - downRawY) > dp(4)
                    if (moved) {
                        window.params.y = startY + (event.rawY - downRawY).roundToInt()
                        if (window.kind == OverlayKind.Desktop) {
                            window.params.x = 0
                        } else {
                            window.params.x = startX + (event.rawX - downRawX).roundToInt()
                        }
                        clampWindowToDisplay(window)
                        updateWindow(window)
                    }
                    return true
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (!moved && window.kind == OverlayKind.Desktop) {
                        (window.view as? DesktopLyricsCardView)?.toggleExpanded()
                    } else {
                        preferences.edit {
                            putInt(window.kind.xKey, window.params.x)
                            putInt(window.kind.yKey, window.params.y)
                        }
                    }
                    return true
                }
            }
            return false
        }
    }

    private class OutlinedTextSpan(
        private val textColor: Int
    ) : ReplacementSpan() {
        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?
        ): Int = paint.measureText(text, start, end).roundToInt()

        override fun draw(
            canvas: Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
        ) {
            val oldColor = paint.color
            val oldStyle = paint.style
            val oldStrokeWidth = paint.strokeWidth
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = 0xCC000000.toInt()
            canvas.drawText(text, start, end, x, y.toFloat(), paint)
            paint.style = Paint.Style.FILL
            paint.strokeWidth = oldStrokeWidth
            paint.color = textColor
            canvas.drawText(text, start, end, x, y.toFloat(), paint)
            paint.color = oldColor
            paint.style = oldStyle
            paint.strokeWidth = oldStrokeWidth
        }
    }

    private data class OverlayWindow(
        val kind: OverlayKind,
        val view: View,
        val params: WindowManager.LayoutParams
    )

    private data class LyricRenderText(
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

    private data class OverlayLyrics(
        val synced: SyncedLyrics?,
        val plainLines: List<String>
    ) {
        companion object {
            val Empty = OverlayLyrics(null, emptyList())
        }
    }

    private enum class OverlayKind(
        val xKey: String,
        val yKey: String,
        val sizeKey: String,
        val colorKey: String
    ) {
        Desktop(DESKTOP_LYRICS_X, DESKTOP_LYRICS_Y, DESKTOP_LYRICS_TEXT_SIZE, DESKTOP_LYRICS_TEXT_COLOR),
        StatusBar(STATUS_BAR_LYRICS_X, STATUS_BAR_LYRICS_Y, STATUS_BAR_LYRICS_TEXT_SIZE, STATUS_BAR_LYRICS_TEXT_COLOR);
    }

    private fun OverlayKind.defaultX(): Int = when (this) {
        OverlayKind.Desktop -> 0
        OverlayKind.StatusBar -> 0
    }

    private fun OverlayKind.defaultY(): Int = when (this) {
        OverlayKind.Desktop -> resources.displayMetrics.heightPixels / 2 - dp(80)
        OverlayKind.StatusBar -> 0
    }

    companion object {
        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun shouldRun(context: Context): Boolean {
            return canDrawOverlays(context) &&
                    (Preferences.desktopLyricsEnabled || Preferences.statusBarLyricsEnabled)
        }

        fun sync(context: Context) {
            val intent = Intent(context, LyricsOverlayService::class.java)
            if (shouldRun(context)) {
                context.startService(intent)
            } else {
                context.stopService(intent)
            }
        }
    }
}
