package anomaly.hex.android.audio

import java.io.ByteArrayOutputStream

/** Minimal 16-bit mono PCM WAV writer. The whole clip is small enough to hold. */
object WavFile {
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    const val HEADER_BYTES = 44

    fun encode(samples: ShortArray): ByteArray {
        val dataBytes = samples.size * 2
        val out = ByteArrayOutputStream(HEADER_BYTES + dataBytes)
        out.ascii("RIFF")
        out.le32(36 + dataBytes)
        out.ascii("WAVE")
        out.ascii("fmt ")
        out.le32(16)
        out.le16(1) // PCM
        out.le16(CHANNELS)
        out.le32(SAMPLE_RATE)
        out.le32(SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8)
        out.le16(CHANNELS * BITS_PER_SAMPLE / 8)
        out.le16(BITS_PER_SAMPLE)
        out.ascii("data")
        out.le32(dataBytes)
        for (sample in samples) {
            out.le16(sample.toInt() and 0xFFFF)
        }
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.ascii(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun ByteArrayOutputStream.le16(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.le32(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }
}
