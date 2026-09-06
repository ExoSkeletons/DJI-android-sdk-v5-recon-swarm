@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api.ac.dto.actions

import com.kcg.dr.flight.AircraftController
import com.kcg.dr.location.UserMetrics
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("takeoff")
object Takeoff : Action {
    override suspend fun act(aircraft: AircraftController, user: UserMetrics?) = aircraft.takeoff()
}
