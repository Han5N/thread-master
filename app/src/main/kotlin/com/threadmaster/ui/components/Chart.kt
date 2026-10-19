package com.threadmaster.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 图表里的一条曲线。values 按时间先后排列，长度可以与其它序列不同。 */
data class ChartSeries(
    val label: String,
    val color: Color,
    val values: List<Float>,
)

/**
 * 轻量折线图。
 *
 * 不引入第三方图表库：这里只需要折线、渐变填充和网格，自绘能精确控制
 * 每条曲线的绘制成本，也避免为一张图多打几百 KB 的依赖。
 *
 * 所有序列共用同一个纵轴上限，保证"整机 CPU"和"应用 CPU"可以直接对比。
 */
@Composable
fun MultiLineChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    yMaxOverride: Float? = null,
    showGrid: Boolean = true,
    showFill: Boolean = true,
) {
    val gridColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.65f)
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val bottom = size.height
            val top = 0f
            val left = 0f
            val right = size.width

            if (showGrid) {
                for (i in 0..3) {
                    val y = top + (bottom - top) * i / 3f
                    drawLine(
                        color = gridColor,
                        start = Offset(left, y),
                        end = Offset(right, y),
                        strokeWidth = 1f,
                    )
                }
            }

            val dataMax = series.mapNotNull { s -> s.values.maxOrNull() }.maxOrNull() ?: 0f
            val maxV = (yMaxOverride ?: dataMax).coerceAtLeast(1f)

            for (s in series) {
                if (s.values.size < 2) continue
                val path = Path()
                val step = (right - left) / (s.values.size - 1)
                s.values.forEachIndexed { i, raw ->
                    val v = if (raw.isFinite()) raw.coerceIn(0f, maxV) else 0f
                    val x = left + i * step
                    val y = bottom - (v / maxV) * (bottom - top)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                if (showFill) {
                    val fill = Path().apply {
                        addPath(path)
                        lineTo(right, bottom)
                        lineTo(left, bottom)
                        close()
                    }
                    drawPath(
                        path = fill,
                        brush = Brush.verticalGradient(
                            colors = listOf(s.color.copy(alpha = 0.28f), Color.Transparent),
                            startY = top,
                            endY = bottom,
                        ),
                    )
                }
                drawPath(
                    path = path,
                    color = s.color,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
        }
    }
}
