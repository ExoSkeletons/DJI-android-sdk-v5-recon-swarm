@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api.actions

import com.kcg.dr.flight.AircraftController
import com.kcg.dr.location.UserMetrics
import com.kcg.dr.utils.CoroutineUtils.awaitValue
import com.kcg.dr.utils.LocationUtils
import com.kcg.dr.utils.LocationUtils.distanceTo
import com.kcg.dr.utils.LocationUtils.translate
import com.kcg.dr.utils.as2D
import com.kcg.dr.utils.atAlt
import dji.sampleV5.aircraft.util.ToastUtils
import kotlinx.schema.generator.json.SerialDescription
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
@SerialName("follow_me")
@SerialDescription("Follow the user from above")
data class FollowMe(
    val cruiseHeight: Double = 15.0,
    val followDistance: Double = 4.0,
    @property:SerialDescription("1..8 (m/s)")
    val maxVelocity: Double = 8.0,
    val accelerationDist: Double = 2.0,
    val decelerationDist: Double = 4.0,
) : Action {
    override suspend fun act(aircraft: AircraftController, user: UserMetrics?) {
        val flyToTolerance = 1.5
        with(aircraft) {
            // If aircraft is far from a perch position, move closer
            val deviceLocation = user?.liveLocation ?: return
            val dl = deviceLocation.awaitValue()
            val pl = dl.atAlt(cruiseHeight)
            if (abs(
                    ac.location.value?.as2D?.distanceTo(dl.as2D)
                        ?.minus(followDistance) ?: 0.0
                ) > flyToTolerance
            ) {
                ToastUtils.showToast("looking for device")
                lookAtWithSpin(dl.as2D, dl.altitude)
                ToastUtils.showToast("moving to perch")
                flyToSticks(
                    pl.translate(
                        followDistance,
                        LocationUtils.RelativeDirection.BACKWARD,
                        ac.heading.value
                    ),
                    maxVelocity = maxVelocity,
                    accelerationDist = accelerationDist,
                    decelerationDist = decelerationDist,
                )
            }

            // Orbiting pattern
            perchShoulder(
                deviceLocation,
                cruiseHeight, followDistance,
                followVelocity = maxVelocity,
            )
        }
    }

    override val description: String get() = "Follow at ${cruiseHeight}m, ${followDistance}m away"
}