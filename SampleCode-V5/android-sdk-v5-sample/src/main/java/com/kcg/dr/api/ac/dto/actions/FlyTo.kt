@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api.ac.dto.actions

import com.kcg.dr.djiutils.dto.LocationCoordinate3DSerializer
import com.kcg.dr.flight.AircraftController
import com.kcg.dr.location.UserMetrics
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import kotlinx.schema.generator.json.SerialDescription
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("fly_gps")
@SerialDescription("Flies the aircraft to a specific GPS based (lat/lng/alt) location")
data class FlyTo(
    @Serializable(with = LocationCoordinate3DSerializer::class)
    @property:SerialDescription("Destination GPS location (lat/lng/alt)")
    val target: LocationCoordinate3D,
    @SerialName("0..10 (m/s)")
    val maxVelocity: Double = 8.0
) : Action {
    override suspend fun act(aircraft: AircraftController, user: UserMetrics?) =
        aircraft.flyToSticks(target, maxVelocity = maxVelocity)

    override val description get() = "Fly to ${target.toJson()}"
}
