@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api.ac.dto.actions

import com.kcg.dr.djiutils.dto.LocationCoordinate2DSerializer
import com.kcg.dr.flight.AircraftController
import com.kcg.dr.location.UserMetrics
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import kotlinx.schema.generator.json.SerialDescription
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("look_at")
@SerialDescription("Rotates aircraft camera Gimbal to point/look at a specific GPS location")
data class LookAt(
    @Serializable(with = LocationCoordinate2DSerializer::class)
    val target: LocationCoordinate2D,
    val height: Double? = null
) : Action {
    override suspend fun act(aircraft: AircraftController, user: UserMetrics?) =
        aircraft.lookAtWithSpin(target, height)

    override val description get() = "Look at ${target.toJson()}"
}
