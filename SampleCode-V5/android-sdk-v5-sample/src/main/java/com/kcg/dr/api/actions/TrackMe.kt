package com.kcg.dr.api.actions

import com.kcg.dr.flight.AircraftController
import com.kcg.dr.location.UserMetrics
import dji.sampleV5.aircraft.util.ToastUtils
import kotlinx.schema.generator.json.SerialDescription
import kotlinx.serialization.Serializable

@Serializable
@SerialDescription("Look at and Camera track the user's location")
sealed class TrackMe(
    val fovTolerance: Double = 17.0
) : Action {
    override suspend fun act(aircraft: AircraftController, user: UserMetrics?) {
        user?.liveLocation?.let {
            ToastUtils.showToast("camera tracking phone location")
            aircraft.lookAtAndTrack(it, fovTolerance)
        } ?: throw IllegalStateException("Missing device location")
    }
}