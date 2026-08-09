@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api.actions

import com.kcg.dr.flight.AircraftController
import com.kcg.dr.flight.AircraftController.CircleFaceMode
import kotlinx.schema.generator.json.SerialDescription
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("scan_ground")
@SerialDescription("Fly a circle while looking at ground")
data class ScanGround(
    val radius: Double,
    val velocity: Double = 4.0,
    @property:SerialName("facing")
    val faceMode: CircleFaceMode = CircleFaceMode.OUTER,
    val clockwise: Boolean = true,
) : Action {
    override suspend fun act(controller: AircraftController) =
        controller.scanGround(radius, velocity, faceMode, clockwise)
}
