package com.kcg.dr.location

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import dji.sdk.keyvalue.value.common.LocationCoordinate3D

class UserVM(application: Application) : AndroidViewModel(application) {
    val location = MutableLiveData<LocationCoordinate3D>()
    val humanHeight = MutableLiveData(3.0)
    val standingLocation = MediatorLiveData<LocationCoordinate3D?>(null).apply {
        addSource(location) {
            val l = it ?: return@addSource
            humanHeight.value?.let { h -> l.apply { altitude = h } }
            postValue(l)
        }
        addSource(humanHeight) { h ->
            val l = location.value ?: return@addSource
            l.apply { altitude = h }
            postValue(l)
        }
    }
    val metrics: UserMetrics = UserMetrics(location, humanHeight)
}
