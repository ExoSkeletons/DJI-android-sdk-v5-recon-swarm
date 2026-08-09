@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api.actions

import com.kcg.dr.flight.AircraftController
import com.kcg.dr.flight.AircraftController.CircleFaceMode
import com.kcg.dr.location.UserMetrics
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("fly_circle")
data class Circle(
    val radius: Double,
    val velocity: Double,
    val count: Double = 1.0,
    val clockwise: Boolean = true,
    @property:SerialName("facing")
    val faceMode: CircleFaceMode = CircleFaceMode.CENTER,
) : Action {
    override suspend fun act(aircraft: AircraftController, user: UserMetrics?) =
        aircraft.flyCircle(radius, velocity, count, clockwise, faceMode)
}
