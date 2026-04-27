@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api

import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.common.Velocity3D
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject

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

class SerializerSurrogates() {
    @Serializable
    data class LocationCoordinate2DSer(
        val latitude: Double,
        val longitude: Double,
    )

    object LocationCoordinate2DSerializer : KSerializer<LocationCoordinate2D> {
        override val descriptor: SerialDescriptor = LocationCoordinate2DSer.serializer().descriptor

        override fun serialize(encoder: Encoder, value: LocationCoordinate2D) {
            // Convert DJI object -> Surrogate -> JSON
            val sur = LocationCoordinate2DSer(value.latitude, value.longitude)
            encoder.encodeSerializableValue(LocationCoordinate2DSer.serializer(), sur)
        }

        override fun deserialize(decoder: Decoder): LocationCoordinate2D {
            // Convert JSON -> Surrogate -> DJI object
            val sur = decoder.decodeSerializableValue(LocationCoordinate2DSer.serializer())
            return LocationCoordinate2D(sur.latitude, sur.longitude)
        }
    }

    @Serializable
    data class LocationCoordinate3DSer(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double,
    )

    object LocationCoordinate3DSerializer : KSerializer<LocationCoordinate3D> {
        override val descriptor: SerialDescriptor = LocationCoordinate3DSer.serializer().descriptor

        override fun serialize(encoder: Encoder, value: LocationCoordinate3D) {
            // Convert DJI object -> Surrogate -> JSON
            val sur = LocationCoordinate3DSer(value.latitude, value.longitude, value.altitude)
            encoder.encodeSerializableValue(LocationCoordinate3DSer.serializer(), sur)
        }

        override fun deserialize(decoder: Decoder): LocationCoordinate3D {
            // Convert JSON -> Surrogate -> DJI object
            val sur = decoder.decodeSerializableValue(LocationCoordinate3DSer.serializer())
            return LocationCoordinate3D(sur.latitude, sur.longitude, sur.altitude)
        }
    }

    @Serializable
    data class Velocity3DSer(
        val x: Double,
        val y: Double,
        val z: Double,
    )

    object Velocity3DSerializer : KSerializer<Velocity3D> {
        override val descriptor: SerialDescriptor = Velocity3DSer.serializer().descriptor

        override fun serialize(encoder: Encoder, value: Velocity3D) {
            // Convert DJI object -> Surrogate -> JSON
            val sur = Velocity3DSer(value.x, value.y, value.z)
            encoder.encodeSerializableValue(Velocity3DSer.serializer(), sur)
        }

        override fun deserialize(decoder: Decoder): Velocity3D {
            // Convert JSON -> Surrogate -> DJI object
            val sur = decoder.decodeSerializableValue(Velocity3DSer.serializer())
            return Velocity3D(sur.x, sur.y, sur.z)
        }
    }
}