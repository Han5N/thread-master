package com.threadmaster.collector

/**
 * 单个 CPU 核心的频率读数，单位 kHz。
 *
 * 频率直接来自 `scaling_cur_freq`，这是内核调度器当前实际使用的频率，
 * 比读取 `cpuinfo_cur_freq`（部分高通平台会返回失败）稳定得多。
 */
data class CoreFreq(
    val core: Int,
    val curKhz: Int,
    val minKhz: Int = 0,
    val maxKhz: Int = 0,
) {
    val curMhz: Int get() = curKhz / 1000
}

/** 一条温度传感器读数，[celsius] 已转换为摄氏度。 */
data class TempReading(val zone: String, val celsius: Float)

/**
 * 目标应用某个线程在一个采样窗口内的表现。
 *
 * [cpuPercent] 是相对**单核**的占用率：一个线程跑满一个核心即 100%，
 * 这与 top/htop 的线程视图一致，不除以核心数。
 * [waitPercent] 是同窗口内线程处于 runnable 但等待 CPU 的时间占比，
 * 它比 cpuPercent 更能暴露"线程想跑却排不上队"的卡顿根因。
 */
data class ThreadSample(
    val pid: Int,
    val tid: Int,
    val name: String,
    val cpuPercent: Float,
    val waitPercent: Float,
    val core: Int,
    val priority: Int,
    val state: Char,
)

/**
 * 一次完整的采样结果：系统级指标 + 目标应用全部线程。
 *
 * 这份结构同时喂给三处：悬浮窗、界面实时刷新、Room 落库，
 * 因此所有派生计算（差分、百分比）都在构造前完成，消费方只做展示。
 */
data class SystemSnapshot(
    val ts: Long,
    val cpuUsage: Float = Float.NaN,
    val perCoreUsage: List<Float> = emptyList(),
    val cores: List<CoreFreq> = emptyList(),
    val memTotalKb: Long = 0,
    val memAvailableKb: Long = 0,
    val swapTotalKb: Long = 0,
    val swapFreeKb: Long = 0,
    val zramUsedKb: Long = 0,
    val zramTotalKb: Long = 0,
    val gpuFreqKhz: Int = 0,
    val gpuMaxKhz: Int = 0,
    val gpuModel: String = "",
    val gpuFreqList: List<Int> = emptyList(),
    val gpuBusyPercent: Float = Float.NaN,
    val gpuTempC: Float = Float.NaN,
    val fps: Float = Float.NaN,
    val renderLatencyMs: Float = Float.NaN,
    val fpsLayer: String = "",
    val refreshHz: Float = Float.NaN,
    val batteryCurrentMa: Float = Float.NaN,
    val batteryVoltageV: Float = Float.NaN,
    val batteryPowerMw: Float = Float.NaN,
    val batteryTempC: Float = Float.NaN,
    val batteryLevel: Int = -1,
    val batteryStatus: String = "",
    val netRxBytesPerSec: Long = 0,
    val netTxBytesPerSec: Long = 0,
    val load1: Float = Float.NaN,
    val load5: Float = Float.NaN,
    val load15: Float = Float.NaN,
    val runQueue: Int = 0,
    val blockedQueue: Int = 0,
    val ctxtPerSec: Int = 0,
    val intrPerSec: Int = 0,
    val temps: List<TempReading> = emptyList(),
    val foreground: Boolean = false,
    val targetPid: Int = 0,
    val targetCpuPercent: Float = Float.NaN,
    val targetRssKb: Long = 0,
    val targetThreadCount: Int = 0,
    val targetIoReadPerSec: Long = 0,
    val targetIoWritePerSec: Long = 0,
    val threads: List<ThreadSample> = emptyList(),
) {
    val memUsedKb: Long get() = (memTotalKb - memAvailableKb).coerceAtLeast(0)
    val swapUsedKb: Long get() = (swapTotalKb - swapFreeKb).coerceAtLeast(0)

    /** 所有核心频率的算术平均，HUD 上用来快速判断是否降频。 */
    val avgFreqMhz: Int
        get() = cores.filter { it.curKhz > 0 }.let { list ->
            if (list.isEmpty()) 0 else list.sumOf { it.curKhz } / list.size / 1000
        }

    /** 按线程名关键字挑出 CPU 温度，各家内核命名差异大，用关键词兜底。 */
    val cpuTempC: Float
        get() = temps.filter { t ->
            val n = t.zone.lowercase()
            n.contains("cpu") || n.contains("soc") || n.contains("cluster") ||
                n.contains("tsens") || n.contains("apc")
        }.maxByOrNull { it.celsius }?.celsius ?: Float.NaN

    val batteryTempFromZone: Float
        get() = temps.firstOrNull { it.zone.lowercase().contains("batt") }?.celsius ?: Float.NaN
}

/** 可选中的目标应用。 */
data class TargetApp(
    val packageName: String,
    val label: String,
    val uid: Int,
    val pid: Int,
    val running: Boolean,
)

/** 采集会话的运行态，供界面顶栏展示。 */
enum class MonitorPhase {
    /** 未启动。 */
    IDLE,

    /** 已拿到 root 并开始采样，但目标应用还没到前台，数据不入库。 */
    WAITING,

    /** 目标应用在前台，正在记录。 */
    RECORDING,
}

data class MonitorState(
    val running: Boolean = false,
    val rootGranted: Boolean = false,
    val targetPackage: String = "",
    val targetLabel: String = "",
    val targetPid: Int = 0,
    val sessionId: Long = 0,
    val startedAt: Long = 0,
    val phase: MonitorPhase = MonitorPhase.IDLE,
    val foreground: Boolean = false,
    val error: String? = null,
)
