package com.kcg.dr.api

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dji.sampleV5.aircraft.R
import java.net.Inet4Address
import java.net.NetworkInterface

private const val TAG = "DroneApiService"


const val EXTRA_PORT: String = "PORT"
const val NOTIFICATION_CHANNEL_ID = "drone_api_service_channel"
const val NOTIFICATION_ID = 1304

class ApiServerService() : Service() {
    companion object {
        fun start(context: Context, port: Int) {
            with(
                Intent(context, ApiServerService::class.java).apply {
                    putExtra(EXTRA_PORT, port)
                }
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    context.startForegroundService(this)
                else context.startService(this)
            }
        }
    }

    private var server: ApiHttpServer? = null
    private var port: Int = 8080

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        port = intent?.getIntExtra(EXTRA_PORT, port) ?: port
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()

        val notification: Notification = createNotification()
        // Start the service in the foreground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
            )
        } else startForeground(NOTIFICATION_ID, notification)

        // Start server
        if (server != null) {
            Log.i(TAG, "Restarting server")
        server?.stop()
            server = null
        }
        Log.i(TAG, "Starting server on port=$port")
        //server = ApiHttpServer(port).also { it.start() }
        Log.i(TAG, "HTTP server started on port $port")
    }

    override fun onDestroy() {
        // server?.stop()
        Log.i(TAG, "HTTP server stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        // Create notification channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Aircraft Controller",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Aircraft Controller"
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        // Create notification
        val notification: Notification =
            NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID).apply {
                setContentTitle("Drone API Server")
                setContentText("Address: ${getLocalIpAddress() ?: "Unknown"} Port: $port")
                setSmallIcon(R.drawable.aircraft)
                setOngoing(true)
            }.build()
        return notification
    }

    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            for (inf in interfaces) {
                if (!inf.isUp || inf.isLoopback) continue
                val addresses = inf.inetAddresses.toList()
                for (address in addresses)
                    if (!address.isLoopbackAddress && address is Inet4Address)
                        return address.hostAddress
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}