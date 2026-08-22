package com.kcg.dr.location

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

class UserVM(application: Application) : AndroidViewModel(application) {
    val location = MutableStateFlow<LocationCoordinate3D?>(null)
    val humanHeight = MutableStateFlow(3.0)

    val locationLiveData: LiveData<LocationCoordinate3D?> = location.asLiveData()
    val humanHeightLiveData: LiveData<Double> = humanHeight.asLiveData()

    val metrics: UserMetrics = UserMetrics(location, humanHeight)
}
