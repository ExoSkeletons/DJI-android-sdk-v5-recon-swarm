package com.kcg.dr.api

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kcg.dr.api.Tunneling.Cloudflared
import com.kcg.dr.flight.AircraftController
import com.kcg.dr.utils.ServiceUtils
import kotlinx.coroutines.launch

class ApiServerVM(
    application: Application,
    private val controller: AircraftController
) : AndroidViewModel(application) {
    companion object {
        val CONTROLLER_KEY = object : CreationExtras.Key<AircraftController> {}
        val Factory = viewModelFactory {
            initializer {
                ApiServerVM(
                    this[APPLICATION_KEY]
                        ?: throw IllegalArgumentException("Application required"),
                    this[CONTROLLER_KEY]
                        ?: throw IllegalArgumentException("AircraftController required in CreationExtras"),
                )
            }
        }
    }

    val isServiceRunning = MutableLiveData(false)
    val isServiceBound = MutableLiveData(false)
    val tunnelingUrl = MutableLiveData<String>(null)

    private val server = MutableLiveData<ApiServer?>()

    val isServerRunning = server.switchMap {
        it?.isRunning ?: MutableLiveData(false)
    }
    val serverLogs = server.switchMap {
        it?.requests ?: MutableLiveData(emptyList())
    }


    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? ApiServerService.ApiServerBinder

            server.value = binder?.server
            isServiceBound.value = true

            binder?.server?.setController(controller)
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
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
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
            connection = connection
        )
        viewModelScope.launch {
            val urls = Cloudflared.startTunneling(context = context, port = port)
            tunnelingUrl.value = urls.firstOrNull()
        }
    }

    fun stopService() {
        val context = getApplication<Application>().applicationContext
        ServiceUtils.stopService(
            context,
            ApiServerService::class.java,
            connection = connection
        )
        // stop tunneling
        isServiceBound.value = false
        isServiceRunning.value = false
    }

    override fun onCleared() {
        super.onCleared()
        // stopService()
        if (isServiceBound.value == true) {
            try {
                getApplication<Application>().unbindService(connection)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
