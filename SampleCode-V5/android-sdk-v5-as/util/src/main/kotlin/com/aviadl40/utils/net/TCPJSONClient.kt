package com.aviadl40.utils.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import org.json.JSONObject

open class TCPJSONClient<T>(
    timeout: Int = 0,
    maxRetries: Int? = 3,
    retryDelay: Long = 1_000L, maxRetryDelay: Long = 20_000L,
    publishScope: CoroutineScope = CoroutineScope(Dispatchers.Main),
    netScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val deserializer: DeserializationStrategy<T>
) : TCPClient(timeout, maxRetries, retryDelay, maxRetryDelay, publishScope, netScope) {
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    override fun onMessage(message: String) {
        super.onMessage(message)
        // Try to parse
        try {
            val data: T = json.decodeFromString(deserializer, message)
            val json = JSONObject(message)
            onParse(data, json)
        } catch (e: Exception) {
            onError(e)
        }
    }

    open fun onParse(data: T, json: JSONObject) {}
}