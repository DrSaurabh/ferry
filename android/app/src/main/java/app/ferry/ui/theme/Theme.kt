package app.ferry.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isSpecified

val FerryBlue = Color(0xFF1D4ED8)
val FerryAmber = Color(0xFFFBBF24)
val StatusGreen = Color(0xFF16A34A)
val StatusGrey = Color(0xFF9CA3AF)
val StatusRed = Color(0xFFDC2626)

/** Phone theme (design §4.1): Material3, Ferry blue / amber. */
@Composable
fun FerryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = FerryBlue, secondary = FerryAmber, onSecondary = Color.Black),
        content = content,
    )
}

/** TV theme (design §5.1, plan §4.9): dark, type × 1.5. The focus ring is drawn by the TV cards. */
@Composable
fun TvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF93C5FD),
            secondary = FerryAmber,
            background = Color(0xFF0B1220),
            surface = Color(0xFF0B1220),
            surfaceVariant = Color(0xFF1E293B),
        ),
        typography = Typography().scaled(1.5f),
        content = content,
    )
}

private fun TextStyle.sc(f: Float): TextStyle = copy(
    fontSize = if (fontSize.isSpecified) fontSize * f else fontSize,
    lineHeight = if (lineHeight.isSpecified) lineHeight * f else lineHeight,
)

private fun Typography.scaled(f: Float) = Typography(
    displayLarge = displayLarge.sc(f), displayMedium = displayMedium.sc(f), displaySmall = displaySmall.sc(f),
    headlineLarge = headlineLarge.sc(f), headlineMedium = headlineMedium.sc(f), headlineSmall = headlineSmall.sc(f),
    titleLarge = titleLarge.sc(f), titleMedium = titleMedium.sc(f), titleSmall = titleSmall.sc(f),
    bodyLarge = bodyLarge.sc(f), bodyMedium = bodyMedium.sc(f), bodySmall = bodySmall.sc(f),
    labelLarge = labelLarge.sc(f), labelMedium = labelMedium.sc(f), labelSmall = labelSmall.sc(f),
)
