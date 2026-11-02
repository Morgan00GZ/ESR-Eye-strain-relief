package com.eyestrainrelief.attention

import android.util.Log
import com.eyestrainrelief.shell.ShizukuShell
import java.util.concurrent.Executors

/**
 * 小米 AON 注视检测。
 *
 * 系统里由 `com.xiaomi.aon` 实现 AttentionService，可通过 shell 主动触发一次检测：
 *   cmd attention call checkAttention          -> 派发成功返回 true
 *   cmd attention getLastTestCallbackCode      -> 取结果
 *
 * 实测回调码：1 = 正在注视屏幕，3 = 没在注视（脸离开摄像头范围）。
 * 任何一环取不到都返回 null，调用方回退到亮屏计时——本模块属于增强项，不是必要条件。
 */
object AttentionProbe {

    private const val TAG = "ESR"

    /** 传感器判定需要几秒，实测 3 秒可出结果，留 4 秒余量 */
    private const val CHECK_WAIT_MS = 4000L

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "attention-probe").apply { isDaemon = true }
    }

    /** true=在看，false=没在看，null=取不到 */
    fun checkAsync(onResult: (Boolean?) -> Unit) {
        executor.execute { onResult(checkBlocking()) }
    }

    private fun checkBlocking(): Boolean? {
        if (!ShizukuShell.hasPermission()) return null
        val dispatched = ShizukuShell.execSync("cmd attention call checkAttention") ?: return null
        if (!dispatched.contains("true")) return null
        try {
            Thread.sleep(CHECK_WAIT_MS)
        } catch (_: InterruptedException) {
            return null
        }
        val raw = ShizukuShell.execSync("cmd attention getLastTestCallbackCode") ?: return null
        val code = raw.trim().lineSequence().firstOrNull()?.trim()?.toIntOrNull() ?: return null
        Log.d(TAG, "attention code=$code")
        return when (code) {
            1 -> true
            3 -> false
            else -> null
        }
    }
}
