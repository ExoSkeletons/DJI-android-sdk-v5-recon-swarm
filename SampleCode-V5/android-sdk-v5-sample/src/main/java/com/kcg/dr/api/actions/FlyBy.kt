@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api.actions

import com.kcg.dr.flight.AircraftController
import com.kcg.dr.location.UserMetrics
import kotlinx.schema.generator.json.SerialDescription
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("fly_by")
@SerialDescription("Moves aircraft relative to it's current position (m).")
data class FlyBy(
    @property:SerialDescription("x+ is forward")
    val dx: Double = 0.0,
    @property:SerialDescription("y+ is right")
    val dy: Double = 0.0,
    @property:SerialDescription("z+ is up")
    val dz: Double = 0.0,
    @property:SerialDescription("(m/s)")
    val velocity: Double = 4.0,
) : Action {
    override suspend fun act(aircraft: AircraftController, user: UserMetrics?) =
        aircraft.flyBy(Triple(dx, dy, dz), velocity)

    override val description = "Fly ${
        buildString {
            dx.takeIf { it != 0.0 }
                ?.let { append(" ${it}m" + (if (it > 0) "forward" else "backward")) }
            dy.takeIf { it != 0.0 }
                ?.let { append(" ${it}m" + (if (it > 0) "right" else "left")) }
            dz.takeIf { it != 0.0 }
                ?.let { append(" ${it}m" + (if (it > 0) "up" else "down")) }
        }
    } at $velocity m/s"
}
