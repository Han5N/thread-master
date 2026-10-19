package com.threadmaster.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 纯黑白配色。
 *
 * 性能工具里颜色应该只承担"区分"和"告警"两件事，不需要品牌色。
 * 亮色下强调色是黑，暗色下强调色是白，按钮、开关、选中态都靠它反转，
 * 图表用前景色的不同透明度来区分多条曲线。
 */
private fun monoLight() = lightColorScheme(
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFFFFFFF),
    primaryVariant = Color(0xFF2B2B2B),
    onPrimaryVariant = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF1A1A1A),
    onPrimaryContainer = Color(0xFFFFFFFF),
    disabledPrimary = Color(0xFFBFBFBF),
    disabledOnPrimary = Color(0xFFF5F5F5),
    disabledPrimaryButton = Color(0xFFBFBFBF),
    disabledOnPrimaryButton = Color(0xFFFFFFFF),
    disabledPrimarySlider = Color(0xFFCCCCCC),
    secondary = Color(0xFFF0F0F0),
    onSecondary = Color(0xFF000000),
    secondaryVariant = Color(0xFFE8E8E8),
    onSecondaryVariant = Color(0xFF1F1F1F),
    secondaryContainer = Color(0xFFF0F0F0),
    onSecondaryContainer = Color(0xFF666666),
    background = Color(0xFFF5F5F5),
    onBackground = Color(0xFF000000),
    onBackgroundVariant = Color(0xFF6B6B6B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFFFFFFF),
    onSurfaceSecondary = Color(0xCC000000),
    onSurfaceVariantSummary = Color(0x99000000),
    onSurfaceVariantActions = Color(0x66000000),
    surfaceContainer = Color(0xFFFFFFFF),
    onSurfaceContainer = Color(0xFF000000),
    onSurfaceContainerVariant = Color(0xFF6B6B6B),
    surfaceContainerHigh = Color(0xFFEDEDED),
    onSurfaceContainerHigh = Color(0xFF5A5A5A),
    surfaceContainerHighest = Color(0xFFE6E6E6),
    onSurfaceContainerHighest = Color(0xFF000000),
    outline = Color(0xFFDCDCDC),
    dividerLine = Color(0xFFE6E6E6),
    tertiaryContainer = Color(0xFFF0F0F0),
    onTertiaryContainer = Color(0xFF000000),
    tertiaryContainerVariant = Color(0xFFF0F0F0),
)

private fun monoDark() = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    primaryVariant = Color(0xFFE0E0E0),
    onPrimaryVariant = Color(0xFF000000),
    primaryContainer = Color(0xFF2A2A2A),
    onPrimaryContainer = Color(0xFFFFFFFF),
    disabledPrimary = Color(0xFF3A3A3A),
    disabledOnPrimary = Color(0xFF1A1A1A),
    disabledPrimaryButton = Color(0xFF3A3A3A),
    disabledOnPrimaryButton = Color(0xFF1A1A1A),
    disabledPrimarySlider = Color(0xFF333333),
    secondary = Color(0xFF1E1E1E),
    onSecondary = Color(0xFFF2F2F2),
    secondaryVariant = Color(0xFF262626),
    onSecondaryVariant = Color(0xFFE8E8E8),
    secondaryContainer = Color(0xFF1E1E1E),
    onSecondaryContainer = Color(0xFF9A9A9A),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF2F2F2),
    onBackgroundVariant = Color(0xFF8E8E8E),
    surface = Color(0xFF0D0D0D),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF141414),
    onSurfaceSecondary = Color(0xCCF2F2F2),
    onSurfaceVariantSummary = Color(0x99F2F2F2),
    onSurfaceVariantActions = Color(0x66F2F2F2),
    surfaceContainer = Color(0xFF161616),
    onSurfaceContainer = Color(0xFFF2F2F2),
    onSurfaceContainerVariant = Color(0xFF8E8E8E),
    surfaceContainerHigh = Color(0xFF1E1E1E),
    onSurfaceContainerHigh = Color(0xFFB0B0B0),
    surfaceContainerHighest = Color(0xFF262626),
    onSurfaceContainerHighest = Color(0xFFF2F2F2),
    outline = Color(0xFF2A2A2A),
    dividerLine = Color(0xFF1F1F1F),
    tertiaryContainer = Color(0xFF1A1A1A),
    onTertiaryContainer = Color(0xFFF2F2F2),
    tertiaryContainerVariant = Color(0xFF1A1A1A),
)

@Composable
fun ThreadMasterTheme(dark: Boolean, content: @Composable () -> Unit) {
    MiuixTheme(
        colors = if (dark) monoDark() else monoLight(),
        content = content,
    )
}
