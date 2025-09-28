@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.vocom

import com.kcg.dr.vocom.RecognitionMemory.RecognitionSample.ObstacleInfo
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class RecognitionMemory(private val memTime: Double) {
    @Serializable
    data class RecognitionSample(
        val timestamp: Double,
        @SerialName("roi_norm")
        val roi: List<Double>,
        val objects: List<ObstacleInfo> = listOf()
    ) {
        @Serializable
        data class ObstacleInfo(
            @SerialName("id")
            val id: Long = -1,
            @SerialName("class")
            var type: ObstacleType = ObstacleType.Unknown,
            @SerialName("confidence")
            val confidence: Double,

            @SerialName("offset_center")
            val position: List<Double>,
            val boundingBox: List<Double> = listOf(0.0, 0.0, 0.0, 0.0),

            @SerialName("moving")
            var inMotion: Boolean = false,

            var lastSeenTimestamp: Double = 0.0,
            var distance: Int = 0,
            var direction: Direction = Direction.Front,
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

    val memory = mutableListOf<ObstacleInfo>()

    fun see(sample: RecognitionSample) {
        onSeen(sample.objects)

        // Update timestamp for all objects
        sample.objects.forEach { it.lastSeenTimestamp = sample.timestamp }

        // Map objects that had their info updated (same object ID)
        val updatedObjects = memory.mapNotNull { oldItem ->
            sample.objects.find { newItem -> newItem.id == oldItem.id }
                ?.let { newItem -> oldItem to newItem }
        }
        // Remove existing (outdated) memory for newly seen objects (same object ID)
        val newObjects = sample.objects.filter { it.id !in memory.map { obj -> obj.id } }
        onMemoryAdded(newObjects)
        onMemoryUpdated(updatedObjects)
        memory.removeAll { it.id in sample.objects.map { obj -> obj.id } }
        // Add newly seen objects to memory
        memory.addAll(sample.objects)

        // Forget memory of objects that have not been seen for long
        val forgotten = memory.filter { it.lastSeenTimestamp < sample.timestamp - memTime }
        memory.removeAll(forgotten)
        onForget(forgotten)
    }

    var onSeen: (sample: List<RecognitionSample.ObstacleInfo>) -> Unit = {}
    var onMemoryAdded: (new: List<RecognitionSample.ObstacleInfo>) -> Unit = {}
    var onMemoryUpdated: (updated: List<Pair<RecognitionSample.ObstacleInfo, RecognitionSample.ObstacleInfo>>) -> Unit = {}
    var onForget: (forgotten: List<RecognitionSample.ObstacleInfo>) -> Unit = {}
}