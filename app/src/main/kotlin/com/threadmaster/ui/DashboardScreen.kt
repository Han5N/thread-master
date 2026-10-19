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
import com.threadmaster.collector.LivePoint
import com.threadmaster.collector.MonitorPhase
import com.threadmaster.collector.SystemSnapshot
import com.threadmaster.ui.components.AppIcon
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
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

@Composable
fun DashboardScreen(vm: MonitorViewModel, onPickApp: () -> Unit) {
    val state by vm.state.collectAsState()
    val snap by vm.snapshot.collectAsState()
    val live by vm.live.collectAsState()
    val settings by vm.settingsState.collectAsState()
    val context = LocalContext.current

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TargetCard(
                phase = state.phase,
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

        state.error?.let { item { ErrorBanner(it) } }

        if (state.phase == MonitorPhase.WAITING) {
            item { WaitingBanner(state.targetLabel.ifEmpty { state.targetPackage }) }
        }

        item { CpuCard(snap, live) }

        item { CoresCard(snap) }

        item { GpuCard(snap) }

        item { MemoryCard(snap) }

        item { ThermalCard(snap) }

        item { PowerNetCard(snap) }

        item { SystemLoadCard(snap) }

        item {
            Text(
                text = "采样间隔 ${settings.intervalMs}ms · 仅在目标应用位于前台时入库",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun TargetCard(
    phase: MonitorPhase,
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(packageName = targetPackage.ifEmpty { "tm" }, size = 46.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = targetLabel.ifEmpty { "还没有选择目标应用" },
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = when (phase) {
                        MonitorPhase.WAITING -> "等待应用进入前台…"
                        MonitorPhase.RECORDING -> "记录中 · ${fmtDuration(System.currentTimeMillis() - startedAt)} · ${threadCount} 线程"
                        MonitorPhase.IDLE -> targetPackage.ifEmpty { "选择游戏或应用，进入前台后自动开始" }
                    },
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
            StatusDot(active = phase == MonitorPhase.RECORDING, text = "")
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onToggle,
                modifier = Modifier.weight(1f),
                cornerRadius = 14.dp,
                colors = ButtonDefaults.buttonColors(
                    color = if (running) MiuixTheme.colorScheme.surfaceContainerHigh
                    else MiuixTheme.colorScheme.primary,
                    contentColor = if (running) MiuixTheme.colorScheme.onSurface
                    else MiuixTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(
                    text = if (running) "停止记录" else "开始记录",
                    fontSize = 13.sp,
                )
            }
            Button(
                onClick = onPick,
                cornerRadius = 14.dp,
                colors = ButtonDefaults.buttonColors(
                    color = MiuixTheme.colorScheme.secondary,
                    contentColor = MiuixTheme.colorScheme.onSecondary,
                ),
            ) {
                Text("选择", fontSize = 13.sp)
            }
            Button(
                onClick = onToggleHud,
                cornerRadius = 14.dp,
                colors = ButtonDefaults.buttonColors(
                    color = if (hudEnabled) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.secondary,
                    contentColor = if (hudEnabled) MiuixTheme.colorScheme.onPrimary
                    else MiuixTheme.colorScheme.onSecondary,
                ),
            ) {
                Text("HUD", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun WaitingBanner(label: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column {
            Text(
                "已就绪，等待进入应用",
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "把 $label 切到前台后才会开始写数据，切回后台会自动暂停。",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.error.copy(alpha = 0.12f))
            .padding(12.dp),
    ) {
        Text(message, color = MiuixTheme.colorScheme.error, fontSize = 12.sp)
    }
}

@Composable
private fun CpuCard(snap: SystemSnapshot?, live: List<LivePoint>) {
    val cpu = snap?.cpuUsage ?: Float.NaN
    val appCpu = snap?.targetCpuPercent ?: Float.NaN
    val recent = remember(live) { live.takeLast(240) }
    val spanSec = (recent.size * 0.5f).toInt()

    TmCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Column {
                SectionLabel("整机 CPU")
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = fmt(cpu, 0),
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 42.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "%",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
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
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 20.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${snap?.avgFreqMhz ?: 0} MHz",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        MultiLineChart(
            series = listOf(
                ChartSeries("整机", MiuixTheme.colorScheme.onSurface, recent.map { it.cpu }),
                ChartSeries(
                    "应用",
                    MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    recent.map { it.targetCpu },
                ),
            ),
            yMaxOverride = 100f,
            modifier = Modifier.fillMaxWidth().height(110.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "实线 整机 · 灰线 应用 · 最近 ${spanSec}s",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun CoresCard(snap: SystemSnapshot?) {
    val cores = snap?.cores.orEmpty()
    val usage = snap?.perCoreUsage.orEmpty()
    if (cores.isEmpty()) return
    val max = cores.maxOfOrNull { it.maxKhz }?.takeIf { it > 0 }
        ?: cores.maxOfOrNull { it.curKhz }?.coerceAtLeast(1) ?: 1

    TmCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("核心")
            Spacer(Modifier.weight(1f))
            Text(
                "${cores.size} 核 · 峰值 ${max / 1000} MHz",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 10.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        CoreFreqBars(
            freqs = cores.map { it.core to it.curKhz },
            maxFreq = max,
        )
        Spacer(Modifier.height(10.dp))
        for (core in cores) {
            val u = usage.getOrNull(core.core) ?: Float.NaN
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "CPU${core.core}",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(44.dp),
                )
                Box(Modifier.weight(1f)) {
                    RatioBar(
                        ratio = if (u.isFinite()) u / 100f else 0f,
                        height = 5,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = fmt(u, 0) + "%",
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(36.dp),
                )
                Text(
                    text = "${core.curMhz}",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(44.dp),
                )
            }
        }
    }
}

@Composable
private fun GpuCard(snap: SystemSnapshot?) {
    val model = snap?.gpuModel.orEmpty()
    if (model.isEmpty() && (snap?.gpuFreqKhz ?: 0) == 0) return
    TmCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("GPU")
            Spacer(Modifier.weight(1f))
            Text(
                model.ifEmpty { "Adreno" },
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 10.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            MetricCell(
                label = "占用",
                value = fmt(snap?.gpuBusyPercent ?: Float.NaN, 0),
                unit = "%",
            )
            Spacer(Modifier.width(24.dp))
            MetricCell(
                label = "频率",
                value = ((snap?.gpuFreqKhz ?: 0) / 1000).toString(),
                unit = "MHz",
            )
            Spacer(Modifier.width(24.dp))
            MetricCell(
                label = "温度",
                value = fmt(snap?.gpuTempC ?: Float.NaN, 1),
                unit = "℃",
            )
            Spacer(Modifier.weight(1f))
            val maxMhz = (snap?.gpuMaxKhz ?: 0) / 1000
            if (maxMhz > 0) {
                Text(
                    "上限 ${maxMhz}",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        RatioBar(
            ratio = if ((snap?.gpuBusyPercent ?: Float.NaN).isFinite()) (snap!!.gpuBusyPercent / 100f) else 0f,
            height = 5,
        )
    }
}

@Composable
private fun MemoryCard(snap: SystemSnapshot?) {
    val total = snap?.memTotalKb ?: 0L
    val used = snap?.memUsedKb ?: 0L
    val ratio = if (total > 0) used.toFloat() / total else 0f
    val zramUsed = snap?.zramUsedKb ?: 0L

    TmCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("内存")
            Spacer(Modifier.weight(1f))
            Text(
                "ZRAM ${zramUsed / 1024} MB",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            MetricCell(
                label = "已用",
                value = String.format(Locale.US, "%.1f", used / 1024f / 1024f),
                unit = "GB",
            )
            Spacer(Modifier.width(20.dp))
            MetricCell(
                label = "总量",
                value = String.format(Locale.US, "%.1f", total / 1024f / 1024f),
                unit = "GB",
            )
            Spacer(Modifier.width(20.dp))
            MetricCell(
                label = "目标进程",
                value = fmt((snap?.targetRssKb ?: 0L) / 1024f, 0),
                unit = "MB",
            )
        }
        Spacer(Modifier.height(8.dp))
        RatioBar(ratio, height = 5)
    }
}

@Composable
private fun ThermalCard(snap: SystemSnapshot?) {
    val temps = snap?.temps.orEmpty()
    val cpu = snap?.cpuTempC ?: Float.NaN
    val picked = remember(temps) {
        temps.filter {
            val n = it.zone.lowercase()
            n.contains("cpu") || n.contains("gpu") || n.contains("batt") ||
                n.contains("soc") || n.contains("skin") || n.contains("disp")
        }.distinctBy { it.zone }.sortedByDescending { it.celsius }.take(6)
    }
    if (picked.isEmpty() && cpu.isNaN()) return

    TmCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("温度")
            Spacer(Modifier.weight(1f))
            Text(
                "CPU ${fmt(cpu, 1)}℃",
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(8.dp))
        if (picked.isEmpty()) {
            Text(
                "没有读到可用的 thermal_zone",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 11.sp,
            )
        } else {
            for (t in picked) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = t.zone.take(18),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 10.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Text(
                        text = fmt(t.celsius, 1) + "℃",
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun PowerNetCard(snap: SystemSnapshot?) {
    TmCard(Modifier.fillMaxWidth()) {
        Row {
            Column(Modifier.weight(1f)) {
                MetricCell(
                    label = "电池",
                    value = fmt(snap?.batteryPowerMw ?: Float.NaN, 0),
                    unit = "mW",
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${snap?.batteryStatus?.ifEmpty { "未知" } ?: "未知"} · ${snap?.batteryLevel?.takeIf { it >= 0 } ?: "--"}% · ${fmt(snap?.batteryTempC ?: Float.NaN, 1)}℃",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 10.sp,
                )
            }
            Column(Modifier.weight(1f)) {
                MetricCell(
                    label = "网络",
                    value = fmtBytesPerSec(snap?.netRxBytesPerSec ?: 0),
                    unit = "/s ↓",
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "↑ ${fmtBytesPerSec(snap?.netTxBytesPerSec ?: 0)}/s",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 10.sp,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "目标进程 IO 读 ${fmtBytesPerSec(snap?.targetIoReadPerSec ?: 0)}/s · 写 ${fmtBytesPerSec(snap?.targetIoWritePerSec ?: 0)}/s",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun SystemLoadCard(snap: SystemSnapshot?) {
    TmCard(Modifier.fillMaxWidth()) {
        Row {
            Column(Modifier.weight(1f)) {
                MetricCell(
                    label = "Load 1m",
                    value = fmt(snap?.load1 ?: Float.NaN, 2),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "5m ${fmt(snap?.load5 ?: Float.NaN, 2)} · 15m ${fmt(snap?.load15 ?: Float.NaN, 2)}",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 10.sp,
                )
            }
            Column(Modifier.weight(1f)) {
                MetricCell(
                    label = "运行队列",
                    value = (snap?.runQueue ?: 0).toString(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "阻塞 ${snap?.blockedQueue ?: 0}",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 10.sp,
                )
            }
            Column(Modifier.weight(1f)) {
                MetricCell(
                    label = "上下文切换",
                    value = fmt((snap?.ctxtPerSec ?: 0) / 1000f, 0),
                    unit = "k/s",
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "中断 ${fmt((snap?.intrPerSec ?: 0) / 1000f, 0)}k/s",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 10.sp,
                )
            }
        }
    }
}
