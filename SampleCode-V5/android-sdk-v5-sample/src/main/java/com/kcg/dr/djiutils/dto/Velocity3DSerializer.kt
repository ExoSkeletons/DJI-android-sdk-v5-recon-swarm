@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.djiutils.dto

import dji.sdk.keyvalue.value.common.Velocity3D
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
@SerialName("Velocity3D")
data class Velocity3DSer(
    val x: Double,
    val y: Double,
    val z: Double,
)

object Velocity3DSerializer : KSerializer<Velocity3D> {
    override val descriptor: SerialDescriptor = Velocity3DSer.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Velocity3D) {
        val sur = Velocity3DSer(value.x, value.y, value.z)
        encoder.encodeSerializableValue(Velocity3DSer.serializer(), sur)
    }

    override fun deserialize(decoder: Decoder): Velocity3D {
        val sur = decoder.decodeSerializableValue(Velocity3DSer.serializer())
        return Velocity3D(sur.x, sur.y, sur.z)
    }
}
