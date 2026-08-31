package com.kcg.dr.api.server

import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.kcg.dr.api.dto.KeyActivator
import com.kcg.dr.api.dto.Responses.djiErrorResponse
import com.kcg.dr.api.dto.Responses.errorResponse
import com.kcg.dr.api.dto.Responses.exceptResponse
import com.kcg.dr.api.dto.Responses.nok
import com.kcg.dr.api.dto.Responses.ok
import com.kcg.dr.api.dto.Responses.status
import com.kcg.dr.api.dto.StreamRequest
import com.kcg.dr.api.dto.TTSRequest
import com.kcg.dr.api.dto.actions.Action
import com.kcg.dr.api.dto.actions.FlyTo
import com.kcg.dr.api.dto.actions.LookAt
import com.kcg.dr.api.toElement
import com.kcg.dr.api.toJsonElement
import com.kcg.dr.djiutils.DJIErrorException
import com.kcg.dr.djiutils.actionOrExcept
import com.kcg.dr.flight.AircraftController
import com.kcg.dr.location.UserMetrics
import com.kcg.dr.managers.TTSManager
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
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import java.nio.channels.ClosedChannelException
import java.util.Locale

private const val TAG = "ApiHttpServer"

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    @OptIn(ExperimentalSerializationApi::class)
    decodeEnumsCaseInsensitive = true
    @OptIn(ExperimentalSerializationApi::class)
    allowComments = true
    @OptIn(ExperimentalSerializationApi::class)
    allowTrailingComma = true
}

class ApiServer {
    private var server: ApplicationEngine? = null
    private var controller: AircraftController? = null
    private var user: UserMetrics? = null

    val requests = MutableSharedFlow<String>(
        replay = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val wsIncoming = MutableSharedFlow<String>(
        replay = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val isRunning = MutableLiveData(false)

    fun configure(c: AircraftController?, u: UserMetrics?) {
        controller = c
        user = u
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
            install(ContentNegotiation) { json(json) }
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
                    // log rest requests
                    val log = "${call.request.httpMethod.value} ${call.request.uri}"
                    requests.tryEmit(log)

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

                route("/status") { aircraftStatusRoute() }

                post("/tts") {
                    val request = call.receive<TTSRequest>()
                    TTSManager.speak(
                        request.text,
                        request.country?.let { Locale(request.lang, it) } ?: Locale(request.lang)
                    )
                    call.respond(ok())
                }

                route("/c") {
                    controllerRoute(
                        { this@ApiServer.controller },
                        { this@ApiServer.user }
                    )
                    route("/ws") {
                        webSocket("/echo") {
                            send("Echo connected")
                            for (frame in incoming) {
                                frame as? Frame.Text ?: continue
                                val receivedText = frame.readText()
                                wsIncoming.emit(receivedText)
                                if (Regex("bye|x|stop").matches(receivedText)) {
                                    close(CloseReason(CloseReason.Codes.NORMAL, "Client said BYE"))
                                } else {
                                    send(Frame.Text("Hi, $receivedText!"))
                                }
                            }
                        }
                        webSocket("/sticks") {
                            send("Connected to sticks websocket")
                            sticksControlSession(wsIncoming) { this@ApiServer.controller }
                        }
                        webSocket("/telemetry") {
                            send("Connected to telemetry websocket")
                            val controller = this@ApiServer.controller
                            if (controller == null) {
                                sendSerialized(errorResponse { "No controller" })
                                close(
                                    CloseReason(
                                        CloseReason.Codes.INTERNAL_ERROR,
                                        "No controller"
                                    )
                                )
                                return@webSocket
                            }
                            telemetrySession(controller)
                        }
                    }
                }


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

private fun Route.aircraftStatusRoute() {
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
    get(Regex("/(fly|takeoff)")) {
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

private fun Route.controllerRoute(
    controllerProvider: () -> AircraftController?,
    userProvider: () -> UserMetrics?
) {
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

private suspend fun DefaultWebSocketServerSession.sticksControlSession(
    wsIncoming: MutableSharedFlow<String>,
    controllerProvider: () -> AircraftController?
) {
    val responseFlow = MutableSharedFlow<JsonObject>()
    val responderJob = launch {
        responseFlow.collect { response ->
            sendSerialized(response)
        }
    }

    runCatching {
        for (frame in incoming) {
            frame as? Frame.Text ?: continue
            val receivedText = frame.readText()
            wsIncoming.emit(receivedText)
            val sticksRequest = Json.decodeFromString<AircraftController.FlightParam>(receivedText)
            controllerProvider()?.sendFlightParam(sticksRequest)
            responseFlow.emit(ok {
                put("param", sticksRequest.toString())
            })
        }
    }.onFailure { e ->
        when (e) {
            is ClosedChannelException -> Log.i(TAG, "WebSocket closed ${closeReason.await()}")
            else -> Log.e(TAG, "WebSocket exception ${closeReason.await()}", e)
        }
    }.also { responderJob.cancel() }
}

private suspend fun DefaultWebSocketServerSession.telemetrySession(
    controller: AircraftController
) {
    runCatching {
        combine(
            controller.ac.location,
            controller.ac.batteryPercent,
            controller.ac.velocity,
        ) { location, battery, velocity ->
            buildJsonObject {
                put("location", location?.toJson().toJsonElement())
                put("battery", battery)
                put("velocity", velocity.toJson().toJsonElement())
            }
        }.collect {
            sendSerialized(it)
        }
    }.onFailure { e ->
        when (e) {
            is ClosedChannelException -> Log.i(TAG, "WebSocket closed ${closeReason.await()}")
            else -> Log.e(TAG, "WebSocket exception ${closeReason.await()}", e)
        }
    }
}