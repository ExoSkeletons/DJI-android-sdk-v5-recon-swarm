@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api.actions

import com.kcg.dr.flight.AircraftController
import com.kcg.dr.utils.TTSManager.speak
import com.kcg.dr.utils.toDegrees
import dji.sampleV5.aircraft.util.ToastUtils
import kotlinx.schema.generator.json.SerialDescription
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
@SerialName("report_status")
@SerialDescription("Tells user aircraft status. Use if asked questions related to aircraft state.")
data class ReportStatus(
    @property:SerialDescription("Topics to report on. If empty, reports all.")
    val of: List<Topic> = emptyList(),
) : Action {
    @Serializable
    enum class Topic {
        Battery,

        @SerialDescription("If asked where aircraft is.")
        Location,

//        @SerialDescription("If asked how fast aircraft is moving.")
//        Velocity,
    }

    override suspend fun act(controller: AircraftController) =
        (of.takeIf { it.isNotEmpty() } ?: Topic.values().toList()).forEach {
            reportOn(it, controller)
        }

    fun reportOn(topic: Topic, controller: AircraftController) {
        val text = when (topic) {
            Topic.Battery -> "Battery is at ${controller.ac.batteryPercent.value}%"
            Topic.Location -> buildString {
                append(controller.ac.location.value?.let {
                    "I'm at ${it.toJson()}"
                } ?: "Location is unknown")
                append(controller.ac.heading.value?.let {
                    ", Heading is ${it.toDegrees()}°"
                })
            }

            // Topic.Velocity -> "Flying speed is ${controller.ac.velocity.value}ms"
        }
        speak(text)
        ToastUtils.showToast(text)
    }
}