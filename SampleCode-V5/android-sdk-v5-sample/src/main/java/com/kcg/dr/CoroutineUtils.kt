package com.kcg.dr

import dji.sdk.keyvalue.key.DJIKey
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

    suspend fun <T> suspendGet(key: DJIKey<T>): T? {
        val g = suspendCancellableCoroutine { cont ->
            key.get(
                onSuccess = { cont.resume(it) },
                onFailure = { error -> cont.resumeWithException(DJIErrorException(error)) }
            )
        }
        return g
    }

    suspend fun <T> suspendSet(
        key: DJIKey<T>,
        value: T,
    ) {
        suspendCancellableCoroutine { cont ->
            key.set(
                value,
                onSuccess = { cont.resume(Unit) },
                onFailure = { error -> cont.resumeWithException(DJIErrorException(error)) }
            )
        }
    }

    suspend fun <T, R> suspendAction(
        key: DJIKey.ActionKey<T, R>,
    ): R {
        val r = suspendCancellableCoroutine { cont ->
            key.action(
                onSuccess = { cont.resume(it) },
                onFailure = { error -> cont.resumeWithException(DJIErrorException(error)) }
            )
        }
        return r
    }
}