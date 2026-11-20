package anomaly.hex.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp

/**
 * A flat, M0 take on Hex's dot scope: a rolling row of levels rather than the
 * braided shader. Good enough to see silence, speech, and clipping.
 */
@Composable
fun DotMeter(
    level: Float,
    active: Boolean,
    modifier: Modifier = Modifier,
    dots: Int = 48,
) {
    val history = remember { mutableStateListOf<Float>() }

    LaunchedEffect(active, level) {
        if (!active) {
            history.clear()
            return@LaunchedEffect
        }
        history.add(level)
        while (history.size > dots) history.removeAt(0)
    }

    Canvas(modifier.fillMaxWidth().height(46.dp)) {
        val step = size.width / dots
        val baseRadius = (size.height * 0.16f).coerceAtLeast(1.5f)
        for (index in 0 until dots) {
            val value = history.getOrElse(index) { 0f }
            drawCircle(
                color = HexColors.accent.copy(alpha = 0.10f + 0.9f * value),
                radius = baseRadius * (0.55f + 1.1f * value),
                center = Offset(
                    x = step * (index + 0.5f),
                    y = size.height / 2f,
                ),
            )
        }
    }
}
