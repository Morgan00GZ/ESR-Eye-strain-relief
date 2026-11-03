package com.eyestrainrelief.core

import android.content.Context
import kotlin.math.roundToInt

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

    /**
     * 最低亮度：直接存系统亮度控制值（0–255，0 表示不启用）。
     *
     * 系统亮度条用的是感知刻度（实测约为 100·√(值/255)），与 0–255 不成线性，
     * 所以界面不再把它称作百分比，而是显示控制值本身 + 等价的亮度条位置。
     */
    var minBrightnessValue: Int
        get() {
            if (sp.contains(KEY_MIN_BRIGHTNESS_VALUE)) {
                return sp.getInt(KEY_MIN_BRIGHTNESS_VALUE, DEFAULT_MIN_BRIGHTNESS)
            }
            // 旧版本存的是百分比，按线性关系迁移成等价控制值，避免语义变更后阈值突然变暗
            val legacyPercent = sp.getInt(KEY_MIN_BRIGHTNESS_LEGACY, 0)
            if (legacyPercent <= 0) return DEFAULT_MIN_BRIGHTNESS
            val migrated = (legacyPercent / 100.0 * MAX_BRIGHTNESS)
                .roundToInt()
                .coerceIn(1, MAX_BRIGHTNESS)
            sp.edit().putInt(KEY_MIN_BRIGHTNESS_VALUE, migrated).apply()
            return migrated
        }
        set(value) = sp.edit().putInt(KEY_MIN_BRIGHTNESS_VALUE, value).apply()

    companion object {
        private const val NAME = "esr_prefs"
        private const val KEY_THRESHOLD = "threshold_ms"
        private const val KEY_OVERLAY_SEC = "overlay_seconds"
        private const val KEY_RESET_OFF_MS = "reset_off_ms"
        private const val KEY_GAZE_TIMEOUT_MS = "gaze_timeout_ms"
        private const val KEY_MIN_BRIGHTNESS_VALUE = "min_brightness_value"
        /** 旧键：百分比语义，仅用于迁移 */
        private const val KEY_MIN_BRIGHTNESS_LEGACY = "min_brightness_percent"

        const val DEFAULT_THRESHOLD_MS = 20L * 60_000L
        const val DEFAULT_OVERLAY_S = 25
        const val DEFAULT_RESET_OFF_MS = 20_000L
        const val DEFAULT_GAZE_TIMEOUT_MS = 180_000L
        /** 系统亮度控制值上限 */
        const val MAX_BRIGHTNESS = 255
        /** 默认不启用亮度守卫 */
        const val DEFAULT_MIN_BRIGHTNESS = 0
    }
}
