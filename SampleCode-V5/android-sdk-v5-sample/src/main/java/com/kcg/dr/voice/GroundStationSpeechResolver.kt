package com.kcg.dr.voice

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

class GroundStationSpeechResolver(
    val url: String,
    val port: Int
) : SpeechExecutor<String, Unit>, PipelineResolver<String> {
    private val client = HttpClient(CIO)

    override fun execution(t: String): suspend () -> Unit = {
        Log.d("GroundStationSpeechResolver", "posting to $url:$port\n$t")
        val response = client.post("http://$url:$port/input") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("text", t)
            })
        }
        Log.d("GroundStationSpeechResolver", "response: ${response.status}")
        Log.i("GroundStationSpeechResolver", "$response")
    }

    // todo: use translation stage in pipelining to pre process speech before sending to ground station
    override val pipeline: List<PipelineResolver.Stage> = listOf()

    override suspend fun finalResolve(speech: String, locale: Locale): String = speech

    override fun describe(
        t: String,
        locale: Locale
    ): SpeechResolver.Description = SpeechResolver.Description(
        "-> Ground Station:\n$t",
        "Sending command to ground station"
    )
}