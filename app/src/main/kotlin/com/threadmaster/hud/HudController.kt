package com.threadmaster.hud

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import com.threadmaster.MainActivity
import com.threadmaster.collector.MonitorEngine
import com.threadmaster.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 悬浮窗的挂载与拖动逻辑。
 *
 * 触摸策略：按下时记录起点，移动超过阈值才算拖动，否则松手当作点击打开主界面。
 * 这样在游戏里误触 HUD 不会直接跳出去，只能先把它拖开。
 */
class HudController(
    private val context: Context,
    private val engine: MonitorEngine,
    private val settings: SettingsStore,
) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var view: HudView? = null
    private var params: WindowManager.LayoutParams? = null
    private var job: Job? = null

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false

    fun attach() {
        if (view != null) return
        // 每次挂载都重建作用域：detach 时已经 cancel，复用会得到一个永远不会执行收集的 scope
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val hud = HudView(context)
        val density = context.resources.displayMetrics.density
        val width = (density * 208).toInt()
        val lp = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = context.resources.displayMetrics.widthPixels - width - (density * 12).toInt()
            y = (density * 96).toInt()
            alpha = settings.state.value.hudAlpha
        }

        hud.setOnTouchListener { _, event -> onTouch(event, hud) }
        runCatching { windowManager.addView(hud, lp) }
        view = hud
        params = lp

        job = scope.launch {
            combine(engine.snapshot, engine.live) { snap, live -> snap to live }
                .collect { (snap, live) -> hud.update(snap, live) }
        }
    }

    fun detach() {
        job?.cancel()
        scope.cancel()
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        params = null
    }

    private fun onTouch(event: MotionEvent, hud: HudView): Boolean {
        val lp = params ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                startX = lp.x
                startY = lp.y
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!dragging && (abs(dx) > dp(6) || abs(dy) > dp(6))) dragging = true
                if (dragging) {
                    lp.x = startX + dx.toInt()
                    lp.y = startY + dy.toInt()
                    runCatching { windowManager.updateViewLayout(hud, lp) }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    val intent = Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    runCatching { context.startActivity(intent) }
                }
                return true
            }
        }
        return false
    }

    private fun dp(v: Int): Float = v * context.resources.displayMetrics.density
}
