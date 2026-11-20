package anomaly.hex.android.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class WavFileTest {
    @Test
    fun header_describes_mono_16khz_pcm() {
        val samples = ShortArray(16_000)
        val wav = WavFile.encode(samples)

        assertEquals(WavFile.HEADER_BYTES + samples.size * 2, wav.size)
        assertEquals("RIFF", wav.ascii(0, 4))
        assertEquals("WAVE", wav.ascii(8, 4))
        assertEquals("fmt ", wav.ascii(12, 4))
        assertEquals("data", wav.ascii(36, 4))
        assertEquals(36 + samples.size * 2, wav.le32(4))
        assertEquals(1, wav.le16(20))
        assertEquals(WavFile.CHANNELS, wav.le16(22))
        assertEquals(WavFile.SAMPLE_RATE, wav.le32(24))
        assertEquals(32_000, wav.le32(28))
        assertEquals(2, wav.le16(32))
        assertEquals(WavFile.BITS_PER_SAMPLE, wav.le16(34))
        assertEquals(samples.size * 2, wav.le32(40))
    }

    @Test
    fun samples_round_trip_little_endian() {
        val wav = WavFile.encode(shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE))

        assertEquals(0, wav.le16(WavFile.HEADER_BYTES))
        assertEquals(1, wav.le16(WavFile.HEADER_BYTES + 2))
        assertEquals(0xFFFF, wav.le16(WavFile.HEADER_BYTES + 4))
        assertEquals(0x7FFF, wav.le16(WavFile.HEADER_BYTES + 6))
        assertEquals(0x8000, wav.le16(WavFile.HEADER_BYTES + 8))
    }

    private fun ByteArray.ascii(offset: Int, length: Int): String =
        String(this, offset, length, Charsets.US_ASCII)

    private fun ByteArray.le16(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.le32(offset: Int): Int =
        le16(offset) or (le16(offset + 2) shl 16)
}
