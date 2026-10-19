package com.threadmaster.hud

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.threadmaster.collector.LivePoint
import com.threadmaster.collector.SystemSnapshot
import java.util.Locale

/**
 * 悬浮窗绘制层。
 *
 * 两种形态：
 * - 紧凑：整机/应用占用、每核频率、温度与最近曲线；
 * - 展开：补充每核占用、GPU、内存、网络、调度负载和线程 Top5。
 *
 * 用原生 Canvas 而不是 Compose：这是每秒重绘两次、常驻游戏上层的 View，
 * 自绘一次只要几个 Paint 和一条 Path，没有重组和布局开销。
 */
@SuppressLint("ViewConstructor")
class HudView(context: Context) : View(context) {

    private var snapshot: SystemSnapshot? = null
    private var live: List<LivePoint> = emptyList()

    /** 展开态由点击切换，尺寸在 onMeasure 里跟着变。 */
    var expanded: Boolean = false
        private set

    private val dp = resources.displayMetrics.density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_BG }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C_BORDER
        style = Paint.Style.STROKE
        strokeWidth = dp * 1f
    }
    private val rect = RectF()

    private val titlePaint = textPaint(11f, C_TEXT_DIM, mono = false)
    private val labelPaint = textPaint(10f, C_TEXT_DIM, mono = false)
    private val valuePaint = textPaint(11f, C_TEXT, mono = true)
    private val bigPaint = textPaint(34f, C_TEXT, mono = true).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val rowPaint = textPaint(11f, C_TEXT, mono = true)
    private val appBigPaint = textPaint(22f, C_TEXT, mono = true).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_TEXT }
    private val barBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_BAR_BG }

    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C_TEXT
        style = Paint.Style.STROKE
        strokeWidth = dp * 1.4f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val curveAltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C_TEXT_DIM
        style = Paint.Style.STROKE
        strokeWidth = dp * 1.2f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C_GRID
        strokeWidth = dp * 0.8f
    }
    private val path = Path()

    fun update(snap: SystemSnapshot?, points: List<LivePoint>) {
        snapshot = snap
        live = points
        invalidate()
    }

    fun toggleExpanded() {
        expanded = !expanded
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (dp * if (expanded) 300 else 208).toInt()
        val h = (dp * if (expanded) 430 else 156).toInt()
        setMeasuredDimension(
            resolveSize(w, widthMeasureSpec),
            resolveSize(h, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = dp * 10f
        val radius = dp * 14f

        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, bgPaint)
        rect.inset(dp * 0.5f, dp * 0.5f)
        canvas.drawRoundRect(rect, radius, radius, borderPaint)

        val snap = snapshot
        if (snap == null) {
            canvas.drawText("等待采样…", pad, h / 2, valuePaint)
            return
        }
        if (expanded) drawExpanded(canvas, snap, w, h, pad) else drawCompact(canvas, snap, w, h, pad)
    }

    private fun drawCompact(canvas: Canvas, snap: SystemSnapshot, w: Float, h: Float, pad: Float) {
        var y = pad + dp * 10f
        drawHeader(canvas, snap, w, pad, y)

        y += dp * 30f
        val cpu = if (snap.cpuUsage.isNaN()) 0f else snap.cpuUsage
        val cpuText = String.format(Locale.US, "%.0f", cpu)
        canvas.drawText(cpuText, pad, y, bigPaint)
        canvas.drawText("%", pad + bigPaint.measureText(cpuText) + dp * 2f, y, labelPaint)

        val appCpu = if (snap.targetCpuPercent.isNaN()) 0f else snap.targetCpuPercent
        val appText = String.format(Locale.US, "APP %.0f%%", appCpu)
        canvas.drawText(appText, w - pad - valuePaint.measureText(appText), y - dp * 4f, valuePaint)
        val thrText = "${snap.targetThreadCount} 线程"
        canvas.drawText(thrText, w - pad - labelPaint.measureText(thrText), y + dp * 8f, labelPaint)

        y += dp * 12f
        drawCoreBars(canvas, snap, w, pad, y, dp * 18f)

        y += dp * 34f
        val temp = if (snap.cpuTempC.isNaN()) "--" else String.format(Locale.US, "%.1f℃", snap.cpuTempC)
        val fps = if (snap.fps.isNaN()) "--" else String.format(Locale.US, "%.0f", snap.fps)
        val gpu = if (snap.gpuBusyPercent.isNaN()) "--" else String.format(Locale.US, "%.0f%%", snap.gpuBusyPercent)
        var x = pad
        for (p in listOf(temp, "${fps}fps", "GPU $gpu")) {
            canvas.drawText(p, x, y, valuePaint)
            x += valuePaint.measureText(p) + dp * 10f
        }
        val freq = "${snap.avgFreqMhz}MHz"
        canvas.drawText(freq, w - pad - labelPaint.measureText(freq), y, labelPaint)

        drawMiniChart(canvas, y + dp * 8f, h - pad, w, pad)
    }

    private fun drawExpanded(canvas: Canvas, snap: SystemSnapshot, w: Float, h: Float, pad: Float) {
        var y = pad + dp * 10f
        drawHeader(canvas, snap, w, pad, y)

        // 主数字区
        y += dp * 44f
        val cpu = if (snap.cpuUsage.isNaN()) 0f else snap.cpuUsage
        val cpuText = String.format(Locale.US, "%.0f", cpu)
        canvas.drawText(cpuText, pad, y, bigPaint)
        canvas.drawText("%", pad + bigPaint.measureText(cpuText) + dp * 2f, y, labelPaint)
        canvas.drawText("整机", pad, y + dp * 12f, labelPaint)

        val appCpu = if (snap.targetCpuPercent.isNaN()) 0f else snap.targetCpuPercent
        val appText = String.format(Locale.US, "%.0f%%", appCpu)
        canvas.drawText(appText, w - pad - appBigPaint.measureText(appText), y, appBigPaint)
        canvas.drawText("目标应用", w - pad - labelPaint.measureText("目标应用"), y + dp * 12f, labelPaint)

        // 每核占用
        y += dp * 22f
        val usage = snap.perCoreUsage
        val cores = snap.cores
        for (core in cores.take(8)) {
            val u = usage.getOrNull(core.core) ?: Float.NaN
            val ratio = if (u.isFinite()) (u / 100f).coerceIn(0f, 1f) else 0f
            canvas.drawText("CPU${core.core}", pad, y, labelPaint)
            val barLeft = pad + dp * 40f
            val barRight = w - pad - dp * 82f
            canvas.drawRoundRect(RectF(barLeft, y - dp * 8f, barRight, y - dp * 3f), dp * 2, dp * 2, barBgPaint)
            canvas.drawRoundRect(
                RectF(barLeft, y - dp * 8f, barLeft + (barRight - barLeft) * ratio, y - dp * 3f),
                dp * 2, dp * 2, barPaint,
            )
            val pct = if (u.isFinite()) String.format(Locale.US, "%3.0f%%", u) else " -- "
            canvas.drawText(pct, w - pad - dp * 74f, y, rowPaint)
            canvas.drawText("${core.curMhz}MHz", w - pad - dp * 40f, y, labelPaint)
            y += dp * 15f
        }

        // 指标网格
        y += dp * 4f
        drawMetricRow(canvas, pad, y, w,
            "结温" to (if (snap.cpuTempC.isNaN()) "--" else String.format(Locale.US, "%.1f℃", snap.cpuTempC)),
            "帧率" to (if (snap.fps.isNaN()) "--" else String.format(Locale.US, "%.0f", snap.fps)),
            "刷新" to (if (snap.refreshHz.isNaN()) "--" else String.format(Locale.US, "%.0fHz", snap.refreshHz)),
        )
        y += dp * 18f
        drawMetricRow(canvas, pad, y, w,
            "GPU" to (if (snap.gpuBusyPercent.isNaN()) "--" else String.format(Locale.US, "%.0f%%", snap.gpuBusyPercent)),
            "显频" to "${snap.gpuFreqKhz / 1000}M",
            "G温" to (if (snap.gpuTempC.isNaN()) "--" else String.format(Locale.US, "%.0f℃", snap.gpuTempC)),
        )
        y += dp * 18f
        val memUsedGb = snap.memUsedKb / 1024f / 1024f
        val memTotalGb = snap.memTotalKb / 1024f / 1024f
        drawMetricRow(canvas, pad, y, w,
            "内存" to String.format(Locale.US, "%.1fG", memUsedGb),
            "共" to String.format(Locale.US, "%.0fG", memTotalGb),
            "Load" to (if (snap.load1.isNaN()) "--" else String.format(Locale.US, "%.1f", snap.load1)),
        )
        y += dp * 18f
        drawMetricRow(canvas, pad, y, w,
            "下行" to fmtRate(snap.netRxBytesPerSec),
            "上行" to fmtRate(snap.netTxBytesPerSec),
            "切换" to "${snap.ctxtPerSec / 1000}k",
        )

        // 线程 Top5
        y += dp * 20f
        canvas.drawLine(pad, y - dp * 8f, w - pad, y - dp * 8f, gridPaint)
        canvas.drawText("线程 Top", pad, y, labelPaint)
        y += dp * 14f
        val top = snap.threads.sortedByDescending { it.cpuPercent }.take(5)
        for (t in top) {
            val name = if (t.name.length > 16) t.name.take(15) + "…" else t.name
            canvas.drawText(name, pad, y, rowPaint)
            val pct = String.format(Locale.US, "%.0f%%", t.cpuPercent)
            canvas.drawText(pct, w - pad - dp * 34f, y, rowPaint)
            canvas.drawText("c${t.core}", w - pad - dp * 10f, y, labelPaint)
            y += dp * 14f
        }

        // 曲线
        val chartTop = y + dp * 4f
        val chartBottom = h - pad
        if (chartBottom - chartTop > dp * 20f) {
            drawMiniChart(canvas, chartTop, chartBottom, w, pad)
        }
    }

    private fun drawHeader(canvas: Canvas, snap: SystemSnapshot, w: Float, pad: Float, y: Float) {
        val running = snap.targetPid != 0
        barPaint.color = if (running) C_TEXT else C_DIM
        canvas.drawCircle(pad + dp * 3f, y - dp * 3.5f, dp * 3f, barPaint)
        barPaint.color = C_TEXT
        canvas.drawText(
            if (snap.foreground) "记录中" else if (running) "后台" else "应用未运行",
            pad + dp * 10f,
            y,
            titlePaint,
        )
        val label = if (snap.targetPid == 0) "PID --" else "PID ${snap.targetPid}"
        canvas.drawText(label, w - pad - labelPaint.measureText(label), y, labelPaint)
    }

    private fun drawCoreBars(canvas: Canvas, snap: SystemSnapshot, w: Float, pad: Float, y: Float, maxH: Float) {
        val cores = snap.cores
        if (cores.isEmpty()) return
        val gap = dp * 2f
        val barW = ((w - pad * 2 - gap * (cores.size - 1)) / cores.size).coerceAtMost(dp * 14f)
        for ((i, core) in cores.withIndex()) {
            val x = pad + i * (barW + gap)
            val max = if (core.maxKhz > 0) core.maxKhz else cores.maxOf { it.curKhz }.coerceAtLeast(1)
            val ratio = (core.curKhz.toFloat() / max).coerceIn(0f, 1f)
            val bh = (maxH * ratio).coerceAtLeast(dp * 1.5f)
            canvas.drawRoundRect(RectF(x, y, x + barW, y + maxH), dp, dp, barBgPaint)
            canvas.drawRoundRect(RectF(x, y + maxH - bh, x + barW, y + maxH), dp, dp, barPaint)
        }
    }

    private fun drawMetricRow(canvas: Canvas, pad: Float, y: Float, w: Float, vararg cells: Pair<String, String>) {
        val cellW = (w - pad * 2) / cells.size
        for ((i, cell) in cells.withIndex()) {
            val x = pad + i * cellW
            canvas.drawText(cell.first, x, y - dp * 5f, labelPaint)
            canvas.drawText(cell.second, x, y + dp * 8f, rowPaint)
        }
    }

    private fun drawMiniChart(canvas: Canvas, top: Float, bottom: Float, w: Float, pad: Float) {
        canvas.drawLine(pad, top, w - pad, top, gridPaint)
        canvas.drawLine(pad, bottom, w - pad, bottom, gridPaint)

        val points = if (live.size > MAX_POINTS) live.subList(live.size - MAX_POINTS, live.size) else live
        if (points.size < 2) return
        drawCurve(canvas, points, pad, top, w - pad, bottom, curvePaint) { it.cpu }
        drawCurve(canvas, points, pad, top, w - pad, bottom, curveAltPaint) { it.targetCpu }
    }

    private fun drawCurve(
        canvas: Canvas,
        points: List<LivePoint>,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        paint: Paint,
        selector: (LivePoint) -> Float,
    ) {
        path.reset()
        val stepX = (right - left) / (points.size - 1)
        for ((i, p) in points.withIndex()) {
            val v = selector(p).coerceIn(0f, 100f)
            val px = left + i * stepX
            val py = bottom - (v / 100f) * (bottom - top)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, paint)
    }

    private fun fmtRate(bytes: Long): String {
        if (bytes <= 0) return "0"
        val kb = bytes / 1024.0
        return if (kb < 1024) String.format(Locale.US, "%.0fK", kb)
        else String.format(Locale.US, "%.1fM", kb / 1024)
    }

    private fun textPaint(sizeSp: Float, color: Int, mono: Boolean): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // HUD 不跟随系统字体缩放：它是固定尺寸的浮层，放大字体会直接溢出
            textSize = sizeSp * resources.displayMetrics.density
            this.color = color
            typeface = if (mono) Typeface.MONOSPACE else Typeface.DEFAULT
        }

    companion object {
        private const val MAX_POINTS = 90

        private val C_BG = 0xF20A0A0A.toInt()
        private val C_BORDER = 0x33FFFFFF
        private val C_TEXT = 0xFFF2F2F2.toInt()
        private val C_TEXT_DIM = 0xFF8E8E8E.toInt()
        private val C_DIM = 0xFF4A4A4A.toInt()
        private val C_BAR_BG = 0x33FFFFFF
        private val C_GRID = 0x22FFFFFF
    }
}
