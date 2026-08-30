package com.kcg.dr.api

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.asLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kcg.dr.api.Tunneling.Cloudflared
import com.kcg.dr.flight.AircraftController
import com.kcg.dr.location.UserMetrics
import com.kcg.dr.utils.ServiceUtils
import dji.sampleV5.aircraft.util.ToastUtils
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch

class ApiServerVM(
    application: Application,
    private val controller: AircraftController,
    private val user: UserMetrics
) : AndroidViewModel(application) {
    companion object {
        val CONTROLLER_KEY = object : CreationExtras.Key<AircraftController> {}
        val USER_KEY = object : CreationExtras.Key<UserMetrics> {}
        val Factory = viewModelFactory {
            initializer {
                ApiServerVM(
                    this[APPLICATION_KEY]
                        ?: throw IllegalArgumentException("Application required"),
                    this[CONTROLLER_KEY]
                        ?: throw IllegalArgumentException("AircraftController required in CreationExtras"),
                    this[USER_KEY]
                        ?: throw IllegalArgumentException("UserMetrics required in CreationExtras")
                )
            }
        }
    }

    val isServiceRunning = MutableLiveData(false)
    val isServiceBound = MutableLiveData(false)
    val tunnelingUrl = MutableLiveData<String?>(null)

    private val server = MutableLiveData<ApiServer?>()

    val isServerRunning = server.switchMap {
        it?.isRunning ?: MutableLiveData(false)
    }

    val serverLogs = server.switchMap { s ->
        s?.requests?.scan(emptyList<String>()) { acc, value ->
            (acc + value).takeLast(10)
        }?.map { it.joinToString("\n") }?.asLiveData() ?: MutableLiveData()
    }

    val wsLogs = server.switchMap { s ->
        s?.wsIncoming?.scan(emptyList<String>()) { acc, value ->
            (acc + value).takeLast(2)
        }?.map { it.joinToString("\n") }?.asLiveData() ?: MutableLiveData()
    }


    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? ApiServerService.ApiServerBinder

            server.value = binder?.server
            isServiceBound.value = true
            isServiceRunning.value = true

            binder?.server?.configure(controller, user)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            server.value = null
            isServiceBound.value = false
        }
    }

    init {
        // Sync with service if it's already running
        val context = application.applicationContext
        val intent = Intent(context, ApiServerService::class.java)
        try {
            context.bindService(intent, serviceConnection, 0)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun startService(channelId: String, host: String = "0.0.0.0", port: Int = 8080) {
        val context = getApplication<Application>().applicationContext
        if (isServiceRunning.value == true) return
        ServiceUtils.startService(
            context,
            Intent(context, ApiServerService::class.java).apply {
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
            },
            connection = serviceConnection
        )
    }

    fun stopService() {
        val context = getApplication<Application>().applicationContext
        ServiceUtils.stopService(
            context,
            ApiServerService::class.java,
            connection = serviceConnection
        )
        server.value = null
        // startTunneling(port)
        isServiceBound.value = false
        isServiceRunning.value = false
    }

    fun startTunneling(port: Int = 8080) {
        viewModelScope.launch {
            val urls = Cloudflared.startTunneling(
                context = getApplication<Application>().applicationContext,
                port = port
            )
            tunnelingUrl.value = urls.firstOrNull()
        }
    }

    fun stopTunneling() {
        viewModelScope.launch {
            Cloudflared.stopTunneling()
            tunnelingUrl.value = null
        }
    }

    override fun onCleared() {
        super.onCleared()
        // stopService()
        if (isServiceBound.value == true) {
            try {
                getApplication<Application>().unbindService(serviceConnection)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
