package com.kcg.dr.vocom.waypoints

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import kotlinx.coroutines.launch

class WaypointsViewModel(application: Application) : AndroidViewModel(application) {
    private var repository: WPLocationRepository = WPLocationRepository(getApplication())

    private val _locations = MutableLiveData<Map<String, LocationCoordinate3D?>>(emptyMap())
    val locations: LiveData<Map<String, LocationCoordinate3D?>> = _locations

    fun loadWaypoints() {
        viewModelScope.launch {
            repository.load()
            _locations.postValue(repository.locations())
        }
    }

    fun updateWaypoint(name: String, location: LocationCoordinate3D?) {
        viewModelScope.launch {
            repository.put(name, location)
            _locations.postValue(repository.locations())
        }
    }
}
