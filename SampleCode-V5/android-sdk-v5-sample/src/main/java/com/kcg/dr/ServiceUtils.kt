package com.kcg.dr

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat

object ServiceUtils {
    fun Service.startAsForeground(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                id,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
            )
        } else startForeground(id, notification)
    }

    fun startService(context: Context, intent: Intent) {
        with(intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(this)
            else context.startService(this)
        }
    }

    fun stopService(context: Context, clazz: Class<out Service>) {
        val intent = Intent(context, clazz)
        context.stopService(intent)
        context.stopService(intent)
    }
}