@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api

import com.kcg.dr.api.SerializerSurrogates.LocationCoordinate2DSerializer
import com.kcg.dr.api.SerializerSurrogates.LocationCoordinate3DSerializer
import com.kcg.dr.controller.AircraftController
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable


@Serializable
abstract class FlightRequest

@Serializable
data class SequenceRequest(
    val list: List<FlightRequest>,
) : FlightRequest()


@Serializable
data class DelayRequest(
    val seconds: Double,
) : FlightRequest()


@Serializable
data class FlyToRequest(
    @Serializable(with = LocationCoordinate3DSerializer::class)
    val target: LocationCoordinate3D,
    val maxVelocity: Double,
) : FlightRequest()

@Serializable
data class LookAtRequest(
    @Serializable(with = LocationCoordinate2DSerializer::class)
    val target: LocationCoordinate2D,
    val height: Double,
) : FlightRequest()


fun Route.controllerRoute(controller: AircraftController) {
    post("/flyTo") {
        val request = call.receive<FlyToRequest>()
        controller.fly {
            flyToSticks(
                target = request.target,
                maxVelocity = request.maxVelocity
            )
        }
    }

    post("/lookAt") {
        val request = call.receive<LookAtRequest>()
        controller.fly { lookAtWithSpin(request.target, request.height) }
    }

    post("/stop") { controller.stop() }
    post("/takeoff") { controller.takeoff() }
    post("/land") { controller.land() }
}