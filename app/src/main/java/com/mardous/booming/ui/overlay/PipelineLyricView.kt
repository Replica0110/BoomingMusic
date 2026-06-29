package com.mardous.booming.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextPaint
import android.util.TypedValue
import android.view.Choreographer
import android.view.View
import kotlin.math.ceil
import kotlin.math.roundToInt

internal class PipelineLyricView(context: Context) : View(context), Choreographer.FrameCallback {
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()
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
            val words = content?.mainSyllables?.filter { it.content.isNotEmpty() }.orEmpty()
            val model = if (content != null && words.isNotEmpty()) {
                RenderModel(
                    identity = text.key,
                    text = content.content,
                    begin = words.first().start,
                    end = words.last().end,
                    words = words.map {
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


