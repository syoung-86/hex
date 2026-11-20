package anomaly.hex.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import anomaly.hex.android.DebugLog
import anomaly.hex.android.audio.Recorder
import anomaly.hex.android.audio.WavFile
import anomaly.hex.android.commands.CommandParser
import anomaly.hex.android.commands.CommandRunner
import anomaly.hex.android.engine.Engine
import anomaly.hex.android.service.CaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

private enum class Stage { Listening, Working, Done, Error }

/**
 * The command half of Hex: it hears a sentence, matches it against the local
 * grammar, and performs an action. It never inserts text.
 */
@Composable
fun CommandScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var stage by remember { mutableStateOf(Stage.Listening) }
    var transcript by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<CommandRunner.Result?>(null) }
    var started by remember { mutableStateOf(false) }
    var processed by remember { mutableStateOf(false) }
    val recorder by Recorder.state.collectAsStateWithLifecycle()

    fun close() {
        if (Recorder.state.value.phase == Recorder.Phase.Recording) Recorder.cancel()
        onClose()
    }

    BackHandler { close() }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            result = CommandRunner.Result(false, "Microphone permission needed")
            stage = Stage.Error
            return@LaunchedEffect
        }
        if (!CaptureService.running.value) CaptureService.start(context)
        Recorder.start(context)
        started = true
    }

    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        val began = SystemClock.elapsedRealtime()
        var heard = false
        var lastVoice = began
        while (true) {
            delay(60)
            if (Recorder.state.value.phase != Recorder.Phase.Recording) break
            val state = Recorder.state.value
            if (state.level > 0.14f) {
                heard = true
                lastVoice = SystemClock.elapsedRealtime()
            }
            val elapsed = SystemClock.elapsedRealtime() - began
            if (elapsed >= MAX_MS ||
                (!heard && elapsed >= NO_SPEECH_MS) ||
                (heard && SystemClock.elapsedRealtime() - lastVoice >= SILENCE_MS)
            ) {
                Recorder.stop()
                break
            }
        }
    }

    LaunchedEffect(recorder.phase) {
        when (recorder.phase) {
            Recorder.Phase.Saved -> if (started && !processed) {
                processed = true
                stage = Stage.Working
                val file = recorder.recording?.file
                if (file == null) {
                    result = CommandRunner.Result(false, "Capture lost")
                    stage = Stage.Error
                    return@LaunchedEffect
                }
                val outcome = withContext(Dispatchers.IO) {
                    runCatching {
                        Engine.transcribe(
                            context.applicationContext,
                            WavFile.decodePcm16(file),
                        )
                    }
                }
                val text = outcome.getOrNull()?.text?.trim().orEmpty()
                transcript = text
                file.delete()
                if (text.isEmpty()) {
                    result = CommandRunner.Result(
                        false,
                        outcome.exceptionOrNull()?.message ?: "I didn't hear anything",
                    )
                    stage = Stage.Error
                } else {
                    val executed = CommandRunner.run(context, CommandParser.parse(text))
                    result = executed
                    stage = if (executed.ok) Stage.Done else Stage.Error
                    DebugLog.log(
                        "Command",
                        "heard ${text.length} chars; ${if (executed.ok) "done" else "no match"}",
                    )
                    if (executed.ok) {
                        delay(2_200)
                        onClose()
                    }
                }
            }

            Recorder.Phase.Failed -> if (started) {
                result = CommandRunner.Result(false, recorder.error ?: "Capture failed")
                stage = Stage.Error
                Recorder.acknowledge()
            }

            else -> Unit
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HexColors.bgDeep)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .clickable(enabled = stage == Stage.Done || stage == Stage.Error) { close() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "HEX COMMANDS",
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                color = HexColors.textFaint,
            )

            when (stage) {
                Stage.Listening -> {
                    DotMeter(
                        level = recorder.level,
                        active = recorder.phase == Recorder.Phase.Recording,
                    )
                    Text(
                        "Listening",
                        style = MaterialTheme.typography.titleLarge,
                        color = HexColors.textBase,
                    )
                    Text(
                        formatClock(recorder.elapsedMs),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 22.sp,
                        color = HexColors.textMuted,
                    )
                    Text(
                        "Try “set a timer for five minutes” or “open WhatsApp”.",
                        style = MaterialTheme.typography.bodySmall,
                        color = HexColors.textFaint,
                    )
                }

                Stage.Working -> {
                    Text(
                        "Thinking…",
                        style = MaterialTheme.typography.titleLarge,
                        color = HexColors.textBase,
                    )
                    if (transcript.isNotBlank()) {
                        Text(
                            transcript,
                            style = MaterialTheme.typography.bodyMedium,
                            color = HexColors.textMuted,
                        )
                    }
                }

                Stage.Done, Stage.Error -> {
                    Text(
                        if (result?.ok == true) "Done" else "No command",
                        style = MaterialTheme.typography.titleLarge,
                        color = if (result?.ok == true) HexColors.success else HexColors.danger,
                    )
                    Text(
                        result?.message.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = HexColors.textBase,
                    )
                    if (transcript.isNotBlank()) {
                        Text(
                            "“$transcript”",
                            style = MaterialTheme.typography.bodySmall,
                            color = HexColors.textFaint,
                        )
                    }
                }
            }

            TextButton(onClick = { close() }) {
                Text(if (stage == Stage.Listening || stage == Stage.Working) "Cancel" else "Close")
            }
        }
    }
}

private const val MAX_MS = 12_000L
private const val NO_SPEECH_MS = 4_000L
private const val SILENCE_MS = 900L

private fun formatClock(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(Locale.US, seconds / 60, seconds % 60)
}
