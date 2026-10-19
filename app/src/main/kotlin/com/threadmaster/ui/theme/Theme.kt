package com.threadmaster.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 全局色板。深色是这类常驻监控工具的默认形态，也避免夜间瞄一眼被闪到。 */
object TmColors {
    val Bg = Color(0xFF07090C)
    val Surface = Color(0xFF0E1418)
    val SurfaceHi = Color(0xFF151C22)
    val Stroke = Color(0xFF1F2A31)
    val Primary = Color(0xFF4ADE80)
    val Secondary = Color(0xFF38BDF8)
    val Warn = Color(0xFFFBBF24)
    val Danger = Color(0xFFF87171)
    val TextPrimary = Color(0xFFE8EEF2)
    val TextSecondary = Color(0xFF8FA3AF)
    val TextDim = Color(0xFF5B6B76)
}

private val scheme = darkColorScheme(
    primary = TmColors.Primary,
    onPrimary = Color(0xFF05130A),
    primaryContainer = Color(0xFF11301D),
    onPrimaryContainer = TmColors.Primary,
    secondary = TmColors.Secondary,
    onSecondary = Color(0xFF04141C),
    tertiary = TmColors.Warn,
    background = TmColors.Bg,
    onBackground = TmColors.TextPrimary,
    surface = TmColors.Surface,
    onSurface = TmColors.TextPrimary,
    surfaceVariant = TmColors.SurfaceHi,
    onSurfaceVariant = TmColors.TextSecondary,
    outline = TmColors.Stroke,
    outlineVariant = TmColors.Stroke,
    error = TmColors.Danger,
)

private val tmTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 44.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
)

@Composable
fun ThreadMasterTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = tmTypography, content = content)
}
