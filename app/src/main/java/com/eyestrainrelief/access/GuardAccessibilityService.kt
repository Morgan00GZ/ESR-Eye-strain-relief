package com.eyestrainrelief.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.eyestrainrelief.audio.BreakNotifier
import com.eyestrainrelief.media.MediaPauseHelper
import com.eyestrainrelief.overlay.BreakOverlay
import com.eyestrainrelief.service.MonitorService

/**
 * 守卫无障碍服务。承担四件事：
 * 1. 提供 TYPE_ACCESSIBILITY_OVERLAY 窗口（层级高于状态栏，这是普通悬浮窗做不到的）；
 * 2. 开机后由系统自动重新绑定，顺带拉起计时服务，比 BOOT_COMPLETED 更可靠；
 * 3. 浮窗期间吞掉返回与 Home；
 * 4. 浮窗期间监听来电，一响铃立即撤掉浮窗，避免遮挡来电界面。
 */
class GuardAccessibilityService : AccessibilityService() {

    companion object {
        private const val ESCAPE_PRESSES = 5
        private const val REATTACH_THROTTLE_MS = 600L
        private const val WATCHDOG_INTERVAL_MS = 30_000L
        private const val SYSTEM_UI = "com.android.systemui"

        @Volatile
        var instance: GuardAccessibilityService? = null
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlay: BreakOverlay? = null
    private var finishCallback: (() -> Unit)? = null
    private var keyFilterEnabled = false
    private var volumeDownCount = 0

    /** 上次把浮窗重新置顶的时间，用于节流 */
    private var lastReattachAt = 0L

    /** 上次检查计时服务是否存活的时间 */
    private var lastWatchdogAt = 0L

    private var callListenerRegistered = false

    /**
     * 来电阻断。浮窗层级高于来电界面，不处理会导致漏接电话，因此只在浮窗存续期间注册，
     * 一响铃立刻撤掉浮窗。无 READ_PHONE_STATE 权限时注册会抛异常，降级为窗口名检测。
     */
    @Suppress("DEPRECATION")
    private val phoneStateListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            if (state == TelephonyManager.CALL_STATE_RINGING) {
                mainHandler.post { if (overlay?.isShowing == true) dismissBreak() }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun registerCallListener() {
        if (callListenerRegistered) return
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            tm.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            callListenerRegistered = true
        } catch (_: Throwable) {
        }
    }

    @Suppress("DEPRECATION")
    private fun unregisterCallListener() {
        if (!callListenerRegistered) return
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            tm.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        } catch (_: Throwable) {
        }
        callListenerRegistered = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // 按键过滤由 XML 常驻声明，这里只同步真实状态。
        // 运行期调用 setServiceInfo 会触发系统重新绑定服务，配置被 XML 覆盖，过滤随之失效。
        keyFilterEnabled = (serviceInfo?.flags ?: 0) and
            AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS != 0
        android.util.Log.d("ESR", "service connected, keyFilter=$keyFilterEnabled")
        // 应用可能刚被系统重新绑定（开机、内存回收后），借此拉起计时服务
        MonitorService.start(this)
    }

    /**
     * 用户用手势切走时，浮窗本身不会消失，但可能被新前台的窗口压住。
     * 这里监听前台变化，一旦换到别的应用就立刻把浮窗重新挂到最上层，
     * 保证倒计时期间用户始终看到的是遮罩。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 看门狗：无障碍服务由系统托管、几乎不会被回收，用它顺带盯着计时服务，
        // 发现不在就重新拉起。这是整套保活里唯一「系统级」的一环。
        checkMonitorAlive()

        if (overlay?.isShowing != true) return
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // 来电/通话界面优先：立即撤掉浮窗，宁可少挡一次也不能漏接电话
        if (isCallUi(pkg)) {
            dismissBreak()
            return
        }
        // 本应用与系统界面不处理
        if (pkg == packageName || pkg == SYSTEM_UI) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastReattachAt < REATTACH_THROTTLE_MS) return
        lastReattachAt = now
        overlay?.bringToFront()
    }

    /** 无通话状态权限时的兜底：靠窗口包名识别来电界面 */
    private fun isCallUi(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("incallui") || p.contains("incall") || p.contains("dialer")
    }

    /**
     * 计时服务看门狗。
     *
     * 借无障碍事件的顺风车，不额外注册任何定时器：有窗口变化才检查一次，30 秒内最多一次。
     * 这里无条件拉起而不判断实例是否存在——startForegroundService 是幂等的，已在运行时
     * 只会多走一次 onStartCommand，比依赖单例引用可靠（实测用 am stopservice 停掉服务后
     * 单例判断并不可靠）。
     */
    private fun checkMonitorAlive() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWatchdogAt < WATCHDOG_INTERVAL_MS) return
        lastWatchdogAt = now
        MonitorService.start(this)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        unregisterCallListener()
        overlay?.hide()
        overlay = null
        finishCallback = null
        super.onDestroy()
    }

    fun isBreakShowing(): Boolean = overlay?.isShowing == true

    /** 返回 false 表示当前无法显示（无障碍未就绪或已在显示中） */
    fun showBreak(seconds: Int, onFinish: () -> Unit): Boolean {
        if (overlay?.isShowing == true) return false

        val view = BreakOverlay(this)
        overlay = view
        finishCallback = onFinish
        volumeDownCount = 0
        view.onPauseToggle = { paused -> MediaPauseHelper.setPaused(this, paused) }
        view.onRingBell = { BreakNotifier.notifyBreakEnd(this) }
        registerCallListener()

        val ok = view.show(seconds) { dismissBreak() }
        if (!ok) {
            unregisterCallListener()
            overlay = null
            finishCallback = null
        }
        return ok
    }

    fun dismissBreak() {
        overlay?.hide()
        overlay = null
        unregisterCallListener()
        val cb = finishCallback
        finishCallback = null
        cb?.invoke()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        android.util.Log.d("ESR", "onKeyEvent ${event.keyCode} action=${event.action} showing=${overlay?.isShowing}")
        if (overlay?.isShowing != true) return false

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    volumeDownCount++
                    if (volumeDownCount >= ESCAPE_PRESSES) {
                        volumeDownCount = 0
                        // 紧急脱出，防止异常情况下用户被锁在浮窗内
                        mainHandler.post { dismissBreak() }
                    }
                }
                return true
            }
            // 浮窗期间吞掉返回与最近任务，避免用户离开当前应用
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH -> return true
            else -> return false
        }
    }
}
