package com.kcg.dr.api.server

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kcg.dr.api.VideoTcpServer
import com.kcg.dr.flight.AircraftController
import com.aviadl40.utils.net.getLocalIpAddress
import com.aviadl40.utils.android.startAsForeground
import dji.sampleV5.aircraft.R

private const val TAG = "DroneApiService"

const val EXTRA_HOST: String = "HOST"
const val EXTRA_PORT: String = "PORT"
const val DEFAULT_HOST: String = "0.0.0.0"
const val DEFAULT_API_PORT: Int = 8080
const val DEFAULT_STREAM_PORT: Int = 5600
const val NOTIFICATION_ID = 1304

class ApiServerService : Service() {
    private var server = ApiServer()
    private var streamServer = VideoTcpServer()

    var host: String = DEFAULT_HOST
    var apiPort: Int = DEFAULT_API_PORT
    var streamPort: Int = DEFAULT_STREAM_PORT

    inner class ApiServerBinder : Binder() {
        val server: ApiServer get() = this@ApiServerService.server
    }

    private val binder = ApiServerBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startAsForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        host = intent?.getStringExtra(EXTRA_HOST) ?: host
        apiPort = intent?.getIntExtra(EXTRA_PORT, apiPort) ?: apiPort

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification())

        server.start(host = host, port = apiPort)
        Log.i(TAG, "HTTP server started on port $apiPort")
        streamServer.start(port = streamPort)
        Log.i(TAG, "Stream server started on port $apiPort")

        return START_STICKY
    }

    override fun onDestroy() {
        server.stop()
        Log.i(TAG, "HTTP server stopped")
        streamServer.stop()
        Log.i(TAG, "Stream server stopped")
        super.onDestroy()
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, AircraftController.TAG).apply {
            setContentTitle("Drone API Server")
            setContentText("$host:$apiPort @ ${getLocalIpAddress() ?: "-"}")
            setSmallIcon(R.drawable.aircraft)
            setOngoing(true)
        }.build()
    }
}