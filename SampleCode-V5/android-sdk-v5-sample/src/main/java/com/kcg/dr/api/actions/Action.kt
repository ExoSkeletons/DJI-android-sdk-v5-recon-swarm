@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api.actions

import com.kcg.dr.flight.AircraftController
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

@Serializable
@SerialName("action")
sealed interface Action {
    suspend fun act(controller: AircraftController): Any?

    val description: String get() = this::class.serializer().descriptor.serialName
}
