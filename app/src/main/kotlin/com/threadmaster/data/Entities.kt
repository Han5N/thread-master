package com.threadmaster.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次记录会话。
 *
 * 会话是数据的归属单位：同一个应用可能被记录很多次，
 * 每条曲线都属于某个会话，删除会话时样本通过外键级联清理。
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appLabel: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val intervalMs: Long,
    val sampleCount: Int = 0,
)

/** 系统级采样行，一个采样点一行。 */
@Entity(
    tableName = "sys_samples",
    indices = [Index(value = ["sessionId", "ts"])],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class SysSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val ts: Long,
    /** 整机 CPU 占用率，0..100。 */
    val cpuUsage: Float,
    /** 所有核心频率均值，MHz。 */
    val cpuFreqMhz: Int,
    val memUsedMb: Int,
    val memTotalMb: Int,
    val swapUsedMb: Int,
    val gpuBusy: Float,
    val gpuFreqMhz: Int,
    val gpuTemp: Float,
    val fps: Float,
    val batteryMa: Float,
    val batteryTemp: Float,
    val netRxKb: Int,
    val netTxKb: Int,
    val cpuTemp: Float,
    /** 目标应用 CPU 占用，按线程累加，可超过 100。 */
    val targetCpu: Float,
    val targetRssMb: Int,
    val threadCount: Int,
    val gpuModel: String = "",
    val gpuMaxMhz: Int = 0,
    val refreshHz: Float = -1f,
    val load1: Float = -1f,
    val zramUsedMb: Int = 0,
    val batteryPowerMw: Float = -1f,
    val ctxtPerSec: Int = 0,
    val targetIoReadKb: Int = 0,
    val targetIoWriteKb: Int = 0,
)

/** 线程级采样行。写入频率低于系统采样，避免高频小事务拖慢记录。 */
@Entity(
    tableName = "thread_samples",
    indices = [
        Index(value = ["sessionId", "ts"]),
        Index(value = ["sessionId", "tid"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class ThreadSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val ts: Long,
    val tid: Int,
    val name: String,
    val cpuPercent: Float,
    val waitPercent: Float,
    val core: Int,
)

/** 系统曲线聚合结果，按时间桶求均值，避免把上万个点直接丢给图表。 */
data class SysAggregate(
    val bucket: Long,
    val cpuUsage: Float,
    val memUsedMb: Float,
    val cpuTemp: Float,
    val gpuBusy: Float,
    val fps: Float,
    val targetCpu: Float,
    val batteryMa: Float,
)

/** 线程全程汇总，用于历史页的线程排行。 */
data class ThreadSummary(
    val tid: Int,
    val name: String,
    val avgCpu: Float,
    val maxCpu: Float,
    val samples: Int,
)

/** 单条线程曲线的时间桶。 */
data class ThreadPoint(
    val bucket: Long,
    val cpuPercent: Float,
)
