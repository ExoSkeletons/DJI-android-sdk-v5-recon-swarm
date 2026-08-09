@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api.actions

import com.kcg.dr.flight.AircraftController
import kotlinx.schema.generator.json.SerialDescription
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("gimbal_pitch")
@SerialDescription("Pitches aircraft camera Gimbal up/down")
data class GimbalPitch(val angle: Double) : Action {
    override suspend fun act(controller: AircraftController) =
        controller.pitchCamera(angle)

    override val description = "Pitch Gimbal to ${angle}°"
}
