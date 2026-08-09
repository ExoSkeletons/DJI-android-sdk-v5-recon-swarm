@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api.actions

import com.kcg.dr.flight.AircraftController
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("land")
object Land : Action {
    override suspend fun act(controller: AircraftController) = controller.land()
}
