package com.threadmaster.ui.components

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale
import kotlin.math.abs

/** Miuix 卡片封装：可选点击，统一圆角与内边距。 */
@Composable
fun TmCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (onClick != null) {
        Card(modifier = modifier, onClick = onClick, content = content)
    } else {
        Card(modifier = modifier, content = content)
    }
}

/** 小节标题，统一用次要前景色和字号。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        fontSize = 11.sp,
        modifier = modifier,
    )
}

@Composable
fun MetricCell(
    label: String,
    value: String,
    unit: String = "",
    valueColor: Color = MiuixTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(label, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 11.sp)
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = valueColor,
                fontSize = 20.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(2.dp))
                Text(
                    unit,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
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
                .background(
                    if (active) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.outline
                )
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 12.sp,
        )
    }
}

/** 横向比例条，黑白主题下用前景色的透明度区分。 */
@Composable
fun RatioBar(
    ratio: Float,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.primary,
    height: Int = 4,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape(50))
            .background(MiuixTheme.colorScheme.surfaceContainerHighest),
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

/** 每核频率条：高度表示当前频率占该核上限的比例。 */
@Composable
fun CoreFreqBars(
    freqs: List<Pair<Int, Int>>,
    maxFreq: Int,
    modifier: Modifier = Modifier,
    barColor: Color = MiuixTheme.colorScheme.primary,
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
                        .background(barColor.copy(alpha = 0.9f)),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = core.toString(),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/**
 * 应用图标。
 *
 * 用 PackageManager 同步取图：图标本身在系统缓存里，一次读取通常不到 1ms，
 * 放进 LazyColumn 的 remember 里足够，没必要为它引一个图片加载库。
 */
@Composable
fun AppIcon(packageName: String, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val image = remember(packageName) { loadAppIcon(context, packageName) }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(MiuixTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(size),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = packageName.take(1).uppercase(),
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = (size.value * 0.4f).sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private fun loadAppIcon(context: Context, packageName: String): ImageBitmap? = runCatching {
    context.packageManager.getApplicationIcon(packageName)
        .toBitmap(96, 96)
        .asImageBitmap()
}.getOrNull()

/** Miuix 没有分段控件，用卡片 + 反色选中项自己拼一个，视觉上仍是同一套语言。 */
@Composable
fun SegmentedRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { index, label ->
            val active = index == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        if (active) MiuixTheme.colorScheme.primary
                        else Color.Transparent
                    )
                    .clickable { onSelect(index) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (active) MiuixTheme.colorScheme.onPrimary
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/** 统一风格确认弹窗。Miuix 的 Dialog 需要额外容器，这里用系统 Dialog 套它的卡片更省事。 */
@Composable
fun TmConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth()) {
            Text(
                text = title,
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    cornerRadius = 12.dp,
                    colors = ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.secondary,
                        contentColor = MiuixTheme.colorScheme.onSecondary,
                    ),
                ) { Text("取消", fontSize = 13.sp) }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    cornerRadius = 12.dp,
                    colors = ButtonDefaults.buttonColors(
                        color = if (danger) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary,
                        contentColor = if (danger) MiuixTheme.colorScheme.onError else MiuixTheme.colorScheme.onPrimary,
                    ),
                ) { Text(confirmText, fontSize = 13.sp) }
            }
        }
    }
}


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

fun clampName(name: String, max: Int = 18): String =
    if (name.length <= max) name else name.take(max - 1) + "…"
