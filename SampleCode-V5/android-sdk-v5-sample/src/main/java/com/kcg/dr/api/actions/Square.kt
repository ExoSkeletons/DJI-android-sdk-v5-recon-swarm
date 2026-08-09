@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api.actions

import com.kcg.dr.flight.AircraftController
import kotlinx.schema.generator.json.SerialDescription
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("fly_square")
data class Square(
    @property:SerialDescription("Side length (m)")
    val side: Double,
    val velocity: Double,
    val clockwise: Boolean = true,
) : Action {
    override suspend fun act(controller: AircraftController) =
        controller.flySquare(side, velocity, clockwise)
}
