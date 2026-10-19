package com.threadmaster.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Query("UPDATE sessions SET endedAt = :endedAt, sampleCount = :count WHERE id = :id")
    suspend fun finish(id: Long, endedAt: Long, count: Int)

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: Long): SessionEntity?

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM sessions")
    suspend fun clear()
}

@Dao
interface SampleDao {

    @Insert
    suspend fun insertSysBatch(items: List<SysSampleEntity>)

    @Insert
    suspend fun insertThreadBatch(items: List<ThreadSampleEntity>)

    /**
     * 按时间桶聚合系统曲线。
     *
     * 用整数除法做桶，bucket 由调用方按会话总时长动态选择（例如 1 小时用 10 秒一桶），
     * 保证图表点数稳定在几百以内，避免一次读出数万行。
     */
    @Query(
        """
        SELECT (ts / :bucketMs) * :bucketMs AS bucket,
               AVG(CASE WHEN cpuUsage >= 0 THEN cpuUsage END) AS cpuUsage,
               AVG(CASE WHEN memUsedMb >= 0 THEN memUsedMb END) AS memUsedMb,
               AVG(CASE WHEN cpuTemp >= 0 THEN cpuTemp END) AS cpuTemp,
               AVG(CASE WHEN gpuBusy >= 0 THEN gpuBusy END) AS gpuBusy,
               AVG(CASE WHEN fps >= 0 THEN fps END) AS fps,
               AVG(CASE WHEN targetCpu >= 0 THEN targetCpu END) AS targetCpu,
               AVG(CASE WHEN batteryMa >= 0 THEN batteryMa END) AS batteryMa
        FROM sys_samples
        WHERE sessionId = :sessionId
        GROUP BY bucket
        ORDER BY bucket ASC
        """
    )
    suspend fun sysCurve(sessionId: Long, bucketMs: Long): List<SysAggregate>

    /** 单条线程的 CPU 曲线。 */
    @Query(
        """
        SELECT (ts / :bucketMs) * :bucketMs AS bucket,
               AVG(cpuPercent) AS cpuPercent
        FROM thread_samples
        WHERE sessionId = :sessionId AND tid = :tid
        GROUP BY bucket
        ORDER BY bucket ASC
        """
    )
    suspend fun threadCurve(sessionId: Long, tid: Int, bucketMs: Long): List<ThreadPoint>

    /** 会话内线程排行，按平均占用降序。 */
    @Query(
        """
        SELECT tid, name, AVG(cpuPercent) AS avgCpu, MAX(cpuPercent) AS maxCpu, COUNT(*) AS samples
        FROM thread_samples
        WHERE sessionId = :sessionId
        GROUP BY tid
        ORDER BY avgCpu DESC
        LIMIT 60
        """
    )
    suspend fun threadSummaries(sessionId: Long): List<ThreadSummary>

    @Query("SELECT COUNT(*) FROM sys_samples WHERE sessionId = :sessionId")
    suspend fun sampleCount(sessionId: Long): Int

    @Query("DELETE FROM sys_samples WHERE sessionId = :sessionId")
    suspend fun deleteSys(sessionId: Long)

    @Query("DELETE FROM thread_samples WHERE sessionId = :sessionId")
    suspend fun deleteThreads(sessionId: Long)
}
