package com.kcg.dr.api

import android.util.Log
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class DjiVideoTcpServer {
    private companion object {
        const val TAG = "VideoTcpStreamer"
        private val cameraManager get() = MediaDataCenter.getInstance().cameraStreamManager
    }

    private var serverSocket: ServerSocket? = null
    private var acceptPool: ExecutorService? = null
    private val atmClient = AtomicReference<Socket?>(null)

    private val atmLoggedCodec = AtomicBoolean(false)

    private val streamListener =
        ICameraStreamManager.ReceiveStreamListener { data, offset, length, info ->
            // Log the codec once
            if (atmLoggedCodec.compareAndSet(false, true))
                Log.i(TAG, "video codec = ${info.mimeType}")

            val client = atmClient.get() ?: return@ReceiveStreamListener // nobody connected yet
            val out = client.getOutputStream()
            try {
                out.write(data, offset, length)
            } catch (e: Exception) {
                Log.w(TAG, "error writing to client", e)
                closeClientAndSet()
            }
        }

    private fun closeClientAndSet(client: Socket? = null) {
        atmClient.getAndSet(client)?.let {
            runCatching {
                it.close()
                Log.i(TAG, "video client closed: ${it.inetAddress}")
            }
        }
        Log.i(TAG, "video client set: ${client?.inetAddress}")
    }

    fun start(port: Int, cameraIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN) {
        Log.d(TAG, "video server start $port ${cameraIndex.name}")

        stop()

        Log.d(TAG, "video server starting")

        val server = ServerSocket(port)
        serverSocket = server
        cameraManager.addReceiveStreamListener(cameraIndex, streamListener)

        acceptPool = Executors.newSingleThreadExecutor().apply {
            execute {
                while (!server.isClosed) {
                    // Accept client connection
                    val next = runCatching { server.accept() }.getOrNull() ?: break
                    Log.d(TAG, "video client accepted: ${next.inetAddress}")
                    next.apply {
                        tcpNoDelay = true
                        keepAlive = true
                    }
                    // Replace and close any previous client
                    closeClientAndSet(next)
                }
            }
        }
    }

    fun stop() {
        Log.d(TAG, "video server stopping")
        cameraManager.removeReceiveStreamListener(streamListener)
        closeClientAndSet(null)
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptPool?.shutdownNow()
        Log.i(TAG, "video server stopped")
    }
}