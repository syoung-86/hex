package anomaly.hex.android.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Point
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import anomaly.hex.android.DebugLog
import anomaly.hex.android.accessibility.Inserter
import anomaly.hex.android.audio.Recorder
import anomaly.hex.android.audio.WavFile
import anomaly.hex.android.engine.Engine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * M2. The service owns the bubble and the one insertion path. It keeps the
 * target node from when the bubble appears, records through the same Recorder
 * the app uses, transcribes on the engine, then splices the text into that
 * field. Nothing else in Hex talks to another app.
 */
class HexAccessibilityService : AccessibilityService(), BubbleView.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var bubble: BubbleView? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false
    private var bubbleSize = 0

    private var target: AccessibilityNodeInfo? = null
    private var ownsCapture = false
    private var busy = false
    private var generation = 0
    private var undo: Inserter.Before? = null
    private var undoAt = 0L

    private val refresh = Runnable { refreshFocus() }
    private val undoExpiry = Runnable { expireUndo() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WindowManager::class.java)
        bubbleSize = (76 * resources.displayMetrics.density).toInt()
        bubble = BubbleView(this, this)
        scope.launch {
            Recorder.state.collect { state -> onRecorderState(state) }
        }
        refreshFocus()
        DebugLog.log("Bubble", "accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 120)
    }

    override fun onInterrupt() {
        if (ownsCapture) Recorder.stop()
        hideBubble()
        DebugLog.log("Bubble", "interrupted")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        if (ownsCapture) Recorder.stop()
        hideBubble()
        DebugLog.log("Bubble", "accessibility service destroyed")
        super.onDestroy()
    }

    private fun refreshFocus() {
        if (ownsCapture || busy || undo != null) return
        val focused = Inserter.focusedEditable(rootInActiveWindow)
        if (focused == null) {
            target = null
            hideBubble()
        } else {
            target = focused
            showBubble()
        }
    }

    private fun showBubble() {
        if (attached) return
        val view = bubble ?: return
        val position = positionFor()
        val layout = WindowManager.LayoutParams(
            bubbleSize,
            bubbleSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = position.x
            y = position.y
            title = "Hex bubble"
        }
        try {
            windowManager.addView(view, layout)
            params = layout
            attached = true
            view.state = BubbleView.State(BubbleView.Mode.Idle)
        } catch (error: Exception) {
            DebugLog.log("Bubble", "add failed: ${error.message}")
        }
    }

    private fun hideBubble() {
        if (!attached) return
        val view = bubble ?: return
        runCatching { windowManager.removeView(view) }
        attached = false
        params = null
    }

    private fun positionFor(): Point {
        val metrics = resources.displayMetrics
        val density = metrics.density
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val defaultX = metrics.widthPixels - bubbleSize - (14 * density).toInt()
        val defaultY = (metrics.heightPixels * 0.55f).toInt()
        val savedX = prefs.getInt("x", defaultX)
        val savedY = prefs.getInt("y", defaultY)
        val imeTop = try {
            val bounds = Rect()
            windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                ?.getBoundsInScreen(bounds)
            if (bounds.width() == 0 || bounds.height() == 0) null else bounds.top
        } catch (_: Exception) {
            null
        }
        val gap = (10 * density).toInt()
        val y = if (imeTop != null && savedY + bubbleSize > imeTop) {
            imeTop - bubbleSize - gap
        } else {
            savedY
        }
        return Point(
            savedX.coerceIn(0, (metrics.widthPixels - bubbleSize).coerceAtLeast(0)),
            y.coerceIn(0, (metrics.heightPixels - bubbleSize).coerceAtLeast(0)),
        )
    }

    private fun startCapture() {
        if (Recorder.state.value.phase == Recorder.Phase.Recording) return
        var node = target ?: Inserter.focusedEditable(rootInActiveWindow)
        if (node == null || !node.refresh() || !node.isEditable) {
            node = Inserter.focusedEditable(rootInActiveWindow)
        }
        if (node == null || !node.isEditable) {
            showError("Focus a text field")
            return
        }
        if (Inserter.isSensitive(node)) {
            showError("Cannot insert here")
            return
        }

        target = node
        undo = null
        handler.removeCallbacks(undoExpiry)
        if (!CaptureService.running.value) {
            CaptureService.start(this)
        }
        ownsCapture = true
        busy = false
        showBubble()
        bubble?.state = BubbleView.State(BubbleView.Mode.Listening)
        Recorder.start(this)
        DebugLog.log("Bubble", "capture started")
    }

    private fun onRecorderState(state: Recorder.State) {
        when (state.phase) {
            Recorder.Phase.Recording -> if (ownsCapture) {
                bubble?.state = BubbleView.State(
                    mode = BubbleView.Mode.Listening,
                    level = state.level,
                    elapsedMs = state.elapsedMs,
                )
            }

            Recorder.Phase.Saved -> if (ownsCapture) {
                ownsCapture = false
                val file = state.recording?.file
                if (file == null) {
                    showError("Capture lost")
                } else {
                    transcribe(file)
                }
            }

            Recorder.Phase.Failed -> if (ownsCapture) {
                ownsCapture = false
                showError(state.error ?: "Capture failed")
                Recorder.acknowledge()
            }

            Recorder.Phase.Idle -> Unit
        }
    }

    private fun transcribe(file: File) {
        busy = true
        val id = ++generation
        bubble?.state = BubbleView.State(BubbleView.Mode.Busy)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    Engine.transcribe(applicationContext, WavFile.decodePcm16(file))
                }
            }
            if (id != generation) return@launch

            result.onSuccess { outcome ->
                if (outcome.text.isBlank()) {
                    file.delete()
                    showError("No speech detected")
                    return@onSuccess
                }
                val node = target
                if (node == null) {
                    showError("Field is gone")
                    return@onSuccess
                }
                when (val inserted = Inserter.insert(applicationContext, node, outcome.text)) {
                    is Inserter.Outcome.Inserted -> {
                        undo = inserted.before
                        undoAt = SystemClock.uptimeMillis() + UNDO_MS
                        bubble?.state = BubbleView.State(
                            mode = BubbleView.Mode.Inserted,
                            undoAvailable = true,
                        )
                        handler.removeCallbacks(undoExpiry)
                        handler.postDelayed(undoExpiry, UNDO_MS)
                        file.delete()
                        DebugLog.log(
                            "Bubble",
                            "inserted ${outcome.text.length} chars in ${outcome.runMs} ms",
                        )
                    }

                    Inserter.Outcome.Pasted -> {
                        bubble?.state = BubbleView.State(BubbleView.Mode.Inserted)
                        hideSoon(1200)
                        file.delete()
                        DebugLog.log("Bubble", "pasted ${outcome.text.length} chars")
                    }

                    Inserter.Outcome.Copied -> {
                        bubble?.state = BubbleView.State(
                            mode = BubbleView.Mode.Inserted,
                            message = "Copied",
                        )
                        hideSoon(2000)
                        file.delete()
                        DebugLog.log("Bubble", "copied, insertion unavailable")
                    }

                    is Inserter.Outcome.Failed -> showError(inserted.reason)
                }
            }.onFailure { error ->
                DebugLog.log("Bubble", "transcribe failed: ${error.message} (${file.name})")
                showError(error.message ?: "Transcription failed")
            }

            busy = false
        }
    }

    private fun showError(message: String) {
        DebugLog.log("Bubble", "error: $message")
        bubble?.state = BubbleView.State(BubbleView.Mode.Error, message = message)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        handler.postDelayed({
            if (!ownsCapture && !busy && undo == null) {
                bubble?.state = BubbleView.State(BubbleView.Mode.Idle)
                refreshFocus()
            }
        }, 2500)
    }

    private fun hideSoon(delayMs: Long) {
        handler.postDelayed({
            if (!ownsCapture && !busy && undo == null) {
                bubble?.state = BubbleView.State(BubbleView.Mode.Idle)
                refreshFocus()
            }
        }, delayMs)
    }

    private fun expireUndo() {
        undo = null
        undoAt = 0
        if (!ownsCapture && !busy) {
            bubble?.state = BubbleView.State(BubbleView.Mode.Idle)
            refreshFocus()
        }
    }

    private fun undoLast() {
        val before = undo ?: return
        undo = null
        handler.removeCallbacks(undoExpiry)
        val restored = Inserter.restore(before)
        bubble?.state = if (restored) {
            BubbleView.State(BubbleView.Mode.Idle)
        } else {
            BubbleView.State(BubbleView.Mode.Error, message = "Undo failed")
        }
        DebugLog.log("Bubble", if (restored) "undo restored" else "undo failed")
        refreshFocus()
    }

    override fun onTap() {
        when {
            undo != null && SystemClock.uptimeMillis() <= undoAt -> undoLast()
            ownsCapture && Recorder.state.value.phase == Recorder.Phase.Recording ->
                Recorder.stop()
            busy || ownsCapture -> Unit
            else -> startCapture()
        }
    }

    override fun onHoldStart() {
        if (!ownsCapture && !busy) startCapture()
    }

    override fun onHoldEnd() {
        if (ownsCapture && Recorder.state.value.phase == Recorder.Phase.Recording) {
            Recorder.stop()
        }
    }

    override fun onUndo() {
        undoLast()
    }

    override fun onDrag(dx: Float, dy: Float) {
        val layout = params ?: return
        val metrics = resources.displayMetrics
        layout.x = (layout.x + dx).toInt()
            .coerceIn(0, (metrics.widthPixels - bubbleSize).coerceAtLeast(0))
        layout.y = (layout.y + dy).toInt()
            .coerceIn(0, (metrics.heightPixels - bubbleSize).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(bubble, layout) }
    }

    override fun onDragEnd() {
        val layout = params ?: return
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt("x", layout.x)
            .putInt("y", layout.y)
            .apply()
    }

    companion object {
        private const val PREFS = "hex.bubble"
        private const val UNDO_MS = 8_000L
    }
}
