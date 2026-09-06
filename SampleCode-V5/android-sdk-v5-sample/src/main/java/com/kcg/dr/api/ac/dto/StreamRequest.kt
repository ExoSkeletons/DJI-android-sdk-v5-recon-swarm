@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api.ac.dto

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
data class StreamRequest(
    val rtmpUrl: String? = null
)