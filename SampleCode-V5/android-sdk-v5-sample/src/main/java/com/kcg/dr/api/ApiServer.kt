package com.kcg.dr.api

import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.kcg.dr.api.Responses.djiErrorResponse
import com.kcg.dr.api.Responses.errorResponse
import com.kcg.dr.api.Responses.exceptResponse
import com.kcg.dr.api.Responses.nok
import com.kcg.dr.api.Responses.ok
import com.kcg.dr.flight.AircraftController
import com.kcg.dr.utils.CoroutineUtils.actionOrExcept
import com.kcg.dr.utils.DJIErrorException
import dji.sdk.keyvalue.key.AirLinkKey
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.v5.et.create
import dji.v5.et.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.IgnoreTrailingSlash
import io.ktor.server.routing.Route
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.sendSerialized
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.channels.ClosedChannelException

private const val TAG = "ApiHttpServer"

class ApiServer {
    private var server: ApplicationEngine? = null
    private var controller: AircraftController? = null

    val requests = MutableLiveData<List<String>>(emptyList())

    val isRunning = MutableLiveData(false)

    fun setController(c: AircraftController?) {
        controller = c
    }

    fun stop() {
        server?.stop()
        server = null
        isRunning.value = false
        Log.i(TAG, "Ktor server stopped")
    }

    fun start(host: String, port: Int) {
        if (server != null) stop()

        server = embeddedServer(CIO, host = host, port = port) {
            install(ContentNegotiation) { json() }
            install(IgnoreTrailingSlash)
            install(WebSockets) {
                contentConverter = KotlinxWebsocketSerializationConverter(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
            install(StatusPages) {
                exception<DJIErrorException> { call, e ->
                    Log.d("API", "got dji ex with error ${e.error.description()}")
                    call.respond(djiErrorResponse(e))
                }
                exception<Exception> { call, e ->
                    Log.d("API", "got exception ${e.message}")
                    call.respond(exceptResponse(e))
                }
            }

            routing {
                get("/") {
                    call.respondText(
                        "<html><body><h2>Drone API Server Running. $host : $port</h2></body></html>",
                        contentType = ContentType.Text.Html
                    )
                }

                intercept(ApplicationCallPipeline.Plugins) {
                    val log = "${call.request.httpMethod.value} ${call.request.uri}"
                    requests.postValue(
                        requests.value?.let { list -> (list + listOf(log)).take(10) } ?: listOf(log)
                    )

                    val rcAvailable = RemoteControllerKey.KeyConnection.create().get(false)
                    if (!rcAvailable) {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            "No connection to Remote Controller"
                        )
                        return@intercept finish()
                    }
                    val aircraftAvailable = FlightControllerKey.KeyConnection.create().get(false)
                    if (!aircraftAvailable) {
                        call.respond(HttpStatusCode.ServiceUnavailable, "No connection to Aircraft")
                        return@intercept finish()
                    }
                    val productConnected = ProductKey.KeyConnection.create().get(false)
                    if (!productConnected) {
                        call.respond(HttpStatusCode.ServiceUnavailable, "Product not connected")
                        return@intercept finish()
                    }
                }

                route("/status") { statusRoute() }

                route("/c") { controllerRoute { this@ApiServer.controller } }

                quickActionsRoute()
                keyActivationRoute()
            }
        }.start(wait = false)
        isRunning.value = true

        Log.i(TAG, "Ktor server started on port $port")
    }
}
private fun Routing.keyActivationRoute() {
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
    get("/gps") {
        try {
            val valid = FlightControllerKey.KeyGPSIsValid.create().get(false)
            val satCount = FlightControllerKey.KeyGPSSatelliteCount.create().get()
            val signalLevel = FlightControllerKey.KeyGPSSignalLevel.create().get()
            val compass = FlightControllerKey.KeyCompassHeading.create().get()

            val build: JsonObjectBuilder.() -> Unit = {
                put("satCount", satCount)
                put("signalLevel", signalLevel.toElement())
                put("valid", valid)
                put("compass", compass)
            }
            call.respond(if (valid) ok(build) else nok(build))
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

private fun Routing.quickActionsRoute() {
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
}