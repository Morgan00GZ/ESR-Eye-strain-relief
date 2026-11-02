package com.eyestrainrelief.core

import android.os.SystemClock

/**
 * 统一时间基准。全部使用 elapsedRealtime，不受用户改系统时间、时区切换影响。
 */
object Timebase {
    fun now(): Long = SystemClock.elapsedRealtime()
}
