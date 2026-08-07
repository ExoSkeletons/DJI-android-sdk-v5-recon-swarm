package com.kcg.dr.flight

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kcg.dr.flight.dji.DJIAircraft
import com.kcg.dr.flight.dji.DJIGimbal
import com.kcg.dr.flight.dji.DJIRCStick
import com.kcg.dr.flight.dji.DJIVirtualStick
import dji.sampleV5.aircraft.models.VirtualStickVM
import kotlinx.coroutines.launch

class AircraftControlViewModel(
    application: Application,
    virtualStickVM: VirtualStickVM,
) : AndroidViewModel(application) {
    companion object {
        // todo: remove this when we decouple from stickVM
        val STICK_VM_KEY = object : CreationExtras.Key<VirtualStickVM> {}

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AircraftControlViewModel(
                    this[APPLICATION_KEY]
                        ?: throw IllegalArgumentException("Application required"),
                    this[STICK_VM_KEY]
                        ?: throw IllegalArgumentException("AircraftController required in CreationExtras")
                )
            }
        }
    }

    val controller: AircraftController = AircraftController(
        DJIVirtualStick(virtualStickVM),
        DJIRCStick(),
        DJIAircraft(),
        DJIGimbal(),
    ).apply {
        viewModelScope.launch {
            init()
        }
    }
    val c = controller

    val aircraftLocation = c.ac.location.asLiveData()
    val aircraftHeight = c.ac.height.asLiveData()
    val batteryPercent = c.ac.batteryPercent.asLiveData()
    val gimbalAttitude = c.camGim.attitude.asLiveData()
    val attitude = c.ac.attitude.asLiveData()
    val heading = c.ac.heading.asLiveData()

    override fun onCleared() {
        super.onCleared()
        c.destroy()
    }
}
