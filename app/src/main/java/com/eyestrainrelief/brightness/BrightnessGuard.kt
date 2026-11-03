package com.eyestrainrelief.brightness

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.eyestrainrelief.core.Prefs
import com.eyestrainrelief.shell.ShizukuShell
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 最低亮度守卫。
 *
 * 用 ContentObserver 监听 SCREEN_BRIGHTNESS，属于事件驱动，没有任何轮询开销：
 * 只有亮度真的变化时才醒一次，值低于阈值就写回。
 *
 * 阈值直接是系统亮度控制值（0–255），不是百分比。原因：系统亮度条用感知刻度
 * （实机实测约为 100·√(值/255)），0–255 与条上位置差得很远，把它当百分比会误导。
 *
 * 写入走双通道：优先 WRITE_SETTINGS（无 IPC 延迟，拖动滑块时不会卡），
 * 没有该权限则退回 Shizuku 执行 settings 命令。
 *
 * 注意：自动亮度模式下系统会按环境光持续覆盖，本守卫会与其形成拉锯——
 * 每次被调低都会被拉回，用户不会看到低于阈值，但亮度可能在阈值附近小幅波动。
 */
class BrightnessGuard(
    private val context: Context,
    private val prefs: Prefs
) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            enforce()
        }
    }

    fun start() {
        if (registered) return
        try {
            appContext.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                false,
                observer
            )
            registered = true
            // 启动时先纠正一次当前值
            enforce()
        } catch (t: Throwable) {
            Log.w(TAG, "register brightness observer failed", t)
        }
    }

    fun stop() {
        if (!registered) return
        try {
            appContext.contentResolver.unregisterContentObserver(observer)
        } catch (_: Throwable) {
        }
        registered = false
    }

    /**
     * 检查并拉回。幂等：写回后会再次触发 observer，此时值已达标直接返回，不会形成死循环。
     */
    fun enforce() {
        val minValue = prefs.minBrightnessValue
        if (minValue <= 0) return
        val floor = minValue.coerceIn(1, Prefs.MAX_BRIGHTNESS)
        val current = readCurrent()
        if (current < 0 || current >= floor) return
        Log.d(TAG, "brightness $current below min $floor, restoring")
        write(floor)
    }

    private fun readCurrent(): Int = try {
        Settings.System.getInt(
            appContext.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            -1
        )
    } catch (_: Throwable) {
        -1
    }

    private fun write(value: Int): Boolean {
        if (canWriteDirectly()) {
            return try {
                Settings.System.putInt(
                    appContext.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    value
                )
            } catch (_: Throwable) {
                false
            }
        }
        if (ShizukuShell.hasPermission()) {
            ShizukuShell.execAsync("settings put system screen_brightness $value")
            return true
        }
        return false
    }

    /** 是否具备直接写系统设置的权限 */
    fun canWriteDirectly(): Boolean = try {
        Settings.System.canWrite(appContext)
    } catch (_: Throwable) {
        false
    }

    /** 当前系统亮度控制值（0–255），读不到返回 -1 */
    fun readCurrentValue(): Int = readCurrent()

    companion object {
        private const val TAG = "ESR"

        /**
         * 把亮度控制值换算成亮度条上的位置（0–100），仅用于界面显示。
         *
         * 系统亮度条不是线性刻度：实测机型（HyperOS 4）上约为 100·√(值/255)，
         * 控制值 64 只走到亮度条的一半。原生 Android 是线性刻度，此处会偏高，
         * 所以界面上标注「约」。本换算不参与任何判定逻辑。
         */
        fun estimateBarPercent(value: Int): Int {
            if (value <= 0) return 0
            val ratio = value.coerceAtMost(Prefs.MAX_BRIGHTNESS).toDouble() / Prefs.MAX_BRIGHTNESS
            return (100.0 * sqrt(ratio)).roundToInt().coerceIn(1, 100)
        }
    }
}
