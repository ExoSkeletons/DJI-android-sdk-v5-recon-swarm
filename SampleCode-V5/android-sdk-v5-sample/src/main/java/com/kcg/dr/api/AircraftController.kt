@file:OptIn(InternalSerializationApi::class)

package com.kcg.dr.api

import com.kcg.dr.api.Responses.ok
import com.kcg.dr.api.Responses.status
import com.kcg.dr.api.actions.Action
import com.kcg.dr.api.actions.FlyTo
import com.kcg.dr.api.actions.LookAt
import com.kcg.dr.flight.AircraftController
import com.kcg.dr.location.UserMetrics
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

@Serializable
data class FlyRequest(val mission: List<Action>)

@OptIn(ExperimentalSerializationApi::class)
fun Route.controllerRoute(f: () -> Pair<AircraftController?, UserMetrics?>) {
    lateinit var controller: AircraftController
    lateinit var user: UserMetrics

    intercept(ApplicationCallPipeline.Plugins) {
        val (cr, usr) = f()
        if (cr == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, "AircraftController not initialized.")
            finish() // This prevents the actual get/post handlers below from running
            return@intercept
        }
        controller = cr
        if (usr != null)
            user = usr
    }

    get("/") { call.respond(status { "controller is ready" }) }
    post("/flyTo") {
        val request = call.receive<FlyTo>()
        controller.fly {
            flyToSticks(
                target = request.target,
                maxVelocity = request.maxVelocity
            )
        }
        call.respond(ok {
            put(
                FlyTo::class.serializer().descriptor.serialName,
                request.target.toJson().toJsonElement()
            )
        })
    }
    post("/lookAt") {
        val request = call.receive<LookAt>()
        controller.fly { lookAtWithSpin(request.target, request.height) }
        call.respond(ok {
            put(
                LookAt::class.serializer().descriptor.serialName,
                request.target.toJson().toJsonElement()
            )
        })
    }

    post("/fly") {
        val request = call.receive<FlyRequest>()
        controller.fly {
            for (action: Action in request.mission)
                action.act(controller, user)
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