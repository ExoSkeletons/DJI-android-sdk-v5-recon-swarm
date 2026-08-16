@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api.dto.actions

import com.kcg.dr.flight.AircraftController
import com.kcg.dr.location.UserMetrics
import kotlinx.coroutines.delay
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

@Serializable
@SerialName("delay")
data class Delay(val seconds: Double) : Action {
    override suspend fun act(aircraft: AircraftController, user: UserMetrics?) = delay(seconds.seconds)
    override val description get() = "Wait $seconds seconds"
}