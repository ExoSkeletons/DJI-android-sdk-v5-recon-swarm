package com.kcg.dr.djiutils

import com.kcg.dr.api.dto.Responses.isConnectionError
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.v5.et.create
import dji.v5.et.get


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