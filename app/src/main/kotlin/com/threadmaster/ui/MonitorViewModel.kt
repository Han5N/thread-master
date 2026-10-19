package com.threadmaster.ui

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.threadmaster.ThreadMasterApp
import com.threadmaster.collector.LivePoint
import com.threadmaster.collector.MonitorState
import com.threadmaster.collector.SystemSnapshot
import com.threadmaster.collector.TargetApp
import com.threadmaster.data.AppSettings
import com.threadmaster.data.SessionEntity
import com.threadmaster.data.SessionRepository
import com.threadmaster.data.ThreadPoint
import com.threadmaster.data.ThreadSummary
import com.threadmaster.service.MonitorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
/**
 * 界面层的唯一 ViewModel。
 *
 * 采集引擎是应用级单例，ViewModel 只做转发和查询，
 * 这样旋转屏幕、退到后台再回来，数据流都不会断。
 */
class MonitorViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ThreadMasterApp
    val engine = app.engine
    val settings = app.settings
    private val repo: SessionRepository = app.repository

    val state: StateFlow<MonitorState> = engine.state
    val snapshot: StateFlow<SystemSnapshot?> = engine.snapshot
    val live: StateFlow<List<LivePoint>> = engine.live
    val settingsState: StateFlow<AppSettings> = settings.state

    val sessions: StateFlow<List<SessionEntity>> = repo.observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _apps = MutableStateFlow<List<TargetApp>>(emptyList())
    val apps: StateFlow<List<TargetApp>> = _apps.asStateFlow()

    private val _appsLoading = MutableStateFlow(false)
    val appsLoading: StateFlow<Boolean> = _appsLoading.asStateFlow()

    private val _detailCurve = MutableStateFlow<List<Float>>(emptyList())
    val detailCurve: StateFlow<List<Float>> = _detailCurve.asStateFlow()

    private val _detailThreads = MutableStateFlow<List<ThreadSummary>>(emptyList())
    val detailThreads: StateFlow<List<ThreadSummary>> = _detailThreads.asStateFlow()

    private val _threadCurve = MutableStateFlow<List<ThreadPoint>>(emptyList())
    val threadCurve: StateFlow<List<ThreadPoint>> = _threadCurve.asStateFlow()

    private val _diagnosis = MutableStateFlow<String?>(null)
    val diagnosis: StateFlow<String?> = _diagnosis.asStateFlow()

    /** 载入可监控应用列表。桌面应用才有意义，系统服务不在其中。 */
    fun loadApps() {
        if (_appsLoading.value) return
        _appsLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val pm = getApplication<Application>().packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(intent, PackageManager.GET_META_DATA)
            val list = resolved
                .mapNotNull { it.activityInfo?.applicationInfo }
                .distinctBy { it.packageName }
                .map { info ->
                    TargetApp(
                        packageName = info.packageName,
                        label = runCatching { pm.getApplicationLabel(info).toString() }
                            .getOrDefault(info.packageName),
                        uid = info.uid,
                        pid = 0,
                        running = false,
                    )
                }
                .filter { it.packageName != getApplication<Application>().packageName }
                .sortedBy { it.label.lowercase() }
            _apps.value = list
            _appsLoading.value = false
        }
    }

    fun startMonitoring(packageName: String, label: String) {
        MonitorService.start(getApplication(), packageName, label)
    }

    fun stopMonitoring() {
        MonitorService.stop(getApplication())
    }

    fun setInterval(ms: Long) = settings.setInterval(ms)
    fun setFpsEnabled(enabled: Boolean) = settings.setFpsEnabled(enabled)
    fun setRecordThreads(enabled: Boolean) = settings.setRecordThreads(enabled)

    fun setForegroundOnly(enabled: Boolean) = settings.setForegroundOnly(enabled)

    fun setThemeMode(mode: Int) = settings.setThemeMode(mode)
    fun setHudEnabled(enabled: Boolean) = settings.setHudEnabled(enabled)

    fun loadSessionDetail(sessionId: Long, metric: DetailMetric) {
        viewModelScope.launch(Dispatchers.IO) {
            val session = repo.session(sessionId) ?: return@launch
            val bucket = bucketFor(session)
            val curve = repo.sysCurve(sessionId, bucket)
            val values = curve.map { metric.pick(it) }.filter { it.isFinite() && it >= 0f }
            _detailCurve.value = values
            _detailThreads.value = repo.threadSummaries(sessionId)
        }
    }

    fun loadThreadCurve(sessionId: Long, tid: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val session = repo.session(sessionId) ?: return@launch
            val bucket = bucketFor(session)
            _threadCurve.value = repo.threadCurve(sessionId, tid, bucket)
        }
    }

    fun diagnoseFps() {
        _diagnosis.value = "诊断中…"
        viewModelScope.launch(Dispatchers.IO) {
            _diagnosis.value = runCatching { engine.diagnoseFps() }
                .getOrElse { "诊断失败：${'$'}{it.message}" }
        }
    }

    fun dismissDiagnosis() {
        _diagnosis.value = null
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch(Dispatchers.IO) { repo.deleteSession(sessionId) }
    }

    fun clearAllData() {
        viewModelScope.launch(Dispatchers.IO) { repo.clearAll() }
    }

    /** 图表桶大小：把任意长度的会话压到 150~400 个点，既省内存又可读。 */
    private fun bucketFor(session: SessionEntity): Long {
        val end = session.endedAt ?: System.currentTimeMillis()
        val duration = (end - session.startedAt).coerceAtLeast(1000)
        return (duration / 300).coerceIn(1000, 30_000)
    }
}

/** 历史详情页可切换的曲线指标。 */
enum class DetailMetric(val label: String) {
    Cpu("CPU"),
    TargetCpu("应用"),
    Mem("内存"),
    Temp("温度"),
    Fps("帧率"),
    Gpu("GPU");

    fun pick(a: com.threadmaster.data.SysAggregate): Float = when (this) {
        Cpu -> a.cpuUsage
        TargetCpu -> a.targetCpu
        Mem -> a.memUsedMb
        Temp -> a.cpuTemp
        Fps -> a.fps
        Gpu -> a.gpuBusy
    }
}
