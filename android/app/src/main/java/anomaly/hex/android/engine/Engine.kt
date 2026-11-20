package anomaly.hex.android.engine

import android.content.Context
import android.os.SystemClock
import anomaly.hex.android.DebugLog
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File

/**
 * M1: Parakeet TDT-CTC 110M int8 through sherpa-onnx. The model lives in the
 * app's private storage, so loading needs no permission and no network. One
 * recognizer is kept warm; the caller decides when to release it.
 */
object Engine {
    const val SAMPLE_RATE = 16_000

    private const val DIR = "models/parakeet-ctc-110m"
    private const val MIN_MODEL_BYTES = 100_000_000L

    data class Result(
        val text: String,
        val loadMs: Long,
        val runMs: Long,
        val audioMs: Long,
    )

    @Volatile private var recognizer: OfflineRecognizer? = null

    fun dir(context: Context): File = File(context.filesDir, DIR)

    fun modelFile(context: Context): File = File(dir(context), "model.int8.onnx")

    fun tokensFile(context: Context): File = File(dir(context), "tokens.txt")

    fun installed(context: Context): Boolean {
        val model = modelFile(context)
        return model.isFile &&
            model.length() >= MIN_MODEL_BYTES &&
            tokensFile(context).isFile
    }

    fun status(context: Context): String {
        val model = modelFile(context)
        return when {
            !model.isFile || !tokensFile(context).isFile -> "Model missing"
            model.length() < MIN_MODEL_BYTES ->
                "Model looks truncated (${model.length()} B)"
            recognizer != null -> "Loaded · ${model.length() / 1_000_000} MB"
            else -> "Installed · ${model.length() / 1_000_000} MB"
        }
    }

    @Synchronized
    fun transcribe(context: Context, samples: FloatArray): Result {
        val startedAt = SystemClock.elapsedRealtime()
        val model = modelFile(context)
        val tokens = tokensFile(context)
        require(model.isFile && tokens.isFile) { "Model files are missing" }
        require(samples.isNotEmpty()) { "No audio to transcribe" }

        val engine = recognizer ?: load(model, tokens).also {
            recognizer = it
            DebugLog.log("Engine", "loaded ${model.name} (${model.length() / 1_000_000} MB)")
        }
        val loadMs = SystemClock.elapsedRealtime() - startedAt

        val stream = engine.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            val decodeAt = SystemClock.elapsedRealtime()
            engine.decode(stream)
            val text = engine.getResult(stream).text.trim()
            Result(
                text = text,
                loadMs = loadMs,
                runMs = SystemClock.elapsedRealtime() - decodeAt,
                audioMs = samples.size * 1000L / SAMPLE_RATE,
            )
        } finally {
            stream.release()
        }
    }

    @Synchronized
    fun release() {
        recognizer?.release()
        recognizer = null
        DebugLog.log("Engine", "released")
    }

    private fun load(model: File, tokens: File): OfflineRecognizer {
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = model.absolutePath),
                tokens = tokens.absolutePath,
                numThreads = 4,
                debug = false,
            ),
        )
        return OfflineRecognizer(config = config)
    }
}
