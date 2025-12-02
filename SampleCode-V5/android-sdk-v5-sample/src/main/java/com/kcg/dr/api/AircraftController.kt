@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject


@Serializable
data class ControllerRequest(
    val command: String, // TODO: enum
    val param: JsonObject? = null,
)

@Serializable
data class FlightMissionRequest(
    val actions: List<ControllerRequest>
)