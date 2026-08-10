@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api.actions

import android.util.Log
import com.kcg.dr.flight.AircraftController
import com.kcg.dr.location.UserMetrics
import com.kcg.dr.utils.ResourcesManager
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
@SerialDescription("Tell user the status of some aircraft metrics. Use if asked questions related to aircraft state.")
data class ReportStatus(
    @property:SerialDescription("Metrics to report on. Empty list for all.")
    val of: List<Metric> = emptyList(),
) : Action {
    @Serializable
    enum class Metric {
        @SerialName("battery")
        Battery,

        @SerialName("location")
        @SerialDescription("If asked where we are.")
        Location,

        @SerialName("user_location")
        UserLocation,

        @SerialName("speed")
        @SerialDescription("aircraft speed.")
        Velocity,

        @SerialName("distance")
        @SerialDescription("aircraft distance from user.")
        Distance,
    }

    override suspend fun act(aircraft: AircraftController, user: UserMetrics?) {
        val metricCategories = (of.takeIf { it.isNotEmpty() } ?: Metric.values().toList())
        val report = buildString {
            metricCategories.forEach {
                appendReportOn(it, aircraft, user)
                appendLine()
            }
        }
        speak(report, ResourcesManager.locale)
        ToastUtils.showToast(report)
        Log.i("ReportStatus", report)
    }

    private fun StringBuilder.appendReportOn(
        metric: Metric,
        controller: AircraftController,
        user: UserMetrics?
    ) {
        with(ResourcesManager.resources) {
            Log.d("ResourceManager", getString(R.string.command_hello))
            when (metric) {
                Metric.Battery -> append(
                    getString(
                        R.string.report_fmt_battery,
                        controller.ac.batteryPercent.value
                    )
                )

                Metric.Location -> {
                    append(controller.ac.location.value?.let {
                        getString(
                            R.string.report_fmt_location_aircraft,
                            it.latitude,
                            it.longitude,
                            it.altitude
                        )
                    } ?: getString(R.string.location_unknown))
                    controller.ac.heading.value.let {
                        append(
                            ", " + getString(
                                R.string.report_fmt_heading,
                                it
                            )
                        )
                    }
                }

                Metric.UserLocation -> {
                    user?.liveLocation?.value?.let {
                        append(
                            ".\n" + getString(
                                R.string.report_fmt_location_device,
                                it.latitude,
                                it.longitude
                            )
                        )
                    }
                }

                Metric.Velocity -> append(controller.ac.velocity.value.let {
                    // getString(R.string.report_fmt_velocity_aircraft_xyz, it.x, it.y, it.z)
                    getString(R.string.report_fmt_velocity_aircraft, it.asXYZ().mag)
                })

                Metric.Distance -> {
                    val loc = controller.ac.location.value ?: "I can't find my location"
                    val userLoc = user?.liveLocation?.value ?: "I can't find your location"
                }
            }
        }
    }

    override val description: String get() = "Report ${of.joinToString(", ")} Status"
}