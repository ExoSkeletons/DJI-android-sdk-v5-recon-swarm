package com.kcg.dr.location

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import dji.sdk.keyvalue.value.common.LocationCoordinate3D

class UserMetrics(
    val liveLocation: LiveData<LocationCoordinate3D>,
    val humanHeight: LiveData<Double>,
)