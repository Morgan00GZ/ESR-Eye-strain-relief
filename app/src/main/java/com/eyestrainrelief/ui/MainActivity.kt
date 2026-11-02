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
                    if (grantResult == PackageManager.PERMISSION_GRANTED) "Shizuku 已授权"
                    else "Shizuku 授权被拒绝"
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
                "屏幕连续使用达到阈值后弹出全屏休息浮窗，倒计时结束自动消失。",
                13f, Color.parseColor("#6E7681")
            ).apply { setPadding(0, dp(8), 0, dp(6)) }
        )
        root.addView(
            text(
                "浮窗出现时连续按音量下键 5 次可紧急退出，用于异常情况自保。",
                12f, Color.parseColor("#8A8A8A")
            ).apply { setPadding(0, 0, 0, dp(6)) }
        )
        root.addView(
            text(
                "最低亮度：屏幕亮度低于该值时自动拉回，设为 0 或留空则不启用。",
                12f, Color.parseColor("#8A8A8A")
            ).apply { setPadding(0, 0, 0, dp(20)) }
        )

        root.addView(card {
            accessibilityValue = row(it, "无障碍服务")
            shizukuValue = row(it, "Shizuku 权限")
            usedValue = row(it, "本轮已用")
            leftValue = row(it, "距提醒")
            phaseValue = row(it, "计时状态")
            brightnessValue = row(it, "屏幕亮度")
            minBrightnessValue = row(it, "最低亮度")
            writeSettingsValue = row(it, "修改系统设置")
        })

        root.addView(
            actionButton("开启无障碍服务") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        )
        root.addView(
            actionButton("授权 Shizuku（媒体控制）") {
                when {
                    !ShizukuShell.isAvailable() ->
                        toast("未检测到 Shizuku，请先在手机上启动 Shizuku 服务")
                    ShizukuShell.hasPermission() ->
                        toast("Shizuku 已授权")
                    else ->
                        ShizukuShell.requestPermission()
                }
            }
        )
        root.addView(
            actionButton("立即测试一次浮窗") {
                val ok = MonitorService.instance?.triggerNow() ?: false
                if (!ok) toast("请先开启无障碍服务")
            }
        )
        root.addView(
            actionButton("重置计数") {
                MonitorService.instance?.resetCounter()
                toast("已重置")
            }
        )

        root.addView(
            text("阈值（分钟）", 15f, Color.parseColor("#111111"), bold = true)
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
            actionButton("保存阈值") {
                val minutes = thresholdInput.text.toString().toLongOrNull() ?: 0L
                if (minutes <= 0L) {
                    toast("请输入正整数分钟")
                } else {
                    prefs.thresholdMs = minutes * 60_000L
                    MonitorService.start(this)
                    toast("已保存，阈值 $minutes 分钟")
                }
            }
        )

        brightnessInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "最低亮度 %（1-100，0 或留空 = 关闭）"
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
            actionButton("保存最低亮度") {
                val raw = brightnessInput.text.toString().trim()
                val percent = if (raw.isEmpty()) 0 else (raw.toIntOrNull() ?: -1)
                if (percent < 0 || percent > 100) {
                    toast("请输入 0-100 的整数，0 表示关闭")
                } else {
                    prefs.minBrightnessPercent = percent
                    MonitorService.start(this)
                    toast(if (percent == 0) "已关闭最低亮度" else "已设置最低亮度 $percent%")
                }
            }
        )
        root.addView(
            actionButton("允许修改系统设置") {
                if (Settings.System.canWrite(this)) {
                    toast("已具备修改亮度的权限")
                } else {
                    try {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } catch (_: Throwable) {
                        toast("无法打开设置页，请手动到「特殊权限 → 修改系统设置」授权")
                    }
                }
            }
        )

        return ScrollView(this).apply { addView(root) }
    }

    private fun refreshStatus() {
        val enabled = isAccessibilityEnabled()
        accessibilityValue.text = if (enabled) "已开启" else "未开启"
        accessibilityValue.setTextColor(if (enabled) colorOk else colorWarn)

        val shizukuAvailable = ShizukuShell.isAvailable()
        val shizukuGranted = shizukuAvailable && ShizukuShell.hasPermission()
        shizukuValue.text = when {
            !shizukuAvailable -> "未运行"
            shizukuGranted -> "已授权"
            else -> "未授权"
        }
        shizukuValue.setTextColor(if (shizukuGranted) colorOk else colorWarn)

        val snap = MonitorService.instance?.snapshot()
        if (snap == null) {
            usedValue.text = "服务未运行"
            leftValue.text = "--"
            phaseValue.text = "--"
            return
        }
        val totalMin = (prefs.thresholdMs / 60_000L)
        usedValue.text = formatDuration(snap.effectiveMs)
        leftValue.text = "${formatDuration(snap.remainingMs)}（阈值 $totalMin 分钟）"
        phaseValue.text = when {
            snap.gazeSuspended -> "未检测到注视，已暂停"
            snap.phase.name == "GAZE" -> "注视计时中"
            snap.phase.name == "SCREEN" -> "亮屏计时中"
            else -> "已暂停（屏幕熄灭）"
        }

        val currentPercent = currentBrightnessPercent()
        val minPercent = prefs.minBrightnessPercent
        brightnessValue.text = when {
            currentPercent < 0 -> "读取失败"
            minPercent > 0 -> "$currentPercent%（下限 $minPercent%）"
            else -> "$currentPercent%"
        }
        minBrightnessValue.text = if (minPercent > 0) "$minPercent%" else "未设置"
        val writable = canWriteSettings()
        writeSettingsValue.text = if (writable) "已授权" else "未授权"
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
        return if (min > 0L) "${min}分${sec}秒" else "${sec}秒"
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}
