@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.djiutils.dto

import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
@SerialName("LocationCoordinate3D")
data class LocationCoordinate3DSer(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
)

object LocationCoordinate3DSerializer : KSerializer<LocationCoordinate3D> {
    override val descriptor: SerialDescriptor = LocationCoordinate3DSer.serializer().descriptor

    override fun serialize(encoder: Encoder, value: LocationCoordinate3D) {
        val sur = LocationCoordinate3DSer(value.latitude, value.longitude, value.altitude)
        encoder.encodeSerializableValue(LocationCoordinate3DSer.serializer(), sur)
    }

    override fun deserialize(decoder: Decoder): LocationCoordinate3D {
        val sur = decoder.decodeSerializableValue(LocationCoordinate3DSer.serializer())
        return LocationCoordinate3D(sur.latitude, sur.longitude, sur.altitude)
    }
}
