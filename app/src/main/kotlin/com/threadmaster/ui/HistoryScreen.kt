package com.threadmaster.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threadmaster.data.SessionEntity
import com.threadmaster.ui.components.AppIcon
import com.threadmaster.ui.components.ChartSeries
import com.threadmaster.ui.components.MultiLineChart
import com.threadmaster.ui.components.SectionLabel
import com.threadmaster.ui.components.SegmentedRow
import com.threadmaster.ui.components.TmCard
import com.threadmaster.ui.components.TmConfirmDialog
import com.threadmaster.ui.components.clampName
import com.threadmaster.ui.components.fmt
import com.threadmaster.ui.components.fmtDuration
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(vm: MonitorViewModel, onOpen: (Long) -> Unit) {
    val sessions by vm.sessions.collectAsState()
    var pendingDelete by remember { mutableStateOf<SessionEntity?>(null) }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("记录会话")
                Spacer(Modifier.weight(1f))
                Text(
                    "${sessions.size} 条",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 10.sp,
                )
            }
        }
        if (sessions.isEmpty()) {
            item {
                TmCard(Modifier.fillMaxWidth()) {
                    Text(
                        "还没有历史记录。开始一次监控后，系统指标和每条线程的曲线都会留在这里。",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 12.sp,
                    )
                }
            }
        }
        items(sessions, key = { it.id }) { session ->
            SessionCard(
                session = session,
                onClick = { onOpen(session.id) },
                onLongClick = { pendingDelete = session },
            )
        }
    }

    pendingDelete?.let { target ->
        TmConfirmDialog(
            title = "删除这次记录？",
            message = "${target.appLabel} 的 ${target.sampleCount} 个采样点及其线程数据将一并删除。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                vm.deleteSession(target.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun SessionCard(session: SessionEntity, onClick: () -> Unit, onLongClick: () -> Unit) {
    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val end = session.endedAt ?: System.currentTimeMillis()
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        onLongPress = onLongClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(packageName = session.packageName, size = 38.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = session.appLabel.ifEmpty { session.packageName },
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${timeFmt.format(Date(session.startedAt))} · ${session.sampleCount} 点 · ${session.intervalMs}ms",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                text = if (session.endedAt == null) "进行中" else fmtDuration(end - session.startedAt),
                color = if (session.endedAt == null) MiuixTheme.colorScheme.onSurface
                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
fun SessionDetailScreen(vm: MonitorViewModel, sessionId: Long, onBack: () -> Unit) {
    val sessions by vm.sessions.collectAsState()
    val curve by vm.detailCurve.collectAsState()
    val threads by vm.detailThreads.collectAsState()
    val threadCurve by vm.threadCurve.collectAsState()

    var metric by remember { mutableStateOf(DetailMetric.Cpu) }
    var selectedTid by remember { mutableStateOf<Int?>(null) }

    val session = remember(sessions, sessionId) { sessions.firstOrNull { it.id == sessionId } }

    LaunchedEffect(sessionId, metric) { vm.loadSessionDetail(sessionId, metric) }
    LaunchedEffect(sessionId, selectedTid) {
        selectedTid?.let { vm.loadThreadCurve(sessionId, it) }
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Card(
                    modifier = Modifier.size(44.dp),
                    cornerRadius = 22.dp,
                    onClick = onBack,
                    insideMargin = PaddingValues(0.dp),
                ) {
                    Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
                        Text("←", fontSize = 18.sp, color = MiuixTheme.colorScheme.onSurface)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = session?.appLabel ?: "会话详情",
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    session?.let {
                        Text(
                            text = "${it.packageName} · ${it.sampleCount} 个采样点",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }

        item {
            SegmentedRow(
                options = DetailMetric.entries.map { it.label },
                selected = DetailMetric.entries.indexOf(metric),
                onSelect = { metric = DetailMetric.entries[it] },
            )
        }

        item { BigCurveCard(curve, metric) }

        selectedTid?.let { tid ->
            item {
                val t = threads.firstOrNull { it.tid == tid }
                CurveCard(
                    title = "线程 ${t?.name ?: tid}",
                    values = threadCurve.map { it.cpuPercent },
                    unit = "%",
                    yMax = 100f,
                    onClear = { selectedTid = null },
                )
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("线程排行（按平均占用）")
                Spacer(Modifier.weight(1f))
                Text(
                    "${threads.size} 条",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 10.sp,
                )
            }
        }

        items(threads, key = { it.tid }) { item ->
            ThreadSummaryRow(
                tid = item.tid,
                name = item.name,
                avg = item.avgCpu,
                max = item.maxCpu,
                selected = selectedTid == item.tid,
                onClick = { selectedTid = if (selectedTid == item.tid) null else item.tid },
            )
        }

        if (threads.isEmpty() && session?.endedAt != null) {
            item {
                TmCard(Modifier.fillMaxWidth()) {
                    Text(
                        "这次会话没有线程明细。可能记录时关闭了线程采集，或者目标应用提前退出。",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun BigCurveCard(values: List<Float>, metric: DetailMetric) {
    val valid = values.filter { it.isFinite() }
    val avg = if (valid.isEmpty()) Float.NaN else valid.average().toFloat()
    val max = valid.maxOrNull() ?: Float.NaN
    val yMax = when (metric) {
        DetailMetric.Cpu, DetailMetric.TargetCpu, DetailMetric.Gpu -> 100f
        DetailMetric.Fps -> 120f
        DetailMetric.Temp -> 100f
        DetailMetric.Mem -> (max.takeIf { it.isFinite() } ?: 1000f) * 1.1f
    }
    val unit = when (metric) {
        DetailMetric.Mem -> "MB"
        DetailMetric.Temp -> "℃"
        DetailMetric.Fps -> "fps"
        else -> "%"
    }

    TmCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(metric.label + " 曲线")
            Spacer(Modifier.weight(1f))
            Text(
                "均值 ${fmt(avg, 1)} · 峰值 ${fmt(max, 1)} $unit",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 10.sp,
            )
        }
        Spacer(Modifier.height(10.dp))
        if (values.size < 2) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text(
                    "数据点不足，无法绘制曲线",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                )
            }
        } else {
            MultiLineChart(
                series = listOf(ChartSeries(metric.label, MiuixTheme.colorScheme.onSurface, values)),
                yMaxOverride = yMax,
                modifier = Modifier.fillMaxWidth().height(160.dp),
            )
        }
    }
}

@Composable
private fun CurveCard(
    title: String,
    values: List<Float>,
    unit: String,
    yMax: Float,
    onClear: () -> Unit,
) {
    TmCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            Card(
                modifier = Modifier.height(28.dp),
                cornerRadius = 14.dp,
                onClick = onClear,
                insideMargin = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) {
                Box(Modifier.height(28.dp), contentAlignment = Alignment.Center) {
                    Text("收起", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
        if (values.size < 2) {
            Box(Modifier.fillMaxWidth().height(90.dp), contentAlignment = Alignment.Center) {
                Text(
                    "该线程数据点不足",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                )
            }
        } else {
            MultiLineChart(
                series = listOf(ChartSeries(title, MiuixTheme.colorScheme.onSurface, values)),
                yMaxOverride = yMax,
                modifier = Modifier.fillMaxWidth().height(110.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "峰值 ${fmt(values.maxOrNull() ?: Float.NaN, 1)} $unit",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun ThreadSummaryRow(
    tid: Int,
    name: String,
    avg: Float,
    max: Float,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MiuixTheme.colorScheme.surfaceContainer
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(
                    if (selected) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.outline
                )
        )
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = clampName(name.ifEmpty { "tid-$tid" }, 22),
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                maxLines = 1,
            )
            Text(
                text = "tid $tid",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Text(
            text = "均 ${fmt(avg, 1)}%",
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "峰 ${fmt(max, 1)}%",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}
