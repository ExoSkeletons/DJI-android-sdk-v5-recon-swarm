package com.kcg.dr.api

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kcg.dr.flight.AircraftController
import com.kcg.dr.utils.NetUtils
import com.kcg.dr.utils.ServiceUtils.startAsForeground
import dji.sampleV5.aircraft.R

private const val TAG = "DroneApiService"

const val EXTRA_HOST: String = "HOST"
const val EXTRA_PORT: String = "PORT"
const val DEFAULT_HOST: String = "0.0.0.0"
const val DEFAULT_PORT: Int = 8080
const val NOTIFICATION_ID = 1304

class ApiServerService : Service() {
    private var server = ApiServer()

    var host: String = DEFAULT_HOST
    var port: Int = DEFAULT_PORT

    inner class ApiServerBinder : Binder() {
        val server: ApiServer get() = this@ApiServerService.server
    }

    private val binder = ApiServerBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        host = intent?.getStringExtra(EXTRA_HOST) ?: host
        port = intent?.getIntExtra(EXTRA_PORT, port) ?: port

        startAsForeground(NOTIFICATION_ID, createNotification())

        server.start(host = host, port = port)
        Log.i(TAG, "HTTP server started on port $port")

        return START_STICKY
    }

    override fun onDestroy() {
        server.stop()
        Log.i(TAG, "HTTP server stopped")
        super.onDestroy()
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, AircraftController.TAG).apply {
            setContentTitle("Drone API Server")
            setContentText("$host:$port @ ${NetUtils.getLocalIpAddress() ?: "-"}")
            setSmallIcon(R.drawable.aircraft)
            setOngoing(true)
        }.build()
    }
}