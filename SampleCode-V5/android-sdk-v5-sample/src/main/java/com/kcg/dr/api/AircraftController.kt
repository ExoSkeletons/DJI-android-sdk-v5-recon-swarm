@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api

import com.kcg.dr.LocationCoordinate2DSerializer
import com.kcg.dr.LocationCoordinate3DSerializer
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable


@Serializable
data class FlyToRequest(
    @Serializable(with = LocationCoordinate3DSerializer::class)
    val target: LocationCoordinate3D,
    val maxVelocity: Double,
)

@Serializable
data class LookAtRequest(
    @Serializable(with = LocationCoordinate2DSerializer::class)
    val target: LocationCoordinate2D,
    val height: Double,
)