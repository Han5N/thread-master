package com.threadmaster.collector

import android.system.Os
import android.system.OsConstants
import kotlin.math.abs

/**
 * 解析采集脚本的输出流，并把相邻两帧的原始计数器换算成占用率。
 *
 * 为什么必须在 App 侧做差分：内核暴露的 utime/频率都是累计量或瞬时量，
 * 只有在一个固定时间窗口内求差，才能得到"这段时间里谁吃了 CPU"。
 * 窗口用 [System.nanoTime] 作为基准，不受系统时间跳变影响。
 *
 * 该类不是线程安全的，必须由单一线程按行喂入。
 */
class SampleParser {

    private var inBlock = false
    private var inFps = false
    private var raw = RawBlock()
    private val fpsLines = ArrayList<String>(140)

    private var prevCpu = HashMap<String, LongArray>()
    private var prevNetRx = 0L
    private var prevNetTx = 0L
    private var prevCtxt = 0L
    private var prevIntr = 0L
    private var prevGpuBusy = 0L
    private var prevGpuTotal = 0L
    private var prevBlockNs = 0L
    private var prevTargetIoRead = 0L
    private var prevTargetIoWrite = 0L
    private val prevThreads = HashMap<Int, ThreadCounters>()

    /** 当前帧解析出的帧率缓存，脚本每 2 秒才更新一次。 */
    private var lastFps = Float.NaN

    /** 屏幕刷新率，从 latency 输出的首行刷新周期换算，同样低频更新。 */
    private var lastRefreshHz = Float.NaN

    /** 最近一次窗口内的平均呈现延迟，单位毫秒。 */
    private var lastLatencyMs = Float.NaN

    /**
     * 喂入一行输出。
     *
     * @return 当这一行结束了一个完整采样块时返回快照，否则返回 null。
     */
    fun feed(line: String, nowNs: Long): SystemSnapshot? {
        if (line.isEmpty()) return null
        when {
            line == "B" -> {
                inBlock = true
                raw = RawBlock()
                return null
            }
            line == "E" -> {
                if (!inBlock) return null
                inBlock = false
                return build(nowNs)
            }
            line == "FPSB" -> {
                inFps = true
                fpsLines.clear()
                return null
            }
            line == "FPSE" -> {
                inFps = false
                val result = parseFps(fpsLines, nowNs)
                if (!result.fps.isNaN()) lastFps = result.fps
                if (!result.refreshHz.isNaN()) lastRefreshHz = result.refreshHz
                if (!result.latencyMs.isNaN()) lastLatencyMs = result.latencyMs
                return null
            }
            inFps -> {
                if (fpsLines.size < 140) fpsLines.add(line)
                return null
            }
            inBlock -> parseLine(line)
        }
        return null
    }

    /** 目标应用切换或会话重启时清空差分基线，避免把两次会话之间的时间算成一个巨大的增量。 */
    fun reset() {
        prevCpu.clear()
        prevThreads.clear()
        prevNetRx = 0
        prevNetTx = 0
        prevCtxt = 0
        prevIntr = 0
        prevGpuBusy = 0
        prevGpuTotal = 0
        prevBlockNs = 0
        prevTargetIoRead = 0
        prevTargetIoWrite = 0
        lastFps = Float.NaN
        lastRefreshHz = Float.NaN
        lastLatencyMs = Float.NaN
    }

    private fun parseLine(line: String) {
        val tab = line.indexOf('\t')
        if (tab <= 0) return
        val tag = line.substring(0, tab)
        val body = line.substring(tab + 1)
        when (tag) {
            "C" -> {
                val f = body.split('\t')
                if (f.size >= 9) {
                    val arr = LongArray(8)
                    for (i in 0 until 8) arr[i] = f[i + 1].toLongOrNull() ?: 0L
                    raw.cpu[f[0]] = arr
                }
            }
            "F" -> {
                val f = body.split('\t')
                if (f.size >= 2) {
                    val core = f[0].toIntOrNull() ?: return
                    raw.freq[core] = f[1].toIntOrNull() ?: 0
                }
            }
            "L" -> {
                val f = body.split('\t')
                if (f.size >= 3) {
                    val core = f[0].toIntOrNull() ?: return
                    val min = f[1].toIntOrNull() ?: 0
                    val max = f[2].toIntOrNull() ?: 0
                    raw.limits[core] = min to max
                }
            }
            "M" -> {
                val f = body.split('\t')
                if (f.size >= 2) {
                    val v = f[1].toLongOrNull() ?: 0L
                    when (f[0]) {
                        "t" -> raw.memTotal = v
                        "a" -> raw.memAvail = v
                        "st" -> raw.swapTotal = v
                        "sf" -> raw.swapFree = v
                    }
                }
            }
            "G" -> {
                val f = body.split('\t')
                if (f.size >= 3) {
                    raw.gpuCurKhz = f[0].toIntOrNull() ?: 0
                    raw.gpuBusy = f[1].toLongOrNull() ?: 0L
                    raw.gpuTotal = f[2].toLongOrNull() ?: 0L
                    raw.gpuTemp = f.getOrNull(3)?.toFloatOrNull() ?: Float.NaN
                }
            }
            "BAT" -> {
                val f = body.split('\t')
                if (f.size >= 4) {
                    raw.batCurrent = f[0].toFloatOrNull() ?: Float.NaN
                    raw.batVoltage = f[1].toFloatOrNull() ?: Float.NaN
                    raw.batLevel = f[2].toIntOrNull() ?: -1
                    raw.batTemp = f[3].toFloatOrNull() ?: Float.NaN
                }
            }
            "N" -> {
                val f = body.split('\t')
                if (f.size >= 2) {
                    raw.netRx = f[0].toLongOrNull() ?: 0L
                    raw.netTx = f[1].toLongOrNull() ?: 0L
                }
            }
            "T" -> {
                val f = body.split('\t')
                if (f.size >= 2) {
                    val v = f[1].toFloatOrNull() ?: return
                    raw.temps.add(TempReading(f[0], normalizeZoneTemp(v)))
                }
            }
            "P" -> {
                val f = body.split('\t')
                if (f.size >= 5) {
                    val pid = f[0].toIntOrNull() ?: 0
                    // 多进程应用会连续输出多条 P 行：pid 取第一个作为入口，其余指标累加
                    if (raw.pid == 0) raw.pid = pid
                    raw.pUtime += f[1].toLongOrNull() ?: 0L
                    raw.pStime += f[2].toLongOrNull() ?: 0L
                    raw.pRssKb += f[3].toLongOrNull() ?: 0L
                    raw.pThreads += f[4].toIntOrNull() ?: 0
                    raw.targetIoRead += f.getOrNull(5)?.toLongOrNull() ?: 0L
                    raw.targetIoWrite += f.getOrNull(6)?.toLongOrNull() ?: 0L
                }
            }
            "THR" -> {
                val f = body.split('\t')
                if (f.size >= 9) {
                    val tid = f[1].toIntOrNull() ?: return
                    raw.threads.add(
                        RawThread(
                            pid = f[0].toIntOrNull() ?: 0,
                            tid = tid,
                            name = f[2],
                            utime = f[3].toLongOrNull() ?: 0L,
                            stime = f[4].toLongOrNull() ?: 0L,
                            core = f[5].toIntOrNull() ?: -1,
                            prio = f[6].toIntOrNull() ?: 0,
                            state = f[7].firstOrNull() ?: '?',
                            runNs = f[8].toLongOrNull() ?: 0L,
                            waitNs = f.getOrNull(9)?.toLongOrNull() ?: 0L,
                        )
                    )
                }
            }
            "CT" -> {
                val f = body.split('\t')
                if (f.size >= 4) {
                    raw.ctxt = f[0].toLongOrNull() ?: 0L
                    raw.intr = f[1].toLongOrNull() ?: 0L
                    raw.runQueue = f[2].toIntOrNull() ?: 0
                    raw.blockedQueue = f[3].toIntOrNull() ?: 0
                }
            }
            "LD" -> {
                val f = body.split('\t')
                if (f.size >= 3) {
                    raw.load1 = f[0].toFloatOrNull() ?: Float.NaN
                    raw.load5 = f[1].toFloatOrNull() ?: Float.NaN
                    raw.load15 = f[2].toFloatOrNull() ?: Float.NaN
                }
            }
            "ZR" -> {
                val f = body.split('\t')
                if (f.size >= 2) {
                    raw.zramUsedKb = f[0].toLongOrNull() ?: 0L
                    raw.zramTotalKb = f[1].toLongOrNull() ?: 0L
                }
            }
            "BP" -> {
                val f = body.split('\t')
                if (f.size >= 2) {
                    val uw = f[0].toFloatOrNull()
                    raw.batteryPowerMw = if (uw != null && uw != 0f) uw / 1000f else Float.NaN
                    raw.batteryStatus = f[1]
                }
            }
            "GI" -> {
                val f = body.split('\t')
                if (f.size >= 3) {
                    raw.gpuModel = f[0].trim()
                    raw.gpuMaxKhz = f[1].toIntOrNull() ?: 0
                    raw.gpuFreqList = f[2].split(' ')
                        .mapNotNull { it.trim().toIntOrNull() }
                        .filter { it > 0 }
                }
            }
            "FG" -> raw.foreground = body.trim() == "1"
            "FPSL" -> raw.fpsLayer = body.trim()
        }
    }

    private fun build(nowNs: Long): SystemSnapshot {
        val dtNs = if (prevBlockNs == 0L) 0L else (nowNs - prevBlockNs).coerceAtLeast(1_000_000L)
        val dtSec = dtNs / 1_000_000_000.0

        var cpuUsage = Float.NaN
        val perCore = ArrayList<Float>(raw.cpu.size)
        if (dtNs > 0) {
            for ((key, cur) in raw.cpu) {
                val prev = prevCpu[key] ?: continue
                var dTotal = 0L
                var dIdle = 0L
                for (i in 0 until 8) {
                    val d = (cur[i] - prev[i]).coerceAtLeast(0)
                    dTotal += d
                    // idle 与 iowait 都算"没在干活"
                    if (i == 3 || i == 4) dIdle += d
                }
                if (dTotal <= 0) continue
                val usage = ((dTotal - dIdle) * 100.0 / dTotal).toFloat().coerceIn(0f, 100f)
                if (key == "cpu") {
                    cpuUsage = usage
                } else {
                    val idx = key.removePrefix("cpu").toIntOrNull() ?: continue
                    while (perCore.size <= idx) perCore.add(Float.NaN)
                    perCore[idx] = usage
                }
            }
        }
        prevCpu = HashMap(raw.cpu)

        val cores = raw.freq.entries.sortedBy { it.key }.map { (core, khz) ->
            val (min, max) = raw.limits[core] ?: (0 to 0)
            CoreFreq(core, khz, min, max)
        }

        val netRx = if (dtNs > 0 && prevNetRx > 0 && raw.netRx >= prevNetRx) {
            ((raw.netRx - prevNetRx) / dtSec).toLong()
        } else 0L
        val netTx = if (dtNs > 0 && prevNetTx > 0 && raw.netTx >= prevNetTx) {
            ((raw.netTx - prevNetTx) / dtSec).toLong()
        } else 0L
        prevNetRx = raw.netRx
        prevNetTx = raw.netTx

        val ctxtPerSec = if (dtNs > 0 && prevCtxt > 0 && raw.ctxt >= prevCtxt) {
            ((raw.ctxt - prevCtxt) / dtSec).toInt()
        } else 0
        val intrPerSec = if (dtNs > 0 && prevIntr > 0 && raw.intr >= prevIntr) {
            ((raw.intr - prevIntr) / dtSec).toInt()
        } else 0
        prevCtxt = raw.ctxt
        prevIntr = raw.intr

        val targetIoRead = if (dtNs > 0 && prevTargetIoRead > 0 && raw.targetIoRead >= prevTargetIoRead) {
            ((raw.targetIoRead - prevTargetIoRead) / dtSec).toLong()
        } else 0L
        val targetIoWrite = if (dtNs > 0 && prevTargetIoWrite > 0 && raw.targetIoWrite >= prevTargetIoWrite) {
            ((raw.targetIoWrite - prevTargetIoWrite) / dtSec).toLong()
        } else 0L
        prevTargetIoRead = raw.targetIoRead
        prevTargetIoWrite = raw.targetIoWrite

        var gpuBusyPct = Float.NaN
        if (dtNs > 0 && prevGpuTotal > 0 && raw.gpuTotal > prevGpuTotal) {
            val dBusy = (raw.gpuBusy - prevGpuBusy).coerceAtLeast(0)
            val dTotal = raw.gpuTotal - prevGpuTotal
            gpuBusyPct = (dBusy * 100.0 / dTotal).toFloat().coerceIn(0f, 100f)
        }
        if (raw.gpuTotal > 0) {
            prevGpuBusy = raw.gpuBusy
            prevGpuTotal = raw.gpuTotal
        }

        val schedstatSupported = raw.threads.any { it.runNs > 0L }
        val threadSamples = ArrayList<ThreadSample>(raw.threads.size)
        var targetCpu = 0f
        val alive = HashSet<Int>(raw.threads.size)
        for (rt in raw.threads) {
            alive.add(rt.tid)
            val prev = prevThreads[rt.tid]
            var cpuPct = 0f
            var waitPct = 0f
            if (prev != null && dtNs > 0) {
                if (schedstatSupported) {
                    val dRun = (rt.runNs - prev.runNs).coerceAtLeast(0)
                    val dWait = (rt.waitNs - prev.waitNs).coerceAtLeast(0)
                    cpuPct = (dRun * 100.0 / dtNs).toFloat()
                    waitPct = (dWait * 100.0 / dtNs).toFloat()
                } else {
                    val dTicks = (rt.utime - prev.utime).coerceAtLeast(0) +
                        (rt.stime - prev.stime).coerceAtLeast(0)
                    cpuPct = (dTicks * 1_000_000_000.0 / clkTck / dtNs * 100.0).toFloat()
                }
            }
            if (cpuPct.isFinite()) {
                cpuPct = cpuPct.coerceIn(0f, 100f * cpuCountUpperBound)
                waitPct = if (waitPct.isFinite()) waitPct.coerceIn(0f, 100f) else 0f
                targetCpu += cpuPct
            }
            threadSamples.add(
                ThreadSample(
                    pid = rt.pid,
                    tid = rt.tid,
                    name = rt.name,
                    cpuPercent = if (cpuPct.isFinite()) cpuPct else 0f,
                    waitPercent = waitPct,
                    core = rt.core,
                    priority = rt.prio,
                    state = rt.state,
                )
            )
            prevThreads[rt.tid] = ThreadCounters(rt.utime, rt.stime, rt.runNs, rt.waitNs)
        }
        prevThreads.keys.retainAll(alive)

        prevBlockNs = nowNs

        return SystemSnapshot(
            ts = System.currentTimeMillis(),
            cpuUsage = cpuUsage,
            perCoreUsage = perCore,
            cores = cores,
            memTotalKb = raw.memTotal,
            memAvailableKb = raw.memAvail,
            swapTotalKb = raw.swapTotal,
            swapFreeKb = raw.swapFree,
            gpuFreqKhz = raw.gpuCurKhz,
            gpuBusyPercent = gpuBusyPct,
            gpuTempC = normalizeZoneTemp(raw.gpuTemp),
            fps = lastFps,
            renderLatencyMs = lastLatencyMs,
            batteryCurrentMa = normalizeCurrent(raw.batCurrent),
            batteryVoltageV = normalizeVoltage(raw.batVoltage),
            batteryTempC = normalizeBatteryTemp(raw.batTemp),
            batteryLevel = raw.batLevel,
            netRxBytesPerSec = netRx,
            netTxBytesPerSec = netTx,
            load1 = raw.load1,
            load5 = raw.load5,
            load15 = raw.load15,
            runQueue = raw.runQueue,
            blockedQueue = raw.blockedQueue,
            ctxtPerSec = ctxtPerSec,
            intrPerSec = intrPerSec,
            zramUsedKb = raw.zramUsedKb,
            zramTotalKb = raw.zramTotalKb,
            gpuModel = raw.gpuModel,
            gpuMaxKhz = raw.gpuMaxKhz,
            gpuFreqList = raw.gpuFreqList,
            refreshHz = lastRefreshHz,
            batteryPowerMw = raw.batteryPowerMw,
            batteryStatus = raw.batteryStatus,
            temps = raw.temps,
            foreground = raw.foreground,
            fpsLayer = raw.fpsLayer,
            targetPid = raw.pid,
            targetCpuPercent = if (threadSamples.isEmpty()) Float.NaN else targetCpu,
            targetRssKb = raw.pRssKb,
            targetThreadCount = maxOf(raw.pThreads, raw.threads.size),
            targetIoReadPerSec = targetIoRead,
            targetIoWritePerSec = targetIoWrite,
            threads = threadSamples,
        )
    }

    private data class FpsResult(val fps: Float, val refreshHz: Float, val latencyMs: Float)

    private fun parseFps(lines: List<String>, nowNs: Long): FpsResult {
        val refreshNs = lines.firstOrNull()?.trim()?.toLongOrNull()
        val refreshHz = if (refreshNs != null && refreshNs > 0) {
            1_000_000_000f / refreshNs
        } else {
            Float.NaN
        }
        if (lines.size < 4) return FpsResult(Float.NaN, refreshHz, Float.NaN)

        val times = ArrayList<Long>(lines.size)
        var latencySum = 0L
        var latencyCount = 0
        for (i in 1 until lines.size) {
            // dumpsys 用空格对齐，列之间可能有多个空格，不能用单空格切分
            val parts = lines[i].trim().split(WHITESPACE)
            if (parts.size < 3) continue
            val desired = parts[0].toLongOrNull() ?: 0L
            val actual = parts[1].toLongOrNull() ?: continue
            if (actual > 0) times.add(actual)
            // 实际呈现时间减去期望呈现时间，就是这一帧多等的时间
            if (actual > 0 && desired > 0 && actual > desired) {
                latencySum += actual - desired
                latencyCount++
            }
        }
        val latencyMs = if (latencyCount > 0) {
            (latencySum.toDouble() / latencyCount / 1_000_000.0).toFloat()
        } else {
            Float.NaN
        }
        if (times.size < 2) return FpsResult(Float.NaN, refreshHz, latencyMs)
        times.sort()
        val last = times.last()
        // latency 缓冲里可能全是暂停前的旧帧，超过 1 秒没有新帧就当作 0 fps，而不是虚报历史帧率
        if (nowNs > last && nowNs - last > 1_000_000_000L) return FpsResult(0f, refreshHz, latencyMs)
        val span = last - times.first()
        if (span <= 0) return FpsResult(Float.NaN, refreshHz, latencyMs)
        return FpsResult(((times.size - 1) * 1_000_000_000f / span).coerceIn(0f, 240f), refreshHz, latencyMs)
    }

    private class RawBlock {
        val cpu = LinkedHashMap<String, LongArray>()
        val freq = HashMap<Int, Int>()
        val limits = HashMap<Int, Pair<Int, Int>>()
        var memTotal = 0L
        var memAvail = 0L
        var swapTotal = 0L
        var swapFree = 0L
        var gpuCurKhz = 0
        var gpuBusy = 0L
        var gpuTotal = 0L
        var gpuTemp = Float.NaN
        var batCurrent = Float.NaN
        var batVoltage = Float.NaN
        var batLevel = -1
        var batTemp = Float.NaN
        var netRx = 0L
        var netTx = 0L
        var ctxt = 0L
        var intr = 0L
        var runQueue = 0
        var blockedQueue = 0
        var load1 = Float.NaN
        var load5 = Float.NaN
        var load15 = Float.NaN
        var zramUsedKb = 0L
        var zramTotalKb = 0L
        var gpuModel = ""
        var gpuMaxKhz = 0
        var gpuFreqList: List<Int> = emptyList()
        var batteryPowerMw = Float.NaN
        var batteryStatus = ""
        var foreground = false
        var fpsLayer = ""
        var targetIoRead = 0L
        var targetIoWrite = 0L
        val temps = ArrayList<TempReading>(24)
        var pid = 0
        var pUtime = 0L
        var pStime = 0L
        var pRssKb = 0L
        var pThreads = 0
        val threads = ArrayList<RawThread>(64)
    }

    private data class RawThread(
        val pid: Int,
        val tid: Int,
        val name: String,
        val utime: Long,
        val stime: Long,
        val core: Int,
        val prio: Int,
        val state: Char,
        val runNs: Long,
        val waitNs: Long,
    )

    private data class ThreadCounters(
        val utime: Long,
        val stime: Long,
        val runNs: Long,
        val waitNs: Long,
    )

    companion object {
        private val WHITESPACE = Regex("\\s+")

        /** 单线程占用率理论上限按核心数放宽，避免多核并行时被错误截断到 100。 */
        private val cpuCountUpperBound: Int
            get() = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

        private val clkTck: Long = runCatching {
            Os.sysconf(OsConstants._SC_CLK_TCK).let { if (it > 0) it else 100L }
        }.getOrDefault(100L)

        /** 电池电流单位在不同平台有 µA / mA 两种，用阈值区分。 */
        fun normalizeCurrent(v: Float): Float {
            if (v.isNaN()) return Float.NaN
            val a = abs(v)
            return if (a > 10_000f) a / 1000f else a
        }

        /** 电压可能是 µV / mV / V。 */
        fun normalizeVoltage(v: Float): Float {
            if (v.isNaN()) return Float.NaN
            return when {
                v > 100_000f -> v / 1_000_000f
                v > 10_000f -> v / 1000f
                else -> v
            }
        }

        /** 电池温度通常是 0.1℃，个别平台直接给℃。 */
        fun normalizeBatteryTemp(v: Float): Float {
            if (v.isNaN()) return Float.NaN
            return when {
                v > 1000f -> v / 1000f
                v > 100f -> v / 10f
                else -> v
            }
        }

        /** thermal_zone 温度多数是毫度，少数是 0.1 度或度。 */
        fun normalizeZoneTemp(v: Float): Float {
            if (v.isNaN()) return Float.NaN
            return when {
                v >= 2000f -> v / 1000f
                v >= 200f -> v / 10f
                else -> v
            }
        }
    }
}
