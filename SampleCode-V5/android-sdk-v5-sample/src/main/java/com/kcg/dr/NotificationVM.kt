package com.kcg.dr

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import com.kcg.dr.flight.AircraftController

class NotificationVM(application: Application) : AndroidViewModel(application) {
    private val manager = getApplication<Application>().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    val controllerChannelId = AircraftController.TAG

    init {
        // Create notification channels
        createChannel(controllerChannelId, "Aircraft Controller")
    }

    fun createChannel(channelId: String, channelName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }
}