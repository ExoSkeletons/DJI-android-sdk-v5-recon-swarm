package com.kcg.dr.flight

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.kcg.dr.flight.dji.DJIAircraft
import com.kcg.dr.flight.dji.DJIGimbal
import com.kcg.dr.flight.dji.DJIRCStick
import com.kcg.dr.flight.dji.DJIVirtualStick
import dji.sampleV5.aircraft.models.BasicAircraftControlVM
import dji.sampleV5.aircraft.models.VirtualStickVM
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import kotlinx.coroutines.launch

class AircraftControlViewModel(application: Application) : AndroidViewModel(application) {
    private lateinit var _controller: AircraftController
    val controller: AircraftController get() = _controller

    lateinit var aircraftLocation: LiveData<LocationCoordinate3D?>
    lateinit var aircraftHeight: LiveData<Double>
    lateinit var batteryPercent: LiveData<Int>
    lateinit var gimbalAttitude: LiveData<Attitude?>
    lateinit var attitude: LiveData<Attitude?>
    lateinit var heading: LiveData<Double>

    // FIXME: Not ideal. Use vm factory or god willing actually
    //  convert the controller to a vm (by changing the vm calls in controller
    //  to fire the actual api keys directly) as it should've been from the start loll
    fun initController(
        virtualStickVM: VirtualStickVM,
        basicAircraftControlVM: BasicAircraftControlVM,
    ) {
        if (this::_controller.isInitialized) return

        val c = AircraftController(
            DJIVirtualStick(virtualStickVM),
            DJIRCStick(),
            DJIAircraft(basicAircraftControlVM),
            DJIGimbal()
        )
        _controller = c

        viewModelScope.launch {
            c.init()

            aircraftLocation = c.ac.location.asLiveData()
            aircraftHeight = c.ac.height.asLiveData()
            batteryPercent = c.ac.batteryPercent.asLiveData()
            gimbalAttitude = c.camGim.attitude.asLiveData()
            attitude = c.ac.attitude.asLiveData()
            heading = c.ac.heading.asLiveData()
        }
    }

    override fun onCleared() {
        super.onCleared()
        _controller.destroy()
    }
}
