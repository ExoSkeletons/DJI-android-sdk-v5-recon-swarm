package com.kcg.dr.djiutils

import dji.sdk.keyvalue.key.FlightControllerKey
import dji.v5.common.error.IDJIError
import dji.v5.et.create
import dji.v5.et.get
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put


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

@JvmName("runIfConnectedForResult")
suspend fun <R> runIfConnected(block: (suspend () -> R)): Result<R?> {
    return runCatching {
        if (FlightControllerKey.KeyConnection.create().get() != true) null
        else block()
    }.recover {
        if (it is DJIErrorException && it.error.isConnectionError())
            return@recover null // Transform connection error into null result
        throw it
    }
}

suspend fun runIfConnected(block: (suspend () -> Unit)): Result<Unit> =
    runIfConnected<Unit> { block(); }.map { }

suspend fun ifConnected(block: (suspend () -> Unit)) =
    runIfConnected { block(); }.map { }.getOrDefault(Unit)