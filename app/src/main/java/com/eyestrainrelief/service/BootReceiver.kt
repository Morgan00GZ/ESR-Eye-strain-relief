package com.eyestrainrelief.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启。真机上澎湃OS 仍可能拦截 BOOT_COMPLETED，
 * 因此主要保底路径是「无障碍服务被系统重新绑定」时拉起计时服务。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> MonitorService.start(context)
        }
    }
}
