package com.threadmaster.hud

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
 * 悬浮窗的挂载、拖动与展开逻辑。
 *
 * 触摸策略：
 * - 移动超过阈值算拖动，只挪位置；
 * - 单击（300ms 内没有第二次点击）切换紧凑/展开；
 * - 双击回到主界面。
 *
 * 这样在游戏里误触不会直接跳出去，展开也只是多占一块区域。
 */
class HudController(
    private val context: Context,
    private val engine: MonitorEngine,
    private val settings: SettingsStore,
) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var view: HudView? = null
    private var params: WindowManager.LayoutParams? = null
    private var job: Job? = null

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private var lastTapAt = 0L
    private var pendingSingle: Runnable? = null

    fun attach() {
        if (view != null) return
        // 每次挂载都重建作用域：detach 时已经 cancel，复用会得到一个永远不会执行收集的 scope
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val hud = HudView(context)
        val density = context.resources.displayMetrics.density
        val width = (density * 208).toInt()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
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
        pendingSingle?.let { handler.removeCallbacks(it) }
        pendingSingle = null
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
                if (!dragging) handleTap(hud)
                return true
            }
        }
        return false
    }

    /** 手动区分单击与双击，避免 GestureDetector 在展开时固定 300ms 的迟滞。 */
    private fun handleTap(hud: HudView) {
        val now = SystemClock.uptimeMillis()
        if (now - lastTapAt < 300L) {
            pendingSingle?.let { handler.removeCallbacks(it) }
            pendingSingle = null
            lastTapAt = 0
            openApp()
            return
        }
        lastTapAt = now
        val action = Runnable {
            pendingSingle = null
            toggleExpanded(hud)
        }
        pendingSingle = action
        handler.postDelayed(action, 300)
    }

    private fun toggleExpanded(hud: HudView) {
        hud.toggleExpanded()
        val lp = params ?: return
        // 展开后如果右边放不下，就往左推一推
        val screenWidth = context.resources.displayMetrics.widthPixels
        val needed = if (hud.expanded) (dp(300)).toInt() else (dp(208)).toInt()
        if (lp.x + needed > screenWidth) {
            lp.x = (screenWidth - needed).coerceAtLeast(0)
        }
        runCatching { windowManager.updateViewLayout(hud, lp) }
    }

    private fun openApp() {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { context.startActivity(intent) }
    }

    private fun dp(v: Int): Float = v * context.resources.displayMetrics.density
}
