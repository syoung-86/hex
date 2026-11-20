package anomaly.hex.android.audio

import kotlin.math.log10
import kotlin.math.sqrt

object Levels {
    /** RMS of [count] samples in the -1..1 range. */
    fun rms(samples: ShortArray, count: Int = samples.size): Double {
        if (count <= 0) return 0.0
        var sum = 0.0
        for (index in 0 until count) {
            val value = samples[index] / 32768.0
            sum += value * value
        }
        return sqrt(sum / count)
    }

    /** Maps -60..0 dBFS to 0..1; anything under -60 dB reads as silence. */
    fun normalize(rms: Double): Float {
        if (rms <= 1e-7) return 0f
        val db = 20.0 * log10(rms)
        return ((db + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
    }
}
