package com.kcg.dr.api.ac.routes

import com.aviadl40.utils.json.toElement
import com.aviadl40.utils.json.toJsonElement
import com.kcg.dr.api.ac.dto.StreamRequest
import com.kcg.dr.api.ac.dto.actions.Action
import com.kcg.dr.api.ac.dto.actions.FlyTo
import com.kcg.dr.api.ac.dto.actions.LookAt
import com.kcg.dr.api.responses.errorResponse
import com.kcg.dr.api.responses.exceptResponse
import com.kcg.dr.api.responses.ok
import com.kcg.dr.api.responses.status
import com.kcg.dr.flight.AircraftController
import com.kcg.dr.location.UserMetrics
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer


fun Route.controllerRoute(
    controllerProvider: () -> AircraftController?,
    userProvider: () -> UserMetrics?
) {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        @OptIn(ExperimentalSerializationApi::class)
        decodeEnumsCaseInsensitive = true
        @OptIn(ExperimentalSerializationApi::class)
        allowComments = true
        @OptIn(ExperimentalSerializationApi::class)
        allowTrailingComma = true
    }

    lateinit var controller: AircraftController
    lateinit var user: UserMetrics

    intercept(ApplicationCallPipeline.Plugins) {
        val cr = controllerProvider()
        if (cr == null) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                "AircraftController not initialized."
            )
            finish() // This prevents the actual get/post handlers below from running
            return@intercept
        }
        controller = cr

        val usr = userProvider()
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
            @OptIn(InternalSerializationApi::class)
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
            @OptIn(InternalSerializationApi::class)
            put(
                LookAt::class.serializer().descriptor.serialName,
                request.target.toJson().toJsonElement()
            )
        })
    }

    post("/fly") {
        val actions = when (val element = call.receive<JsonElement>()) {
            is JsonArray -> element.map { json.decodeFromJsonElement<Action>(it) }
            is JsonObject -> listOf(json.decodeFromJsonElement<Action>(element))
            else -> throw BadRequestException("Unsupported JSON format for Action")
        }
        controller.fly { actions.forEach { action -> action.act(this, user) } }
        call.respond(ok {
            put("actions", JsonArray(actions.map { json.encodeToJsonElement(it) }))
        })
    }

    post("/stop") {
        controller.stop()
        call.respond(status { "stop" })
    }
    post("/takeoff") {
        controller.fly { takeoff() }
        call.respond(status { "taking off" })
    }
    post("/land") {
        controller.fly { land() }
        call.respond(status { "landing" })
    }

    get("/(wave|hi|hey|hello)".toRegex()) {
        controller.fly { wave() }
        call.respond(status { "Hello! o/" })
    }

    route("/stream") {
        post("/start") {
            val request = call.receive<StreamRequest>()
            val url = request.rtmpUrl?.trim('"')
            if (url == null) {
                call.respond(errorResponse { "rtmp url is required" })
                return@post
            }
            runCatching {
                controller.cam.startStream(url)
                call.respond(ok {
                    put("message", "Stream started")
                    put("url", url)
                })
            }.onFailure { e ->
                call.respond(exceptResponse(e))
            }
        }
        post("/stop") {
            runCatching {
                controller.cam.stopStream()
                call.respond(ok {
                    put("message", "Stream stopped")
                })
            }.onFailure { e ->
                call.respond(exceptResponse(e))
            }
        }
        get("/status") {
            call.respond(ok {
                put("isStreaming", controller.cam.isStreaming.value)
                controller.cam.liveStreamStatus.value?.toElement()?.let {
                    put("status", it)
                }
            })
        }
    }
}