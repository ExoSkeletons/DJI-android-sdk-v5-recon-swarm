package com.kcg.dr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class JobRepeater(
    private val coroutineScope: CoroutineScope,
    private val timeout: Long,
    private val repeatTime: Long = timeout,
    private val action: suspend () -> Unit
) {
    private var loopJob: Job? = null

    fun start(initDelay: Long = timeout, repeatDelay: Long = repeatTime) {
        if (loopJob?.isActive == true) return

        loopJob = coroutineScope.launch {
            delay(initDelay)
            while (isActive) {
                action()
                delay(repeatDelay)
            }
        }
    }

    fun isActive() = loopJob?.isActive ?: false

    fun restart(delay: Long = timeout, repeatDelay: Long = repeatTime) {
        cancel()
        start(delay, repeatDelay)
    }

    fun cancel() {
        loopJob?.cancel()
        loopJob = null
    }
}
