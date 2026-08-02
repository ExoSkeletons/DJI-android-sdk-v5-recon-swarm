@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api

import com.kcg.dr.api.Responses.ok
import com.kcg.dr.api.Responses.status
import com.kcg.dr.api.SerializerSurrogates.LocationCoordinate2DSerializer
import com.kcg.dr.api.SerializerSurrogates.LocationCoordinate3DSerializer
import com.kcg.dr.flight.AircraftController
import com.kcg.dr.flight.AircraftController.CircleFaceMode
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.delay
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.time.Duration.Companion.seconds


@Serializable
@SerialName("action")
sealed interface Action {
    suspend fun act(controller: AircraftController): Any?
}

class TemporalActions {
    @Serializable
    @SerialName("delay")
    data class Delay(val seconds: Double) : Action {
        override suspend fun act(controller: AircraftController) =
            delay(seconds.seconds)
    }
}

class BasicActions{
    @Serializable
    @SerialName("takeoff")
    object Takeoff : Action {
        override suspend fun act(controller: AircraftController) = controller.takeoff()
    }

    @Serializable
    @SerialName("land")
    object Land : Action {
        override suspend fun act(controller: AircraftController) = controller.land()
    }

    @Serializable
    @SerialName("stop")
    object Stop : Action {
        override suspend fun act(controller: AircraftController) = controller.stop()
    }
}

class FlightActions {
    @Serializable
    @SerialName("fly_by")
    data class FlyBy(
        val x: Double = 0.0,
        val y: Double = 0.0,
        val z: Double = 0.0,
        val velocity: Double = 1.0,
    ) : Action {
        override suspend fun act(controller: AircraftController) =
            controller.flyBy(Triple(x, y, z), velocity)
    }

    @Serializable
    @SerialName("spin_by")
    data class SpinBy(
        val degrees: Double,
        val angularVelocity: Double = 70.0,
    ) : Action {
        override suspend fun act(controller: AircraftController) =
            controller.spinBy(degrees, angularVelocity)
    }

    @Serializable
    @SerialName("fly_gps")
    data class FlyTo(
        @Serializable(with = LocationCoordinate3DSerializer::class)
        val target: LocationCoordinate3D,
        @SerialName("velocity")
        val maxVelocity: Double
    ) : Action {
        override suspend fun act(controller: AircraftController) =
            controller.flyToSticks(target, maxVelocity = maxVelocity)
    }

    @Serializable
    @SerialName("look_at")
    data class LookAt(
        @Serializable(with = LocationCoordinate2DSerializer::class)
        val target: LocationCoordinate2D,
        val height: Double? = null
    ) : Action {
        override suspend fun act(controller: AircraftController) =
            controller.lookAtWithSpin(target, this@LookAt.height)
    }
}

class PatternActions {
    @Serializable
    @SerialName("fly_circle")
    data class Circle(
        val radius: Double,
        val velocity: Double,
        val count: Double = 1.0,
        val clockwise: Boolean = true,
        @SerialName("facing")
        val faceMode: CircleFaceMode = CircleFaceMode.CENTER,
    ) : Action {
        override suspend fun act(controller: AircraftController) =
            controller.flyCircle(radius, velocity, count, clockwise, faceMode)
    }

    @Serializable
    @SerialName("fly_square")
    data class Square(
        val side: Double,
        val velocity: Double,
        val clockwise: Boolean = true,
    ) : Action {
        override suspend fun act(controller: AircraftController) =
            controller.flySquare(side, velocity, clockwise)
    }
}

class CameraActions {
    @Serializable
    @SerialName("gimbal_pitch")
    data class GimbalPitch(val angle: Double) : Action {
        override suspend fun act(controller: AircraftController) =
            controller.pitchCamera(angle)
    }

    @Serializable
    @SerialName("wave")
    data class Wave(val count: Int = 2) : Action {
        override suspend fun act(controller: AircraftController) =
            controller.wave(count)
    }
}

@Serializable
data class FlyRequest(val mission: List<Action>)

@OptIn(ExperimentalSerializationApi::class)
fun Route.controllerRoute(c: () -> AircraftController?) {
    lateinit var controller: AircraftController
    intercept(ApplicationCallPipeline.Plugins) {
        val cr = c()
        if (cr == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, "AircraftController not initialized.")
            finish() // This prevents the actual get/post handlers below from running
            return@intercept
        }
        controller = cr
    }

    get("/") { call.respond(status { "controller is ready" }) }
    post("/flyTo") {
        val request = call.receive<FlightActions.FlyTo>()
        controller.fly {
            flyToSticks(
                target = request.target,
                maxVelocity = request.maxVelocity
            )
        }
        call.respond(ok {
            put(
                FlightActions.FlyTo::class.serializer().descriptor.serialName,
                request.target.toJson().toJsonElement()
            )
        })
    }
    post("/lookAt") {
        val request = call.receive<FlightActions.LookAt>()
        controller.fly { lookAtWithSpin(request.target, request.height) }
        call.respond(ok {
            put(
                FlightActions.LookAt::class.serializer().descriptor.serialName,
                request.target.toJson().toJsonElement()
            )
        })
    }

    post("/fly") {
        val request = call.receive<FlyRequest>()
        controller.fly {
            for (action: Action in request.mission)
                action.act(controller)
        }
        // respond without waiting for completion
        call.respond(status { "starting mission" })
    }

    post("/stop") {
        controller.stop()
    }
    post("/takeoff") {
        controller.fly { takeoff() }
    }
    post("/land") {
        controller.fly { land() }
    }

    get("/(wave|hi|hey|hello)".toRegex()) {
        controller.fly { wave() }
        call.respond(status { "Hello! o/" })
    }
}