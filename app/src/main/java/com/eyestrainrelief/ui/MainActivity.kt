package com.eyestrainrelief.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.eyestrainrelief.R
import android.widget.Toast
import com.eyestrainrelief.access.GuardAccessibilityService
import com.eyestrainrelief.core.Prefs
import com.eyestrainrelief.service.MonitorService
import com.eyestrainrelief.shell.ShizukuShell
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var accessibilityValue: TextView
    private lateinit var shizukuValue: TextView
    private lateinit var usedValue: TextView
    private lateinit var leftValue: TextView
    private lateinit var phaseValue: TextView
    private lateinit var brightnessValue: TextView
    private lateinit var minBrightnessValue: TextView
    private lateinit var writeSettingsValue: TextView
    private lateinit var thresholdInput: EditText
    private lateinit var brightnessInput: EditText

    /** 状态值统一配色：正常用绿、异常用橙。新增状态行必须复用这两个，不要另起颜色。 */
    private val colorOk = Color.parseColor("#1D9E75")
    private val colorWarn = Color.parseColor("#D85A30")

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == ShizukuShell.PERMISSION_REQUEST_CODE) {
                refreshStatus()
                toast(
                    if (grantResult == PackageManager.PERMISSION_GRANTED)
                        getString(R.string.toast_shizuku_granted)
                    else getString(R.string.toast_shizuku_denied)
                )
            }
        }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            // 界面只在前台时刷新，每秒更新一处文本的开销远小于屏幕本身，
            // 秒数逐秒跳动也更符合直觉
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
        hideFromRecents()
        requestPermissionsIfNeeded()
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Throwable) {
        }
        MonitorService.start(this)
    }

    /**
     * 让本应用不出现在「最近任务」里。
     *
     * 清单里的 android:excludeFromRecents 对 singleTask 无效——任务被复用时不会重新套用属性，
     * 实测仍会出现在最近任务中，只能在运行时对已存在的任务逐个设置。
     */
    private fun hideFromRecents() {
        try {
            getSystemService(ActivityManager::class.java)
                ?.appTasks
                ?.forEach { it.setExcludeFromRecents(true) }
        } catch (_: Throwable) {
        }
    }

    override fun onResume() {
        super.onResume()
        hideFromRecents()
        refreshStatus()
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        // 界面不可见时停止刷新，避免任何无意义的周期任务
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshRunnable)
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(40), dp(24), dp(40))
            setBackgroundColor(Color.WHITE)
        }

        root.addView(text("ESR", 26f, Color.parseColor("#111111"), bold = true))
        root.addView(
            text(
                getString(R.string.intro_main),
                13f, Color.parseColor("#6E7681")
            ).apply { setPadding(0, dp(8), 0, dp(6)) }
        )
        root.addView(
            text(
                getString(R.string.intro_escape),
                12f, Color.parseColor("#8A8A8A")
            ).apply { setPadding(0, 0, 0, dp(6)) }
        )
        root.addView(
            text(
                getString(R.string.intro_brightness),
                12f, Color.parseColor("#8A8A8A")
            ).apply { setPadding(0, 0, 0, dp(20)) }
        )

        root.addView(card {
            accessibilityValue = row(it, getString(R.string.label_accessibility))
            shizukuValue = row(it, getString(R.string.label_shizuku))
            usedValue = row(it, getString(R.string.label_used))
            leftValue = row(it, getString(R.string.label_until_break))
            phaseValue = row(it, getString(R.string.label_phase))
            brightnessValue = row(it, getString(R.string.label_brightness))
            minBrightnessValue = row(it, getString(R.string.label_min_brightness))
            writeSettingsValue = row(it, getString(R.string.label_write_settings))
        })

        root.addView(
            actionButton(getString(R.string.btn_enable_accessibility)) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        )
        root.addView(
            actionButton(getString(R.string.btn_grant_shizuku)) {
                when {
                    !ShizukuShell.isAvailable() ->
                        toast(getString(R.string.toast_shizuku_missing))
                    ShizukuShell.hasPermission() ->
                        toast(getString(R.string.toast_shizuku_granted))
                    else ->
                        ShizukuShell.requestPermission()
                }
            }
        )
        root.addView(
            actionButton(getString(R.string.btn_test_overlay)) {
                val ok = MonitorService.instance?.triggerNow() ?: false
                if (!ok) toast(getString(R.string.toast_need_accessibility))
            }
        )
        root.addView(
            actionButton(getString(R.string.btn_reset)) {
                MonitorService.instance?.resetCounter()
                toast(getString(R.string.toast_reset_done))
            }
        )

        root.addView(
            text(getString(R.string.title_threshold), 15f, Color.parseColor("#111111"), bold = true)
                .apply { setPadding(0, dp(24), 0, dp(10)) }
        )
        thresholdInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((prefs.thresholdMs / 60_000L).toString())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        root.addView(
            thresholdInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            actionButton(getString(R.string.btn_save_threshold)) {
                val minutes = thresholdInput.text.toString().toLongOrNull() ?: 0L
                if (minutes <= 0L) {
                    toast(getString(R.string.toast_invalid_minutes))
                } else {
                    prefs.thresholdMs = minutes * 60_000L
                    MonitorService.start(this)
                    toast(getString(R.string.toast_threshold_saved, minutes))
                }
            }
        )

        brightnessInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.hint_brightness)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            val saved = prefs.minBrightnessPercent
            setText(if (saved > 0) saved.toString() else "")
        }
        root.addView(
            brightnessInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        )
        root.addView(
            actionButton(getString(R.string.btn_save_brightness)) {
                val raw = brightnessInput.text.toString().trim()
                val percent = if (raw.isEmpty()) 0 else (raw.toIntOrNull() ?: -1)
                if (percent < 0 || percent > 100) {
                    toast(getString(R.string.toast_invalid_brightness))
                } else {
                    prefs.minBrightnessPercent = percent
                    MonitorService.start(this)
                    toast(
                        if (percent == 0) getString(R.string.toast_brightness_off)
                        else getString(R.string.toast_brightness_saved, percent)
                    )
                }
            }
        )
        root.addView(
            actionButton(getString(R.string.btn_allow_write_settings)) {
                if (Settings.System.canWrite(this)) {
                    toast(getString(R.string.toast_write_granted))
                } else {
                    try {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } catch (_: Throwable) {
                        toast(getString(R.string.toast_write_failed))
                    }
                }
            }
        )

        return ScrollView(this).apply { addView(root) }
    }

    private fun refreshStatus() {
        val enabled = isAccessibilityEnabled()
        accessibilityValue.text = getString(
            if (enabled) R.string.state_enabled else R.string.state_disabled
        )
        accessibilityValue.setTextColor(if (enabled) colorOk else colorWarn)

        val shizukuAvailable = ShizukuShell.isAvailable()
        val shizukuGranted = shizukuAvailable && ShizukuShell.hasPermission()
        shizukuValue.text = getString(
            when {
                !shizukuAvailable -> R.string.state_not_running
                shizukuGranted -> R.string.state_granted
                else -> R.string.state_not_granted
            }
        )
        shizukuValue.setTextColor(if (shizukuGranted) colorOk else colorWarn)

        val snap = MonitorService.instance?.snapshot()
        if (snap == null) {
            usedValue.text = getString(R.string.state_service_stopped)
            leftValue.text = "--"
            phaseValue.text = "--"
            return
        }
        val totalMin = (prefs.thresholdMs / 60_000L)
        usedValue.text = formatDuration(snap.effectiveMs)
        leftValue.text = getString(
            R.string.fmt_until_break, formatDuration(snap.remainingMs), totalMin
        )
        phaseValue.text = getString(
            when {
                snap.gazeSuspended -> R.string.phase_no_gaze
                snap.phase.name == "GAZE" -> R.string.phase_gaze
                snap.phase.name == "SCREEN" -> R.string.phase_screen
                else -> R.string.phase_screen_off
            }
        )

        val currentPercent = currentBrightnessPercent()
        val minPercent = prefs.minBrightnessPercent
        brightnessValue.text = when {
            currentPercent < 0 -> getString(R.string.state_read_failed)
            minPercent > 0 -> getString(
                R.string.fmt_brightness_with_min, currentPercent, minPercent
            )
            else -> getString(R.string.fmt_percent, currentPercent)
        }
        minBrightnessValue.text = if (minPercent > 0) {
            getString(R.string.fmt_percent, minPercent)
        } else {
            getString(R.string.state_not_set)
        }
        val writable = canWriteSettings()
        writeSettingsValue.text = getString(
            if (writable) R.string.state_granted else R.string.state_not_granted
        )
        writeSettingsValue.setTextColor(if (writable) colorOk else colorWarn)
    }

    private fun currentBrightnessPercent(): Int = try {
        val value = Settings.System.getInt(
            contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            -1
        )
        if (value < 0) -1 else (value * 100 / 255)
    } catch (_: Throwable) {
        -1
    }

    private fun canWriteSettings(): Boolean = try {
        Settings.System.canWrite(this)
    } catch (_: Throwable) {
        false
    }

    private fun isAccessibilityEnabled(): Boolean {        if (GuardAccessibilityService.instance != null) return true
        val expected = ComponentName(this, GuardAccessibilityService::class.java)
        val raw = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return raw.split(':').any {
            it.equals(expected.flattenToString(), ignoreCase = true)
        }
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // 用于浮窗期间检测来电，避免遮挡来电界面
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.READ_PHONE_STATE)
        }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 1001)
    }

    private fun card(block: (LinearLayout) -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setBackgroundColor(Color.parseColor("#F6F6F4"))
            block(this)
        }
    }

    private fun row(parent: LinearLayout, label: String): TextView {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        line.addView(
            text(label, 14f, Color.parseColor("#6E7681")).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        val value = text("--", 14f, Color.parseColor("#111111"), bold = true)
        line.addView(value)
        parent.addView(line)
        return value
    }

    private fun actionButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
    }

    private fun text(s: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView {
        return TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            gravity = Gravity.START
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000L
        val min = totalSec / 60L
        val sec = totalSec % 60L
        return if (min > 0L) {
            getString(R.string.fmt_duration_min_sec, min, sec)
        } else {
            getString(R.string.fmt_duration_sec, sec)
        }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}
