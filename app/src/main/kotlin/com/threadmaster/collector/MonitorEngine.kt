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
 * 会话分成两个阶段：
 * - WAITING：root 与脚本都已就绪，但目标应用还没到前台，只刷新实时预览，**不写库**；
 * - RECORDING：目标应用在前台，开始落库。切回后台时暂停写入，回来继续。
 *
 * 这样记录下来的曲线只覆盖"应用真正在屏幕上跑"的时间，不会混进桌面待机的空数据。
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
    private var foregroundOnly = true
    private var targetPackage = ""
    private var targetLabel = ""
    private var intervalMs = 500L
    private var watchdog: Job? = null

    private val _state = MutableStateFlow(MonitorState())
    val state: StateFlow<MonitorState> = _state.asStateFlow()

    private val _snapshot = MutableStateFlow<SystemSnapshot?>(null)
    val snapshot: StateFlow<SystemSnapshot?> = _snapshot.asStateFlow()

    private val _live = MutableStateFlow<List<LivePoint>>(emptyList())
    val live: StateFlow<List<LivePoint>> = _live.asStateFlow()

    private val liveBuffer = ArrayDeque<LivePoint>(LIVE_CAPACITY)

    suspend fun start(
        packageName: String,
        label: String,
        intervalMs: Long,
        fpsEnabled: Boolean,
        recordThreads: Boolean,
        foregroundOnly: Boolean = true,
    ): Boolean = mutex.withLock {
        if (job != null) stopInternal()

        _state.value = MonitorState(
            targetPackage = packageName,
            targetLabel = label,
            phase = MonitorPhase.WAITING,
        )
        _snapshot.value = null
        liveBuffer.clear()
        _live.value = emptyList()
        sessionId = 0

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

        this.targetPackage = packageName
        this.targetLabel = label
        this.intervalMs = intervalMs
        this.recordThreads = recordThreads
        this.foregroundOnly = foregroundOnly

        val monitor = shell.launch()
        session = monitor

        _state.value = MonitorState(
            running = true,
            rootGranted = true,
            targetPackage = packageName,
            targetLabel = label,
            phase = MonitorPhase.WAITING,
        )

        job = scope.launch { readLoop(monitor) }
        watchdog = scope.launch {
            delay(8000)
            val current = session
            if (job?.isActive == true && _snapshot.value == null) {
                val stderr = current?.drainError().orEmpty()
                mutex.withLock {
                    _state.value = _state.value.copy(
                        running = false,
                        phase = MonitorPhase.IDLE,
                        error = buildString {
                            append("采集会话没有输出：")
                            append(
                                when {
                                    stderr.isNotBlank() -> stderr.take(160)
                                    current == null || !current.isAlive() -> "脚本进程已退出，可能是 su 授权被拒绝或被执行策略拦截"
                                    else -> "脚本在跑但没有产生完整数据块，可能是某个 sysfs 节点读取卡住"
                                }
                            )
                        },
                    )
                    stopInternal()
                }
            }
        }
        true
    }

    suspend fun stop() = mutex.withLock { stopInternal() }

    /**
     * 供 Service.onDestroy 这类不能挂起等待的入口调用。
     * 直接启动一个内部协程收尾，避免在销毁路径上阻塞主线程。
     */
    fun stopAsync() {
        scope.launch { mutex.withLock { stopInternal() } }
    }

    /**
     * 打开界面时提前请求一次 root，让授权弹窗出现在用户看得见的时候，
     * 而不是点下「开始记录」后服务在后台干等。
     */
    suspend fun ensureRoot(): Boolean {
        val ok = shell.checkRoot()
        _state.value = _state.value.copy(rootGranted = ok)
        return ok
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

        _state.value = _state.value.copy(running = false, phase = MonitorPhase.IDLE, foreground = false)
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
            if (_state.value.foreground != snap.foreground) {
                _state.value = _state.value.copy(foreground = snap.foreground)
            }

            when (_state.value.phase) {
                MonitorPhase.WAITING -> {
                    // 应用进程存在且已经在前台，才真正开始一次记录
                    if (snap.targetPid != 0 && (snap.foreground || !foregroundOnly)) {
                        val id = repository.createSession(targetPackage, targetLabel, intervalMs)
                        sessionId = id
                        _state.value = _state.value.copy(
                            phase = MonitorPhase.RECORDING,
                            sessionId = id,
                            startedAt = System.currentTimeMillis(),
                        )
                        repository.append(id, snap, recordThreads)
                    }
                }
                MonitorPhase.RECORDING -> {
                    // 切后台时暂停写入，回来接着记
                    if ((snap.foreground || !foregroundOnly) && sessionId != 0L) {
                        repository.append(sessionId, snap, recordThreads)
                    }
                }
                MonitorPhase.IDLE -> Unit
            }
        }

        if (sessionId != 0L) {
            runCatching { repository.finishSession(sessionId) }
            sessionId = 0
        }
        if (_state.value.running) {
            _state.value = _state.value.copy(
                running = false,
                phase = MonitorPhase.IDLE,
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

    /** 对当前目标应用跑一次帧率诊断。 */
    suspend fun diagnoseFps(): String {
        val pkg = _state.value.targetPackage.ifEmpty { targetPackage }
        if (pkg.isEmpty()) return "还没有选择目标应用，先选一个再诊断"
        return shell.diagnoseFps(pkg)
    }

    /** 会话结束后把缓冲数据落盘，Service 销毁时调用。 */
    suspend fun flush() = repository.flush()

    companion object {
        private const val LIVE_CAPACITY = 600
    }
}
