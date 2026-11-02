package com.eyestrainrelief.core

import android.content.Context

/**
 * 运行参数。全部可调，默认值即产品口径。
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 触发阈值：默认 20 分钟 */
    var thresholdMs: Long
        get() = sp.getLong(KEY_THRESHOLD, DEFAULT_THRESHOLD_MS)
        set(value) = sp.edit().putLong(KEY_THRESHOLD, value).apply()

    /** 浮窗持续秒数：默认 20 秒 */
    var overlaySeconds: Int
        get() = sp.getInt(KEY_OVERLAY_SEC, DEFAULT_OVERLAY_S)
        set(value) = sp.edit().putInt(KEY_OVERLAY_SEC, value).apply()

    /** 单次连续熄屏超过该时长即清零：默认 20 秒 */
    var resetOnScreenOffMs: Long
        get() = sp.getLong(KEY_RESET_OFF_MS, DEFAULT_RESET_OFF_MS)
        set(value) = sp.edit().putLong(KEY_RESET_OFF_MS, value).apply()

    /** 注视信号心跳超时：超过则判定注视源当前不可用，回退亮屏计时 */
    var gazeTimeoutMs: Long
        get() = sp.getLong(KEY_GAZE_TIMEOUT_MS, DEFAULT_GAZE_TIMEOUT_MS)
        set(value) = sp.edit().putLong(KEY_GAZE_TIMEOUT_MS, value).apply()

    /** 最低亮度百分比（0 表示不启用）。低于该值立即拉回。 */
    var minBrightnessPercent: Int
        get() = sp.getInt(KEY_MIN_BRIGHTNESS, DEFAULT_MIN_BRIGHTNESS)
        set(value) = sp.edit().putInt(KEY_MIN_BRIGHTNESS, value).apply()

    companion object {
        private const val NAME = "esr_prefs"
        private const val KEY_THRESHOLD = "threshold_ms"
        private const val KEY_OVERLAY_SEC = "overlay_seconds"
        private const val KEY_RESET_OFF_MS = "reset_off_ms"
        private const val KEY_GAZE_TIMEOUT_MS = "gaze_timeout_ms"
        private const val KEY_MIN_BRIGHTNESS = "min_brightness_percent"

        const val DEFAULT_THRESHOLD_MS = 20L * 60_000L
        const val DEFAULT_OVERLAY_S = 25
        const val DEFAULT_RESET_OFF_MS = 20_000L
        const val DEFAULT_GAZE_TIMEOUT_MS = 180_000L
        /** 默认不启用亮度守卫 */
        const val DEFAULT_MIN_BRIGHTNESS = 0
    }
}
