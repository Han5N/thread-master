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
 * 悬浮窗的绘制层。
 *
 * 刻意用原生 Canvas 而不是 Compose：这是一个每秒重绘两次、常驻在游戏上层的 View，
 * 自绘一次只需要几个 Paint 和一条 Path，没有任何重组、测量和布局开销。
 */
@SuppressLint("ViewConstructor")
class HudView(context: Context) : View(context) {

    private var snapshot: SystemSnapshot? = null
    private var live: List<LivePoint> = emptyList()

    private val dp = resources.displayMetrics.density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_BG }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C_BORDER
        style = Paint.Style.STROKE
        strokeWidth = dp * 1f
    }
    private val rect = RectF()

    private val titlePaint = textPaint(11f, C_TEXT_DIM, mono = false)
    private val appPaint = textPaint(11f, C_TEXT, mono = false)
    private val bigPaint = textPaint(26f, C_PRIMARY, mono = true).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val unitPaint = textPaint(10f, C_TEXT_DIM, mono = false)
    private val smallPaint = textPaint(9f, C_TEXT_DIM, mono = true)
    private val valuePaint = textPaint(10f, C_TEXT, mono = true)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_PRIMARY }
    private val barBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_BAR_BG }

    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C_PRIMARY
        style = Paint.Style.STROKE
        strokeWidth = dp * 1.4f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val curvePaintAlt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C_SECONDARY
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = dp * 10f
        val radius = dp * 12f

        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, bgPaint)
        rect.inset(dp * 0.5f, dp * 0.5f)
        canvas.drawRoundRect(rect, radius, radius, borderPaint)

        val snap = snapshot
        if (snap == null) {
            canvas.drawText("等待采样…", pad, h / 2, valuePaint)
            return
        }

        var y = pad + dp * 10f
        // 标题行：状态点 + 应用名
        val running = snap.targetPid != 0
        barPaint.color = if (running) C_PRIMARY else C_DANGER
        canvas.drawCircle(pad + dp * 3f, y - dp * 3.5f, dp * 3f, barPaint)
        barPaint.color = C_PRIMARY
        canvas.drawText(
            snap.let { if (it.targetPid == 0) "应用未运行" else "监控中" },
            pad + dp * 10f,
            y,
            titlePaint,
        )
        val label = if (snap.targetPid == 0) "PID --" else "PID ${snap.targetPid}"
        drawRight(canvas, label, w - pad, y, appPaint)

        // 主数字：整机 CPU
        y += dp * 30f
        val cpu = if (snap.cpuUsage.isNaN()) 0f else snap.cpuUsage
        canvas.drawText(String.format(Locale.US, "%.0f", cpu), pad, y, bigPaint)
        val numWidth = bigPaint.measureText(String.format(Locale.US, "%.0f", cpu))
        canvas.drawText("%", pad + numWidth + dp * 2f, y, unitPaint)

        // 右上角：目标应用 CPU
        val appCpu = if (snap.targetCpuPercent.isNaN()) 0f else snap.targetCpuPercent
        val appText = String.format(Locale.US, "APP %.0f%%", appCpu)
        canvas.drawText(appText, w - pad - appPaint.measureText(appText), y - dp * 4f, appPaint)
        val thrText = "${snap.targetThreadCount} 线程"
        canvas.drawText(thrText, w - pad - smallPaint.measureText(thrText), y + dp * 8f, smallPaint)

        // 每核频率条
        y += dp * 12f
        val cores = snap.cores
        if (cores.isNotEmpty()) {
            val gap = dp * 2f
            val barW = ((w - pad * 2 - gap * (cores.size - 1)) / cores.size).coerceAtMost(dp * 14f)
            val maxBarH = dp * 18f
            for ((i, core) in cores.withIndex()) {
                val x = pad + i * (barW + gap)
                val max = if (core.maxKhz > 0) core.maxKhz else cores.maxOf { it.curKhz }.coerceAtLeast(1)
                val ratio = (core.curKhz.toFloat() / max).coerceIn(0f, 1f)
                val bh = (maxBarH * ratio).coerceAtLeast(dp * 1.5f)
                canvas.drawRoundRect(
                    RectF(x, y, x + barW, y + maxBarH),
                    dp, dp, barBgPaint,
                )
                canvas.drawRoundRect(
                    RectF(x, y + maxBarH - bh, x + barW, y + maxBarH),
                    dp, dp, barPaint,
                )
            }
        }

        // 指标行
        y += dp * 34f
        val temp = if (snap.cpuTempC.isNaN()) "--" else String.format(Locale.US, "%.1f℃", snap.cpuTempC)
        val fps = if (snap.fps.isNaN()) "--" else String.format(Locale.US, "%.0f", snap.fps)
        val gpu = if (snap.gpuBusyPercent.isNaN()) "--" else String.format(Locale.US, "%.0f%%", snap.gpuBusyPercent)
        val freq = "${snap.avgFreqMhz}MHz"
        val parts = listOf(temp, "${fps}fps", "GPU $gpu")
        var x = pad
        for (p in parts) {
            canvas.drawText(p, x, y, valuePaint)
            x += valuePaint.measureText(p) + dp * 10f
        }
        canvas.drawText(freq, w - pad - smallPaint.measureText(freq), y, smallPaint)

        // 迷你曲线：整机 CPU（绿）+ 目标应用（蓝）
        val chartTop = y + dp * 8f
        val chartBottom = h - pad
        if (chartBottom - chartTop < dp * 12f) return
        canvas.drawLine(pad, chartTop, w - pad, chartTop, gridPaint)
        canvas.drawLine(pad, chartBottom, w - pad, chartBottom, gridPaint)

        val points = if (live.size > MAX_POINTS) live.subList(live.size - MAX_POINTS, live.size) else live
        if (points.size >= 2) {
            drawCurve(canvas, points, pad, chartTop, w - pad, chartBottom, ::cpuOf, curvePaint)
            drawCurve(canvas, points, pad, chartTop, w - pad, chartBottom, ::targetOf, curvePaintAlt)
        }
    }

    private fun drawCurve(
        canvas: Canvas,
        points: List<LivePoint>,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        selector: (LivePoint) -> Float,
        paint: Paint,
    ) {
        val maxValue = points.maxOfOrNull { selector(it) }?.coerceAtLeast(10f) ?: 100f
        val scaleMax = maxOf(maxValue, 100f)
        path.reset()
        val stepX = (right - left) / (points.size - 1)
        for ((i, p) in points.withIndex()) {
            val v = selector(p).coerceIn(0f, scaleMax)
            val px = left + i * stepX
            val py = bottom - (v / scaleMax) * (bottom - top)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, paint)
    }

    private fun cpuOf(p: LivePoint) = p.cpu

    private fun targetOf(p: LivePoint) = p.targetCpu

    private fun drawRight(canvas: Canvas, text: String, right: Float, y: Float, paint: Paint) {
        canvas.drawText(text, right - paint.measureText(text), y, paint)
    }

    private fun textPaint(sizeSp: Float, color: Int, mono: Boolean): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // HUD 不跟随系统字体缩放：它是固定尺寸的浮层，放大字体会直接溢出
            textSize = sizeSp * resources.displayMetrics.density
            this.color = color
            typeface = if (mono) Typeface.MONOSPACE else Typeface.DEFAULT
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (dp * 208).toInt()
        val h = (dp * 156).toInt()
        setMeasuredDimension(
            resolveSize(w, widthMeasureSpec),
            resolveSize(h, heightMeasureSpec),
        )
    }

    companion object {
        private const val MAX_POINTS = 90

        private val C_BG = 0xEE0B1116.toInt()
        private val C_BORDER = 0x334ADE80
        private val C_PRIMARY = 0xFF4ADE80.toInt()
        private val C_SECONDARY = 0xFF38BDF8.toInt()
        private val C_TEXT = 0xFFE8EEF2.toInt()
        private val C_TEXT_DIM = 0xFF8FA3AF.toInt()
        private val C_DANGER = 0xFFF87171.toInt()
        private val C_BAR_BG = 0x33FFFFFF
        private val C_GRID = 0x22FFFFFF
    }
}
