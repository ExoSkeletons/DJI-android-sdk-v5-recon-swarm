package com.aviadl40.utils.coroutine

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

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