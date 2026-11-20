package anomaly.hex.android.audio

import android.content.Context
import java.io.File

data class Recording(
    val file: File,
    val durationMs: Long,
    val sizeBytes: Long,
) {
    val name: String get() = file.name
}

object Recordings {
    private const val DIR = "recordings"
    private const val BYTES_PER_MS =
        WavFile.SAMPLE_RATE * WavFile.CHANNELS * WavFile.BITS_PER_SAMPLE / 8 / 1000.0

    fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    fun list(context: Context, limit: Int = 5): List<Recording> =
        dir(context)
            .listFiles { file -> file.isFile && file.extension == "wav" }
            ?.sortedByDescending { it.lastModified() }
            ?.take(limit)
            ?.map(::fromFile)
            ?: emptyList()

    fun fromFile(file: File): Recording {
        val dataBytes = (file.length() - WavFile.HEADER_BYTES).coerceAtLeast(0L)
        return Recording(
            file = file,
            durationMs = (dataBytes / BYTES_PER_MS).toLong(),
            sizeBytes = file.length(),
        )
    }
}
