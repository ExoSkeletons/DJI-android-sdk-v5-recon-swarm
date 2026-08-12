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
    @property:SerialDescription("height to ascend to and scan at (m)")
    val height: Double? = null,
    @property:SerialDescription("scan circle radius (m)")
    val radius: Double = 3.0,
    @property:SerialDescription("1..6 (m/s)")
    val velocity: Double = 4.0,
    @property:SerialName("facing")
    val faceMode: CircleFaceMode = CircleFaceMode.OUTWARDS,
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
