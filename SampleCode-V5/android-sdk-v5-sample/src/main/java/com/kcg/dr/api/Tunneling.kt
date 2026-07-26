@file:Suppress("SpellCheckingInspection")

package com.kcg.dr.api

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

object Tunneling {
    private const val P_FILE_NAME = "pinggy-linux-x64"
    private const val P_LIBNAME = "$P_FILE_NAME.so"

    private const val P_DEBUG_PORT = 4300

    fun getPinggyLibFile(context: Context): File =
        getFileFromLibs(context, P_LIBNAME)

    suspend fun startTunneling(context: Context, port: Int): List<String> {
        // get executable file
        val pinggy = getExecutibleFromAssets(context, P_FILE_NAME)

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
        val debugClient = HttpClient(CIO)
        val debugResponse = debugClient.get("http://localhost:$P_DEBUG_PORT/urls")
        val json = Json.parseToJsonElement(debugResponse.bodyAsText()).jsonObject
        val urls = json["urls"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

        return urls
    }
}

// todo: put these in a helper class
fun getFileFromLibs(context: Context, fileName: String): File {
    val libDir = context.applicationInfo.nativeLibraryDir
    System.loadLibrary(fileName)
    return File(libDir, fileName)
}

fun getFileOrCopyFromAssets(context: Context, fileName: String, forceCopy: Boolean): File {
    val output = File(context.filesDir, fileName)
    if (!forceCopy && output.exists()) return output

    val assetDir = context.assets.open(fileName)
    assetDir.use { input ->
        output.outputStream().use { output ->
            input.copyTo(output)
        }
    }
    return output
}

fun getExecutibleFromAssets(context: Context, fileName: String): File {
    val file = getFileOrCopyFromAssets(context, fileName, false)
    ProcessBuilder(
        "chmod", "+x", file.absolutePath
    )
        .redirectErrorStream(true)
        .start()
        .waitFor()
    return file
}