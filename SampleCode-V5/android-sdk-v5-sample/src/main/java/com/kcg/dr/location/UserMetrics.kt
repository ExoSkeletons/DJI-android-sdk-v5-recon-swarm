package com.kcg.dr.location

import com.kcg.dr.utils.atAlt
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

class UserMetrics(
    val liveLocation: StateFlow<LocationCoordinate3D?>,
    val humanHeight: StateFlow<Double>,
    val standingLocation: Flow<LocationCoordinate3D?> = combine(liveLocation, humanHeight) { l, h ->
        l?.atAlt(h)
    }
)