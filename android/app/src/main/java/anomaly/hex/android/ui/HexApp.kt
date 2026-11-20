package anomaly.hex.android.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import anomaly.hex.android.BuildConfig
import anomaly.hex.android.DebugLog
import anomaly.hex.android.audio.Recorder
import anomaly.hex.android.audio.Recording
import anomaly.hex.android.audio.Recordings
import anomaly.hex.android.service.CaptureService
import java.io.File
import java.util.Locale

@Composable
fun HexApp() {
    val context = LocalContext.current

    var permissionTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val micGranted = remember(permissionTick) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
    val notificationsGranted = remember(permissionTick) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    val micPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            permissionTick++
        }
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            permissionTick++
        }

    val recorder by Recorder.state.collectAsStateWithLifecycle()
    val serviceRunning by CaptureService.running.collectAsStateWithLifecycle()
    val logLines by DebugLog.lines.collectAsStateWithLifecycle()

    var recordings by remember { mutableStateOf(Recordings.list(context)) }
    var playingFile by remember { mutableStateOf<File?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    fun stopPlayback() {
        player?.release()
        player = null
        playingFile = null
    }

    fun play(recording: Recording) {
        stopPlayback()
        val next = MediaPlayer()
        try {
            next.setDataSource(recording.file.absolutePath)
            next.setOnCompletionListener {
                it.release()
                player = null
                playingFile = null
            }
            next.prepare()
            next.start()
            player = next
            playingFile = recording.file
        } catch (error: Exception) {
            next.release()
            DebugLog.log("Playback", "failed: ${error.message}")
        }
    }

    fun share(recording: Recording) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            recording.file,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share recording"))
    }

    fun copyLog() {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Hex log", logLines.joinToString("\n")))
        Toast.makeText(context, "Log copied", Toast.LENGTH_SHORT).show()
    }

    fun record() {
        when {
            recorder.phase == Recorder.Phase.Recording -> Recorder.stop()
            !micGranted -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
            else -> {
                if (!serviceRunning) CaptureService.start(context)
                Recorder.start(context)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            player?.release()
            player = null
        }
    }

    LaunchedEffect(recorder.phase) {
        if (recorder.phase == Recorder.Phase.Saved) {
            recordings = Recordings.list(context)
        }
    }

    HexTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(HexColors.bgDeep)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Hex",
                    style = MaterialTheme.typography.headlineSmall,
                    color = HexColors.textBase,
                )
                Spacer(Modifier.width(10.dp))
                Chip("M0 · capture")
                Spacer(Modifier.weight(1f))
                Text(
                    Build.MODEL,
                    style = MaterialTheme.typography.bodySmall,
                    color = HexColors.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            SectionCard("Microphone") {
                StatusRow(
                    title = "Record audio",
                    detail = if (micGranted) "Granted" else "Needed to capture WAVs",
                    ok = micGranted,
                    action = if (micGranted) null else "Grant",
                    onAction = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                )
                StatusRow(
                    title = "Notifications",
                    detail = if (notificationsGranted) {
                        "Granted"
                    } else {
                        "The ready notification stays quiet without it"
                    },
                    ok = notificationsGranted,
                    action = if (notificationsGranted) null else "Grant",
                    onAction = {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                )
            }

            SectionCard("Capture") {
                DotMeter(
                    level = recorder.level,
                    active = recorder.phase == Recorder.Phase.Recording,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatClock(recorder.elapsedMs),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 26.sp,
                        color = HexColors.textBase,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = when (recorder.phase) {
                            Recorder.Phase.Idle -> "ready"
                            Recorder.Phase.Recording -> "recording"
                            Recorder.Phase.Saved -> "saved"
                            Recorder.Phase.Failed -> "error"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = HexColors.textFaint,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    val recording = recorder.phase == Recorder.Phase.Recording
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .background(if (recording) HexColors.danger else HexColors.contrast)
                            .clickable { record() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (recording) "Stop" else "Record",
                            style = MaterialTheme.typography.titleMedium,
                            color = HexColors.bgDeep,
                        )
                    }
                }
                recorder.error?.let { message ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = HexColors.danger,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { Recorder.acknowledge() }) { Text("OK") }
                    }
                }
                Text(
                    "16 kHz mono WAV · 60 s cap · 0.3 s minimum",
                    style = MaterialTheme.typography.bodySmall,
                    color = HexColors.textFaint,
                )
            }

            SectionCard(
                title = "Recordings",
                trailing = {
                    TextButton(onClick = { recordings = Recordings.list(context) }) {
                        Text("Refresh")
                    }
                },
            ) {
                if (recordings.isEmpty()) {
                    Text(
                        "Nothing yet. Record five seconds and play it back.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = HexColors.textMuted,
                    )
                } else {
                    recordings.forEach { recording ->
                        RecordingRow(
                            recording = recording,
                            playing = playingFile == recording.file,
                            onPlay = {
                                if (playingFile == recording.file) stopPlayback() else play(recording)
                            },
                            onShare = { share(recording) },
                        )
                    }
                }
            }

            SectionCard(
                title = "Foreground service",
                trailing = { Chip(if (serviceRunning) "running" else "stopped") },
            ) {
                StatusRow(
                    title = "Microphone service",
                    detail = if (serviceRunning) {
                        "Ready; the process stays foreground"
                    } else {
                        "Not running"
                    },
                    ok = serviceRunning,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton("Start", enabled = micGranted) {
                        CaptureService.start(context)
                    }
                    SmallButton("Stop", enabled = serviceRunning) {
                        CaptureService.stop(context)
                    }
                }
                Text(
                    "M0 experiment: start it, record, background Hex, lock the phone, and check the WAV still finishes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = HexColors.textMuted,
                )
            }

            SectionCard("Device") {
                KeyValue("Model", "${Build.MANUFACTURER} ${Build.MODEL}")
                KeyValue("Android", "API ${Build.VERSION.SDK_INT} · ${Build.VERSION.RELEASE}")
                KeyValue("ABI", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
                KeyValue("App", BuildConfig.VERSION_NAME)
                KeyValue("Network", "none (no INTERNET permission)")
            }

            SectionCard(
                title = "Debug",
                trailing = {
                    Row {
                        TextButton(onClick = { DebugLog.clear() }) { Text("Clear") }
                        TextButton(onClick = { copyLog() }) { Text("Copy") }
                    }
                },
            ) {
                Text(
                    text = if (logLines.isEmpty()) {
                        "No events yet."
                    } else {
                        logLines.takeLast(14).joinToString("\n")
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = HexColors.textMuted,
                )
            }

            Text(
                "M0 · capture only. Accessibility, bubble, and engine come next.",
                style = MaterialTheme.typography.bodySmall,
                color = HexColors.textFaint,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(HexColors.layer01)
            .border(1.dp, HexColors.borderWeak, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = HexColors.textBase,
            )
            Spacer(Modifier.weight(1f))
            trailing?.invoke()
        }
        content()
    }
}

@Composable
private fun StatusRow(
    title: String,
    detail: String,
    ok: Boolean,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (ok) HexColors.success else HexColors.warmMic),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = HexColors.textBase,
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = HexColors.textFaint,
            )
        }
        if (action != null) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun RecordingRow(
    recording: Recording,
    playing: Boolean,
    onPlay: () -> Unit,
    onShare: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                formatClock(recording.durationMs),
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
                color = HexColors.textBase,
            )
            Text(
                "${formatBytes(recording.sizeBytes)} · ${recording.name}",
                style = MaterialTheme.typography.bodySmall,
                color = HexColors.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onPlay) { Text(if (playing) "Stop" else "Play") }
        TextButton(onClick = onShare) { Text("Share") }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row {
        Text(
            key,
            style = MaterialTheme.typography.bodySmall,
            color = HexColors.textFaint,
            modifier = Modifier.width(74.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = HexColors.textMuted,
        )
    }
}

@Composable
private fun Chip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(HexColors.layer02)
            .border(1.dp, HexColors.borderWeak, RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = HexColors.textMuted,
        )
    }
}

@Composable
private fun SmallButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (enabled) HexColors.layer02 else HexColors.layer01)
            .border(1.dp, HexColors.borderWeak, RoundedCornerShape(9.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) HexColors.textBase else HexColors.textFaint,
        )
    }
}

private fun formatClock(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(Locale.US, seconds / 60, seconds % 60)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(Locale.US, bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f kB".format(Locale.US, bytes / 1_000.0)
    else -> "$bytes B"
}
