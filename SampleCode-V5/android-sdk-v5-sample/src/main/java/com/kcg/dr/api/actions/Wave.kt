@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api.actions

import com.kcg.dr.flight.AircraftController
import kotlinx.schema.generator.json.SerialDescription
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("wave")
@SerialDescription("Demo function to Wave the camera in a cute way")
data class Wave(val count: Int = 2) : Action {
    override suspend fun act(controller: AircraftController) =
        controller.wave(count)
}
