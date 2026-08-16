@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api.dto.actions

import com.kcg.dr.flight.AircraftController
import com.kcg.dr.location.UserMetrics
import kotlinx.schema.generator.json.SerialDescription
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("gimbal_pitch")
@SerialDescription("Pitches aircraft camera Gimbal up/down")
data class GimbalPitch(
    @property:SerialDescription("-90..60 (degrees)")
    val angle: Double
) : Action {
    override suspend fun act(aircraft: AircraftController, user: UserMetrics?) =
        aircraft.pitchCamera(angle)

    override val description = "Pitch Gimbal to ${angle}°"
}
