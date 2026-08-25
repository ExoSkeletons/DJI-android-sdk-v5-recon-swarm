@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api.dto.actions

import com.kcg.dr.djiutils.LocationUtils
import com.kcg.dr.djiutils.LocationUtils.bearingTo
import com.kcg.dr.djiutils.LocationUtils.distanceTo
import com.kcg.dr.djiutils.LocationUtils.translate
import com.kcg.dr.djiutils.as2D
import com.kcg.dr.djiutils.atAlt
import com.kcg.dr.flight.AircraftController
import com.kcg.dr.location.UserMetrics
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
    val cruiseHeight: Double? = null,
    val followDistance: Double = 3.5,
    @property:SerialDescription("1..8 (m/s)")
    val maxVelocity: Double = 5.0,
    val accelerationDist: Double = 2.0,
    val decelerationDist: Double = 4.0,
) : Action {
    override suspend fun act(aircraft: AircraftController, user: UserMetrics?) {
        val deviceLocation = user?.liveLocation ?: return

        val flyToTolerance = 1.5

        with(aircraft) {
            val dl = deviceLocation.filterNotNull().first()
            val currentLoc = ac.location.value ?: return
            val ch = cruiseHeight ?: ac.height.value

            // If aircraft is far from a perch position, move closer to perch location
            if (abs(
                    currentLoc.as2D.distanceTo(dl.as2D)
                        .minus(followDistance)
                ) > flyToTolerance
            ) {
                val bearingTo = currentLoc.as2D.bearingTo(dl.as2D)
                val pl = dl.translate(
                    followDistance,
                    LocationUtils.RelativeDirection.BACKWARD,
                    bearingTo
                ).atAlt(ch)

                ToastUtils.showToast("Looking for you")
                lookAtWithSpin(dl.as2D, user.humanHeight.value)
                ToastUtils.showToast("Moving to Perch")

                withEyesOn(deviceLocation) {
                    flyToSticks(
                        pl,
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
                ch, followDistance,
                maxVelocity = maxVelocity,
                accelerationDist = accelerationDist,
                decelerationDist = decelerationDist,
            )
        }
    }

    override val description: String get() = "Follow at ${cruiseHeight}m, ${followDistance}m away"
}