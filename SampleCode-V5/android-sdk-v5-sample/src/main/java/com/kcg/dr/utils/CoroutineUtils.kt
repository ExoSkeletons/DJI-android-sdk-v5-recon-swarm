package com.kcg.dr.utils

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kcg.dr.api.dto.Responses.isConnectionError
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.et.set
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

object CoroutineUtils {
    class SuspendCancellableTrace : CancellationException()

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

    suspend fun <T> LiveData<T>.awaitValue(
        timeout: Duration = Duration.INFINITE,
        updateInterval: Duration = 100.milliseconds
    ) : T {
        require(timeout >= updateInterval) { "timeout $timeout to short, must be greater than update interval $updateInterval" }

        return withTimeout(timeout) {
            while (isActive && !isInitialized && value == null)
                delay(updateInterval)
            value ?: throw CancellationException()
        }
    }

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