package com.kcg.dr.utils

import com.kcg.dr.api.dto.Responses.isConnectionError
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.et.set
import dji.v5.lib.codec.util.DJIRuntimeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DJIErrorException(val error: IDJIError, throwable: Throwable? = null) :
    DJIRuntimeException(
        "${error.errorType()}: " +
                "${error.errorCode()},${error.innerCode()} " +
                "${error.description() ?: ""} ${error.hint() ?: ""}",
        throwable
    )

suspend fun <P, R> DJIKey.ActionKey<P, R>.actionOrExcept(p: P): R =
    suspendCancellableCoroutine { cont ->
        this.action(
            param = p,
            onSuccess = { cont.resume(it) },
            onFailure = { error -> cont.resumeWithException(DJIErrorException(error)) }
        )
    }

private class SuspendCancellableTrace : CancellationException()

@JvmName("awaitCallbackWithParam")
suspend fun <T> awaitCallback(block: (CommonCallbacks.CompletionCallbackWithParam<T>) -> Unit): T? {
    val trace = SuspendCancellableTrace()
    return suspendCancellableCoroutine { cont ->
        val resumeCallback = object : CommonCallbacks.CompletionCallbackWithParam<T> {
            override fun onSuccess(value: T?) = cont.resume(value)

            override fun onFailure(error: IDJIError) =
                cont.resumeWithException(DJIErrorException(error, trace))
        }
        block(resumeCallback)
    }
}

suspend fun awaitCallback(block: (CommonCallbacks.CompletionCallback) -> Unit) =
    awaitCallback<Unit> { c ->
        block(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() = c.onSuccess(Unit)
            override fun onFailure(error: IDJIError) = c.onFailure(error)
        })
    }

@JvmName("awaitCallbackOrNullWithParam")
suspend fun <T> awaitOrNull(block: (CommonCallbacks.CompletionCallbackWithParam<T>) -> Unit): T? =
    awaitCallback<T> { c ->
        block(object : CommonCallbacks.CompletionCallbackWithParam<T> {
            override fun onSuccess(value: T?) = c.onSuccess(value)
            override fun onFailure(error: IDJIError) = c.onSuccess(null)
        })
    }

suspend fun awaitOrNull(block: (CommonCallbacks.CompletionCallback) -> Unit) =
    awaitOrNull<Unit> { c ->
        block(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() = c.onSuccess(Unit)
            override fun onFailure(error: IDJIError) = c.onSuccess(null)
        })
    }

suspend fun await(block: (() -> Unit, ((IDJIError) -> Unit)) -> Unit) =
    await<Unit> { s, e -> block({ s(Unit) }, e) }

@JvmName("awaitResult")
suspend fun <R> await(block: (((R) -> Unit), ((IDJIError) -> Unit)) -> Unit): R {
    val trace = SuspendCancellableTrace()
    return suspendCancellableCoroutine { cont ->
        block(
            { cont.resume(it) },
            { cont.resumeWithException(DJIErrorException(it, trace)) }
        )
    }
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

suspend fun <R> DJIKey<R>.getOrExcept(): R? =
    suspendCancellableCoroutine { cont ->
        this.get(
            onSuccess = { cont.resume(it) },
            onFailure = { error -> cont.resumeWithException(DJIErrorException(error)) }
        )
    }

suspend fun <P> DJIKey<P>.setOrExcept(value: P) =
    suspendCancellableCoroutine { cont ->
        this.set(
            value,
            onSuccess = { cont.resume(Unit) },
            onFailure = { error -> cont.resumeWithException(DJIErrorException(error)) }
        )
    }

suspend fun <P, R> DJIKey.ActionKey<P, R>.actionOrExcept(): R =
    suspendCancellableCoroutine { cont ->
        this.action(
            onSuccess = { cont.resume(it) },
            onFailure = { error -> cont.resumeWithException(DJIErrorException(error)) }
        )
    }
