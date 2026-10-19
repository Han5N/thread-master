package com.threadmaster.collector

import android.content.Context
import com.threadmaster.data.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.ArrayDeque

/** 实时曲线的内存缓存点，只服务悬浮窗和界面最近几分钟的视图。 */
data class LivePoint(
    val ts: Long,
    val cpu: Float,
    val targetCpu: Float,
    val memPercent: Float,
    val temp: Float,
    val gpu: Float,
    val fps: Float,
    val freqMhz: Int,
)

/**
 * 采集引擎：管理 root 会话的生命周期、解析数据流、分发给 UI 与数据库。
 *
 * 全局单例，因为前台服务、界面、悬浮窗看到必须是同一份数据。
 */
class MonitorEngine(
    private val appContext: Context,
    private val repository: SessionRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val shell = RootShell(appContext)

    private var job: Job? = null
    private var session: RootShell.MonitorSession? = null
    private var sessionId: Long = 0L
    private var recordThreads = true
    private var watchdog: Job? = null

    private val _state = MutableStateFlow(MonitorState())
    val state: StateFlow<MonitorState> = _state.asStateFlow()

    private val _snapshot = MutableStateFlow<SystemSnapshot?>(null)
    val snapshot: StateFlow<SystemSnapshot?> = _snapshot.asStateFlow()

    private val _live = MutableStateFlow<List<LivePoint>>(emptyList())
    val live: StateFlow<List<LivePoint>> = _live.asStateFlow()

    private val liveBuffer = ArrayDeque<LivePoint>(LIVE_CAPACITY)

    /**
     * 启动一次新的记录会话。已存在会话时会先正常收尾。
     *
     * @return 是否成功进入运行态，失败原因写入 [state]。
     */
    suspend fun start(
        packageName: String,
        label: String,
        intervalMs: Long,
        fpsEnabled: Boolean,
        recordThreads: Boolean,
    ): Boolean = mutex.withLock {
        if (job != null) stopInternal()

        _state.value = MonitorState(rootGranted = false, targetPackage = packageName, targetLabel = label)
        _snapshot.value = null
        liveBuffer.clear()
        _live.value = emptyList()

        if (!shell.checkRoot()) {
            _state.value = MonitorState(
                error = "没有拿到 root 权限，请在弹出的授权窗口中允许",
                targetPackage = packageName,
                targetLabel = label,
            )
            return@withLock false
        }

        if (!shell.installScript()) {
            _state.value = MonitorState(
                error = "采集脚本写入失败，检查 /data/local/tmp 是否可写",
                targetPackage = packageName,
                targetLabel = label,
            )
            return@withLock false
        }

        if (!shell.writeConfig(packageName, intervalMs / 1000.0, fpsEnabled)) {
            _state.value = MonitorState(
                error = "采集配置写入失败",
                targetPackage = packageName,
                targetLabel = label,
            )
            return@withLock false
        }

        val id = repository.createSession(packageName, label, intervalMs)
        sessionId = id
        this.recordThreads = recordThreads

        val monitor = shell.launch()
        session = monitor

        _state.value = MonitorState(
            running = true,
            rootGranted = true,
            targetPackage = packageName,
            targetLabel = label,
            sessionId = id,
            startedAt = System.currentTimeMillis(),
        )

        job = scope.launch { readLoop(monitor) }
        watchdog = scope.launch {
            delay(4000)
            if (job?.isActive == true && _snapshot.value == null) {
                mutex.withLock {
                    _state.value = _state.value.copy(
                        running = false,
                        error = "采集会话没有输出，可能是 su 授权被拒绝或脚本被系统拦截",
                    )
                    stopInternal()
                }
            }
        }
        true
    }

    suspend fun stop() = mutex.withLock { stopInternal() }

    /**
     * 打开界面时提前请求一次 root，让授权弹窗出现在用户看得见的时候，
     * 而不是点下「开始记录」后服务在后台干等。
     */
    suspend fun ensureRoot(): Boolean {
        val ok = shell.checkRoot()
        _state.value = _state.value.copy(rootGranted = ok)
        return ok
    }

    /**
     * 供 Service.onDestroy 这类不能挂起等待的入口调用。
     * 直接启动一个内部协程收尾，避免在销毁路径上阻塞主线程。
     */
    fun stopAsync() {
        scope.launch { mutex.withLock { stopInternal() } }
    }

    private suspend fun stopInternal() {
        watchdog?.cancel()
        watchdog = null

        val current = session
        session = null
        // 先断掉数据源，读循环才会从阻塞的 readLine 里退出来
        runCatching { current?.process?.destroy() }
        job?.cancelAndJoin()
        job = null
        shell.stopSession(current)

        if (sessionId != 0L) {
            repository.finishSession(sessionId)
            sessionId = 0L
        }

        _state.value = _state.value.copy(running = false)
    }

    private suspend fun readLoop(monitor: RootShell.MonitorSession) {
        val parser = SampleParser()
        var lastPid = 0
        while (currentCoroutineContext().isActive && monitor.isAlive()) {
            val line = monitor.readLine() ?: break
            val snap = parser.feed(line, System.nanoTime()) ?: continue
            _snapshot.value = snap
            pushLive(snap)
            if (snap.targetPid != lastPid) {
                lastPid = snap.targetPid
                _state.value = _state.value.copy(targetPid = snap.targetPid)
            }
            repository.append(sessionId, snap, recordThreads)
        }
        // 流意外结束时不要装作还在跑
        if (_state.value.running) {
            runCatching { repository.flush() }
            _state.value = _state.value.copy(
                running = false,
                error = "采集会话已结束，可能是目标进程被杀或 root 会话被回收",
            )
        }
    }

    private fun pushLive(snap: SystemSnapshot) {
        val memPercent = if (snap.memTotalKb > 0) {
            snap.memUsedKb * 100f / snap.memTotalKb
        } else 0f
        val point = LivePoint(
            ts = snap.ts,
            cpu = if (snap.cpuUsage.isNaN()) 0f else snap.cpuUsage,
            targetCpu = if (snap.targetCpuPercent.isNaN()) 0f else snap.targetCpuPercent,
            memPercent = memPercent,
            temp = if (snap.cpuTempC.isNaN()) 0f else snap.cpuTempC,
            gpu = if (snap.gpuBusyPercent.isNaN()) 0f else snap.gpuBusyPercent,
            fps = if (snap.fps.isNaN()) 0f else snap.fps,
            freqMhz = snap.avgFreqMhz,
        )
        synchronized(liveBuffer) {
            if (liveBuffer.size >= LIVE_CAPACITY) liveBuffer.removeFirst()
            liveBuffer.addLast(point)
            _live.value = liveBuffer.toList()
        }
    }

    /** 会话结束后把缓冲数据落盘，Service 销毁时调用。 */
    suspend fun flush() = repository.flush()

    companion object {
        private const val LIVE_CAPACITY = 600
    }
}
