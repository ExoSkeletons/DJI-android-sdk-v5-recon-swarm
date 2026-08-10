@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api.actions

import android.util.Log
import com.kcg.dr.flight.AircraftController
import com.kcg.dr.utils.ResourcesManager
import com.kcg.dr.location.UserMetrics
import com.kcg.dr.utils.TTSManager.speak
import com.kcg.dr.utils.asXYZ
import com.kcg.dr.utils.mag
import dji.sampleV5.aircraft.R
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
        with(ResourcesManager.resources) {
            when (topic) {
                Topic.Battery -> append(
                    getString(
                        R.string.report_fmt_battery,
                        controller.ac.batteryPercent.value
                    )
                )

                Topic.Location -> {
                    append(controller.ac.location.value?.let {
                        getString(
                            R.string.report_fmt_location_aircraft,
                            it.latitude,
                            it.longitude,
                            it.altitude
                        )
                    } ?: getString(R.string.location_unknown))
                    append(controller.ac.heading.value.let {
                        ", " + getString(R.string.report_fmt_heading, it)
                    })
                    append(user?.liveLocation?.value?.let {
                        ", " + getString(
                            R.string.report_fmt_location_device,
                            it.latitude,
                            it.longitude
                        )
                    })
                }

                Topic.Velocity -> append(controller.ac.velocity.value.let {
                    // getString(R.string.report_fmt_velocity_aircraft_xyz, it.x, it.y, it.z)
                    getString(R.string.report_fmt_velocity_aircraft, it.asXYZ().mag)
                })
            }
        }
    }

    override val description: String get() = "Report ${of.joinToString(", ")} Status"
}