package com.kcg.dr.api.dji.routes

import com.aviadl40.utils.json.toElement
import com.aviadl40.utils.json.toJsonElement
import com.kcg.dr.api.dji.dto.KeyActivator
import com.kcg.dr.api.dji.responses.djiErrorResponse
import com.kcg.dr.api.responses.errorResponse
import com.kcg.dr.api.responses.exceptResponse
import com.kcg.dr.api.responses.nok
import com.kcg.dr.api.responses.ok
import com.kcg.dr.djiutils.DJIErrorException
import com.kcg.dr.djiutils.actionOrExcept
import dji.sdk.keyvalue.key.AirLinkKey
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.v5.et.create
import dji.v5.et.get
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put


fun Routing.djiApiKeyRoute() {
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

fun Route.djiStatusRoute() {
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

fun Routing.djiQuickActionsRoute() {
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