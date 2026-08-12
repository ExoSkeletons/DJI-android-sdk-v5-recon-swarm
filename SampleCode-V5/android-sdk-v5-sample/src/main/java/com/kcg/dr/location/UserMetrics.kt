package com.kcg.dr.location

import kotlinx.coroutines.flow.StateFlow
import dji.sdk.keyvalue.value.common.LocationCoordinate3D

class UserMetrics(
    val liveLocation: StateFlow<LocationCoordinate3D?>,
    val humanHeight: StateFlow<Double>,
)