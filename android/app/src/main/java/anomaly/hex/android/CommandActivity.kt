package anomaly.hex.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import anomaly.hex.android.ui.CommandScreen
import anomaly.hex.android.ui.HexTheme

/**
 * Launched by "Hex Commands" (Quick Tap, the app drawer, or the card in Hex).
 * Records one command, acts on it, and finishes; it never inserts text.
 */
class CommandActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HexTheme {
                CommandScreen(onClose = { finish() })
            }
        }
    }
}
