package com.kcg.dr.api

import android.util.Log
import com.kcg.dr.CoroutineUtils.actionOrExcept
import com.kcg.dr.CoroutineUtils.getOrExcept
import com.kcg.dr.DJIErrorException
import com.kcg.dr.controller.AircraftController
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.v5.et.create
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.pipeline.PipelineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TAG = "ApiHttpServer"

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
                    get("/") {
                        statusHandler()
                    }
                    // Battery
                    get("/battery") {
                        try {
                            val voltage = BatteryKey.KeyVoltage.create().getOrExcept()
                            val capacity = BatteryKey.KeyFullChargeCapacity.create().getOrExcept()
                            val remaining = BatteryKey.KeyChargeRemaining.create().getOrExcept()
                            val percent =
                                BatteryKey.KeyChargeRemainingInPercent.create().getOrExcept()
                            call.respond(buildJsonObject {
                                put("ok", true)
                                put("voltage", voltage)
                                put("capacity", capacity)
                                put("remaining", remaining)
                                put("percent", percent)
                            })
                        } catch (e: DJIErrorException) {
                            call.respond(errorResponse(e))
                        }
                    }
                }

                controller?.let {
                    route("/controller") {
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
                }

                get("/fly") {
                    try {
                        val isFlying =
                            FlightControllerKey.KeyIsFlying.create().getOrExcept() ?: false
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
                        call.respond(errorResponse(e))
                    }
                }
                get("/land") {
                    try {
                        FlightControllerKey.KeyStartAutoLanding.create().actionOrExcept()
                        call.respond(buildJsonObject { put("ok", true) })
                    } catch (e: DJIErrorException) {
                        call.respond(errorResponse(e))
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
                        call.respond(errorResponse(e))
                    } catch (e: Exception) {
                        call.respond(exceptResponse(e))
                    }
                }
            }
        }.start(wait = false)

        Log.i(TAG, "Ktor server started on port $port")
    }
}

private fun errorResponse(e: DJIErrorException): JsonObject = buildJsonObject {
    put("ok", false)
    put("error", e.error.toString())
}

private fun exceptResponse(e: Exception): JsonObject = buildJsonObject {
    Log.e(TAG, "Exception: ${e.message}", e)
    buildJsonObject {
        put("ok", false)
        put("error", e.message)
    }
}