package com.kcg.dr.api.actions

import com.kcg.dr.flight.AircraftController
import com.kcg.dr.location.UserMetrics
import dji.sampleV5.aircraft.util.ToastUtils
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
sealed class FlyToMe(
    val maxVelocity: Double = 8.0,
    val accelerationDist: Double = 2.0,
    val decelerationDist: Double = 4.0,
) : Action {
    override suspend fun act(aircraft: AircraftController, user: UserMetrics?) {
        with(aircraft) {
            ToastUtils.showToast("following phone location")
            coroutineScope {
                launch { takeoff() }
            }
            flyToSticks(
                user?.liveLocation?.value!!,
                maxVelocity = maxVelocity,
                accelerationDist = accelerationDist,
                decelerationDist = decelerationDist
            )
        }
    }
}