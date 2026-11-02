package com.eyestrainrelief.audio

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * 浮窗结束提示。
 *
 * 目标：像收到一条系统通知那样响铃 + 震动，但**不产生任何通知对象**——
 * 通知栏、锁屏、通知历史都不会留下痕迹。
 *
 * 因此这里不走 NotificationManager，而是：
 * 1. 铃声：直接播放系统当前的通知铃声（RingtoneManager 取默认 URI），
 *    你在系统设置里换铃声它会自动跟着变；
 * 2. 震动：VibrationEffect 两下短震，振幅用 DEFAULT_AMPLITUDE，自动跟随系统震动强度；
 * 3. 策略：静音/振动模式只震不响；勿扰期间完全不动作。
 *
 * 铃声时长不可控（各机型默认通知音 1~3 秒不等），统一在 2 秒处截断。
 */
object BreakNotifier {

    private const val TAG = "ESR"

    /** 铃声最长播放时长，避免个别机型通知音过长 */
    private const val RING_MAX_MS = 2000L

    private val handler = Handler(Looper.getMainLooper())
    private var playing: Ringtone? = null

    fun notifyBreakEnd(context: Context) {
        val app = context.applicationContext
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val ringerMode = audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL

        // 震动不受勿扰与静音影响：这几秒的休息提醒靠触觉也能传达，
        // 且用户明确要求静音/勿扰下仍需感知到结束时刻。
        vibrate(app)

        // 只有「正常模式且未开勿扰」才出声，避免开会或夜间突然响铃
        val silent = isDoNotDisturb(app) || ringerMode != AudioManager.RINGER_MODE_NORMAL
        if (!silent) {
            playNotificationSound(app)
        }
    }

    private fun isDoNotDisturb(context: Context): Boolean {
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val filter = nm?.currentInterruptionFilter
                ?: NotificationManager.INTERRUPTION_FILTER_ALL
            filter != NotificationManager.INTERRUPTION_FILTER_ALL
        } catch (_: Throwable) {
            false
        }
    }

    private fun playNotificationSound(context: Context) {
        stopPlaying()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
            val ringtone = RingtoneManager.getRingtone(context, uri) ?: return
            // 明确走通知音量通道，与系统通知的响度保持一致
            ringtone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone.play()
            playing = ringtone
            handler.postDelayed({ stopPlaying() }, RING_MAX_MS)
        } catch (t: Throwable) {
            Log.w(TAG, "play notification sound failed", t)
        }
    }

    private fun stopPlaying() {
        val ringtone = playing ?: return
        playing = null
        try {
            if (ringtone.isPlaying) ringtone.stop()
        } catch (_: Throwable) {
        }
    }

    /**
     * 两下短震：80ms 振动 + 120ms 间隔 + 80ms 振动。
     * 振幅用 DEFAULT_AMPLITUDE，系统会按用户设置的通知震动强度自动缩放。
     */
    private fun vibrate(context: Context) {
        try {
            val vibrator = resolveVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return
            val timings = longArrayOf(0L, 80L, 120L, 80L)
            val amplitudes = intArrayOf(
                0,
                VibrationEffect.DEFAULT_AMPLITUDE,
                0,
                VibrationEffect.DEFAULT_AMPLITUDE
            )
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } catch (t: Throwable) {
            Log.w(TAG, "vibrate failed", t)
        }
    }

    private fun resolveVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
