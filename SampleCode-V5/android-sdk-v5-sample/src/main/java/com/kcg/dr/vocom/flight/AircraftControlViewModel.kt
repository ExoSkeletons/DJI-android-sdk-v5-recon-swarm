package com.kcg.dr.vocom.flight

import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kcg.dr.api.ControllerBridge
import dji.sampleV5.aircraft.models.BasicAircraftControlVM
import dji.sampleV5.aircraft.models.CameraGimbalVM
import dji.sampleV5.aircraft.models.IntelligentFlightVM
import dji.sampleV5.aircraft.models.VirtualStickVM
import dji.sampleV5.aircraft.models.WayPointV3VM
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.common.LocationCoordinate3D

class AircraftControlViewModel : ViewModel() {
    private var _controller: AircraftController? = null
    val controller: AircraftController? get() = _controller

    val aircraftLocation = MediatorLiveData<LocationCoordinate3D?>()
    val aircraftHeight = MediatorLiveData<Double>()
    val batteryPercent = MediatorLiveData<Int>()
    val gimbalAttitude = MediatorLiveData<Attitude?>()
    val attitude = MediatorLiveData<Attitude?>()
    val heading = MediatorLiveData<Double>()

    fun initController(
        virtualStickVM: VirtualStickVM,
        basicAircraftControlVM: BasicAircraftControlVM,
        cameraGimbalVM: CameraGimbalVM,
        intelligentFlightVM: IntelligentFlightVM,
        wayPointV3VM: WayPointV3VM
    ) {
        if (_controller != null) return
        
        val c = AircraftController(
            viewModelScope,
            virtualStickVM,
            basicAircraftControlVM,
            cameraGimbalVM,
            intelligentFlightVM,
            wayPointV3VM
        )
        _controller = c
        ControllerBridge.controller = c
        
        c.init()
        
        aircraftLocation.addSource(c.location) { aircraftLocation.value = it }
        aircraftHeight.addSource(c.height) { aircraftHeight.value = it }
        batteryPercent.addSource(c.batteryPercent) { batteryPercent.value = it }
        gimbalAttitude.addSource(c.gimbalAttitude) { gimbalAttitude.value = it }
        attitude.addSource(c.attitude) { attitude.value = it }
        heading.addSource(c.heading) { heading.value = it }
    }

    override fun onCleared() {
        super.onCleared()
        _controller?.destroy()
        ControllerBridge.controller = null
    }
}
