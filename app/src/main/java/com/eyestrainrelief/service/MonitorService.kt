package com.eyestrainrelief.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.eyestrainrelief.access.GuardAccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import com.eyestrainrelief.attention.AttentionProbe
import com.eyestrainrelief.brightness.BrightnessGuard
import com.eyestrainrelief.core.Prefs
import com.eyestrainrelief.core.SessionTimer
import com.eyestrainrelief.core.Timebase
import com.eyestrainrelief.shell.ShizukuShell
import com.eyestrainrelief.ui.MainActivity

/**
 * 常驻计时服务。
 *
 * 功耗约定：整个应用中只有这里持有一个「到期定时器」，且它只在计时真正向前推进时存在，
 * 熄屏或浮窗期间立即移除。除此之外不存在任何轮询、周期任务或 WakeLock。
 */
class MonitorService : Service() {

    companion object {
        private const val CHANNEL_ID = "esr_monitor"
        private const val NOTIFICATION_ID = 1001

        /** 注视采样间隔 */
        private const val GAZE_INTERVAL_MS = 30_000L
        /** 连续几次判定没在看才冻结计时 */
        private const val NOT_ATTENTIVE_LIMIT = 2
        /** 到点但没人在看时，推迟多久再判 */
        private const val PRESENCE_RECHECK_MS = 3 * 60_000L

        @Volatile
        var instance: MonitorService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, MonitorService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Throwable) {
                // 后台启动前台服务可能被系统限制，忽略即可，无障碍服务连接时会再次拉起
            }
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var timer: SessionTimer
    private val handler = Handler(Looper.getMainLooper())

    private var suspended = false
    private var receiverRegistered = false

    /** 最低亮度守卫 */
    private lateinit var brightnessGuard: BrightnessGuard

    /**
     * 仅浮窗存续期间持有的屏幕常亮锁。
     * 实测 FLAG_KEEP_SCREEN_ON 对无障碍浮窗不生效，必须用 WakeLock 才能保证浮窗全程可见。
     * 带超时获取，且浮窗结束立即释放，不会成为常驻唤醒源。
     */
    private var screenLock: PowerManager.WakeLock? = null

    private val dueRunnable = Runnable { onThresholdReached() }

    /** 连续判定「没人在看」的次数 */
    private var notAttentiveStreak = 0
    private var gazeSuspended = false

    /**
     * 注视采样循环。仅在屏幕亮着时运行，熄屏立即停掉。
     * 单次采样要几秒，所以用「完成后再排下一次」而不是固定节拍，避免任务堆积。
     */
    private val gazeRunnable = object : Runnable {
        override fun run() {
            if (!isScreenOn()) return
            AttentionProbe.checkAsync { attentive ->
                handler.post {
                    onGazeSample(attentive)
                    if (isScreenOn()) {
                        handler.postDelayed(this, GAZE_INTERVAL_MS)
                    }
                }
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    val now = Timebase.now()
                    // 亮屏本身就是「有人在操作」的强证据，先清掉注视暂停，
                    // 否则回来之后要等下一次采样才有 60 秒的计时空窗。
                    notAttentiveStreak = 0
                    gazeSuspended = false
                    timer.setGazeSuspended(now, false)
                    timer.onScreenOn(now)
                    refreshNotification()
                    reschedule()
                    // 屏幕亮了，恢复注视采样
                    handler.removeCallbacks(gazeRunnable)
                    handler.post(gazeRunnable)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    timer.onScreenOff(Timebase.now())
                    // 熄屏后计时停止，撤掉定时器，不做任何定时唤醒
                    handler.removeCallbacks(dueRunnable)
                    handler.removeCallbacks(gazeRunnable)
                    refreshNotification()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        // 最低亮度守卫：事件驱动，跟随亮度变化即时纠偏
        brightnessGuard = BrightnessGuard(this, prefs)
        brightnessGuard.start()
        timer = SessionTimer(
            thresholdMs = prefs.thresholdMs,
            resetOnScreenOffMs = prefs.resetOnScreenOffMs,
            gazeTimeoutMs = prefs.gazeTimeoutMs,
        )
        timer.init(Timebase.now(), isScreenOn())

        startForeground(NOTIFICATION_ID, buildNotification())
        registerScreenReceiver()
        // 启动自愈：清理旧版可能残留的锁定态，并把自身加入 Doze 白名单
        if (ShizukuShell.hasPermission()) {
            ShizukuShell.submit {
                ShizukuShell.execSync("am task lock stop")
                ShizukuShell.execSync("cmd deviceidle whitelist +$packageName")
            }
        }
        reschedule()
        // 屏幕亮着就开始注视采样；Shizuku 不可用时探测会立刻返回 null，等同不做判断
        if (isScreenOn()) handler.post(gazeRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 看门狗会周期性重复拉起本服务，这里只在参数真正变化时才做重活
        val newThreshold = prefs.thresholdMs
        val newReset = prefs.resetOnScreenOffMs
        val newGazeTimeout = prefs.gazeTimeoutMs
        val changed = newThreshold != timer.thresholdMs ||
            newReset != timer.resetOnScreenOffMs ||
            newGazeTimeout != timer.gazeTimeoutMs
        if (changed) {
            timer.thresholdMs = newThreshold
            timer.resetOnScreenOffMs = newReset
            timer.gazeTimeoutMs = newGazeTimeout
            reschedule()
        }
        // 看门狗每 30 秒拉起一次本服务，顺带刷新通知以反映无障碍开关等状态变化；
        // refreshNotification 内部会比对文案，内容没变时不会重复打扰系统。
        refreshNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        handler.removeCallbacks(dueRunnable)
        handler.removeCallbacks(gazeRunnable)
        if (this::brightnessGuard.isInitialized) brightnessGuard.stop()
        releaseScreenLock()
        if (receiverRegistered) {
            try {
                unregisterReceiver(screenReceiver)
            } catch (_: Throwable) {
            }
            receiverRegistered = false
        }
        super.onDestroy()
    }

    fun snapshot(): SessionTimer.Snapshot = timer.snapshot(Timebase.now())

    /** 供界面重置计数 */
    fun resetCounter() {
        timer.reset()
        refreshNotification()
        reschedule()
    }

    /** 供界面手动触发一次浮窗（调试用） */
    fun triggerNow(): Boolean {
        handler.removeCallbacks(dueRunnable)
        return showBreakWithGuard()
    }

    private fun onThresholdReached() {
        handler.removeCallbacks(dueRunnable)
        // 到点先确认人在不在，避免对着空屏弹窗。取不到结果就照常提醒。
        AttentionProbe.checkAsync { attentive ->
            handler.post {
                if (attentive == false) {
                    handler.postDelayed(dueRunnable, PRESENCE_RECHECK_MS)
                } else if (!showBreakWithGuard()) {
                    timer.reset()
                    reschedule()
                }
            }
        }
    }

    /**
     * 处理一次注视采样结果。
     * 连续两次判定「没在看」才冻结计时，单次抖动不算，避免用户低头看一眼键盘就被停表。
     * 任何一次判定「在看」立刻恢复。
     */
    private fun onGazeSample(attentive: Boolean?) {
        if (attentive == null) return
        if (attentive) {
            notAttentiveStreak = 0
            if (gazeSuspended) {
                gazeSuspended = false
                timer.setGazeSuspended(Timebase.now(), false)
                refreshNotification()
                reschedule()
            }
        } else {
            notAttentiveStreak++
            if (!gazeSuspended && notAttentiveStreak >= NOT_ATTENTIVE_LIMIT) {
                gazeSuspended = true
                timer.setGazeSuspended(Timebase.now(), true)
                handler.removeCallbacks(dueRunnable)
                refreshNotification()
            }
        }
    }

    /**
     * 显示浮窗并守住当前任务。
     *
     * 不做系统级锁定：`am task lock` 在澎湃OS4 上会连带锁屏（LockTask 内部调用 lockNow），
     * 有密码锁的设备无法自动恢复，实测会导致浮窗结束后停在锁屏。
     * 改为记录当前任务，用户一旦用手势离开就立即拉回，代价只是离开瞬间闪一下，
     * 但不会锁屏，也不影响媒体播放状态。
     */
    private fun showBreakWithGuard(): Boolean {
        val guard = GuardAccessibilityService.instance
        if (guard == null) {
            notifyGuardMissing()
            timer.reset()
            reschedule()
            return false
        }
        val seconds = prefs.overlaySeconds
        suspended = true
        val shown = guard.showBreak(seconds) { endBreak() }
        if (!shown) {
            suspended = false
            reschedule()
            return false
        }
        acquireScreenLock(seconds)
        return true
    }

    private fun acquireScreenLock(seconds: Int) {
        try {
            releaseScreenLock()
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            screenLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                "ESR:break"
            ).apply {
                setReferenceCounted(false)
                acquire((seconds + 15) * 1000L)
            }
        } catch (_: Throwable) {
        }
    }

    private fun releaseScreenLock() {
        try {
            screenLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {
        }
        screenLock = null
    }

    private fun endBreak() {
        suspended = false
        releaseScreenLock()
        // 浮窗结束说明用户回来了，清掉注视暂停状态并重新计时
        notAttentiveStreak = 0
        gazeSuspended = false
        timer.setGazeSuspended(Timebase.now(), false)
        // 浮窗期间属于休息，不计入使用时长
        timer.reset()
        refreshNotification()
        reschedule()
    }

    /**
     * 重新挂载唯一的那一个到期定时器。
     * 只在「正在累积」时才存在，其余时刻不存在任何待执行的延迟任务。
     */
    private fun reschedule() {
        handler.removeCallbacks(dueRunnable)
        if (suspended) return
        val snap = timer.snapshot(Timebase.now())
        if (!snap.accumulating) return
        if (snap.remainingMs <= 0L) {
            handler.post(dueRunnable)
            return
        }
        handler.postDelayed(dueRunnable, snap.remainingMs)
    }

    private fun isScreenOn(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isInteractive
    }

    private fun registerScreenReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(
            this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, "屏幕计时", NotificationManager.IMPORTANCE_MIN
        ).apply {
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        createChannel()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("屏幕休息提醒")
            .setContentText("正在统计屏幕使用时长")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .build()
    }

    /** 上次推送的通知文案，内容不变时不重复 notify */
    private var lastNotificationText: String? = null

    /** 本应用的无障碍服务是否真的处于启用状态（被用户关掉时整个功能会静默失效） */
    private fun isGuardEnabled(): Boolean {
        return try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                ?: return false
            if (!am.isEnabled) return false
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo?.serviceInfo?.packageName == packageName }
        } catch (_: Throwable) {
            false
        }
    }

    private fun refreshNotification() {
        val snap = timer.snapshot(Timebase.now())
        val usedMin = snap.effectiveMs / 60_000L
        val leftMin = snap.remainingMs / 60_000L
        val text = when {
            !isGuardEnabled() -> "无障碍服务未开启，浮窗无法显示"
            snap.gazeSuspended -> "未检测到注视，已暂停（已用 ${usedMin} 分钟）"
            snap.accumulating -> "已用 ${usedMin} 分钟，距提醒约 ${leftMin} 分钟"
            else -> "已用 ${usedMin} 分钟（屏幕熄灭，暂停计时）"
        }
        // 文案没变就不重复通知，避免看门狗每 30 秒刷一次系统通知
        if (text == lastNotificationText) return
        lastNotificationText = text
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentTitle("屏幕休息提醒")
                .setContentText(text)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
        )
    }

    private fun notifyGuardMissing() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            NOTIFICATION_ID + 1,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("无法显示休息浮窗")
                .setContentText("请在系统设置中开启 ESR 的无障碍服务")
                .setAutoCancel(true)
                .build()
        )
    }
}
