@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.remote_api

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class Response(
    val ok: Boolean = true,
    val result: JsonElement? = null,
    val error: String? = null,
    val errorCode: String? = null
)