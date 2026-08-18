package com.kcg.dr.api

import android.util.Log
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class VideoTcpServer {
    private companion object {
        const val TAG = "VideoTcpStreamer"
        private val cameraManager get() = MediaDataCenter.getInstance().cameraStreamManager
    }

    private var serverSocket: ServerSocket? = null
    private val acceptPool = Executors.newSingleThreadExecutor()
    private val client = AtomicReference<Socket?>(null)

    private val loggedCodec = AtomicBoolean(false)

    private val streamListener =
        ICameraStreamManager.ReceiveStreamListener { data, offset, length, info ->
            // Log the codec once so the Linux side picks h264parse vs h265parse.
            if (loggedCodec.compareAndSet(false, true))
                Log.i(TAG, "video codec = ${info.mimeType}")

            val sink = client.get() ?: return@ReceiveStreamListener   // nobody connected yet
            try {
                sink.getOutputStream().write(data, offset, length)
            } catch (e: Exception) {
                Log.w(TAG, "error writing to client", e)
                client.compareAndSet(sink, null)
                runCatching { sink.close() }
            }
        }

    fun start(port: Int, cameraIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN) {
        val server = ServerSocket(port)
        serverSocket = server
        cameraManager.addReceiveStreamListener(cameraIndex, streamListener)

        acceptPool.execute {
            while (!server.isClosed) {
                // Accept client connection
                val next = runCatching { server.accept() }.getOrNull() ?: break
                Log.d(TAG, "video client accepted: ${next.inetAddress}")
                next.apply {
                    tcpNoDelay = true
                    keepAlive = true
                }
                // Replace and close any previous client
                client.getAndSet(next)?.let { runCatching { it.close() } }
                Log.i(TAG, "video client updated: ${next.inetAddress}")
            }
        }
    }

    fun stop() {
        Log.d(TAG, "video server stopping")
        cameraManager.removeReceiveStreamListener(streamListener)
        client.getAndSet(null)?.let { runCatching { it.close() } }
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptPool.shutdownNow()
        Log.i(TAG, "video server stopped")
    }
}