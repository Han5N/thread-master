package com.threadmaster.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threadmaster.collector.SystemSnapshot
import com.threadmaster.ui.components.ChartSeries
import com.threadmaster.ui.components.CoreFreqBars
import com.threadmaster.ui.components.MetricCell
import com.threadmaster.ui.components.MultiLineChart
import com.threadmaster.ui.components.RatioBar
import com.threadmaster.ui.components.SectionLabel
import com.threadmaster.ui.components.StatusDot
import com.threadmaster.ui.components.TmCard
import com.threadmaster.ui.components.fmt
import com.threadmaster.ui.components.fmtBytesPerSec
import com.threadmaster.ui.components.fmtDuration
import com.threadmaster.ui.theme.TmColors
import java.util.Locale

/**
 * 监控主页：目标应用、实时曲线、核心频率、关键指标。
 *
 * 页面本身不持有任何采集状态，全部从 ViewModel 的 StateFlow 读取，
 * 所以退到后台再回来不会有断层，也不需要重建采样。
 */
@Composable
fun DashboardScreen(vm: MonitorViewModel, onPickApp: () -> Unit) {
    val state by vm.state.collectAsState()
    val snap by vm.snapshot.collectAsState()
    val live by vm.live.collectAsState()
    val settings by vm.settingsState.collectAsState()
    val context = LocalContext.current

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TargetCard(
                running = state.running,
                targetLabel = if (state.running) state.targetLabel else settings.lastLabel,
                targetPackage = if (state.running) state.targetPackage else settings.lastPackage,
                startedAt = state.startedAt,
                threadCount = snap?.targetThreadCount ?: 0,
                hudEnabled = settings.hudEnabled,
                onPick = onPickApp,
                onToggle = {
                    if (state.running) {
                        vm.stopMonitoring()
                    } else if (settings.lastPackage.isNotEmpty()) {
                        vm.startMonitoring(settings.lastPackage, settings.lastLabel)
                    } else {
                        onPickApp()
                    }
                },
                onToggleHud = {
                    if (!settings.hudEnabled && !Settings.canDrawOverlays(context)) {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    } else {
                        vm.setHudEnabled(!settings.hudEnabled)
                    }
                },
            )
        }

        if (state.error != null) {
            item { ErrorBanner(state.error!!) }
        }

        item { CpuCard(snap, live) }

        item { CoresCard(snap) }

        item { MetricsGrid(snap) }

        item {
            Text(
                text = "采样间隔 ${settings.intervalMs}ms · 线程明细每 ${com.threadmaster.data.SessionRepository.THREAD_INTERVAL_MS}ms 落库",
                color = TmColors.TextDim,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun TargetCard(
    running: Boolean,
    targetLabel: String,
    targetPackage: String,
    startedAt: Long,
    threadCount: Int,
    hudEnabled: Boolean,
    onPick: () -> Unit,
    onToggle: () -> Unit,
    onToggleHud: () -> Unit,
) {
    TmCard(Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(running, if (running) "记录中" else "待机")
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onToggleHud, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Filled.Layers,
                        contentDescription = "悬浮窗",
                        tint = if (hudEnabled) TmColors.Primary else TmColors.TextDim,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = targetLabel.ifEmpty { "还没有选择目标应用" },
                color = TmColors.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (targetPackage.isEmpty()) "选择一个游戏或应用，开始线程级记录" else targetPackage,
                color = TmColors.TextDim,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )

            if (running) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    InfoBit("时长", fmtDuration(System.currentTimeMillis() - startedAt))
                    InfoBit("线程", threadCount.toString())
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onToggle,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (running) TmColors.SurfaceHi else TmColors.Primary,
                        contentColor = if (running) TmColors.Danger else Color(0xFF05130A),
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(
                        if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (running) "停止记录" else "开始记录", fontSize = 13.sp)
                }
                Button(
                    onClick = onPick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TmColors.SurfaceHi,
                        contentColor = TmColors.TextSecondary,
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Filled.Apps, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("选择", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun InfoBit(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TmColors.TextDim, fontSize = 11.sp)
        Spacer(Modifier.width(5.dp))
        Text(
            value,
            color = TmColors.TextSecondary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(TmColors.Danger.copy(alpha = 0.12f))
            .padding(12.dp),
    ) {
        Text(message, color = TmColors.Danger, fontSize = 12.sp)
    }
}

@Composable
private fun CpuCard(snap: SystemSnapshot?, live: List<com.threadmaster.collector.LivePoint>) {
    val cpu = snap?.cpuUsage ?: Float.NaN
    val appCpu = snap?.targetCpuPercent ?: Float.NaN
    val recent = remember(live) { live.takeLast(240) }

    TmCard(Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                Column {
                    SectionLabel("整机 CPU")
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = fmt(cpu, 0),
                            color = TmColors.Primary,
                            fontSize = 40.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "%",
                            color = TmColors.TextDim,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(bottom = 8.dp, start = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    SectionLabel("目标应用")
                    Text(
                        text = fmt(appCpu, 0) + "%",
                        color = TmColors.Secondary,
                        fontSize = 20.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${snap?.avgFreqMhz ?: 0} MHz",
                        color = TmColors.TextDim,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            MultiLineChart(
                series = listOf(
                    ChartSeries("整机", TmColors.Primary, recent.map { it.cpu }),
                    ChartSeries("应用", TmColors.Secondary, recent.map { it.targetCpu }),
                ),
                yMaxOverride = 100f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp),
            )
            Spacer(Modifier.height(8.dp))
            Row {
                LegendDot(TmColors.Primary, "整机")
                Spacer(Modifier.width(14.dp))
                LegendDot(TmColors.Secondary, "目标应用")
                Spacer(Modifier.weight(1f))
                val spanSec = (recent.size * 0.5f).toInt()
                Text("最近 ${spanSec}s", color = TmColors.TextDim, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(color))
        Spacer(Modifier.width(5.dp))
        Text(text, color = TmColors.TextDim, fontSize = 10.sp)
    }
}

@Composable
private fun CoresCard(snap: SystemSnapshot?) {
    val cores = snap?.cores.orEmpty()
    if (cores.isEmpty()) return
    val max = cores.maxOfOrNull { it.maxKhz }?.takeIf { it > 0 }
        ?: cores.maxOfOrNull { it.curKhz }?.coerceAtLeast(1) ?: 1

    TmCard(Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("核心频率")
                Spacer(Modifier.weight(1f))
                Text(
                    "${cores.size} 核 · 峰值 ${max / 1000} MHz",
                    color = TmColors.TextDim,
                    fontSize = 10.sp,
                )
            }
            Spacer(Modifier.height(10.dp))
            CoreFreqBars(
                freqs = cores.map { it.core to it.curKhz },
                maxFreq = max,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in cores.take(8)) {
                    Text(
                        text = "${c.curMhz}",
                        color = TmColors.TextSecondary,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricsGrid(snap: SystemSnapshot?) {
    val memTotal = snap?.memTotalKb ?: 0L
    val memUsed = snap?.memUsedKb ?: 0L
    val memRatio = if (memTotal > 0) memUsed.toFloat() / memTotal else 0f

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TmCard(Modifier.weight(1f)) {
                MetricCell(
                    label = "温度",
                    value = fmt(snap?.cpuTempC ?: Float.NaN, 1),
                    unit = "℃",
                    valueColor = tempColor(snap?.cpuTempC ?: Float.NaN),
                )
            }
            TmCard(Modifier.weight(1f)) {
                MetricCell(
                    label = "帧率",
                    value = fmt(snap?.fps ?: Float.NaN, 0),
                    unit = "fps",
                    valueColor = fpsColor(snap?.fps ?: Float.NaN),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TmCard(Modifier.weight(1f)) {
                Column {
                    MetricCell(
                        label = "GPU",
                        value = fmt(snap?.gpuBusyPercent ?: Float.NaN, 0),
                        unit = "%",
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${(snap?.gpuFreqKhz ?: 0) / 1000} MHz",
                        color = TmColors.TextDim,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            TmCard(Modifier.weight(1f)) {
                Column {
                    MetricCell(
                        label = "内存",
                        value = String.format(Locale.US, "%.1f", memUsed / 1024f / 1024f),
                        unit = "GB",
                    )
                    Spacer(Modifier.height(7.dp))
                    RatioBar(memRatio, color = if (memRatio > 0.85f) TmColors.Warn else TmColors.Secondary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "共 ${String.format(Locale.US, "%.1f", memTotal / 1024f / 1024f)} GB",
                        color = TmColors.TextDim,
                        fontSize = 10.sp,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TmCard(Modifier.weight(1f)) {
                Column {
                    MetricCell(
                        label = "电池电流",
                        value = fmt(snap?.batteryCurrentMa ?: Float.NaN, 0),
                        unit = "mA",
                        valueColor = currentColor(snap?.batteryCurrentMa ?: Float.NaN),
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "电量 ${snap?.batteryLevel?.takeIf { it >= 0 } ?: "--"}% · ${fmt(snap?.batteryTempC ?: Float.NaN, 1)}℃",
                        color = TmColors.TextDim,
                        fontSize = 10.sp,
                    )
                }
            }
            TmCard(Modifier.weight(1f)) {
                Column {
                    MetricCell(
                        label = "网络",
                        value = fmtBytesPerSec(snap?.netRxBytesPerSec ?: 0),
                        unit = "/s ↓",
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "↑ ${fmtBytesPerSec(snap?.netTxBytesPerSec ?: 0)}/s",
                        color = TmColors.TextDim,
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

private fun tempColor(t: Float): Color = when {
    !t.isFinite() -> TmColors.TextPrimary
    t >= 70f -> TmColors.Danger
    t >= 55f -> TmColors.Warn
    else -> TmColors.TextPrimary
}

private fun fpsColor(fps: Float): Color = when {
    !fps.isFinite() -> TmColors.TextPrimary
    fps < 25f -> TmColors.Danger
    fps < 50f -> TmColors.Warn
    else -> TmColors.Primary
}

private fun currentColor(ma: Float): Color = when {
    !ma.isFinite() -> TmColors.TextPrimary
    ma >= 2500f -> TmColors.Danger
    ma >= 1500f -> TmColors.Warn
    else -> TmColors.TextPrimary
}
