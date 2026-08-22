package com.kcg.dr.djiutils

import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


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

suspend fun await(block: (() -> Unit, ((IDJIError) -> Unit)) -> Unit) =
    await<Unit> { s, e -> block({ s(Unit) }, e) }

@JvmName("awaitResultOrNull")
suspend fun <R> awaitOrNull(block: ((R) -> Unit, ((IDJIError) -> Unit)) -> Unit): R? {
    return suspendCancellableCoroutine { cont ->
        block(
            { cont.resume(it) },
            { cont.resume(null) }
        )
    }
}

suspend fun awaitOrNull(block: ((Unit) -> Unit, ((IDJIError) -> Unit)) -> Unit) =
    awaitOrNull<Unit> { s, e -> block({ s(Unit) }, e) }
