package com.kcg.dr.api

import android.content.Context
import android.util.Log
import com.kcg.dr.utils.ExecutableUtils.getExecutableFromLibs
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
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

            Log.d("Tunneling", "pinggy path: ${pinggy.absolutePath}")
            Log.d("Tunneling", "starting process")
            // start tunnel process
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

            // extract generated urls
            Log.d("Tunneling", "getting urls with http client")
            val debugClient = HttpClient(CIO)
            val debugResponse = debugClient.get("http://localhost:$P_DEBUG_PORT/urls")
            val json = Json.parseToJsonElement(debugResponse.bodyAsText()).jsonObject
            val urls = json["urls"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            debugClient.close()
            Log.d("Tunneling", "urls: $urls")
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
        ): List<String> = coroutineScope {
            // get executable file
            val cloudflared = context.getExecutableFromLibs(NAME)

            /*val process = ProcessBuilder(
                cloudflared.absolutePath,
                "tunnel",
                "--url",
                "http://localhost:$port",
            )
                .redirectErrorStream(true)
                .start()

            return@coroutineScope suspendCancellableCoroutine { c ->
                launch(Dispatchers.IO) {
                    val urlRegex = Regex("https://[a-zA-Z0-9-]+\\.trycloudflare\\.com")

                    process.inputStream.bufferedReader().forEachLine { line ->
                        Log.i("Cloudflared", line)
                        urlRegex.find(line)?.let {
                            val url = it.value
                            Log.d("Tunneling", "Found Cloudflare URL: $url")
                            c.resume(listOf(url))
                        }
                    }
                }
            }*/

            withContext(Dispatchers.IO) {
                // Manually request a tunnel by making a call to cloudflare's API
                client.newCall(
                    okhttp3.Request.Builder()
                        .url(QUICK_TUNNEL_ENDPOINT)
                        .post("".toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute().use { response ->
                    val body = json.parseToJsonElement(
                        response.body?.string() ?: return@withContext emptyList<String>()
                    ).jsonObject
                    val result = json.decodeFromJsonElement<CloudflaredResult>(
                        body["result"] ?: return@withContext emptyList<String>()
                    )

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
                    tunnel_id: ${result.tunnelId}
                    credentials-file: ${credFile.absolutePath}
                    protocol: http2
                    ingress:
                        - hostname: ${result.hostname}
                            service: http://localhost:$port
                        - service: http_status:404
                """.trimIndent()
                )

                // Since Cloudflare Go DNS fails, we use Java InetAddress to resolve DNS manually
                // and collect the edge ips from cloudflare's region clusters
                val edgeIps = mutableListOf<String>()
                val edgeClusters = listOf("region1.v2.argotunnel.com", "region2.v2.argotunnel.com")
                for (host in edgeClusters) {
                    InetAddress.getAllByName(host)
                        .filter { it is Inet4Address }
                        .forEach { edgeIps.add("${it.hostAddress}:7844") }
                }

                // Start cloudflared tunnel process, with our manually resolved edge ips
                val command = mutableListOf(
                    cloudflared.absolutePath, "tunnel",
                    "--config", cfgFile.absolutePath,
                    "--edge-ip-version", "4",
                    "--no-autoupdate"
                )
                for (ip in edgeIps.take(MAX_EDGE_IP_COUNT))
                    command.addAll(listOf("--edge", ip))
                command.addAll(listOf("run", result.tunnelId))
                ProcessBuilder(command)
                    .directory(context.cacheDir)
                    .redirectErrorStream(true)
                    .start()

                listOf(result.hostname)
            }
        }

        override suspend fun stopTunneling() {
            TODO("Not yet implemented")
        }
    }
}