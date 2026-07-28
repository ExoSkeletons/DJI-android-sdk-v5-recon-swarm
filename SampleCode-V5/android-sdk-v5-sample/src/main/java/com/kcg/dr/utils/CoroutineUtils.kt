package com.kcg.dr.utils

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dji.sdk.keyvalue.key.DJIKey
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.et.get
import dji.v5.et.set
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
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

    suspend fun <T> awaitCallback(block: (CommonCallbacks.CompletionCallbackWithParam<T>) -> Unit): T? =
        suspendCancellableCoroutine { cont ->
            val resumeCallback = object : CommonCallbacks.CompletionCallbackWithParam<T> {
                override fun onSuccess(value: T?) = cont.resume(value)

                override fun onFailure(error: IDJIError) =
                    cont.resumeWithException(DJIErrorException(error))
            }
            block(resumeCallback)
        }

    suspend fun awaitCallback0(block: (CommonCallbacks.CompletionCallback) -> Unit) =
        suspendCancellableCoroutine {
            val resumeCallback = object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() =
                    it.resume(Unit)

                override fun onFailure(error: IDJIError) =
                    it.resumeWithException(DJIErrorException(error))
            }
            block(resumeCallback)
        }

    suspend fun <T> awaitOrNull(block: (CommonCallbacks.CompletionCallbackWithParam<T>) -> Unit) =
        suspendCancellableCoroutine { cont ->
            val resumeCallback = object : CommonCallbacks.CompletionCallbackWithParam<T> {
                override fun onSuccess(value: T?) = cont.resume(value)
                override fun onFailure(error: IDJIError) = cont.resume(null)
            }
            block(resumeCallback)
        }

    suspend fun await0(block: (() -> Unit, ((IDJIError) -> Unit)) -> Unit) =
        await { s, e -> block({ s(Unit) }, e) }

    suspend fun <R> await(block: (((R?) -> Unit), ((IDJIError) -> Unit)) -> Unit) =
        suspendCancellableCoroutine { cont ->
            block(
                { cont.resume(it) },
                { cont.resumeWithException(DJIErrorException(it)) }
            )
        }
    }

    fun IDJIError.isConnectionError(): Boolean {
        val error = this
        return "REQUEST[ _]HANDLER[ _]NOT[ _]FOUND[ _]"
            .toRegex(RegexOption.IGNORE_CASE)
            .matches(error.errorCode())
    }

    suspend fun <R> runOrNotConnected(block: (suspend () -> R)): R? {
        val connected = await { onSuccess, onFailure ->
            FlightControllerKey.KeyConnection.create().get(onSuccess, onFailure)
        } ?: false
        if (!connected) return null
        return try {
            block()
        } catch (e: DJIErrorException) {
            if (e.error.isConnectionError()) {
                Log.d(AircraftController.TAG, "Soft-ignored connection error: ${e.error}")
                null
            } else throw e
        }
    }

    suspend fun runOrNotConnected0(block: (suspend () -> Unit)) {
        runOrNotConnected { block(); }
    }

    fun <T, F : Flow<T>> F.observe(lifecycleOwner: LifecycleOwner, observer: (T) -> Unit) {
        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                this@observe.collect(observer)
            }
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