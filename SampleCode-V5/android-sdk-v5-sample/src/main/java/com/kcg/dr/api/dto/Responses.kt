package com.kcg.dr.api.dto

import android.util.Log
import com.kcg.dr.utils.DJIErrorException
import dji.v5.common.error.IDJIError
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object Responses {
    fun ok(builderAction: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("ok", true)
        builderAction()
    }

    fun status(status: () -> String): JsonObject = ok { put("status", status()) }

    fun nok(builderAction: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("ok", false)
        builderAction()
    }

    fun errorResponse(message: () -> String): JsonObject = nok { put("error", message()) }

    fun IDJIError.isConnectionError(): Boolean {
        val error = this
        return "REQUEST[ _]HANDLER[ _]NOT[ _]FOUND[ _]"
            .toRegex(RegexOption.IGNORE_CASE)
            .matches(error.errorCode())
    }

    fun IDJIError.toJson() = buildJsonObject {
        put("errorType", errorType().name)
        if (errorCode() != null) put("errorCode", errorCode())
        if (innerCode() != null) put("innerCode", innerCode())
        if (description() != null) put("description", description())

        if (isConnectionError())
            put(
                "hint",
                "Remote Controller might not be connected to Device." +
                        "Have you connected the Device to the RC's USB port?"
            )
    }

    fun djiErrorResponse(
        e: DJIErrorException,
        builderAction: JsonObjectBuilder.(IDJIError) -> Unit = {}
    ): JsonObject = nok {
        put("djiError", buildJsonObject {
            e.error.toJson() + buildJsonObject { builderAction(e.error) }
        })
    }

    fun exceptResponse(
        e: Throwable,
        builderAction: JsonObjectBuilder.(Throwable) -> Unit = {}
    ): JsonObject = nok {
        Log.e("Response", "Exception: ${e.message}", e)
        put("ok", false)
        put("error", e.message)

        builderAction(e)
    }
}