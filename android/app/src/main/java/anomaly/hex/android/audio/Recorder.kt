package anomaly.hex.android.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import anomaly.hex.android.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * M0's capture engine: one AudioRecord, one thread, one WAV. No engine, no
 * streaming, no partials. The state machine keeps the final state on the audio
 * thread so a stop from the UI can never race the file being written.
 */
object Recorder {
    enum class Phase { Idle, Recording, Saved, Failed }

    data class State(
        val phase: Phase = Phase.Idle,
        val elapsedMs: Long = 0L,
        val level: Float = 0f,
        val recording: Recording? = null,
        val error: String? = null,
    )

    private const val MAX_MS = 60_000L
    private const val MIN_SAMPLES = WavFile.SAMPLE_RATE / 3
    private const val CHUNK = 320 // 20 ms at 16 kHz

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var thread: Thread? = null
    @Volatile private var record: AudioRecord? = null

    @SuppressLint("MissingPermission") // the caller checks RECORD_AUDIO before start
    @Synchronized
    fun start(context: Context) {
        if (thread != null) return

        val file = File(Recordings.dir(context), "rec-${System.currentTimeMillis()}.wav")
        val minBuffer = AudioRecord.getMinBufferSize(
            WavFile.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            fail("getMinBufferSize returned $minBuffer")
            return
        }

        val recorder = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(WavFile.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer * 2, CHUNK * 2 * 4))
                .build()
        } catch (error: Exception) {
            fail("AudioRecord failed: ${error.javaClass.simpleName}: ${error.message}")
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            fail("AudioRecord did not initialize")
            return
        }

        record = recorder
        _state.value = State(phase = Phase.Recording)
        DebugLog.log("Audio", "recording ${file.name}")
        thread = Thread({ capture(recorder, file) }, "hex-audio").also { it.start() }
    }

    @Synchronized
    fun stop() {
        thread?.interrupt()
        runCatching { record?.stop() }
    }

    fun acknowledge() {
        if (_state.value.phase != Phase.Recording) {
            _state.value = State()
        }
    }

    @SuppressLint("MissingPermission")
    private fun capture(recorder: AudioRecord, file: File) {
        val chunk = ShortArray(CHUNK)
        val pcm = ShortArray(WavFile.SAMPLE_RATE * 60)
        var total = 0
        var smoothed = 0f
        var failure: String? = null
        val startedAt = SystemClock.elapsedRealtime()

        try {
            recorder.startRecording()
            while (!Thread.currentThread().isInterrupted) {
                val read = recorder.read(chunk, 0, chunk.size)
                if (read <= 0) {
                    if (read < 0) failure = "read returned $read"
                    break
                }
                val room = pcm.size - total
                if (room <= 0) break
                val take = minOf(read, room)
                System.arraycopy(chunk, 0, pcm, total, take)
                total += take

                val level = Levels.normalize(Levels.rms(chunk, read))
                smoothed = smoothed * 0.72f + level * 0.28f
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                _state.value = State(
                    phase = Phase.Recording,
                    elapsedMs = elapsed,
                    level = smoothed,
                )
                if (elapsed >= MAX_MS) break
            }
        } catch (error: Throwable) {
            failure = error.message ?: error.javaClass.simpleName
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            record = null

            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (total < MIN_SAMPLES) {
                file.delete()
                _state.value = State(
                    phase = Phase.Failed,
                    elapsedMs = elapsed,
                    error = failure ?: "Too short (${elapsed} ms).",
                )
                DebugLog.log("Audio", "discarded ${file.name}: ${_state.value.error}")
            } else {
                file.writeBytes(WavFile.encode(pcm.copyOf(total)))
                val recording = Recordings.fromFile(file)
                _state.value = State(
                    phase = Phase.Saved,
                    elapsedMs = elapsed,
                    recording = recording,
                )
                DebugLog.log(
                    "Audio",
                    "saved ${file.name}: ${recording.durationMs} ms, ${recording.sizeBytes} B" +
                        (failure?.let { " ($it)" } ?: ""),
                )
            }

            synchronized(this) { thread = null }
        }
    }

    private fun fail(message: String) {
        _state.value = State(phase = Phase.Failed, error = message)
        DebugLog.log("Audio", message)
    }
}
