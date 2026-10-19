package com.threadmaster.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threadmaster.collector.ThreadSample
import com.threadmaster.ui.components.SectionLabel
import com.threadmaster.ui.components.TmCard
import com.threadmaster.ui.components.clampName
import com.threadmaster.ui.components.fmt
import com.threadmaster.ui.theme.TmColors

private enum class SortKey(val label: String) {
    Cpu("占用"),
    Wait("等待"),
    Tid("TID"),
}

/**
 * 实时线程页。
 *
 * 这是 Thread Master 的主场：把目标应用的全部线程摊开，按占用或等待排序，
 * 一眼就能看出是渲染线程被挤、还是某个后台线程在偷偷跑。
 */
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
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ThreadHeader(snap?.threads.orEmpty(), state.running, snap?.targetThreadCount ?: 0) }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (key in SortKey.entries) {
                    FilterChip(
                        selected = sort == key,
                        onClick = { sort = key },
                        label = { Text(key.label, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = TmColors.Primary.copy(alpha = 0.16f),
                            selectedLabelColor = TmColors.Primary,
                            labelColor = TmColors.TextSecondary,
                        ),
                        border = null,
                    )
                }
            }
        }

        if (threads.isEmpty()) {
            item {
                TmCard(Modifier.fillMaxWidth()) {
                    Text(
                        if (state.running) "目标应用当前没有可读的线程，可能已退出或被系统冻结"
                        else "还没有开始记录，先去监控页选择目标应用",
                        color = TmColors.TextDim,
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
private fun ThreadHeader(threads: List<ThreadSample>, running: Boolean, reported: Int) {
    val totalCpu = threads.sumOf { it.cpuPercent.toDouble() }.toFloat()
    val busiest = threads.maxByOrNull { it.cpuPercent }
    TmCard(Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("线程总览")
                Spacer(Modifier.weight(1f))
                Text(
                    if (running) "${maxOf(reported, threads.size)} 个线程" else "未运行",
                    color = TmColors.TextDim,
                    fontSize = 10.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                SummaryBit("合计占用", fmt(totalCpu, 0) + "%", TmColors.Primary)
                SummaryBit("平均等待", fmt(threads.map { it.waitPercent }.average().toFloat(), 1) + "%", TmColors.Warn)
                SummaryBit("最忙", busiest?.let { clampName(it.name, 10) } ?: "--", TmColors.TextPrimary)
            }
        }
    }
}

@Composable
private fun SummaryBit(label: String, value: String, color: Color) {
    Column {
        Text(label, color = TmColors.TextDim, fontSize = 10.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, color = color, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ThreadRow(t: ThreadSample) {
    val ratio = (t.cpuPercent / 100f).coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(TmColors.Surface),
    ) {
        // 用一条横向占比条当背景，扫一眼就知道哪个线程在吃 CPU
        Box(
            Modifier
                .fillMaxWidth(ratio)
                .height(48.dp)
                .background(TmColors.Primary.copy(alpha = 0.10f)),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = t.name.ifEmpty { "tid-${t.tid}" },
                    color = TmColors.TextPrimary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "tid ${t.tid} · core ${t.core} · prio ${t.priority} · ${t.state}",
                    color = TmColors.TextDim,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = fmt(t.cpuPercent, 1) + "%",
                    color = when {
                        t.cpuPercent >= 50f -> TmColors.Danger
                        t.cpuPercent >= 15f -> TmColors.Primary
                        else -> TmColors.TextSecondary
                    },
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "wait ${fmt(t.waitPercent, 1)}%",
                    color = if (t.waitPercent > 20f) TmColors.Warn else TmColors.TextDim,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
