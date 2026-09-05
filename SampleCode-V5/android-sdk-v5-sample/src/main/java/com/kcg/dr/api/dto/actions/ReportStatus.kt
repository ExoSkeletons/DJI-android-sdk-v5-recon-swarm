@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api.dto.actions

import android.util.Log
import com.kcg.dr.flight.AircraftController
import com.kcg.dr.location.UserMetrics
import com.kcg.dr.djiutils.LocationUtils.distanceTo
import com.kcg.dr.managers.ResourcesManager
import com.kcg.dr.managers.TTSManager.speak
import com.kcg.dr.djiutils.as2D
import com.kcg.dr.djiutils.asXYZ
import com.kcg.dr.djiutils.mag
import com.aviadl40.utils.android.LocaleUtils
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.util.ToastUtils
import kotlinx.schema.generator.json.SerialDescription
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
@SerialName("report_status")
@SerialDescription("Inform user the status of one or more metrics")
data class ReportStatus(
    @property:SerialDescription("Metrics to report on. Empty list reports all")
    val of: List<Metric> = emptyList(),
) : Action {
    @Serializable
    enum class Metric {
        @SerialName("battery")
        Battery,

        @SerialName("user_location")
        @SerialDescription("Pick this if user asks where they are. e.g: \"where am i?\", \"whats my location\"")
        UserLocation,

        @SerialName("aircraft_location")
        @SerialDescription("Pick only if asked where aircraft is. e.g: \"where are you?\", \"where is aircraft?\"")
        Location,

        @SerialName("speed")
        Velocity,

        @SerialName("distance")
        @SerialDescription("distance from user")
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
        speak(report, LocaleUtils.preferred)
        if (of.contains(Metric.UserLocation)) {
            user?.liveLocation?.value?.let {
                aircraft.lookAtWithSpin(it.as2D, user.humanHeight.value)
                aircraft.wave()
            }
        }
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
                    append(
                        run {
                            val loc = controller.ac.location.value
                                ?: return@run getString(R.string.location_unknown)
                            val userLoc = user?.liveLocation?.value
                                ?: return@run getString(R.string.report_fmt_location_device_unknown)

                            getString(
                                R.string.report_fmt_distance_aircraft,
                                loc.distanceTo(userLoc)
                            )
                        }
                    )
                }
            }
        }
    }

    override val description: String get() = "Report ${of.joinToString(", ")} Status"
}