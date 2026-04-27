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
import kotlinx.coroutines.delay
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds


@Serializable
sealed class FlightAction {
    abstract suspend fun act(controller: AircraftController)
}

@Serializable
@SerialName("delay")
data class Delay(val seconds: Double) : FlightAction() {
    override suspend fun act(controller: AircraftController) =
        delay(seconds.seconds)
}

@Serializable
@SerialName("fly_to")
data class FlyTo(
    @Serializable(with = LocationCoordinate3DSerializer::class)
    val target: LocationCoordinate3D,
    val maxVelocity: Double
) : FlightAction() {
    override suspend fun act(controller: AircraftController) =
        controller.flyTo(target)
}

@Serializable
@SerialName("look_at")
data class LookAt(
    @Serializable(with = LocationCoordinate2DSerializer::class)
    val target: LocationCoordinate2D,
    val height: Double
) : FlightAction() {
    override suspend fun act(controller: AircraftController) =
        controller.lookAtWithSpin(target, this@LookAt.height)
}

class PatternActions {
    @Serializable
    @SerialName("circle")
    data class Circle(
        val radius: Double,
        val velocity: Double,
        val count: Double = 1.0,
        val clockwise: Boolean = true,
        @SerialName("facing")
        val faceMode: AircraftController.CircleFaceMode = AircraftController.CircleFaceMode.CENTER,
    ) : Action() {
        override suspend fun act(controller: AircraftController) =
            controller.flyCircle(radius, velocity, count, clockwise, faceMode)
    }

    @Serializable
    @SerialName("square")
    data class Square(
        val side: Double,
        val velocity: Double,
        val clockwise: Boolean = true,
    ) : Action() {
        override suspend fun act(controller: AircraftController) =
            controller.flySquare(side, velocity, clockwise)
    }
}


@Serializable
data class FlyRequest(val mission: List<FlightAction>)

fun Route.controllerRoute(controller: AircraftController) {
    post("/flyTo") {
        val request = call.receive<FlyTo>()
        controller.fly {
            flyToSticks(
                target = request.target,
                maxVelocity = request.maxVelocity
            )
        }
    }
    post("/lookAt") {
        val request = call.receive<LookAt>()
        controller.fly { lookAtWithSpin(request.target, request.height) }
    }

    post("/fly") {
        val request = call.receive<FlyRequest>()
        controller.fly {
            for (request in request.mission) request.act(controller)
        }
    }

    post("/stop") { controller.stop() }
    post("/takeoff") { controller.takeoff() }
    post("/land") { controller.land() }
}