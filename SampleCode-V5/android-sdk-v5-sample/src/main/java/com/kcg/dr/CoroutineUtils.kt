package com.kcg.dr

import dji.sdk.keyvalue.key.DJIKey
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.et.get
import dji.v5.et.set
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object CoroutineUtils {
    suspend fun whileSuspendedBy(
        suspenders: List<suspend () -> Unit>,
        block: suspend () -> Unit
    ) = coroutineScope {
        val jobsList = mutableListOf<Job>()
        for (suspender in suspenders)
            jobsList.add(launch { suspender() })
        block()
        for (job in jobsList)
            job.cancelAndJoin()
    }

    suspend fun whileSuspendedBy(
        suspender: suspend () -> Unit,
        block: suspend () -> Unit
    ) = whileSuspendedBy(listOf(suspender), block)

    suspend fun <T> await(block: (CommonCallbacks.CompletionCallbackWithParam<T>) -> Unit): T? {
        return suspendCancellableCoroutine { cont ->
            val resumeCallback = object : CommonCallbacks.CompletionCallbackWithParam<T> {
                override fun onSuccess(value: T?) = cont.resume(value)

                override fun onFailure(error: IDJIError) = cont.resumeWithException(DJIErrorException(error))
            }
            block(resumeCallback)
        }
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

    suspend fun <P, R> DJIKey.ActionKey<P, R>.actionOrExcept(p: P): R =
        suspendCancellableCoroutine { cont ->
            this.action(
                param = p,
                onSuccess = { cont.resume(it) },
                onFailure = { error -> cont.resumeWithException(DJIErrorException(error)) }
            )
        }
}