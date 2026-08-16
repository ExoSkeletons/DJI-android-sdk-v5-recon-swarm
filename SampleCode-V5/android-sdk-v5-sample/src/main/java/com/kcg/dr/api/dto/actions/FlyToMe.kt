@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api.dto.actions

import com.kcg.dr.flight.AircraftController
import com.kcg.dr.location.UserMetrics
import com.kcg.dr.utils.as2D
import dji.sampleV5.aircraft.util.ToastUtils
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.schema.generator.json.SerialDescription
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("home")
@SerialDescription("Go back home to the user")
data class FlyToMe(
    @property:SerialDescription("1..7 (m/s)")
    val maxVelocity: Double = 4.0,
    val accelerationDist: Double = 3.0,
    val decelerationDist: Double = 4.0,
) : Action {
    override suspend fun act(aircraft: AircraftController, user: UserMetrics?) {
        with(aircraft) {
            user ?: return

            val dl = user.liveLocation.filterNotNull().first()

            ToastUtils.showToast("following phone location")
            lookAtWithSpin(dl.as2D, 0.0)
            flyToSticks(
                dl,
                maxVelocity = maxVelocity,
                accelerationDist = accelerationDist,
                decelerationDist = decelerationDist
            )
        }
    }
}
