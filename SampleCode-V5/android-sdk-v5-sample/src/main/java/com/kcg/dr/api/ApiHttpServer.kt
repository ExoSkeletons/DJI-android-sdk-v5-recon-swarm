package com.kcg.dr.api

import android.util.Log
import com.kcg.dr.CoroutineUtils.actionOrExcept
import com.kcg.dr.DJIErrorException
import com.kcg.dr.api.Responses.djiErrorResponse
import com.kcg.dr.api.Responses.errorResponse
import com.kcg.dr.api.Responses.exceptResponse
import com.kcg.dr.api.Responses.ok
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TAG = "ApiHttpServer"

object ControllerBridge {
    // global injection of controller. remove when controller is refactored to singleton or a vm or smth
    var controller: AircraftController? = null
}

class ApiHttpServer(private val port: Int) {
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
                            call.respond(errorResponse { "Aircraft already in air" })
                            return@get
                        }
                        FlightControllerKey.KeyStartTakeoff.create().actionOrExcept()
                        call.respond(ok())
                    } catch (e: DJIErrorException) {
                        call.respond(djiErrorResponse(e))
                    }
                }
                get("/land") {
                    try {
                        FlightControllerKey.KeyStartAutoLanding.create().actionOrExcept()
                        call.respond(ok())
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

                        call.respond(ok { put("result", result) })
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

            call.respond(ok {
                put("aircraft", buildJsonObject {
                    put("isFlying", isFlying)
                    put("battery", battery)
                    put("velocity3D", velocity3D?.toJson().toJsonElement())
                    put("position3D", location3D?.toJson().toJsonElement())
                    put("attitude", attitude?.toJson().toJsonElement())
                    put("gimbalAttitude", gimbalAttitude?.toJson().toJsonElement())
                })
                put("product", buildJsonObject {
                    put("version", version)
                    put("connection", connection)
                })
                put("controller", buildJsonObject {
                    put("version", controllerVersion)
                    put("connection", controllerConnection)
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
            call.respond(ok {
                put("voltage", voltage)
                put("capacity", capacity)
                put("remaining", remaining)
                put("percent", percent)
            })
        } catch (e: DJIErrorException) {
            call.respond(djiErrorResponse(e))
        } catch (e: Exception) {
            call.respond(exceptResponse(e))
        }
    }
    get("/signal") {
        try {
            val connection = AirLinkKey.KeyConnection.create().get(false)
            val quality = AirLinkKey.KeySignalQuality.create().get()
            val frequency = AirLinkKey.KeyFrequencyBand.create().get()
            val range = AirLinkKey.KeyFrequencyBandRange.create().get()

            call.respond(ok {
                put("connection", connection)
                put("quality", quality)
                put("frequency", frequency.toElement())
                put("range", range.toElement())
            })
        } catch (e: DJIErrorException) {
            call.respond(djiErrorResponse(e))
        } catch (e: Exception) {
            call.respond(exceptResponse(e))
        }
    }
}