package com.threadmaster

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.threadmaster.data.AppSettings
import com.threadmaster.ui.AppPickerDialog
import com.threadmaster.ui.DashboardScreen
import com.threadmaster.ui.HistoryScreen
import com.threadmaster.ui.MonitorViewModel
import com.threadmaster.ui.SessionDetailScreen
import com.threadmaster.ui.SettingsScreen
import com.threadmaster.ui.ThreadsScreen
import com.threadmaster.ui.theme.ThreadMasterTheme
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : ComponentActivity() {

    private val vm: MonitorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()
        lifecycleScope.launch { vm.engine.ensureRoot() }
        setContent {
            val settings by vm.settingsState.collectAsState()
            val dark = when (settings.themeMode) {
                AppSettings.THEME_LIGHT -> false
                AppSettings.THEME_DARK -> true
                else -> isSystemInDarkTheme()
            }
            ThreadMasterTheme(dark = dark) {
                AppRoot(vm)
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }
}

private data class TabItem(val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem("监控", Icons.Filled.Speed),
    TabItem("线程", Icons.Filled.AccountTree),
    TabItem("历史", Icons.Filled.History),
    TabItem("设置", Icons.Filled.Settings),
)

@Composable
private fun AppRoot(vm: MonitorViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    var showPicker by remember { mutableStateOf(false) }
    var detailSessionId by remember { mutableStateOf<Long?>(null) }

    val apps by vm.apps.collectAsState()
    val loadingApps by vm.appsLoading.collectAsState()

    LaunchedEffect(showPicker) {
        if (showPicker) vm.loadApps()
    }

    detailSessionId?.let { id ->
        BackHandler { detailSessionId = null }
        Scaffold(containerColor = MiuixTheme.colorScheme.background) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                SessionDetailScreen(vm = vm, sessionId = id, onBack = { detailSessionId = null })
            }
        }
        return
    }

    Scaffold(
        containerColor = MiuixTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = item.icon,
                        label = item.label,
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> DashboardScreen(vm = vm, onPickApp = { showPicker = true })
                1 -> ThreadsScreen(vm = vm)
                2 -> HistoryScreen(vm = vm, onOpen = { detailSessionId = it })
                else -> SettingsScreen(vm = vm)
            }
        }
    }

    if (showPicker) {
        AppPickerDialog(
            apps = apps,
            loading = loadingApps,
            onDismiss = { showPicker = false },
            onPick = { app ->
                showPicker = false
                vm.startMonitoring(app.packageName, app.label)
            },
        )
    }
}
