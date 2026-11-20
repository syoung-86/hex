package anomaly.hex.android

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * M0's only observability: a bounded in-memory ring plus logcat. Transcripts
 * do not exist yet, so there is nothing private to redact.
 */
object DebugLog {
    private const val LIMIT = 200

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun log(tag: String, message: String) {
        Log.i("HexM0", "$tag: $message")
        val line = "${clock()}  $tag  $message"
        _lines.value = (_lines.value + line).takeLast(LIMIT)
    }

    fun clear() {
        _lines.value = emptyList()
    }

    private fun clock(): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
}
