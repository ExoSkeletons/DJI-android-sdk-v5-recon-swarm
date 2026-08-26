package com.kcg.dr.voice

import android.util.Log
import com.kcg.dr.utils.TCPClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

private const val TAG = "GroundStationSpeechResolver"

class GroundStationSpeechResolver(
    val address: String,
    val port: Int
) : SpeechExecutor<String, Unit>, PipelineResolver<String> {
    // rest client
    private val restClient = HttpClient(CIO)
    // tcp client
    private val tcpClient = TCPClient()

    override suspend fun init() {
        super.init()
        tcpClient.connect(address, port)
    }

    override fun close() {
        super.close()
        tcpClient.disconnect()
    }

    override fun execution(t: String): suspend () -> Unit = {
        val inputObject = buildJsonObject {
            put("text", t)
        }

        Log.d(TAG, "sending to ground station $address:$port\n$t")

        Log.d(TAG, "posting")
        val response = restClient.post("http://$address:$port/input") {
            contentType(ContentType.Application.Json)
            setBody(inputObject)
        }
        Log.d(TAG, "${response.status}")
        if (response.status.value != 200)
            Log.i(TAG, "$response")

        Log.d(TAG, "sending via tcp")
        tcpClient.send(inputObject.toString())
    }

    override val pipeline: List<PipelineResolver.Stage> = listOf(
        TranslatorStage(listOf("he"), "en"),
    )

    override suspend fun finalResolve(speech: String, locale: Locale): String = speech

    override fun describe(
        t: String,
        locale: Locale
    ): SpeechResolver.Description = SpeechResolver.Description(
        "-> Ground Station:\n$t",
        "Sending command to ground station"
    )
}