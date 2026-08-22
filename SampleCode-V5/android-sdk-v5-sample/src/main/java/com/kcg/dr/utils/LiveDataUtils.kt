package com.kcg.dr.utils

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds


fun <T, F : Flow<T>> F.observe(lifecycleOwner: LifecycleOwner, observer: (T) -> Unit) {
    lifecycleOwner.lifecycleScope.launch {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            this@observe.collect(observer)
        }
    }
}

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