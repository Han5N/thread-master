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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threadmaster.collector.MonitorPhase
import com.threadmaster.collector.ThreadSample
import com.threadmaster.ui.components.SectionLabel
import com.threadmaster.ui.components.SegmentedRow
import com.threadmaster.ui.components.TmCard
import com.threadmaster.ui.components.clampName
import com.threadmaster.ui.components.fmt
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class SortKey(val label: String) {
    Cpu("占用"),
    Wait("等待"),
    Tid("TID"),
}

@Composable
fun ThreadsScreen(vm: MonitorViewModel) {
    val snap by vm.snapshot.collectAsState()
    val state by vm.state.collectAsState()
    var sort by remember { mutableStateOf(SortKey.Cpu) }

    val threads = remember(snap, sort) {
        val list = snap?.threads.orEmpty()
        when (sort) {
            SortKey.Cpu -> list.sortedByDescending { it.cpuPercent }
            SortKey.Wait -> list.sortedByDescending { it.waitPercent }
            SortKey.Tid -> list.sortedBy { it.tid }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ThreadHeader(snap?.threads.orEmpty(), state.phase, snap?.targetThreadCount ?: 0) }

        item {
            SegmentedRow(
                options = SortKey.entries.map { it.label },
                selected = SortKey.entries.indexOf(sort),
                onSelect = { sort = SortKey.entries[it] },
            )
        }

        if (threads.isEmpty()) {
            item {
                TmCard(Modifier.fillMaxWidth()) {
                    Text(
                        if (state.phase == MonitorPhase.RECORDING) "目标应用当前没有可读的线程，可能已退出或被系统冻结"
                        else "还没有开始记录，先去监控页选择目标应用",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        items(threads, key = { it.tid }) { t ->
            ThreadRow(t)
        }
    }
}

@Composable
private fun ThreadHeader(threads: List<ThreadSample>, phase: MonitorPhase, reported: Int) {
    val totalCpu = threads.sumOf { it.cpuPercent.toDouble() }.toFloat()
    val busiest = threads.maxByOrNull { it.cpuPercent }
    TmCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("线程总览")
            Spacer(Modifier.weight(1f))
            Text(
                when (phase) {
                    MonitorPhase.RECORDING -> "${maxOf(reported, threads.size)} 个线程"
                    MonitorPhase.WAITING -> "等待前台"
                    MonitorPhase.IDLE -> "未运行"
                },
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 10.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            SummaryBit("合计占用", fmt(totalCpu, 0) + "%")
            SummaryBit("平均等待", fmt(threads.map { it.waitPercent }.average().toFloat(), 1) + "%")
            SummaryBit("最忙", busiest?.let { clampName(it.name, 10) } ?: "--")
        }
    }
}

@Composable
private fun SummaryBit(label: String, value: String) {
    Column {
        Text(label, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 10.sp)
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ThreadRow(t: ThreadSample) {
    val ratio = (t.cpuPercent / 100f).coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(MiuixTheme.colorScheme.surfaceContainer),
    ) {
        Box(
            Modifier
                .fillMaxWidth(ratio)
                .height(50.dp)
                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = t.name.ifEmpty { "tid-${t.tid}" },
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "tid ${t.tid} · core ${t.core} · prio ${t.priority} · ${t.state}",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = fmt(t.cpuPercent, 1) + "%",
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "wait ${fmt(t.waitPercent, 1)}%",
                    color = if (t.waitPercent > 20f) MiuixTheme.colorScheme.error
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
