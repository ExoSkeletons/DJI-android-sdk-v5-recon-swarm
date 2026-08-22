package com.kcg.dr.djiutils

import dji.sdk.keyvalue.key.DJIKey
import dji.v5.et.action
import dji.v5.et.get
import dji.v5.et.set
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


suspend fun <P, R> DJIKey.ActionKey<P, R>.actionOrExcept(p: P): R =
    suspendCancellableCoroutine { cont ->
        this.action(
            param = p,
            onSuccess = { cont.resume(it) },
            onFailure = { error -> cont.resumeWithException(DJIErrorException(error)) }
        )
    }

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
