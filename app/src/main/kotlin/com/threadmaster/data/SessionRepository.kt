package com.threadmaster.data

import com.threadmaster.collector.SystemSnapshot
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/**
 * 会话数据的写入与查询入口。
 *
 * 写入采用内存缓冲 + 批量事务：单条 insert 在 WAL 下也要走一次事务提交，
 * 每秒几十次会给 IO 带来无谓压力，而采样线程本身就跑在别人的 CPU 上。
 * 缓冲上限和超时双阈值保证进程被杀时最多丢最近两三秒的数据。
 */
class SessionRepository(private val db: AppDatabase) {

    private val sysBuffer = ArrayList<SysSampleEntity>(32)
    private val threadBuffer = ArrayList<ThreadSampleEntity>(512)
    private var bufferedSessionId = 0L
    private var lastFlushAt = 0L
    private var lastThreadWriteAt = 0L

    fun observeSessions(): Flow<List<SessionEntity>> = db.sessionDao().observeAll()

    suspend fun createSession(packageName: String, label: String, intervalMs: Long): Long {
        val id = db.sessionDao().insert(
            SessionEntity(
                packageName = packageName,
                appLabel = label,
                startedAt = System.currentTimeMillis(),
                intervalMs = intervalMs,
            )
        )
        bufferedSessionId = id
        lastFlushAt = System.currentTimeMillis()
        lastThreadWriteAt = 0
        return id
    }

    suspend fun finishSession(sessionId: Long) {
        flush()
        val count = db.sampleDao().sampleCount(sessionId)
        db.sessionDao().finish(sessionId, System.currentTimeMillis(), count)
    }

    /**
     * 追加一个采样点。
     *
     * @param recordThreads 是否把线程明细一起入库。线程明细默认 2 秒一条，
     *   在保证曲线可读的前提下把写入量压到系统采样的四分之一。
     */
    suspend fun append(sessionId: Long, snapshot: SystemSnapshot, recordThreads: Boolean) {
        if (bufferedSessionId != sessionId) {
            flush()
            bufferedSessionId = sessionId
            lastThreadWriteAt = 0
        }

        sysBuffer.add(
            SysSampleEntity(
                sessionId = sessionId,
                ts = snapshot.ts,
                cpuUsage = safe(snapshot.cpuUsage),
                cpuFreqMhz = snapshot.avgFreqMhz,
                memUsedMb = (snapshot.memUsedKb / 1024).toInt(),
                memTotalMb = (snapshot.memTotalKb / 1024).toInt(),
                swapUsedMb = (snapshot.swapUsedKb / 1024).toInt(),
                gpuBusy = safe(snapshot.gpuBusyPercent),
                gpuFreqMhz = snapshot.gpuFreqKhz / 1000,
                gpuTemp = safe(snapshot.gpuTempC),
                fps = safe(snapshot.fps),
                batteryMa = safe(snapshot.batteryCurrentMa),
                batteryTemp = safe(snapshot.batteryTempC),
                netRxKb = (snapshot.netRxBytesPerSec / 1024).toInt(),
                netTxKb = (snapshot.netTxBytesPerSec / 1024).toInt(),
                cpuTemp = safe(snapshot.cpuTempC),
                targetCpu = safe(snapshot.targetCpuPercent),
                targetRssMb = (snapshot.targetRssKb / 1024).toInt(),
                threadCount = snapshot.targetThreadCount,
            )
        )

        if (recordThreads && snapshot.ts - lastThreadWriteAt >= THREAD_INTERVAL_MS) {
            lastThreadWriteAt = snapshot.ts
            for (t in snapshot.threads) {
                threadBuffer.add(
                    ThreadSampleEntity(
                        sessionId = sessionId,
                        ts = snapshot.ts,
                        tid = t.tid,
                        name = t.name,
                        cpuPercent = t.cpuPercent,
                        waitPercent = t.waitPercent,
                        core = t.core,
                    )
                )
            }
        }

        val now = System.currentTimeMillis()
        if (sysBuffer.size >= 12 || threadBuffer.size >= 800 || now - lastFlushAt >= 4000) {
            flush()
        }
    }

    /** 把缓冲刷进数据库。失败时清空缓冲，避免一次写入异常导致内存无限增长。 */
    suspend fun flush() {
        if (sysBuffer.isEmpty() && threadBuffer.isEmpty()) return
        val sys = ArrayList(sysBuffer)
        val threads = ArrayList(threadBuffer)
        sysBuffer.clear()
        threadBuffer.clear()
        runCatching {
            db.withTransaction {
                if (sys.isNotEmpty()) db.sampleDao().insertSysBatch(sys)
                if (threads.isNotEmpty()) db.sampleDao().insertThreadBatch(threads)
            }
        }
        lastFlushAt = System.currentTimeMillis()
    }

    suspend fun session(id: Long): SessionEntity? = db.sessionDao().get(id)

    suspend fun sysCurve(sessionId: Long, bucketMs: Long): List<SysAggregate> =
        db.sampleDao().sysCurve(sessionId, bucketMs)

    suspend fun threadCurve(sessionId: Long, tid: Int, bucketMs: Long): List<ThreadPoint> =
        db.sampleDao().threadCurve(sessionId, tid, bucketMs)

    suspend fun threadSummaries(sessionId: Long): List<ThreadSummary> =
        db.sampleDao().threadSummaries(sessionId)

    suspend fun deleteSession(id: Long) {
        if (bufferedSessionId == id) flush()
        db.sessionDao().delete(id)
    }

    suspend fun clearAll() {
        sysBuffer.clear()
        threadBuffer.clear()
        db.sessionDao().clear()
    }

    /**
     * SQLite 会把 NaN 存成 NULL，读回非空 Float 时变成 0，等于凭空捏造了一个数据点。
     * 统一用 -1 表示"这一帧没有该指标"，查询侧再用 CASE WHEN 把它排除在均值之外。
     */
    private fun safe(v: Float): Float = if (v.isFinite()) v else -1f

    companion object {
        /** 线程明细落库间隔，2 秒足以画出趋势，又能把行数压到可接受范围。 */
        const val THREAD_INTERVAL_MS = 2000L
    }
}
