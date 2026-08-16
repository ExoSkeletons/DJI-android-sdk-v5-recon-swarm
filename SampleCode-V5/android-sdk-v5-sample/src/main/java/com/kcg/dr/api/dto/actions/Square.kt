@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api.dto.actions

import com.kcg.dr.flight.AircraftController
import com.kcg.dr.location.UserMetrics
import kotlinx.schema.generator.json.SerialDescription
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("fly_square")
data class Square(
    @property:SerialDescription("Side length (m)")
    val side: Double,
    @property:SerialDescription("1..6 (m/s)")
    val velocity: Double,
    val clockwise: Boolean = true,
) : Action {
    override suspend fun act(aircraft: AircraftController, user: UserMetrics?) =
        aircraft.flySquare(side, velocity, clockwise)
}
