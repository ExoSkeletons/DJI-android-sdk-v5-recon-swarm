package com.kcg.dr.api

import android.util.Log
import com.kcg.dr.CoroutineUtils.actionOrExcept
import com.kcg.dr.CoroutineUtils.getOrExcept
import com.kcg.dr.DJIErrorException
import com.kcg.dr.controller.AircraftController
import dji.sdk.keyvalue.key.AirLinkKey
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.v5.et.create
import dji.v5.et.get
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.IgnoreTrailingSlash
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TAG = "ApiHttpServer"


private fun djiErrorResponse(e: DJIErrorException): JsonObject = buildJsonObject {
    put("ok", false)
    put("djiError", buildJsonObject {
        with(e.error) {
            put("errorType", errorType().toElement())
            if (errorCode() != null) put("errorCode", errorCode())
            if (innerCode() != null) put("innerCode", innerCode())
            if (description() != null) put("description", description())
            if (hint() != null) put("hint", hint())

            if (errorCode().contains("handler( |_|-|.)*not( |_|-|.)*found".toRegex(RegexOption.IGNORE_CASE)))
                put(
                    "hint",
                    "Remote Controller might not be connected to Device. Have you connected the Device to the RC's USB port?"
                )
        }
    })
}

private fun exceptResponse(e: Exception): JsonObject = buildJsonObject {
    Log.e(TAG, "Exception: ${e.message}", e)
    buildJsonObject {
        put("ok", false)
        put("error", e.message)
    }
}

class ApiHttpServer(private val port: Int, private val controller: AircraftController? = null) {
    private var server: ApplicationEngine? = null

    fun stop() {
        server?.stop()
        server = null
        Log.i(TAG, "Ktor server stopped")
    }

    fun start() {
        if (server != null) return

        val host = "0.0.0.0"
        server = embeddedServer(CIO, host = host, port = port) {
            install(ContentNegotiation) { json() }
            install(IgnoreTrailingSlash)

            routing {
                // Home page
                get("/") {
                    call.respondText(
                        "<html><body><h2>Drone API Server Running. $host : $port</h2></body></html>",
                        contentType = ContentType.Text.Html
                    )
                }

                // Status
                route("/status") {
                    statusRoute()
                }

                controller?.let {
                    route("/controller") {
                        controllerRoute(it)
                    }
                }

                get("/fly") {
                    try {
                        val isFlying = FlightControllerKey.KeyIsFlying.create().get(false)
                        if (isFlying) {
                            call.respond(buildJsonObject {
                                put("ok", false)
                                put("error", "Aircraft already in air")
                            })
                            return@get
                        }
                        FlightControllerKey.KeyStartTakeoff.create().actionOrExcept()
                        call.respond(buildJsonObject { put("ok", true) })
                    } catch (e: DJIErrorException) {
                        call.respond(djiErrorResponse(e))
                    }
                }
                get("/land") {
                    try {
                        FlightControllerKey.KeyStartAutoLanding.create().actionOrExcept()
                        call.respond(buildJsonObject { put("ok", true) })
                    } catch (e: DJIErrorException) {
                        call.respond(djiErrorResponse(e))
                    }
                }

                // Key activation
                post("/key") {
                    try {
                        val jsonStr = call.receiveText()
                        val element = Json.parseToJsonElement(jsonStr)
                        val result = KeyActivator.handleKeyRequest(element)

                        call.respond(buildJsonObject {
                            put("ok", true)
                            put("result", result.toString())
                        })
                    } catch (e: DJIErrorException) {
                        call.respond(djiErrorResponse(e))
                    } catch (e: Exception) {
                        call.respond(exceptResponse(e))
                    }
                }
            }
        }.start(wait = false)

        Log.i(TAG, "Ktor server started on port $port")
    }
}

private fun Route.statusRoute() {
    val unavailable = JsonPrimitive("unavailable")
    get("/") {
        try {
            val isFlying = FlightControllerKey.KeyIsFlying.create().get(false)
            val battery = BatteryKey.KeyChargeRemainingInPercent.create().get()
            val velocity3D = FlightControllerKey.KeyAircraftVelocity.create().get()
            val location3D = FlightControllerKey.KeyAircraftLocation3D.create().get()
            val attitude = FlightControllerKey.KeyAircraftAttitude.create().get()
            val gimbalAttitude = GimbalKey.KeyGimbalAttitude.create().get()

            val version = ProductKey.KeyFirmwareVersion.create().get()
            val connection = ProductKey.KeyConnection.create().get(false)

            val controllerConnection = RemoteControllerKey.KeyConnection.create().get(false)
            val controllerVersion = RemoteControllerKey.KeyFirmwareVersion.create().get()

            call.respond(buildJsonObject {
                put("ok", true)
                put("aircraft", buildJsonObject {
                    put("isFlying", isFlying)
                    put("battery", battery.or(unavailable))
                    put("velocity3D", velocity3D?.toJson().toJsonElement().or(unavailable))
                    put("position3D", location3D?.toJson().toJsonElement().or(unavailable))
                    put("attitude", attitude?.toJson().toJsonElement().or(unavailable))
                    put("gimbalAttitude", gimbalAttitude?.toJson().toJsonElement().or(unavailable))
                })
                put("product", buildJsonObject {
                    put("version", version.or(unavailable))
                    put("connection", connection.or(unavailable))
                })
                put("controller", buildJsonObject {
                    put("connection", controllerConnection)
                    put("version", controllerVersion.or(unavailable))
                })
            })
        } catch (e: DJIErrorException) {
            call.respond(djiErrorResponse(e))
        } catch (e: Exception) {
            call.respond(exceptResponse(e))
        }
    }
    // Battery
    get("/battery") {
        try {
            val voltage = BatteryKey.KeyVoltage.create().get()
            val capacity = BatteryKey.KeyFullChargeCapacity.create().get()
            val remaining = BatteryKey.KeyChargeRemaining.create().get()
            val percent = BatteryKey.KeyChargeRemainingInPercent.create().get()
            call.respond(buildJsonObject {
                put("ok", true)
                put("voltage", voltage.or(unavailable))
                put("capacity", capacity.or(unavailable))
                put("remaining", remaining.or(unavailable))
                put("percent", percent.or(unavailable))
            })
        } catch (e: DJIErrorException) {
            call.respond(djiErrorResponse(e))
        }
    }
    get("/signal") {
        try {
            val connection = AirLinkKey.KeyConnection.create().get(false)
            val quality = AirLinkKey.KeySignalQuality.create().get()
            val frequency = AirLinkKey.KeyFrequencyBand.create().get()
            val range = AirLinkKey.KeyFrequencyBandRange.create().get()

            call.respond(buildJsonObject {
                put("ok", true)
                put("connection", connection)
                put("quality", quality.or(unavailable))
                put("frequency", frequency.toElement().or(unavailable))
                put("range", range.toElement().or(unavailable))
            })
        } catch (e: DJIErrorException) {
            call.respond(djiErrorResponse(e))
        } catch (e: Exception) {
            call.respond(exceptResponse(e))
        }
    }
}