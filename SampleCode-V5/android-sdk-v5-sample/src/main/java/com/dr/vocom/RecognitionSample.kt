@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.dr.vocom

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RecognitionSample(
    val timestamp: Double,
    @SerialName("roi_norm")
    val roi: List<Double>,
    val objects: List<ObstacleInfo> = listOf()
) {
    @Serializable
    data class ObstacleInfo(
        @SerialName("class")
        var type: ObstacleType = ObstacleType.Human,
        @SerialName("confidence")
        val confidence: Double,
        @SerialName("offset_center")
        val position: List<Double>,
        val boundingBox: List<Double> = listOf(0.0, 0.0, 0.0, 0.0),
        var distance: Int = 0,
        var direction: Direction = Direction.Front,
        @SerialName("moving")
        var inMotion: Boolean,

        var count: Int = 1
    ) {

        @Serializable
        enum class ObstacleType {
            @SerialName("null")
            Unknown,

            @SerialName("pedestrian")
            Human,

            @SerialName("animal")
            Animal,

            @SerialName("vehicle")
            Vehicle,

            @SerialName("car")
            Car,

            @SerialName("truck")
            Truck,
            Machinery
        }

        @Serializable
        enum class Direction {
            Away,
            Up, Down,
            Front, Back, Left, Right,
            North, West, South, East;

        }
    }

}