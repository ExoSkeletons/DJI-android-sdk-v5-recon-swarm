package com.kcg.dr.flight

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import com.kcg.dr.flight.dji.DJIAircraft
import com.kcg.dr.flight.dji.DJIGimbal
import com.kcg.dr.flight.dji.DJIRCStick
import com.kcg.dr.flight.dji.DJIVirtualStick
import dji.sampleV5.aircraft.models.BasicAircraftControlVM
import dji.sampleV5.aircraft.models.VirtualStickVM
import kotlinx.coroutines.launch

class AircraftControlViewModel(application: Application) : AndroidViewModel(application) {
    private val c = MutableLiveData<AircraftController?>(null)
    val controller get() = c.value!!

    val aircraftLocation = c.switchMap { it?.ac?.location?.asLiveData() ?: MutableLiveData(null) }
    val aircraftHeight = c.switchMap { it?.ac?.height?.asLiveData() ?: MutableLiveData(0.0) }
    val batteryPercent = c.switchMap {
        it?.ac?.batteryPercent?.asLiveData() ?: MutableLiveData(0)
    }
    val gimbalAttitude = c.switchMap {
        it?.camGim?.attitude?.asLiveData() ?: MutableLiveData(null)
    }
    val attitude = c.switchMap { it?.ac?.attitude?.asLiveData() ?: MutableLiveData(null) }
    val heading = c.switchMap { it?.ac?.heading?.asLiveData() ?: MutableLiveData(0.0) }

    fun setController(controller: AircraftController) {
        c.value?.destroy()
        c.postValue(controller)
    }

    fun init(
        basicAircraftControlVM: BasicAircraftControlVM,
        virtualStickVM: VirtualStickVM,
    ) {
        if (c.value != null) return
        viewModelScope.launch {
            val controller = AircraftController(
                viewModelScope,
                DJIVirtualStick(virtualStickVM),
                DJIRCStick(),
                DJIAircraft(basicAircraftControlVM),
                DJIGimbal(),
            ).apply { init() }
            setController(controller)
        }
    }

    override fun onCleared() {
        super.onCleared()
        c.value?.destroy()
    }
}
