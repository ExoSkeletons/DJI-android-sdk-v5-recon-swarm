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
import kotlinx.schema.generator.json.SerialDescription
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

    val description: String get() = this::class.serializer().descriptor.serialName
}

sealed class TemporalActions {
    @Serializable
    @SerialName("delay")
    data class Delay(val seconds: Double) : Action {
        override suspend fun act(controller: AircraftController) = delay(seconds.seconds)
        override val description get() = "Wait $seconds seconds"
    }

    @Serializable
    @SerialName("repeat")
    data class Repeat(val times: Int, val action: Action) : Action {
        override suspend fun act(controller: AircraftController) {
            for (i in 1..times)
                action.act(controller)
        }

        override val description get() = "Repeat (${action.description}) $times times"
    }
}

sealed class BasicActions {
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
}

sealed class FlightActions {
    @Serializable
    @SerialName("fly_by")
    @SerialDescription("Moves aircraft relative to it's current position (m).")
    data class FlyBy(
        @property:SerialDescription("x+ is forward")
        val dx: Double = 0.0,
        @property:SerialDescription("y+ is right")
        val dy: Double = 0.0,
        @property:SerialDescription("z+ is up")
        val dz: Double = 0.0,
        @property:SerialDescription("(m/s)")
        val velocity: Double = 4.0,
    ) : Action {
        override suspend fun act(controller: AircraftController) =
            controller.flyBy(Triple(dx, dy, dz), velocity)

        override val description = "Fly ${
            buildString {
                dx.takeIf { it != 0.0 }
                    ?.let { append(" ${it}m" + (if (it > 0) "forward" else "backward")) }
                dy.takeIf { it != 0.0 }
                    ?.let { append(" ${it}m" + (if (it > 0) "right" else "left")) }
                dz.takeIf { it != 0.0 }
                    ?.let { append(" ${it}m" + (if (it > 0) "up" else "down")) }
            }
        } at $velocity m/s"
    }

    @Serializable
    @SerialName("spin_by")
    @SerialDescription("Spins aircraft relative to it's current heading.")
    data class SpinBy(
        val degrees: Double,
    ) : Action {
        override suspend fun act(controller: AircraftController) = controller.spinBy(degrees)
        override val description = "Spin ${degrees}°"
    }

    @Serializable
    @SerialName("fly_gps")
    @SerialDescription("Flies the aircraft to a specific GPS based (lat/lng/alt) location")
    data class FlyTo(
        @Serializable(with = LocationCoordinate3DSerializer::class)
        @property:SerialDescription("Destination GPS location (lat/lng/alt)")
        val target: LocationCoordinate3D,
        @SerialName("velocity")
        val maxVelocity: Double
    ) : Action {
        override suspend fun act(controller: AircraftController) =
            controller.flyToSticks(target, maxVelocity = maxVelocity)

        override val description get() = "Fly to ${target.toJson()}"
    }
}

sealed class PatternActions {
    @Serializable
    @SerialName("fly_circle")
    data class Circle(
        val radius: Double,
        val velocity: Double,
        val count: Double = 1.0,
        val clockwise: Boolean = true,
        @property:SerialName("facing")
        val faceMode: CircleFaceMode = CircleFaceMode.CENTER,
    ) : Action {
        override suspend fun act(controller: AircraftController) =
            controller.flyCircle(radius, velocity, count, clockwise, faceMode)
    }

    @Serializable
    @SerialName("fly_square")
    data class Square(
        @property:SerialDescription("Side length (m)")
        val side: Double,
        val velocity: Double,
        val clockwise: Boolean = true,
    ) : Action {
        override suspend fun act(controller: AircraftController) =
            controller.flySquare(side, velocity, clockwise)
    }

    @Serializable
    @SerialName("scan_ground")
    @SerialDescription("Fly a circle while looking at ground")
    data class ScanGround(
        val radius: Double,
        val velocity: Double = 4.0,
        @property:SerialName("facing")
        val faceMode: CircleFaceMode = CircleFaceMode.OUTER,
        val clockwise: Boolean = true,
    ) : Action {
        override suspend fun act(controller: AircraftController) =
            controller.scanGround(radius, velocity, faceMode, clockwise)
    }
}

sealed class CameraActions {
    @Serializable
    @SerialName("gimbal_pitch")
    @SerialDescription("Pitches aircraft camera Gimbal up/down")
    data class GimbalPitch(val angle: Double) : Action {
        override suspend fun act(controller: AircraftController) =
            controller.pitchCamera(angle)

        override val description = "Pitch Gimbal to ${angle}°"
    }

    @Serializable
    @SerialName("look_at")
    @SerialDescription("Rotates aircraft camera Gimbal to point/look at a specific GPS location")
    data class LookAt(
        @Serializable(with = LocationCoordinate2DSerializer::class)
        val target: LocationCoordinate2D,
        val height: Double? = null
    ) : Action {
        override suspend fun act(controller: AircraftController) =
            controller.lookAtWithSpin(target, height)

        override val description get() = "Look at ${target.toJson()}"
    }

    @Serializable
    @SerialName("wave")
    @SerialDescription("Demo function to Wave the camera in a cute way")
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
        val request = call.receive<CameraActions.LookAt>()
        controller.fly { lookAtWithSpin(request.target, request.height) }
        call.respond(ok {
            put(
                CameraActions.LookAt::class.serializer().descriptor.serialName,
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