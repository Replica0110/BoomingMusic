package com.mardous.booming.ui.overlay

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.view.Gravity
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
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
import com.mardous.booming.extensions.media.displayArtistName
import com.mardous.booming.playback.PlaybackService
import com.mardous.booming.playback.ProgressObserver
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
    private var currentSong: Song = Song.emptySong
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
            OverlayKind.Desktop -> DesktopLyricsCardView(
                context = overlayContext,
                preferences = preferences,
                controllerProvider = { controller },
                dispatchPlaybackKey = ::dispatchPlaybackKey
            )
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
            currentSong = Song.emptySong
            currentSongId = Song.emptySong.id
            overlayLyrics = OverlayLyrics.Empty
            updateLyricText(force = true)
            return
        }
        currentSong = Song.emptySong
        lyricsJob = serviceScope.launch {
            val song = withContext(IO) {
                repository.songByMediaItem(mediaItem)
            }
            currentSong = song
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
            val overlaySyncedLyrics = syncedLyrics?.forOverlay()
            if (overlaySyncedLyrics?.hasContent == true) {
                return OverlayLyrics(overlaySyncedLyrics, emptyList())
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
        preferences.getBoolean(LyricsViewSettings.Key.ENABLE_SYLLABLE_LYRICS, false) &&
                mainSyllables.any { it.content.isNotEmpty() }

    private fun SyncedLyrics.TextContent.canUsePipelineKaraoke(): Boolean =
        preferences.getBoolean(LyricsViewSettings.Key.ENABLE_SYLLABLE_LYRICS, false) &&
                mainSyllables.any { it.content.isNotEmpty() }

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
        val text = if (artist != null) "$title - $artist" else title
        val primary = if (outlined) text.toOutlinedText(Color.WHITE) else text
        return LyricRenderText(
            primary = primary,
            secondary = null,
            key = "$prefix:$text",
            highlightColor = color
        )
    }

    private fun currentSongTitle(): String {
        val song = currentSong.takeIf { it != Song.emptySong }
        val songTitle = song?.title?.takeIf { it.isNotBlank() }
            ?: song?.fileName?.takeIf { it.isNotBlank() }
        return songTitle
            ?: controller?.mediaMetadata?.title?.toString().orEmpty().ifBlank {
                getString(R.string.now_playing)
            }
    }

    private fun currentSongArtist(): String {
        val song = currentSong.takeIf { it != Song.emptySong }
        return song?.displayArtistName()?.takeIf { it.isNotBlank() }
            ?: controller?.mediaMetadata?.artist?.toString().orEmpty()
    }

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
            if (content.content.isEmpty()) return
            val start = builder.length
            builder.append(content.content)
            builder.setSpan(OutlinedTextSpan(color), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return
        }

        val karaokeStyle = preferences.getBoolean(LyricsViewSettings.Key.ENABLE_KARAOKE_STYLE, false)
        syllables.forEach { word ->
            if (word.content.isEmpty()) return@forEach
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

    private fun SyncedLyrics.forOverlay(): SyncedLyrics? =
        copy(lines = lines.filter { it.hasDisplayableOverlayContent() })
            .takeIf { it.lines.isNotEmpty() }

    private fun SyncedLyrics.Line.hasDisplayableOverlayContent(): Boolean =
        content.content.isNotBlank() || content.mainSyllables.any { it.content.isNotBlank() }

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

    private fun dispatchPlaybackKey(keyCode: Int) {
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
            .setComponent(ComponentName(this, PlaybackService::class.java))
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        startService(intent)
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

    private data class OverlayWindow(
        val kind: OverlayKind,
        val view: View,
        val params: WindowManager.LayoutParams
    )

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
