package com.kcg.dr.api

import android.util.Log
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

class ApiHttpServer(private val port: Int) {
    private var server: ApplicationEngine? = null

    fun start() {
        if (server != null) return

        val host = "0.0.0.0"
        server = embeddedServer(CIO, host = host, port = port) {
            install(ContentNegotiation) { json() }

            routing {
                // Home page
                get("/") {
                    call.respondText(
                        "<html><body><h2>Drone API Server Running. $host : $port</h2></body></html>",
                        contentType = ContentType.Text.Html
                    )
                }

                // Key activation
                post("/key") {
                    try {
                        val jsonStr = call.receiveText()
                        val element = Json.parseToJsonElement(jsonStr)
                        val result =  KeyActivator.handleKeyRequest(element)

                        call.respond(mapOf("ok" to true, "result" to result))
                    } catch (e: Exception) {
                        Log.e("ApiHttpServer", "Exception: ${e.message}", e)
                        call.respond(
                            mapOf("ok" to false, "error" to (e.message ?: "Unknown error"))
                        )
                    }
                }
            }
        }.start(wait = false)

        Log.i("ApiHttpServer", "Ktor server started on port $port")
    }

    fun stop() {
        server?.stop()
        server = null
        Log.i("ApiHttpServer", "Ktor server stopped")
    }
}