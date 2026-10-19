package com.threadmaster.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.threadmaster.MainActivity
import com.threadmaster.R
import com.threadmaster.ThreadMasterApp
import com.threadmaster.hud.HudController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 前台服务：保证锁屏、切后台、进游戏之后采样不被系统回收。
 *
 * 服务只做三件事：维持前台状态、驱动 [com.threadmaster.collector.MonitorEngine]、
 * 按需挂载悬浮窗。所有采集逻辑都在引擎里，界面与服务的生命周期互不影响。
 */
class MonitorService : LifecycleService() {

    private lateinit var app: ThreadMasterApp
    private var hud: HudController? = null
    private var notifyJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        app = application as ThreadMasterApp
        createChannel()
        // 悬浮窗开关随时可能被界面改掉，服务自己盯着设置，不依赖调用方记得来通知
        lifecycleScope.launch {
            app.settings.state
                .map { it.hudEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) attachHudIfNeeded() else detachHud()
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                lifecycleScope.launch {
                    shutdown()
                }
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
                val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
                if (pkg.isEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundNow(getString(R.string.notif_title), "准备授权 root…")
                lifecycleScope.launch {
                    val settings = app.settings.state.value
                    val started = app.engine.start(
                        packageName = pkg,
                        label = label.ifEmpty { pkg },
                        intervalMs = settings.intervalMs,
                        fpsEnabled = settings.fpsEnabled,
                        recordThreads = settings.recordThreads,
                    )
                    if (!started) {
                        val err = app.engine.state.value.error ?: "启动失败"
                        updateNotification("启动失败", err)
                        delay(3500)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@launch
                    }
                    app.settings.rememberTarget(pkg, label.ifEmpty { pkg })
                    attachHudIfNeeded()
                    startNotificationLoop()
                }
            }
            else -> {
                // 系统重建服务时不会带 intent，没有目标就无法采样，直接退出避免留下空转的前台服务
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        notifyJob?.cancel()
        detachHud()
        // 这里不能 suspend 等待，交给引擎自己的作用域收尾，避免主线程被 su 调用卡住
        app.engine.stopAsync()
        super.onDestroy()
    }

    private suspend fun shutdown() {
        notifyJob?.cancel()
        detachHud()
        app.engine.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun detachHud() {
        hud?.detach()
        hud = null
    }

    private fun attachHudIfNeeded() {
        if (!app.settings.state.value.hudEnabled) return
        if (hud != null) return
        if (!android.provider.Settings.canDrawOverlays(this)) return
        hud = HudController(this, app.engine, app.settings).also { it.attach() }
    }

    private fun startNotificationLoop() {
        notifyJob?.cancel()
        notifyJob = lifecycleScope.launch {
            while (isActive) {
                val snap = app.engine.snapshot.value
                val st = app.engine.state.value
                if (!st.running) break
                val title = st.targetLabel.ifEmpty { st.targetPackage }
                val cpu = snap?.cpuUsage?.takeIf { !it.isNaN() }?.let { String.format(Locale.US, "%.0f%%", it) } ?: "--"
                val appCpu = snap?.targetCpuPercent?.takeIf { !it.isNaN() }?.let { String.format(Locale.US, "%.0f%%", it) } ?: "--"
                updateNotification(title, "整机 CPU $cpu · 应用 $appCpu · ${snap?.targetThreadCount ?: 0} 线程")
                delay(2000)
            }
        }
    }

    private fun startForegroundNow(title: String, text: String) {
        val notification = buildNotification(title, text)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    private fun updateNotification(title: String, text: String) {
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification(title, text))
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.notif_action_stop), stop)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "tm_monitor"
        private const val NOTIF_ID = 1001

        const val ACTION_START = "com.threadmaster.action.START"
        const val ACTION_STOP = "com.threadmaster.action.STOP"
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_LABEL = "extra_label"

        fun start(context: Context, packageName: String, label: String) {
            val intent = Intent(context, MonitorService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_LABEL, label)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MonitorService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
