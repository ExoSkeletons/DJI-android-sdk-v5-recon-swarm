@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api.actions

import com.kcg.dr.flight.AircraftController
import kotlinx.schema.generator.json.SerialDescription
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("spin_by")
@SerialDescription("Spins aircraft relative to it's current heading.")
data class SpinBy(
    val degrees: Double,
) : Action {
    override suspend fun act(controller: AircraftController) = controller.spinBy(degrees)
    override val description = "Spin ${degrees}°"
}
