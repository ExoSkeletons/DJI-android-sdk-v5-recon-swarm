package com.kcg.dr.location

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.asLiveData
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import kotlinx.coroutines.flow.MutableStateFlow

class UserVM(application: Application) : AndroidViewModel(application) {
    val location = MutableStateFlow<LocationCoordinate3D?>(null)
    val humanHeight = MutableStateFlow(3.0)

    val locationLiveData: LiveData<LocationCoordinate3D?> = location.asLiveData()
    val humanHeightLiveData: LiveData<Double> = humanHeight.asLiveData()

    val standingLocation = MediatorLiveData<LocationCoordinate3D?>(null).apply {
        addSource(locationLiveData) {
            val l = it ?: return@addSource
            humanHeight.value.let { h -> l.apply { altitude = h } }
            postValue(l)
        }
        addSource(humanHeightLiveData) { h ->
            val l = location.value ?: return@addSource
            l.apply { altitude = h }
            postValue(l)
        }
    }
    val metrics: UserMetrics = UserMetrics(location, humanHeight)
}
