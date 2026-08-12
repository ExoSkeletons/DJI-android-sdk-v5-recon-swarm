@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api.actions

import com.kcg.dr.flight.AircraftController
import com.kcg.dr.flight.AircraftController.CircleFaceMode
import com.kcg.dr.location.UserMetrics
import kotlinx.schema.generator.json.SerialDescription
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("scan_ground")
@SerialDescription("Fly a circle while looking at ground")
data class ScanGround(
    val radius: Double,
    @property:SerialDescription("0..6 (m/s)")
    val velocity: Double = 4.0,
    @property:SerialDescription("height to scan from")
    val height: Double? = null,
    @property:SerialName("facing")
    val faceMode: CircleFaceMode = CircleFaceMode.OUTER,
    val clockwise: Boolean = true,
) : Action {
    override suspend fun act(aircraft: AircraftController, user: UserMetrics?) {
        aircraft.pitchCamera(-90.0)
        val h0 = aircraft.ac.height.value
        height?.let { aircraft.ascendTo(it) }
        aircraft.scanGround(radius, velocity, faceMode, clockwise)
        aircraft.ascendTo(h0)
    }
}
