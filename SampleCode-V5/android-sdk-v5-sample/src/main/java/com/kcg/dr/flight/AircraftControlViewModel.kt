package com.kcg.dr.flight

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.viewModelScope
import com.kcg.dr.flight.dji.DJIAircraft
import com.kcg.dr.flight.dji.DJIGimbal
import com.kcg.dr.flight.dji.DJIRCStick
import com.kcg.dr.flight.dji.DJIVirtualStick
import dji.sampleV5.aircraft.models.BasicAircraftControlVM
import dji.sampleV5.aircraft.models.VirtualStickVM
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.common.LocationCoordinate3D

class AircraftControlViewModel(application: Application) : AndroidViewModel(application) {
    private lateinit var _controller: AircraftController
    val controller: AircraftController get() = _controller

    val aircraftLocation = MediatorLiveData<LocationCoordinate3D?>()
    val aircraftHeight = MediatorLiveData<Double>()
    val batteryPercent = MediatorLiveData<Int>()
    val gimbalAttitude = MediatorLiveData<Attitude?>()
    val attitude = MediatorLiveData<Attitude?>()
    val heading = MediatorLiveData<Double>()

    // FIXME: Not ideal. Use vm factory or god willing actually
    //  convert the controller to a vm (by changing the vm calls in controller
    //  to fire the actual api keys directly) as it should've been from the start loll
    fun initController(
        virtualStickVM: VirtualStickVM,
        basicAircraftControlVM: BasicAircraftControlVM,
    ) {
        if (this::_controller.isInitialized) return

        val c = AircraftController(
            viewModelScope,
            DJIVirtualStick(virtualStickVM),
            DJIRCStick(),
            DJIAircraft(basicAircraftControlVM, virtualStickVM),
            DJIGimbal()
        )
        _controller = c

        c.init()

        aircraftLocation.addSource(c.ac.location) { aircraftLocation.value = it }
        aircraftHeight.addSource(c.ac.height) { aircraftHeight.value = it }
        batteryPercent.addSource(c.ac.batteryPercent) { batteryPercent.value = it }
        gimbalAttitude.addSource(c.ac.gimbalAttitude) { gimbalAttitude.value = it }
        attitude.addSource(c.ac.attitude) { attitude.value = it }
        heading.addSource(c.ac.heading) { heading.value = it }
    }

    override fun onCleared() {
        super.onCleared()
        _controller.destroy()
    }
}
