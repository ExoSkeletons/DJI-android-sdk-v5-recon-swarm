@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.djiutils.dto

import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
@SerialName("LocationCoordinate2D")
data class LocationCoordinate2DSer(
    val latitude: Double,
    val longitude: Double,
)

object LocationCoordinate2DSerializer : KSerializer<LocationCoordinate2D> {
    override val descriptor: SerialDescriptor = LocationCoordinate2DSer.serializer().descriptor

    override fun serialize(encoder: Encoder, value: LocationCoordinate2D) {
        val sur = LocationCoordinate2DSer(value.latitude, value.longitude)
        encoder.encodeSerializableValue(LocationCoordinate2DSer.serializer(), sur)
    }

    override fun deserialize(decoder: Decoder): LocationCoordinate2D {
        val sur = decoder.decodeSerializableValue(LocationCoordinate2DSer.serializer())
        return LocationCoordinate2D(sur.latitude, sur.longitude)
    }
}
