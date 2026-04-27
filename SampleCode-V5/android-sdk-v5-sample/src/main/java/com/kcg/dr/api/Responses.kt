package com.kcg.dr.api

import android.util.Log
import com.kcg.dr.DJIErrorException
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

    fun status(status: ()-> String): JsonObject = ok { put("status", status()) }

    fun nok(builderAction: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("ok", false)
        builderAction()
    }

    fun errorResponse(message: ()-> String): JsonObject = nok { put("error", message()) }

    fun djiErrorResponse(
        e: DJIErrorException,
        builderAction: JsonObjectBuilder.(IDJIError) -> Unit = {}
    ): JsonObject = nok {
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

                builderAction(this)
            }
        })
    }

    fun exceptResponse(
        e: Exception,
        builderAction: JsonObjectBuilder.(Exception) -> Unit = {}
    ): JsonObject = nok {
        Log.e("Response", "Exception: ${e.message}", e)
        put("ok", false)
        put("error", e.message)

        builderAction(e)
    }
}