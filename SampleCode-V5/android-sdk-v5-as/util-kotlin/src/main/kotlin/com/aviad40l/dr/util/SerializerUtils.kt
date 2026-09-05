@file:OptIn(InternalSerializationApi::class)

package com.aviad40l.dr.util

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import kotlin.collections.iterator

fun Any?.toElement(): JsonElement = when (val value = this) {
    null -> JsonNull
    is JSONArray -> {
        val list = mutableListOf<JsonElement>()
        for (i in 0 until value.length())
            list += value.opt(i).toElement()
        JsonArray(list)
    }

    is Collection<*> -> {
        val list = mutableListOf<JsonElement>()
        for (item in value)
            list += item.toElement()
        JsonArray(list)
    }

    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    is Enum<*> -> JsonPrimitive(value.name)
    is JSONObject -> value.toJsonElement()
    else -> JsonPrimitive(value.toString()) // fallback
}

fun JSONObject?.toJsonElement(): JsonElement {
    if (this == null) return JsonNull
    val content = mutableMapOf<String, JsonElement>()
    for (key in this.keys())
        content[key] = this.opt(key).toElement()
    return JsonObject(content)
}

fun JsonElement?.or(other: JsonElement): JsonElement = when {
    this == null || this is JsonNull -> other
    else -> this
}

fun Any?.or(other: JsonElement): JsonElement = when (val value = this) {
    null -> other
    else -> value.toElement()
}