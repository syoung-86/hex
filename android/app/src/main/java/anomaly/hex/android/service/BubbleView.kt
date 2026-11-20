package anomaly.hex.android.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * M2's one control: a small accessibility overlay that never takes focus from
 * the app behind it. Tap starts or stops hands-free dictation; holding it is
 * push-to-talk; dragging moves it; a tap after insertion undoes.
 */
class BubbleView(
    context: Context,
    private val listener: Listener,
) : View(context) {
    enum class Mode { Idle, Listening, Busy, Inserted, Error }

    data class State(
        val mode: Mode = Mode.Idle,
        val level: Float = 0f,
        val elapsedMs: Long = 0L,
        val message: String? = null,
        val undoAvailable: Boolean = false,
    )

    interface Listener {
        fun onTap()
        fun onHoldStart()
        fun onHoldEnd()
        fun onUndo()
        fun onDrag(dx: Float, dy: Float)
        fun onDragEnd()
    }

    var state: State = State()
        set(value) {
            field = value
            contentDescription = when (value.mode) {
                Mode.Idle -> "Hex dictation"
                Mode.Listening -> "Listening"
                Mode.Busy -> "Transcribing"
                Mode.Inserted -> if (value.undoAvailable) "Undo dictation" else "Inserted"
                Mode.Error -> value.message ?: "Hex error"
            }
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEE17191D.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x33FFFFFF
    }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEDEDF2.toInt() }
    private val warm = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF9F0A.toInt() }
    private val danger = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF453A.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val timer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF0FFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 12f * density
        typeface = Typeface.MONOSPACE
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x8FFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 8f * density
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val bang = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF453A.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 20f * density
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val check = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFFEDEDF2.toInt()
    }
    private val glyph = Path()

    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var dragging = false
    private var holdFired = false

    private val hold = Runnable {
        holdFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        listener.onHoldStart()
    }

    init {
        isClickable = true
        isFocusable = false
        contentDescription = "Hex dictation"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (76 * density).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - 6f * density
        val bounds = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        canvas.drawCircle(cx, cy, radius, fill)
        canvas.drawCircle(cx, cy, radius, border)

        val listening = state.mode == Mode.Listening
        ring.color = when (state.mode) {
            Mode.Error -> danger.color
            Mode.Listening -> warm.color
            else -> ink.color
        }
        ring.alpha = if (listening) 255 else 48
        val sweep = if (listening) 300f * state.level.coerceIn(0f, 1f) else 360f
        canvas.drawArc(bounds, -90f, sweep, false, ring)

        when (state.mode) {
            Mode.Idle -> {
                canvas.drawCircle(cx, cy - 3f * density, 5f * density, ink)
                canvas.drawText("hold", cx, cy + 15f * density, small)
            }

            Mode.Listening -> {
                canvas.drawText(clock(state.elapsedMs), cx, cy + 4f * density, timer)
                canvas.drawText("tap to stop", cx, cy + 17f * density, small)
            }

            Mode.Busy -> {
                val phase = (SystemClock.uptimeMillis() % 1000L) / 1000.0 * 2.0 * Math.PI
                for (index in 0 until 3) {
                    val alpha = 0.35 + 0.65 * ((sin(phase + index * 0.8) + 1.0) / 2.0)
                    ink.alpha = (alpha * 255).toInt()
                    canvas.drawCircle(
                        cx + (index - 1) * 9f * density,
                        cy,
                        3.4f * density,
                        ink,
                    )
                }
                ink.alpha = 255
                postInvalidateDelayed(80)
            }

            Mode.Inserted -> {
                if (state.undoAvailable) {
                    canvas.drawText("undo", cx, cy + 4f * density, timer)
                    canvas.drawText("tap", cx, cy + 17f * density, small)
                } else {
                    glyph.reset()
                    glyph.moveTo(cx - 7f * density, cy)
                    glyph.lineTo(cx - 2f * density, cy + 5f * density)
                    glyph.lineTo(cx + 8f * density, cy - 5f * density)
                    canvas.drawPath(glyph, check)
                }
            }

            Mode.Error -> {
                canvas.drawText("!", cx, cy + 7f * density, bang)
                canvas.drawText("tap", cx, cy + 18f * density, small)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                dragging = false
                holdFired = false
                postDelayed(hold, 250)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging &&
                    hypot(event.rawX - downRawX, event.rawY - downRawY) > touchSlop
                ) {
                    dragging = true
                    removeCallbacks(hold)
                }
                if (dragging) {
                    listener.onDrag(event.rawX - lastRawX, event.rawY - lastRawY)
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(hold)
                when {
                    dragging -> listener.onDragEnd()
                    holdFired -> listener.onHoldEnd()
                    state.undoAvailable -> listener.onUndo()
                    else -> performClick()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(hold)
                if (dragging) listener.onDragEnd()
                if (holdFired) listener.onHoldEnd()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        listener.onTap()
        return true
    }

    private fun clock(ms: Long): String {
        val seconds = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }
}
