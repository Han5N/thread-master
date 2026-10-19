package com.threadmaster.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threadmaster.ui.theme.TmColors
import java.util.Locale
import kotlin.math.abs

/** 统一卡片容器：深色底 + 极细描边，信息密度优先。 */
@Composable
fun TmCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(TmColors.Surface)
            .padding(14.dp),
    ) {
        content()
    }
}

/** 小节标题。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = TmColors.TextDim,
        fontSize = 11.sp,
        modifier = modifier,
    )
}

/** 一个指标格：标签 + 数值 + 单位。 */
@Composable
fun MetricCell(
    label: String,
    value: String,
    unit: String = "",
    valueColor: Color = TmColors.TextPrimary,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(label, color = TmColors.TextDim, fontSize = 11.sp)
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = valueColor,
                fontSize = 19.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(2.dp))
                Text(unit, color = TmColors.TextDim, fontSize = 10.sp, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
    }
}

/** 运行状态点 + 文案。 */
@Composable
fun StatusDot(active: Boolean, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (active) TmColors.Primary else TmColors.TextDim)
        )
        Spacer(Modifier.width(6.dp))
        Text(text, color = TmColors.TextSecondary, fontSize = 12.sp)
    }
}

/** 横向进度条，用于内存、GPU 这类比例指标。 */
@Composable
fun RatioBar(
    ratio: Float,
    modifier: Modifier = Modifier,
    color: Color = TmColors.Primary,
    height: Int = 4,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape(50))
            .background(TmColors.SurfaceHi),
    ) {
        Box(
            Modifier
                .fillMaxWidth(ratio.coerceIn(0f, 1f))
                .height(height.dp)
                .clip(RoundedCornerShape(50))
                .background(color),
        )
    }
}

/** 每核频率条组。横向排布，越高表示当前频率越接近该核上限。 */
@Composable
fun CoreFreqBars(
    freqs: List<Pair<Int, Int>>,
    maxFreq: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for ((core, khz) in freqs) {
            val ratio = if (maxFreq > 0) (khz.toFloat() / maxFreq).coerceIn(0.04f, 1f) else 0.04f
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((ratio * 34).dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (ratio > 0.8f) TmColors.Primary else TmColors.Secondary.copy(alpha = 0.75f)),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = core.toString(),
                    color = TmColors.TextDim,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/** 数值格式化：NaN 统一显示为 `--`，避免界面出现 "NaN"。 */
fun fmt(value: Float, digits: Int = 0, suffix: String = ""): String {
    if (!value.isFinite()) return "--"
    return String.format(Locale.US, "%.${digits}f%s", value, suffix)
}

fun fmtBytesPerSec(bytes: Long): String {
    if (bytes <= 0) return "0"
    val kb = bytes / 1024.0
    return when {
        kb < 1024 -> String.format(Locale.US, "%.0fK", kb)
        else -> String.format(Locale.US, "%.1fM", kb / 1024)
    }
}

fun fmtDuration(millis: Long): String {
    val total = abs(millis) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%dh%02dm", h, m)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

/** 线程名过长时截断到固定宽度，保证多列对齐。 */
fun clampName(name: String, max: Int = 18): String =
    if (name.length <= max) name else name.take(max - 1) + "…"
