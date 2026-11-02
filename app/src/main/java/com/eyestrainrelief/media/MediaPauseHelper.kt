package com.eyestrainrelief.media

import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import com.eyestrainrelief.shell.ShizukuShell

/**
 * 媒体播放暂停/恢复。
 *
 * 浮窗是全屏遮罩，不会让下层应用暂停，视频与音频仍在继续，这里通过媒体会话控制。
 *
 * 恢复时必须先确认「仍存在媒体会话」再发 play：否则在桌面场景下，play 会被系统
 * 派发给最近一次的媒体应用（实测为酷狗音乐），凭空把音乐放起来。
 */
object MediaPauseHelper {

    private const val TAG = "ESR"

    fun setPaused(context: Context, paused: Boolean) {
        if (paused) pause(context) else resume(context)
    }

    private fun pause(context: Context) {
        if (ShizukuShell.hasPermission()) {
            ShizukuShell.execAsync("cmd media_session dispatch pause")
        } else {
            dispatchKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE)
        }
    }

    private fun resume(context: Context) {
        if (ShizukuShell.hasPermission()) {
            // 先确认还有活跃会话，避免在桌面把音乐应用拉起来
            ShizukuShell.execAsync("cmd media_session list-sessions") { out ->
                if (!out.isNullOrBlank() && out.contains("package=")) {
                    ShizukuShell.execAsync("cmd media_session dispatch play")
                } else {
                    Log.d(TAG, "no media session, skip resume")
                }
            }
        } else {
            dispatchKey(context, KeyEvent.KEYCODE_MEDIA_PLAY)
        }
    }

    private fun dispatchKey(context: Context, keyCode: Int) {
        try {
            val audioManager =
                context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        } catch (t: Throwable) {
            Log.w(TAG, "dispatchMediaKeyEvent failed", t)
        }
    }
}
