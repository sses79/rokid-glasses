package dev.tim.gesturelab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View

/**
 * The gesture playground, drawn as green strokes on black (black is unlit on
 * the glasses). Everything stays inside the display's 480x480 safe band.
 */
class PlaygroundView(context: Context) : View(context) {

    private val pages = listOf("EMOJI", "TILES", "DIAL", "CAPTURE")
    private val tileLabels = listOf("LIGHT", "MUSIC", "TIMER", "NOTES", "FOCUS", "MUTE")
    private val tiles = BooleanArray(tileLabels.size)

    private var page = 0
    private var dial = 50
    private var dialGrab: Pair<Float, Int>? = null
    private var hand = HandState()
    private var landmarks: List<Point>? = null
    private var cursorX = Float.NaN
    private var cursorY = Float.NaN
    private var toast = ""
    private var toastUntil = 0L

    /** What the Capture page shows; null until the activity provides it. */
    var capture: CaptureUi? = null

    val onCapturePage get() = page == CAPTURE_PAGE

    /** While clips are being recorded, performed gestures must not operate the UI. */
    var locked = false

    /** Camera and tracker status line, set by the activity. */
    var status = "STARTING CAMERA"
    var statusDetail = ""

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val emoji = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val dashes = DashPathEffect(floatArrayOf(4f, 4f), 0f)

    private fun green(alpha: Float) = Color.argb((alpha * 255).toInt(), 64, 255, 94)

    /** Feeds one tracking result in. Call on the main thread. */
    fun onHand(state: HandState, points: List<Point>?) {
        hand = state
        landmarks = points
        for (event in if (locked) emptyList() else state.events) {
            when (event) {
                is GestureEvent.PinchStart -> when (page) {
                    TILES_PAGE -> tileAt(screenX(event.x), screenY(event.y))?.let { tiles[it] = !tiles[it] }
                    DIAL_PAGE -> dialGrab = event.x to dial
                }
                GestureEvent.PinchEnd, GestureEvent.Lost -> dialGrab = null
                GestureEvent.Found -> Unit
            }
        }
        dialGrab?.let { (startX, startValue) ->
            if (state.tracked) dial = (startValue + (state.x - startX) * DIAL_GAIN).toInt().coerceIn(0, 100)
        }
        invalidate()
    }

    fun turnPage(step: Int) {
        if (locked) return
        page = (page + step + pages.size) % pages.size
        dialGrab = null
        toast = "${pages[page]}  ${page + 1}/${pages.size}"
        toastUntil = SystemClock.uptimeMillis() + 1600
        invalidate()
    }

    private val stage get() = RectF(0f, SAFE_TOP + HUD_H, width.toFloat(), SAFE_BOTTOM - FOOT_H)

    // The hand rarely reaches the frame edges, so stretch its travel to cover the stage.
    private fun screenX(x: Float) = stage.left + (0.5f + (x - 0.5f) * CURSOR_GAIN).coerceIn(0f, 1f) * stage.width()
    private fun screenY(y: Float) = stage.top + (0.5f + (y - 0.5f) * CURSOR_GAIN).coerceIn(0f, 1f) * stage.height()

    private fun tileRect(i: Int): RectF {
        val s = stage
        val cols = 2
        val rows = 3
        val gap = 10f
        val pad = 14f
        val w = (s.width() - pad * 2 - gap * (cols - 1)) / cols
        val h = (s.height() - pad * 2 - gap * (rows - 1)) / rows
        val left = s.left + pad + (i % cols) * (w + gap)
        val top = s.top + pad + (i / cols) * (h + gap)
        return RectF(left, top, left + w, top + h)
    }

    private fun tileAt(x: Float, y: Float): Int? = tiles.indices.firstOrNull { tileRect(it).contains(x, y) }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        easeCursor()
        drawHud(canvas)
        when (page) {
            EMOJI_PAGE -> drawEmoji(canvas)
            TILES_PAGE -> drawTiles(canvas)
            DIAL_PAGE -> drawDial(canvas)
            else -> drawCapture(canvas)
        }
        if (page == TILES_PAGE || page == DIAL_PAGE) drawCursor(canvas)
        drawFooter(canvas)
        // Keep animating while the cursor is still gliding or a toast is showing.
        if (hand.tracked || locked || SystemClock.uptimeMillis() < toastUntil) postInvalidateOnAnimation()
    }

    private fun easeCursor() {
        if (!hand.tracked) {
            cursorX = Float.NaN
            return
        }
        val tx = screenX(hand.x)
        val ty = screenY(hand.y)
        if (cursorX.isNaN()) {
            cursorX = tx
            cursorY = ty
        }
        cursorX += (tx - cursorX) * CURSOR_EASE
        cursorY += (ty - cursorY) * CURSOR_EASE
    }

    private fun label(canvas: Canvas, s: String, x: Float, y: Float, size: Float, alpha: Float, align: Paint.Align = Paint.Align.CENTER) {
        text.textSize = size
        text.color = green(alpha)
        text.textAlign = align
        canvas.drawText(s, x, y - (text.descent() + text.ascent()) / 2, text)
    }

    /** Emoji glyphs keep their own colours; only their brightness can be changed. */
    private fun symbol(canvas: Canvas, s: String, x: Float, y: Float, size: Float, alpha: Float) {
        if (s == "←" || s == "→") {
            arrow(canvas, x, y, size, alpha, if (s == "←") -1f else 1f)
            return
        }
        emoji.textSize = size
        emoji.alpha = (alpha * 255).toInt()
        canvas.drawText(s, x, y - (emoji.descent() + emoji.ascent()) / 2, emoji)
    }

    /** A bare stroked arrow, drawn here so it has no background. [direction] is -1 for left, 1 for right. */
    private fun arrow(canvas: Canvas, x: Float, y: Float, size: Float, alpha: Float, direction: Float) {
        val half = size * 0.42f
        val head = size * 0.3f
        val tip = x + half * direction
        stroke.pathEffect = null
        stroke.strokeWidth = size * 0.11f
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.color = green(alpha)
        canvas.drawLine(x - half * direction, y, tip, y, stroke)
        canvas.drawLine(tip, y, tip - head * direction, y - head, stroke)
        canvas.drawLine(tip, y, tip - head * direction, y + head, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
    }

    private fun skeleton(canvas: Canvas, box: RectF, width: Float) {
        stroke.pathEffect = dashes
        stroke.strokeWidth = 1f
        stroke.color = green(0.24f)
        canvas.drawRect(box, stroke)
        stroke.pathEffect = null
        val pts = landmarks ?: return
        fun px(p: Point) = box.left + p.x * box.width()
        fun py(p: Point) = box.top + p.y * box.height()
        canvas.save()
        canvas.clipRect(box)
        stroke.strokeWidth = width
        stroke.color = green(0.72f)
        for ((a, b) in GestureEngine.BONES) canvas.drawLine(px(pts[a]), py(pts[a]), px(pts[b]), py(pts[b]), stroke)
        fill.color = green(1f)
        for (p in pts) canvas.drawCircle(px(p), py(p), width + 0.5f, fill)
        canvas.restore()
    }

    private fun drawHud(canvas: Canvas) {
        val y = SAFE_TOP + HUD_H / 2
        label(canvas, "GESTURE LAB / ${pages[page]}", 12f, y, 13f, 0.72f, Paint.Align.LEFT)
        label(canvas, status, width - 12f, y, 13f, 0.48f, Paint.Align.RIGHT)
        fill.color = green(0.24f)
        canvas.drawRect(12f, SAFE_TOP + HUD_H - 1, width - 12f, SAFE_TOP + HUD_H, fill)
    }

    private fun drawFooter(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val (s, alpha) = when {
            now < toastUntil -> toast to 1f
            page == CAPTURE_PAGE -> (capture?.footer ?: "") to 0.48f
            !hand.tracked -> "SHOW YOUR HAND  /  TAP FOR NEXT PAGE" to 0.48f
            page == EMOJI_PAGE -> "TAP FOR NEXT PAGE" to 0.48f
            else -> "PINCH SELECTS  /  TAP FOR NEXT PAGE" to 0.48f
        }
        label(canvas, s, width / 2f, SAFE_BOTTOM - FOOT_H / 2, 12f, alpha)
    }

    /** Live hand skeleton with the emoji it currently reads as. */
    private fun drawEmoji(canvas: Canvas) {
        val s = stage
        // Same 3:4 shape as the upright camera frame.
        val box = RectF(s.left + 16, s.top + 12, s.left + 16 + 222, s.top + 12 + 296)
        skeleton(canvas, box, 2f)

        val cx = (box.right + s.right) / 2
        val g = hand.gesture
        if (g != null) {
            symbol(canvas, g.symbol, cx, s.top + 120, 110f, 1f)
            label(canvas, g.label, cx, s.top + 214, 17f, 1f)
        } else {
            label(canvas, if (hand.tracked) "NO MATCH" else "NO HAND", cx, s.top + 150, 15f, 0.48f)
        }
        if (hand.tracked) {
            label(canvas, "PINCH ${"%.2f".format(hand.pinchRatio)}${if (hand.pinching) "  [ON]" else ""}", cx, s.top + 262, 12f, 0.48f)
        }
        label(canvas, statusDetail, cx, s.top + 284, 11f, 0.48f)

        // Every gesture it knows, with the detected one picked out.
        val all = EmojiGesture.entries
        val step = (s.width() - 24) / all.size
        val y = box.bottom + 46
        all.forEachIndexed { i, e ->
            val x = s.left + 12 + step * (i + 0.5f)
            val active = e == g
            symbol(canvas, e.symbol, x, y, 26f, if (active) 1f else 0.3f)
            if (active) {
                stroke.strokeWidth = 2f
                stroke.color = green(1f)
                canvas.drawRect(x - step / 2 + 2, y - 22, x + step / 2 - 2, y + 22, stroke)
            }
        }
    }

    private fun drawTiles(canvas: Canvas) {
        val hover = if (cursorX.isNaN()) null else tileAt(cursorX, cursorY)
        for (i in tiles.indices) {
            val r = tileRect(i)
            val on = tiles[i]
            if (on) {
                fill.color = green(0.12f)
                canvas.drawRect(r, fill)
            }
            stroke.pathEffect = null
            stroke.strokeWidth = if (i == hover) 2.5f else 1f
            stroke.color = green(if (i == hover) 1f else if (on) 0.72f else 0.24f)
            canvas.drawRect(r, stroke)
            label(canvas, tileLabels[i], r.centerX(), r.centerY() - 10, 17f, if (on || i == hover) 1f else 0.72f)
            label(canvas, if (on) "[ ON ]" else "OFF", r.centerX(), r.centerY() + 14, 12f, if (on) 1f else 0.48f)
        }
    }

    private fun drawDial(canvas: Canvas) {
        val s = stage
        val holding = dialGrab != null
        val x0 = s.left + 50
        val x1 = s.right - 50
        val y = s.centerY() + 40
        val knob = x0 + (x1 - x0) * dial / 100f

        label(canvas, dial.toString(), s.centerX(), s.centerY() - 60, 64f, 1f)
        label(
            canvas,
            if (holding) "[ HOLDING ]  MOVE LEFT OR RIGHT" else "PINCH TO GRAB THE DIAL",
            s.centerX(), s.centerY() - 8, 12f, if (holding) 1f else 0.48f,
        )
        fill.color = green(0.24f)
        canvas.drawRect(x0, y, x1, y + 1, fill)
        fill.color = green(0.72f)
        canvas.drawRect(x0, y - 1, knob, y + 2, fill)
        fill.color = green(0.48f)
        for (i in 0..10) {
            val tx = x0 + (x1 - x0) * i / 10f
            canvas.drawRect(tx, y + 8, tx + 1, y + if (i % 5 == 0) 16 else 12, fill)
        }
        if (holding) {
            fill.color = green(1f)
            canvas.drawCircle(knob, y, 9f, fill)
        } else {
            stroke.pathEffect = null
            stroke.strokeWidth = 2f
            stroke.color = green(1f)
            canvas.drawCircle(knob, y, 6f, stroke)
        }
    }

    /** Guides the wearer through recording one test clip per gesture. */
    private fun drawCapture(canvas: Canvas) {
        val s = stage
        val ui = capture ?: return
        val box = RectF(s.left + 16, s.top + 12, s.left + 16 + 150, s.top + 12 + 200)
        skeleton(canvas, box, 1.5f)

        val cx = (box.right + s.right) / 2
        if (ui.symbol.isNotEmpty()) symbol(canvas, ui.symbol, cx, s.top + 62, 72f, 1f)
        label(canvas, ui.title, cx, s.top + 128, 17f, 1f)
        if (ui.phase == CapturePhase.RECORDING) {
            fill.color = green(1f)
            canvas.drawCircle(cx - 52, s.top + 164, 6f, fill)
        }
        label(canvas, ui.prompt, cx + if (ui.phase == CapturePhase.RECORDING) 8 else 0, s.top + 164, 16f, 1f)
        label(canvas, ui.detail, cx, s.top + 194, 11f, 0.48f)

        // One row per gesture: done ones show their verdict, the current one is marked.
        val rowH = 19f
        val top = box.bottom + 26
        ui.rows.forEachIndexed { i, row ->
            val x = if (i % 2 == 0) s.left + 24 else s.centerX() + 12
            label(canvas, row.text, x, top + (i / 2) * rowH, 12f, if (row.current) 1f else 0.48f, Paint.Align.LEFT)
        }
    }

    private fun drawCursor(canvas: Canvas) {
        if (cursorX.isNaN()) return
        if (hand.pinching) {
            fill.color = green(1f)
            canvas.drawCircle(cursorX, cursorY, 6f, fill)
        } else {
            stroke.pathEffect = null
            stroke.strokeWidth = 2f
            stroke.color = green(1f)
            canvas.drawCircle(cursorX, cursorY, 10f, stroke)
            fill.color = green(1f)
            canvas.drawRect(cursorX - 1, cursorY - 1, cursorX + 1, cursorY + 1, fill)
        }
    }

    private companion object {
        // Rokid's guideline: keep content between y=80 and y=560 on the 480x640 display.
        const val SAFE_TOP = 80f
        const val SAFE_BOTTOM = 560f
        const val HUD_H = 30f
        const val FOOT_H = 24f
        const val CURSOR_GAIN = 1.6f
        const val CURSOR_EASE = 0.35f
        const val DIAL_GAIN = 220f
        const val EMOJI_PAGE = 0
        const val TILES_PAGE = 1
        const val DIAL_PAGE = 2
        const val CAPTURE_PAGE = 3
    }
}

enum class CapturePhase { IDLE, COUNTDOWN, RECORDING }

data class CaptureRow(val text: String, val current: Boolean)

data class CaptureUi(
    val phase: CapturePhase,
    val symbol: String,
    val title: String,
    val prompt: String,
    val detail: String,
    val rows: List<CaptureRow>,
    val footer: String,
)
