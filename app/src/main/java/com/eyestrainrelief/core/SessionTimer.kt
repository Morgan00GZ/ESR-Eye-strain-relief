package com.eyestrainrelief.core

/** 当前正在累积的是哪一路时长 */
enum class TimingPhase { IDLE, SCREEN, GAZE }

/**
 * 亮屏 / 注视时长状态机。
 *
 * 功耗设计要点：
 * 1. 不做任何周期轮询。累计靠「区间结算」—— 状态变化时把上一段区间按当时的状态一次性补上。
 * 2. 熄屏超过阈值即清零的判定是「惰性求值」：熄屏时只记下时刻，下次亮屏时才比较差值，
 *    熄屏期间不需要任何定时器或闹钟去唤醒 CPU。
 * 3. 调用方只需在真实事件发生时调用本类方法，空闲时本类不产生任何 CPU 唤醒。
 *
 * 时长口径：注视时长必然是亮屏时长的子集。注视源可用时以注视时长为准，
 * 不可用（未接入 / 心跳超时）时回退为亮屏时长。
 */
class SessionTimer(
    var thresholdMs: Long,
    var resetOnScreenOffMs: Long,
    var gazeTimeoutMs: Long,
) {

    data class Snapshot(
        val phase: TimingPhase,
        val screenMs: Long,
        val gazeMs: Long,
        val effectiveMs: Long,
        val remainingMs: Long,
        val accumulating: Boolean,
        val gazeActive: Boolean,
        val gazeSuspended: Boolean,
    )

    var phase: TimingPhase = TimingPhase.IDLE
        private set

    private var screenMs = 0L
    private var gazeMs = 0L

    private var lastSettleAt = 0L
    private var screenOnAt = 0L
    private var offAt = 0L
    private var lastGazeAt = 0L
    private var gazeFresh = false
    private var initialized = false

    /**
     * 注视判定为「人没在看」时冻结计时。
     * 屏幕仍亮着但用户已经不在看，这段时间不该算作使用时长。
     */
    private var gazeSuspended = false

    val accumulating: Boolean get() = phase != TimingPhase.IDLE && !gazeSuspended
    val effectiveMs: Long get() = if (phase == TimingPhase.GAZE) gazeMs else screenMs
    val remainingMs: Long get() = (thresholdMs - effectiveMs).coerceAtLeast(0L)

    /** 服务启动时同步一次真实屏幕状态，避免从错误状态开始积分 */
    fun init(now: Long, screenIsOn: Boolean) {
        initialized = true
        lastSettleAt = now
        offAt = 0L
        screenOnAt = if (screenIsOn) now else 0L
        gazeFresh = false
        lastGazeAt = 0L
        refreshPhase()
    }

    fun onScreenOn(now: Long): Boolean {
        if (!initialized) {
            init(now, true); return false
        }
        settle(now)
        val offDuration = if (offAt > 0L) now - offAt else 0L
        val shouldReset = offAt > 0L && offDuration >= resetOnScreenOffMs
        if (shouldReset) {
            screenMs = 0L
            gazeMs = 0L
        }
        offAt = 0L
        screenOnAt = now
        refreshPhase()
        return shouldReset
    }

    fun onScreenOff(now: Long) {
        if (!initialized) {
            init(now, false); return
        }
        settle(now)
        screenOnAt = 0L
        offAt = now
        gazeFresh = false
        phase = TimingPhase.IDLE
    }

    fun onGazeSignal(now: Long, active: Boolean) {
        if (!initialized) {
            init(now, true)
        }
        settle(now)
        lastGazeAt = now
        gazeFresh = active
        refreshPhase()
    }

    /** 由外部注视采样驱动：暂停或恢复计时。切换前先把已发生的区间结算掉。 */
    fun setGazeSuspended(now: Long, suspended: Boolean) {
        if (!initialized) {
            init(now, false)
        }
        settle(now)
        gazeSuspended = suspended
    }

    /** 结算到此刻，返回快照。幂等，可随时调用。 */
    fun snapshot(now: Long): Snapshot {
        if (!initialized) {
            init(now, false)
        }
        settle(now)
        return Snapshot(
            phase = phase,
            screenMs = screenMs,
            gazeMs = gazeMs,
            effectiveMs = effectiveMs,
            remainingMs = remainingMs,
            accumulating = accumulating,
            gazeActive = gazeFresh,
            gazeSuspended = gazeSuspended,
        )
    }

    fun reset() {
        screenMs = 0L
        gazeMs = 0L
        lastSettleAt = Timebase.now()
    }

    private fun settle(now: Long) {
        if (now <= lastSettleAt) {
            lastSettleAt = maxOf(lastSettleAt, now)
            return
        }
        if (phase == TimingPhase.GAZE && gazeFresh) {
            val validUntil = lastGazeAt + gazeTimeoutMs
            if (now > validUntil) {
                // 心跳超时：有效段仍算注视，超出部分回退为亮屏时长
                accumulate(lastSettleAt, validUntil, TimingPhase.GAZE)
                accumulate(validUntil, now, TimingPhase.SCREEN)
                gazeFresh = false
                phase = TimingPhase.SCREEN
                lastSettleAt = now
                return
            }
        }
        accumulate(lastSettleAt, now, phase)
        lastSettleAt = now
    }

    private fun accumulate(from: Long, to: Long, p: TimingPhase) {
        if (gazeSuspended) return
        val dt = to - from
        if (dt <= 0L) return
        when (p) {
            TimingPhase.GAZE -> {
                gazeMs += dt
                screenMs += dt
            }
            TimingPhase.SCREEN -> screenMs += dt
            TimingPhase.IDLE -> Unit
        }
    }

    private fun refreshPhase() {
        phase = when {
            screenOnAt == 0L -> TimingPhase.IDLE
            gazeFresh -> TimingPhase.GAZE
            else -> TimingPhase.SCREEN
        }
    }
}
