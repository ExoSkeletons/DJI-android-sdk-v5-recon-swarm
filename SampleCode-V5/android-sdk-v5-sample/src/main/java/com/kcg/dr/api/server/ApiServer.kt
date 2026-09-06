package com.kcg.dr.api.server

import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.aviadl40.utils.json.toJsonElement
import com.kcg.dr.api.ac.routes.controllerRoute
import com.kcg.dr.api.dji.responses.djiErrorResponse
import com.kcg.dr.api.dji.routes.djiApiKeyRoute
import com.kcg.dr.api.dji.routes.djiQuickActionsRoute
import com.kcg.dr.api.dji.routes.djiStatusRoute
import com.kcg.dr.api.dto.TTSRequest
import com.kcg.dr.api.responses.errorResponse
import com.kcg.dr.api.responses.exceptResponse
import com.kcg.dr.api.responses.ok
import com.kcg.dr.djiutils.DJIErrorException
import com.kcg.dr.flight.AircraftController
import com.kcg.dr.location.UserMetrics
import com.kcg.dr.managers.TTSManager
import dji.sdk.keyvalue.key.FlightControllerKey
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
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.IgnoreTrailingSlash
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

                route("/status") { djiStatusRoute() }

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
                        webSocket("/gimbal") {
                            send("Connected to gimbal websocket")
                            gimbalControlSession(wsIncoming) { this@ApiServer.controller?.camGim }
                        }
                        webSocket("/telemetry") {
                            send("Connected to telemetry websocket")
                            telemetrySession(controller)
                        }
                    }
                }


                djiQuickActionsRoute()
                djiApiKeyRoute()
            }
        }.start(wait = false)
        isRunning.value = true

        Log.i(TAG, "Ktor server started on port $port")
    }
}


private suspend fun DefaultWebSocketServerSession.sticksControlSession(
    wsIncoming: MutableSharedFlow<String>,
    controllerProvider: () -> AircraftController?
) = handleJsonRequests<AircraftController.FlightParam> { param ->
    wsIncoming.emit(param.toString())
    val controller = controllerProvider() ?: throw IllegalStateException("Controller not ready")
    controller.sendFlightParam(param)
    ok { put("param", param.toString()) }
}

private suspend fun DefaultWebSocketServerSession.gimbalControlSession(
    wsIncoming: MutableSharedFlow<String>,
    gimbalProvider: () -> AircraftController.IGimbal?
) = handleJsonRequests<AircraftController.GimbalRotation> { rotation ->
    wsIncoming.emit(rotation.toString())
    val gimbal = gimbalProvider() ?: throw IllegalStateException("Gimbal not ready")
    gimbal.angleCamera(rotation)
    ok { put("param", rotation.toString()) }
}

private suspend inline fun <reified Request, reified Response> DefaultWebSocketServerSession.handleJsonRequests(
    handler: suspend (Request) -> Response
) {
    val resultFlow = MutableSharedFlow<Response>()
    val expectationFlow = MutableSharedFlow<Throwable>()
    val responderJob = launch {
        launch { resultFlow.collect { sendSerialized(it) } }
        launch {
            expectationFlow.collect { e -> sendSerialized(exceptResponse(e)) }
        }
    }

    runCatching {
        for (frame in incoming) {
            frame as? Frame.Text ?: continue
            val receivedText = frame.readText()
            Log.i(TAG, "Received text:\n$receivedText")

            runCatching {
                val decoded = json.decodeFromString<Request>(receivedText)
                handler(decoded)
            }.onFailure { e ->
                Log.e(TAG, "Failed to handle control session", e)
                expectationFlow.emit(e)
            }.onSuccess { resultFlow.emit(it) }
        }
    }.onFailure { e ->
        when (e) {
            is ClosedChannelException -> Log.i(TAG, "WebSocket closed ${closeReason.await()}")
            else -> Log.e(TAG, "WebSocket exception ${closeReason.await()}", e)
        }
    }.also { responderJob.cancel() }
}

@JvmName("handleJsonRequestsWithJsonObjectResponse")
private suspend inline fun <reified Request> DefaultWebSocketServerSession.handleJsonRequests(handler: suspend (Request) -> JsonObject) =
    handleJsonRequests<Request, JsonObject>(handler)

private suspend fun DefaultWebSocketServerSession.telemetrySession(
    controller: AircraftController?
) {
    if (controller == null) {
        sendSerialized(errorResponse { "No controller" })
        return close(
            CloseReason(
                CloseReason.Codes.INTERNAL_ERROR,
                "No controller"
            )
        )
    }
    runCatching {
        val aircraftTel = combine(
            controller.ac.location,
            controller.ac.attitude,
            controller.ac.batteryPercent,
            controller.ac.velocity,
        ) { location, attitude, battery, velocity ->
            buildJsonObject {
                put("location", location?.toJson().toJsonElement())
                put("attitude", attitude.toJson().toJsonElement())
                put("battery", battery)
                put("velocity", velocity.toJson().toJsonElement())
            }
        }
        val gimbalTel = controller.camGim.attitude.map { attitude ->
            buildJsonObject {
                put("attitude", attitude.toJson().toJsonElement())
            }
        }
        combine(aircraftTel, gimbalTel) { a, g ->
            buildJsonObject {
                put("aircraft", a)
                put("gimbal", g)
            }
        }.collect { sendSerialized(it) }
    }.onFailure { e ->
        when (e) {
            is ClosedChannelException -> Log.i(TAG, "WebSocket closed ${closeReason.await()}")
            else -> Log.e(TAG, "WebSocket exception ${closeReason.await()}", e)
        }
    }
}