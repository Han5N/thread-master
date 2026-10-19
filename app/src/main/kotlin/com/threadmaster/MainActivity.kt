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
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.threadmaster.ui.AppPickerDialog
import com.threadmaster.ui.DashboardScreen
import com.threadmaster.ui.HistoryScreen
import com.threadmaster.ui.MonitorViewModel
import com.threadmaster.ui.SessionDetailScreen
import com.threadmaster.ui.SettingsScreen
import com.threadmaster.ui.ThreadsScreen
import com.threadmaster.ui.theme.ThreadMasterTheme
import com.threadmaster.ui.theme.TmColors
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: MonitorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()
        lifecycleScope.launch { vm.engine.ensureRoot() }
        setContent {
            ThreadMasterTheme {
                AppRoot(vm)
            }
        }
    }

    /** Android 13 起前台服务的通知也要运行时授权，没授权只是不显示通知，服务本身不受影响。 */
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
        SessionDetailScreen(vm = vm, sessionId = id, onBack = { detailSessionId = null })
        return
    }

    Scaffold(
        containerColor = TmColors.Bg,
        bottomBar = {
            NavigationBar(containerColor = TmColors.Surface, tonalElevation = 0.dp) {
                tabs.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = TmColors.Primary,
                            selectedTextColor = TmColors.Primary,
                            unselectedIconColor = TmColors.TextDim,
                            unselectedTextColor = TmColors.TextDim,
                            indicatorColor = TmColors.Primary.copy(alpha = 0.10f),
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
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
