package com.kcg.dr.api.responses

import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

fun exceptResponse(
    e: Throwable,
    builderAction: JsonObjectBuilder.(Throwable) -> Unit = {}
): JsonObject = nok {
    Log.e("Response", "Exception: ${e.message}", e)
    put("ok", false)
    put("error", e.message)

    builderAction(e)
}