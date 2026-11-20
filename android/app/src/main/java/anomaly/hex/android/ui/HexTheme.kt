package anomaly.hex.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Hex's OpenCode dark palette, shared with the iOS app's HexTheme. */
object HexColors {
    val bgDeep = Color(0xFF0A0B0D)
    val bgBase = Color(0xFF111316)
    val layer01 = Color(0xFF17191D)
    val layer02 = Color(0xFF202329)
    val textBase = Color(0xF0FFFFFF)
    val textMuted = Color(0x8FFFFFFF)
    val textFaint = Color(0x5CFFFFFF)
    val borderWeak = Color(0x0FFFFFFF)
    val borderStrong = Color(0x1CFFFFFF)
    val contrast = Color(0xF5FFFFFF)
    val accent = Color(0xFFEDEDF2)
    val warmMic = Color(0xFFFF9F0A)
    val success = Color(0xFF30D158)
    val danger = Color(0xFFFF453A)
}

private val HexTypography = Typography(
    headlineSmall = TextStyle(
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.4).sp,
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    ),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
    labelLarge = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
    ),
)

@Composable
fun HexTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = HexColors.accent,
            onPrimary = HexColors.bgDeep,
            secondary = HexColors.layer02,
            onSecondary = HexColors.textBase,
            background = HexColors.bgDeep,
            onBackground = HexColors.textBase,
            surface = HexColors.layer01,
            onSurface = HexColors.textBase,
            surfaceVariant = HexColors.layer02,
            onSurfaceVariant = HexColors.textMuted,
            outline = HexColors.borderStrong,
            error = HexColors.danger,
            onError = HexColors.bgDeep,
        ),
        typography = HexTypography,
        content = content,
    )
}
