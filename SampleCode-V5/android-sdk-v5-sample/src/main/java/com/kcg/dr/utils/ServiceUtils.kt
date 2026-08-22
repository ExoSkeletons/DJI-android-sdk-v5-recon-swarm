package com.kcg.dr.utils

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat

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

object ServiceUtils {
    fun startService(
        context: Context,
        intent: Intent,
        connection: ServiceConnection? = null
    ) {
        context.apply {
            with(intent) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    startForegroundService(this)
                else startService(this)

                connection?.let { bindService(this, it, Context.BIND_AUTO_CREATE) }
            }
        }
    }

    fun stopService(
        context: Context,
        clazz: Class<out Service>,
        connection: ServiceConnection? = null
    ) {
        val intent = Intent(context, clazz)
        try {
            context.stopService(intent)
            connection?.let { context.unbindService(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}