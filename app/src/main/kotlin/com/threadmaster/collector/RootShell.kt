package com.threadmaster.collector

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.util.concurrent.TimeUnit

/**
 * root shell 的唯一入口。
 *
 * 两条原则：
 * 1. 采集必须走**一条常驻会话**。每次采样 fork 一次 `su` 会带来几十毫秒的
 *    fork/授权开销，等于监控器自己在制造卡顿和发热，数据也就失真了；
 * 2. 短命令（探测 root、写配置、查 pid）可以另开会话，但必须带超时，
 *    否则用户没点授权弹窗时会永久挂住。
 */
class RootShell(private val context: Context) {

    data class ShellResult(val ok: Boolean, val code: Int, val output: String)

    /** 探测 root 是否可用。输出 `uid=0` 才认为拿到了真正的 root shell。 */
    suspend fun checkRoot(): Boolean {
        val r = exec("id")
        return r.ok && r.output.contains("uid=0")
    }

    /**
     * 把采集脚本写入 /data/local/tmp。
     * 用 base64 传输而不是直接 heredoc，避免脚本里的引号、`$`、反引号被 shell 提前解释。
     */
    suspend fun installScript(): Boolean {
        val source = runCatching {
            context.assets.open("collect.sh").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull() ?: return false

        val b64 = Base64.encodeToString(source.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val cmd = buildString {
            append("echo '").append(b64).append("' | base64 -d > ").append(SCRIPT_PATH)
            append(" && chmod 755 ").append(SCRIPT_PATH)
            append(" && echo TM_INSTALL_OK")
        }
        val r = exec(cmd, timeoutMs = 15_000)
        return r.ok && r.output.contains("TM_INSTALL_OK")
    }

    /** 更新采集目标。不需要重启会话，采集脚本每轮都会重读该文件。 */
    suspend fun writeConfig(packageName: String, intervalSec: Double, fpsEnabled: Boolean): Boolean {
        val interval = String.format(java.util.Locale.US, "%.2f", intervalSec)
        val cmd = buildString {
            append("printf 'PKG=%s\\nINTERVAL=%s\\nFPS=%s\\n' ")
            append("'").append(sanitize(packageName)).append("' ")
            append("'").append(interval).append("' ")
            append("'").append(if (fpsEnabled) "1" else "0").append("' ")
            append("> ").append(CONFIG_PATH)
            append(" && echo TM_CFG_OK")
        }
        val r = exec(cmd)
        return r.ok && r.output.contains("TM_CFG_OK")
    }

    /** 启动采集会话，返回可逐行读取的会话对象。 */
    fun launch(): MonitorSession {
        val process = ProcessBuilder("su", "-c", "exec sh $SCRIPT_PATH")
            .redirectErrorStream(false)
            .start()
        return MonitorSession(process)
    }

    /** 停止采集：先按 pid 文件精确 kill，再兜底销毁进程，避免残留 sh 继续占 CPU。 */
    suspend fun stopSession(session: MonitorSession?) {
        runCatching { session?.process?.destroy() }
        exec("kill -9 \$(cat $PID_PATH 2>/dev/null) 2>/dev/null; rm -f $PID_PATH; echo TM_STOP_OK", timeoutMs = 4000)
    }

    /**
     * 帧率诊断：把 measured_fps 节点、SurfaceFlinger 图层列表和 latency 原始输出一次性取回来，
     * 方便在真机上判断到底卡在哪一步。
     */
    suspend fun diagnoseFps(pkg: String): String {
        val p = sanitize(pkg)
        val cmd = "echo '# measured_fps'; " +
            "for f in /sys/class/drm/sde-crtc-0/measured_fps /sys/class/drm/card0/sde-crtc-0/measured_fps /sys/class/graphics/fb0/measured_fps; do " +
            "[ -r \"\$f\" ] && echo \"\$f = \$(cat \"\$f\" 2>/dev/null)\"; done; " +
            "echo '# layers'; dumpsys SurfaceFlinger --list 2>/dev/null | grep -F '$p' | head -n 6; " +
            "echo '# latency'; dumpsys SurfaceFlinger --list 2>/dev/null | grep -F '$p' | head -n 2 | while IFS= read -r l; do " +
            "echo \"--- \$l\"; dumpsys SurfaceFlinger --latency \"\$l\" 2>&1 | head -n 4; done"
        return exec(cmd, 20_000).output
    }

    /** 查询包名对应的主进程 pid，没有运行返回 0。 */
    suspend fun pidOf(packageName: String): Int {
        val r = exec("pidof ${sanitize(packageName)}")
        return r.output.split(' ', '\n').firstNotNullOfOrNull { it.trim().toIntOrNull() } ?: 0
    }

    private suspend fun exec(command: String, timeoutMs: Long = 8000): ShellResult {
        return withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                var process: Process? = null
                try {
                    process = ProcessBuilder("su", "-c", command)
                        .redirectErrorStream(true)
                        .start()
                    process.outputStream.close()
                    val out = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                    if (!finished) {
                        return@withContext ShellResult(false, -1, "执行超时")
                    }
                    ShellResult(process.exitValue() == 0, process.exitValue(), out.trim())
                } catch (e: Exception) {
                    ShellResult(false, -1, e.message ?: "su 调用失败")
                } finally {
                    runCatching { process?.destroy() }
                }
            }
        } ?: ShellResult(false, -1, "su 未响应")
    }

    /** 包名只允许字母数字点下划线，杜绝任何被拼进 shell 的可能性。 */
    private fun sanitize(text: String): String = text.filter { it.isLetterOrDigit() || it == '.' || it == '_' }

    class MonitorSession(val process: Process) {
        private val reader: BufferedReader = process.inputStream.bufferedReader(Charsets.UTF_8)
        private val errReader: BufferedReader = process.errorStream.bufferedReader(Charsets.UTF_8)

        /** 读取一行采集输出，流结束返回 null。 */
        fun readLine(): String? = try {
            reader.readLine()
        } catch (e: Exception) {
            null
        }

        /** 消费 stderr，避免缓冲区写满把采集脚本卡死。 */
        fun drainError(): String = try {
            errReader.readLine() ?: ""
        } catch (e: Exception) {
            ""
        }

        fun isAlive(): Boolean = process.isAlive
    }

    companion object {
        private const val SCRIPT_PATH = "/data/local/tmp/threadmaster_collect.sh"
        private const val CONFIG_PATH = "/data/local/tmp/threadmaster.cfg"
        private const val PID_PATH = "/data/local/tmp/threadmaster.pid"
    }
}
