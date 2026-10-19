package com.threadmaster.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threadmaster.data.AppSettings
import com.threadmaster.ui.components.SectionLabel
import com.threadmaster.ui.components.SegmentedRow
import com.threadmaster.ui.components.TmCard
import com.threadmaster.ui.components.TmConfirmDialog
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsScreen(vm: MonitorViewModel) {
    val settings by vm.settingsState.collectAsState()
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TmCard(Modifier.fillMaxWidth()) {
                SectionLabel("外观")
                Spacer(Modifier.height(10.dp))
                SegmentedRow(
                    options = listOf("跟随系统", "浅色", "深色"),
                    selected = settings.themeMode,
                    onSelect = { vm.setThemeMode(it) },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "黑白两套配色，强调色在浅色下是黑、深色下是白。",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 10.sp,
                )
            }
        }

        item {
            TmCard(Modifier.fillMaxWidth()) {
                SectionLabel("采样")
                Spacer(Modifier.height(10.dp))
                SegmentedRow(
                    options = listOf("250ms", "500ms", "1s", "2s"),
                    selected = listOf(250L, 500L, 1000L, 2000L).indexOf(settings.intervalMs).coerceAtLeast(0),
                    onSelect = { vm.setInterval(listOf(250L, 500L, 1000L, 2000L)[it]) },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "间隔越短曲线越细，采集和写库本身也越费电。游戏场景 500ms 足够。",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 10.sp,
                )
            }
        }

        item {
            TmCard(Modifier.fillMaxWidth()) {
                SectionLabel("记录内容")
                Spacer(Modifier.height(4.dp))
                SwitchRow(
                    title = "帧率采样",
                    subtitle = "通过 SurfaceFlinger latency 每 2 秒取一次目标窗口帧率，同时换算屏幕刷新率",
                    checked = settings.fpsEnabled,
                    onChange = { vm.setFpsEnabled(it) },
                )
                SwitchRow(
                    title = "线程明细",
                    subtitle = "每 2 秒记录一次目标应用全部线程的占用与等待",
                    checked = settings.recordThreads,
                    onChange = { vm.setRecordThreads(it) },
                )
                SwitchRow(
                    title = "只在应用前台时记录",
                    subtitle = "切到后台自动暂停，回到应用继续。关闭后会在整个会话期间持续写库",
                    checked = settings.foregroundOnly,
                    onChange = { vm.setForegroundOnly(it) },
                )
            }
        }

        item {
            TmCard(Modifier.fillMaxWidth()) {
                SectionLabel("悬浮窗")
                Spacer(Modifier.height(4.dp))
                SwitchRow(
                    title = "显示 HUD",
                    subtitle = if (Settings.canDrawOverlays(context)) {
                        "可拖动，单击展开更多数据，双击回到主界面"
                    } else {
                        "需要先授予「显示在其他应用上层」权限"
                    },
                    checked = settings.hudEnabled,
                    onChange = { enabled ->
                        if (enabled && !Settings.canDrawOverlays(context)) {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}"),
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        } else {
                            vm.setHudEnabled(enabled)
                        }
                    },
                )
            }
        }

        item {
            TmCard(Modifier.fillMaxWidth()) {
                SectionLabel("数据")
                Spacer(Modifier.height(8.dp))
                Text(
                    "所有采样保存在应用私有数据库，不上传、不联网。",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "清空全部记录",
                    color = MiuixTheme.colorScheme.error,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable { confirmClear = true }
                        .padding(vertical = 4.dp),
                )
            }
        }

        item {
            TmCard(Modifier.fillMaxWidth()) {
                SectionLabel("关于")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Thread Master 0.2.0",
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "用一条常驻 root shell 读取 /proc 与 /sys，在应用侧做差值计算。" +
                        "线程占用优先取 schedstat 的运行时间，内核未开启时自动回退到 jiffies。",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "CPU 占用按单核计：一条线程跑满一个核心就是 100%，不除以核心数。",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }

    if (confirmClear) {
        TmConfirmDialog(
            title = "清空全部记录？",
            message = "所有会话、系统采样和线程曲线都会被删除，无法恢复。",
            confirmText = "清空",
            danger = true,
            onConfirm = {
                vm.clearAllData()
                confirmClear = false
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (enabled) MiuixTheme.colorScheme.onSurface
                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 10.sp,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = if (enabled) onChange else null,
            enabled = enabled,
        )
    }
}
