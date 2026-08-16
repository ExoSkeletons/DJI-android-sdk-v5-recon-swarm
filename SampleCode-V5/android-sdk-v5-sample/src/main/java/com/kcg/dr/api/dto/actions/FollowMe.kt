@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api.dto.actions

import com.kcg.dr.flight.AircraftController
import com.kcg.dr.location.UserMetrics
import com.kcg.dr.utils.LocationUtils
import com.kcg.dr.utils.LocationUtils.bearingTo
import com.kcg.dr.utils.LocationUtils.distanceTo
import com.kcg.dr.utils.LocationUtils.translate
import com.kcg.dr.utils.as2D
import com.kcg.dr.utils.atAlt
import dji.sampleV5.aircraft.util.ToastUtils
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.schema.generator.json.SerialDescription
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
@SerialName("follow_me")
@SerialDescription("Follow the user from above")
data class FollowMe(
    val cruiseHeight: Double = 7.0,
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
            val dl = deviceLocation.filterNotNull().first()
            val pl = dl.atAlt(cruiseHeight)
            val currentLoc = ac.location.value ?: return

            if (abs(
                    currentLoc.as2D.distanceTo(dl.as2D)
                        .minus(followDistance)
                ) > flyToTolerance
            ) {
                ToastUtils.showToast("Looking for you")
                lookAtWithSpin(dl.as2D, user.humanHeight.value)
                ToastUtils.showToast("Moving to Perch")

                val perchHeading = currentLoc.as2D.bearingTo(dl.as2D)

                withEyesOn(deviceLocation) {
                    flyToSticks(
                        pl.translate(
                            followDistance,
                            LocationUtils.RelativeDirection.BACKWARD,
                            perchHeading
                        ),
                        maxVelocity = maxVelocity,
                        accelerationDist = accelerationDist,
                        decelerationDist = decelerationDist,
                    )
                }
            }

            ToastUtils.showToast("Following you")
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