package com.kcg.dr.api

import android.content.Context
import android.util.Log
import com.aviad40l.dr.util.getExecutableFromLibs
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

private const val TAG = "Tunneling"

object Tunneling {
    interface Tunneler {
        suspend fun startTunneling(context: Context, port: Int): List<String>
        suspend fun stopTunneling()
    }

    object Pinggy : Tunneler {
        private const val P_NAME = "pinggy"
        private const val P_DEBUG_PORT = 4300

        override suspend fun startTunneling(
            context: Context,
            port: Int
        ): List<String> {
            // get executable file
            val pinggy = context.getExecutableFromLibs(P_NAME)

            Log.d(TAG, "pinggy path: ${pinggy.absolutePath}")
            Log.d(TAG, "starting process")
            // start tunnel process
            withContext(Dispatchers.IO) {
                ProcessBuilder(
                    pinggy.absolutePath,
                    "-l",
                    "http://localhost:$port",
                    "-d",
                    P_DEBUG_PORT.toString(),
                )
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    .start()
            }

            // extract generated urls
            Log.d(TAG, "getting urls with http client")
            val debugClient = HttpClient(CIO)
            val debugResponse = debugClient.get("http://localhost:$P_DEBUG_PORT/urls")
            val json = Json.parseToJsonElement(debugResponse.bodyAsText()).jsonObject
            val urls = json["urls"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            debugClient.close()
            Log.d(TAG, "urls: $urls")
            return urls
        }

        override suspend fun stopTunneling() {
            TODO("Not yet implemented")
        }

    }

    object Cloudflared : Tunneler {
        private const val NAME = "cloudflared"
        private const val CRED_FILE = "tunnel_creds.json"
        private const val CFG_FILE = "tunnel_config.yml"
        private const val QUICK_TUNNEL_ENDPOINT = "https://api.trycloudflare.com/tunnel"
        private const val MAX_EDGE_IP_COUNT = 4

        private var currentProcess: Process? = null
        private val tunnelScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private var loggingJob: Job? = null

        @OptIn(InternalSerializationApi::class)
        @Serializable
        private data class CloudflaredResult(
            @SerialName("id")
            val tunnelId: String,
            val hostname: String,
            @SerialName("account_tag")
            val accountTag: String = "",
            val secret: String,
        )

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
        val client = OkHttpClient().newBuilder()
            .callTimeout(20.seconds.toJavaDuration())
            .build()

        override suspend fun startTunneling(
            context: Context, port: Int
        ): List<String> = withContext(Dispatchers.IO) {
            // get executable file
            val cloudflared = context.getExecutableFromLibs(NAME)

            // Manually request a tunnel by making a call to cloudflare's API
            Log.d(TAG, "Making cloudflare API call to $QUICK_TUNNEL_ENDPOINT")
            client.newCall(
                okhttp3.Request.Builder()
                    .url(QUICK_TUNNEL_ENDPOINT)
                    .post("".toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { response ->
                Log.d(TAG, "Cloudflare API response: ${response.code}")
                if (!response.isSuccessful)
                    throw RuntimeException("Cloudflare API error: ${response.code}")
                val body = json.parseToJsonElement(
                    response.body?.string()
                        ?: throw IllegalStateException("No response body")
                ).jsonObject
                val result = json.decodeFromJsonElement<CloudflaredResult>(
                    body["result"]
                        ?: throw IllegalStateException("No result in response body")
                )

                val cfgFile = setupCfg(context, result, port)
                val edgeIps = fetchEdgeIps()

                // Start cloudflared tunnel process, with our manually resolved edge ips
                val command = mutableListOf(
                    cloudflared.absolutePath, "tunnel",
                    "--config", cfgFile.absolutePath,
                    "--no-prechecks",
                    "--edge-ip-version", "4",
                    "--no-autoupdate",
                )
                for (ip in edgeIps.take(MAX_EDGE_IP_COUNT))
                    command.addAll(listOf("--edge", ip))
                command.addAll(listOf("run", result.tunnelId))
                Log.d(TAG, "Starting cloudflared tunnel process")

                stopTunneling()

                val process = ProcessBuilder(command)
                    .directory(context.cacheDir)
                    .redirectErrorStream(true)
                    .start()

                currentProcess = process

                loggingJob?.cancel()
                loggingJob = tunnelScope.launch {
                    val logs = mutableListOf<String>()
                    launch {
                        while (isActive) {
                            delay(1.seconds)
                            synchronized(logs) {
                                if (logs.isEmpty()) return@synchronized
                                Log.d("CloudflareProc", logs.joinToString("\n"))
                                logs.clear()
                            }
                        }
                    }
                    process.inputStream.bufferedReader().use { reader ->
                        runCatching {
                            reader.forEachLine { synchronized(logs) { logs += it } }
                        }
                    }
                }

                Log.d(TAG, "Started cloudflared tunnel process. URL: ${result.hostname}")
                listOf(result.hostname)
            }
        }

        private fun fetchEdgeIps(): MutableList<String> {
            // Since Cloudflare Go DNS fails, we use Java InetAddress to resolve DNS manually
            // and collect the edge ips from cloudflare's region clusters
            val edgeIps = mutableListOf<String>()
            val edgeClusters = listOf("region1.v2.argotunnel.com", "region2.v2.argotunnel.com")
            for (host in edgeClusters) {
                InetAddress.getAllByName(host)
                    .filter { it is Inet4Address }
                    .forEach { edgeIps.add("${it.hostAddress}:7844") }
            }
            return edgeIps
        }

        private fun setupCfg(
            context: Context,
            result: CloudflaredResult,
            port: Int
        ): File {
            // Setup credentials file
            val credFile = File(context.filesDir, CRED_FILE)
            credFile.writeText(
                JSONObject().apply {
                    put("TunnelID", result.tunnelId)
                    put("AccountTag", result.accountTag)
                    put("TunnelSecret", result.secret)
                }.toString()
            )
            // Setup config file
            val cfgFile = File(context.filesDir, CFG_FILE)
            cfgFile.writeText(
                """
                tunnel: ${result.tunnelId}
                credentials-file: ${credFile.absolutePath}
                protocol: quic
                ingress:
                  - hostname: ${result.hostname}
                    service: http://127.0.0.1:$port
                  - service: http_status:404
                """.trimIndent()
            )
            return cfgFile
        }

        override suspend fun stopTunneling() {
            withContext(Dispatchers.IO) {
                loggingJob?.cancel()
                loggingJob = null
                currentProcess?.destroy()
                currentProcess = null
                Log.d(TAG, "Cloudflared tunnel process stopped")
            }
        }
    }
}