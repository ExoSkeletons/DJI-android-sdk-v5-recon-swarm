@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api.actions

import com.kcg.dr.api.SerializerSurrogates.LocationCoordinate3DSerializer
import com.kcg.dr.flight.AircraftController
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
    @SerialName("(m/s)")
    val maxVelocity: Double
) : Action {
    override suspend fun act(controller: AircraftController) =
        controller.flyToSticks(target, maxVelocity = maxVelocity)

    override val description get() = "Fly to ${target.toJson()}"
}
