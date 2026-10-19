package com.threadmaster.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threadmaster.ui.components.SectionLabel
import com.threadmaster.ui.components.TmCard
import com.threadmaster.ui.theme.TmColors

@Composable
fun SettingsScreen(vm: MonitorViewModel) {
    val settings by vm.settingsState.collectAsState()
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TmCard(Modifier.fillMaxWidth()) {
                Column {
                    SectionLabel("采样")
                    Spacer(Modifier.height(10.dp))
                    Text("采样间隔", color = TmColors.TextPrimary, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (ms in listOf(250L, 500L, 1000L, 2000L)) {
                            FilterChip(
                                selected = settings.intervalMs == ms,
                                onClick = { vm.setInterval(ms) },
                                label = { Text("${ms}ms", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = TmColors.Primary.copy(alpha = 0.16f),
                                    selectedLabelColor = TmColors.Primary,
                                    labelColor = TmColors.TextSecondary,
                                ),
                                border = null,
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "间隔越短曲线越细，但采集和写库本身也越费电。游戏场景 500ms 足够。",
                        color = TmColors.TextDim,
                        fontSize = 10.sp,
                    )
                }
            }
        }

        item {
            TmCard(Modifier.fillMaxWidth()) {
                Column {
                    SectionLabel("记录内容")
                    Spacer(Modifier.height(4.dp))
                    SwitchRow(
                        title = "帧率采样",
                        subtitle = "通过 SurfaceFlinger latency 每 2 秒取一次目标窗口帧率",
                        checked = settings.fpsEnabled,
                        onChange = { vm.setFpsEnabled(it) },
                    )
                    SwitchRow(
                        title = "线程明细",
                        subtitle = "每 2 秒记录一次目标应用全部线程的占用与等待",
                        checked = settings.recordThreads,
                        onChange = { vm.setRecordThreads(it) },
                    )
                }
            }
        }

        item {
            TmCard(Modifier.fillMaxWidth()) {
                Column {
                    SectionLabel("悬浮窗")
                    Spacer(Modifier.height(4.dp))
                    SwitchRow(
                        title = "显示 HUD",
                        subtitle = if (Settings.canDrawOverlays(context)) {
                            "可拖动，点一下回到主界面"
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
        }

        item {
            TmCard(Modifier.fillMaxWidth()) {
                Column {
                    SectionLabel("数据")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "所有采样保存在应用私有数据库，不上传、不联网。",
                        color = TmColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "清空全部记录",
                        color = TmColors.Danger,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { confirmClear = true }
                            .padding(vertical = 6.dp, horizontal = 2.dp),
                    )
                }
            }
        }

        item {
            TmCard(Modifier.fillMaxWidth()) {
                Column {
                    SectionLabel("关于")
                    Spacer(Modifier.height(8.dp))
                    Text("Thread Master 0.1.0", color = TmColors.TextPrimary, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "用一条常驻 root shell 读取 /proc 与 /sys，在应用侧做差值计算。" +
                            "线程占用优先取 schedstat 的运行时间，内核未开启时自动回退到 /proc/stat 的 jiffies。",
                        color = TmColors.TextDim,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "CPU 占用按单核计：一条线程跑满一个核心就是 100%，不除以核心数。",
                        color = TmColors.TextDim,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空全部记录？") },
            text = { Text("所有会话、系统采样和线程曲线都会被删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearAllData()
                    confirmClear = false
                }) { Text("清空", color = TmColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
            containerColor = TmColors.SurfaceHi,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TmColors.TextPrimary, fontSize = 13.sp)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = TmColors.TextDim, fontSize = 10.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TmColors.Bg,
                checkedTrackColor = TmColors.Primary,
                uncheckedThumbColor = TmColors.TextDim,
                uncheckedTrackColor = TmColors.SurfaceHi,
            ),
        )
    }
}
