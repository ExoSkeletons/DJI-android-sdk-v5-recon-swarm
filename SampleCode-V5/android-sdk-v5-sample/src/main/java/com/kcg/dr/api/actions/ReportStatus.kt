@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api.actions

import android.util.Log
import com.kcg.dr.flight.AircraftController
import com.kcg.dr.location.UserMetrics
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
        @SerialName("battery")
        Battery,

        @SerialName("location")
        @SerialDescription("If asked where we are.")
        Location,

        @SerialName("speed")
        @SerialDescription("If asked how fast aircraft is moving.")
        Velocity,
    }

    override suspend fun act(aircraft: AircraftController, user: UserMetrics?) {
        val topics = (of.takeIf { it.isNotEmpty() } ?: Topic.values().toList())
        val report = buildString {
            topics.forEach {
                appendReportOn(it, aircraft, user)
                appendLine()
            }
        }
        speak(report)
        ToastUtils.showToast(report)
        Log.i("ReportStatus", report)
    }

    private fun StringBuilder.appendReportOn(
        topic: Topic,
        controller: AircraftController,
        user: UserMetrics?
    ) {
        when (topic) {
            Topic.Battery -> append("Battery is at ${controller.ac.batteryPercent.value}%")
            Topic.Location -> {
                append(controller.ac.location.value?.let { // todo: add resources/context to act and use stringRes
                    "I'm at ${it.toJson()}"
                } ?: "Location is unknown")
                append(controller.ac.heading.value.let {
                    ", Heading is ${it.toDegrees()}°"
                })
                append(user?.liveLocation?.value.let {
                    ", You're at ${it?.toJson()}"
                })
            }

            Topic.Velocity -> append(controller.ac.velocity.value.let {
                "Moving at ${it.x} x, ${it.y} y, ${it.z}z m/s"
            })
        }
    }
}