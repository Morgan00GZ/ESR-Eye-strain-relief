package com.eyestrainrelief.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.eyestrainrelief.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全屏纯色休息浮窗。
 *
 * 使用 TYPE_ACCESSIBILITY_OVERLAY，层级高于状态栏与导航栏，因此下拉通知栏也会被遮住。
 * 窗口可触摸但不可聚焦：触摸被本窗口消费，下层应用收不到；按键由无障碍服务的
 * onKeyEvent 统一处理，不依赖窗口焦点。
 *
 * 持有 FLAG_KEEP_SCREEN_ON：浮窗必须全程可见。否则 20 秒无操作会触发系统熄屏，
 * 用户回来看到的是锁屏而不是原应用，紧急脱出也会被误判为失败。
 *
 * 倒计时以「到期时刻」为准逐次修正，而非累加 1 秒，避免任何一次延迟造成漂移。
 */
class BreakOverlay(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var root: LinearLayout? = null
    private var numberView: TextView? = null
    private var clockDateView: TextView? = null
    private var clockTimeView: TextView? = null
    private var pauseButton: TextView? = null
    private var lastParams: WindowManager.LayoutParams? = null
    private var endAt = 0L
    private var finished = false
    private var paused = false
    private var onFinish: (() -> Unit)? = null

    /** 上次刷新时钟的整秒，同一秒内的多次 tick 不重复写文本 */
    private var lastClockSecond = -1L

    // 格式串取自资源：中文「yyyy年M月d日 EEEE / a h:mm:ss」，英文「yyyy-MM-dd EEEE / h:mm:ss a」
    // locale 用系统默认值，这样星期名、AM/PM 才会与界面对应语言一致
    private val dateFormat = SimpleDateFormat(
        context.getString(R.string.date_pattern), Locale.getDefault()
    )
    private val timeFormat = SimpleDateFormat(
        context.getString(R.string.time_pattern), Locale.getDefault()
    )

    /** 暂停按钮状态变化：true 表示已请求暂停活动应用 */
    var onPauseToggle: ((Boolean) -> Unit)? = null

    /** 倒计时自然走完时触发（紧急脱出不触发），用于播放提示音 */
    var onRingBell: (() -> Unit)? = null

    val isShowing: Boolean get() = root != null

    private val tickRunnable = object : Runnable {
        override fun run() {
            updateClock()
            val remainMs = endAt - SystemClock.elapsedRealtime()
            val seconds = ((remainMs + 999L) / 1000L).toInt().coerceAtLeast(0)
            numberView?.text = seconds.toString()
            if (remainMs <= 0L) {
                finish()
            } else {
                handler.postDelayed(this, 250L)
            }
        }
    }

    /** 时钟每秒刷新一次：同一秒内多次 tick 不重复写文本，避免无谓重绘 */
    private fun updateClock() {
        val now = System.currentTimeMillis()
        val second = now / 1000L
        if (second == lastClockSecond) return
        lastClockSecond = second
        val date = Date(now)
        clockDateView?.text = dateFormat.format(date)
        clockTimeView?.text = timeFormat.format(date)
    }

    fun show(totalSeconds: Int, onFinish: () -> Unit): Boolean {
        if (root != null) return false
        this.onFinish = onFinish
        this.finished = false
        this.paused = false
        this.endAt = SystemClock.elapsedRealtime() + totalSeconds * 1000L

        val view = buildView()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        return try {
            windowManager.addView(view, lp)
            root = view
            lastParams = lp
            numberView = view.findViewWithTag(TAG_NUMBER)
            clockDateView = view.findViewWithTag(TAG_CLOCK_DATE)
            clockTimeView = view.findViewWithTag(TAG_CLOCK_TIME)
            pauseButton = view.findViewWithTag(TAG_PAUSE)
            lastClockSecond = -1L
            updateClock()
            numberView?.text = totalSeconds.toString()
            handler.post(tickRunnable)
            true
        } catch (t: Throwable) {
            root = null
            lastParams = null
            numberView = null
            clockDateView = null
            clockTimeView = null
            pauseButton = null
            false
        }
    }

    /**
     * 把浮窗重新挂到最上层。
     *
     * 用户用手势离开原应用时浮窗并不会消失，但可能被新前台的窗口压住。
     * 同层级窗口的叠放顺序由添加顺序决定，因此先移除再添加即可重新置顶。
     * 倒计时状态不受影响，只是窗口重新挂载一次。
     */
    fun bringToFront() {
        val view = root ?: return
        val params = lastParams ?: return
        try {
            windowManager.removeViewImmediate(view)
        } catch (_: Throwable) {
        }
        try {
            windowManager.addView(view, params)
        } catch (t: Throwable) {
            android.util.Log.w("ESR", "reattach overlay failed", t)
        }
    }

    fun hide() {
        handler.removeCallbacks(tickRunnable)
        // 浮窗无论以何种方式结束，都要把已暂停的活动应用恢复回去
        if (paused) {
            paused = false
            onPauseToggle?.invoke(false)
        }
        val view = root ?: return
        root = null
        lastParams = null
        numberView = null
        clockDateView = null
        clockTimeView = null
        pauseButton = null
        try {
            windowManager.removeView(view)
        } catch (_: Throwable) {
        }
    }

    private fun finish() {
        if (finished) return
        finished = true
        handler.removeCallbacks(tickRunnable)
        onRingBell?.invoke()
        hide()
        val cb = onFinish
        onFinish = null
        cb?.invoke()
    }

    private fun togglePause() {
        paused = !paused
        pauseButton?.text = context.getString(
            if (paused) R.string.overlay_resume else R.string.overlay_pause
        )
        pauseButton?.alpha = if (paused) 0.7f else 1f
        onPauseToggle?.invoke(paused)
    }

    private fun buildView(): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(BACKGROUND)
            // 可点击但不拦截分发：空白区域吞掉触摸，子按钮仍能正常响应
            isClickable = true
            isFocusable = false
            contentDescription = context.getString(R.string.overlay_content_desc)
        }

        // 时间两行：亮度取「休息一下」同款灰，21sp（在 15sp 基础上放大 40%）
        val clockDate = TextView(context).apply {
            tag = TAG_CLOCK_DATE
            setTextColor(HINT_COLOR)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 21f)
            gravity = Gravity.CENTER
            text = dateFormat.format(Date())
        }

        val clockTime = TextView(context).apply {
            tag = TAG_CLOCK_TIME
            setTextColor(HINT_COLOR)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 21f)
            gravity = Gravity.CENTER
            // 等宽字体：秒数跳动时字符宽度一致，文本不会左右抖动
            typeface = Typeface.MONOSPACE
            text = timeFormat.format(Date())
        }

        val number = TextView(context).apply {
            tag = TAG_NUMBER
            setTextColor(Color.WHITE)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 132f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            text = "0"
        }

        val hint = TextView(context).apply {
            setTextColor(HINT_COLOR)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
            text = context.getString(R.string.overlay_hint)
        }

        val pause = TextView(context).apply {
            tag = TAG_PAUSE
            setTextColor(Color.WHITE)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            text = context.getString(R.string.overlay_pause)
            background = GradientDrawable().apply {
                cornerRadius = dp(32).toFloat()
                setColor(BUTTON_BG)
                setStroke(dp(1), BUTTON_STROKE)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { togglePause() }
        }

        container.addView(
            clockDate,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            clockTime,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        )
        container.addView(
            number,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20) }
        )
        container.addView(
            hint,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(18) }
        )
        container.addView(
            pause,
            LinearLayout.LayoutParams(dp(168), dp(62)).apply { topMargin = dp(30) }
        )
        return container
    }

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG_NUMBER = "break_number"
        const val TAG_CLOCK_DATE = "break_clock_date"
        const val TAG_CLOCK_TIME = "break_clock_time"
        const val TAG_PAUSE = "break_pause"
        // 近黑背景：OLED 屏幕上像素近乎不发光，强制视觉与功耗两头都占
        val BACKGROUND = Color.parseColor("#0B0D10")
        val HINT_COLOR = Color.parseColor("#6E7681")
        val BUTTON_BG = Color.parseColor("#1B2027")
        val BUTTON_STROKE = Color.parseColor("#333B46")
    }
}
