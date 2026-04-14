package com.kcg.dr.api

import android.app.Notification
import android.app.Notification.EXTRA_CHANNEL_ID
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kcg.dr.ServiceUtils.startAsForeground
import dji.sampleV5.aircraft.R
import java.net.Inet4Address
import java.net.NetworkInterface

private const val TAG = "DroneApiService"


const val EXTRA_PORT: String = "PORT"
const val NOTIFICATION_ID = 1304

class ApiServerService() : Service() {
    private var server: ApiHttpServer? = null
    private var port: Int = 8080
    private var channelId: String = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        port = intent?.getIntExtra(EXTRA_PORT, port) ?: port
        channelId = intent?.getStringExtra(EXTRA_CHANNEL_ID)
            ?: throw RuntimeException("No channel id provided")

        startAsForeground(NOTIFICATION_ID, createNotification())

        // Start server
        if (server != null) {
            Log.i(TAG, "Restarting server")
            server?.stop()
            server = null
        }
        Log.i(TAG, "Starting server on port=$port")
        server = ApiHttpServer(port).also { it.start() }
        Log.i(TAG, "HTTP server started on port $port, $")

        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        Log.i(TAG, "HTTP server stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        // Create notification
        val notification: Notification =
            NotificationCompat.Builder(this, channelId).apply {
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