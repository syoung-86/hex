package anomaly.hex.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelsTest {
    @Test
    fun silence_reads_as_zero() {
        assertEquals(0f, Levels.normalize(0.0), 0.0001f)
        assertEquals(0.0, Levels.rms(ShortArray(320)), 0.0001)
    }

    @Test
    fun full_scale_reads_as_one() {
        val loud = ShortArray(320) { index ->
            if (index % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE
        }
        assertEquals(1f, Levels.normalize(Levels.rms(loud)), 0.01f)
    }

    @Test
    fun quieter_input_reads_lower() {
        val loud = ShortArray(320) { (Short.MAX_VALUE / 2).toShort() }
        val quiet = ShortArray(320) { (Short.MAX_VALUE / 32).toShort() }
        val loudLevel = Levels.normalize(Levels.rms(loud))
        val quietLevel = Levels.normalize(Levels.rms(quiet))

        assertTrue("$loudLevel should exceed $quietLevel", loudLevel > quietLevel)
        assertTrue(quietLevel >= 0f)
    }
}
