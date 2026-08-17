package com.kcg.dr.flight

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.kcg.dr.flight.dji.DJIAircraft
import com.kcg.dr.flight.dji.DJICamera
import com.kcg.dr.flight.dji.DJIGimbal
import com.kcg.dr.flight.dji.DJIRCStick
import com.kcg.dr.flight.dji.DJIVirtualStick
import kotlinx.coroutines.launch

class AircraftControlVM(
    application: Application,
) : AndroidViewModel(application) {
    val controller: AircraftController = AircraftController(
        DJIVirtualStick(),
        DJIRCStick(),
        DJIAircraft(),
        DJIGimbal(),
        DJICamera(),
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
