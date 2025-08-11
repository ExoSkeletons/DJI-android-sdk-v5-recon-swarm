package com.dr.vocom

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.net.SocketAddress

open class TCPClient(
    private val timeout: Int = 0,
    private val maxRetries: Int? = 3,
    private val retryDelay: Long = 1_000L, private val maxRetryDelay: Long = 20_000L,
    private val publishScope: CoroutineScope = CoroutineScope(Dispatchers.Main),
    private val netScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    // Internal socket connection
    private var socket: Socket? = null

    // Coroutine jobs for read and connecting
    private var connectJob: Job? = null
    private var readJob: Job? = null

    // Flag to indicate manual disconnection (used to stop auto-reconnect)
    private var userDisconnected = false

    // Input and output streams for socket communication
    private var reader: BufferedReader? = null
    private var writer: PrintWriter? = null

    /**
     * Callback triggered when connected to server.
     */
    open fun onConnected(socket: Socket) {}

    /**
     * Callback triggered when a full line is received from the socket.
     */
    open fun onMessage(message: String) {}

    /**
     * Callback triggered when an error occurs (e.g., socket failure, stream read error).
     */
    open fun onError(error: Throwable) {}

    /**
     * Callback triggered when the connection is lost or manually disconnected.
     */
    open fun onDisconnect() {}

    /**
     * Callback triggered before a reconnect attempt, with the delay (in ms) to wait.
     */
    open fun onReconnectAttempt(delay: Long) {}

    /**
     * Connect to a Server.
     * If already connected or connecting, this will do nothing.
     *
     * @param serverAddress The Server Address
     */
    fun connect(
        serverAddress: SocketAddress
    ) {
        userDisconnected = false
        doConnect(serverAddress)
    }

    private fun doConnect(serverAddress: SocketAddress) {
        connectJob?.cancel()
        connectJob = netScope.launch {
            val serverSocket = Socket()

            var retries = 0
            var currentRetryDelay = retryDelay
            // Try connecting
            while (isActive && !userDisconnected) {
                // Connect
                try {
                    serverSocket.connect(serverAddress, timeout)
                    // Connected!
                    socket = serverSocket
                    publishScope.launch { onConnected(serverSocket) }

                    // Setup read/write streams
                    reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
                    writer = PrintWriter(socket!!.getOutputStream(), true)
                    // Start reading job
                    startReadJob()
                    // Exit connection job
                    return@launch
                } catch (e: Exception) {
                    // Connection failed
                    publishScope.launch { onError(e) }

                    // Retry
                    retries++
                    if (maxRetries != null && retries > maxRetries) break
                    publishScope.launch { onReconnectAttempt(currentRetryDelay) }
                    delay(currentRetryDelay)
                    currentRetryDelay = (currentRetryDelay * 2).coerceAtMost(maxRetryDelay)
                }
            }
        }
    }

    /**
     * Starts a coroutine that continuously reads lines from the socket input stream.
     * Each line is passed to [onMessage] callback.
     */
    private fun startReadJob() {
        readJob?.cancel()
        readJob = netScope.launch {
            try {
                while (isActive && socket?.isConnected == true) {
                    val line = reader?.readLine() ?: break
                    publishScope.launch { onMessage(line) }
                }
            } catch (e: Exception) {
                publishScope.launch { onError(e) }
            } finally {
                publishScope.launch { onDisconnect() }
                // Reading stopped / Connecting closed
                if (!userDisconnected)
                // Try reconnect
                    socket?.remoteSocketAddress?.let { doConnect(it) }
            }
        }
    }

    /**
     * Sends a string message to the server.
     * Automatically appends a newline character.
     *
     * @param message The string to send
     */
    fun send(message: String) {
        writer?.println(message)
    }

    /**
     * Disconnects from the server, cancels all coroutines, and cleans up resources.
     * Sets [userDisconnected] to true to prevent automatic reconnection.
     */
    fun disconnect() {
        userDisconnected = true
        readJob?.cancel()
        connectJob?.cancel()

        try {
            reader?.close()
            writer?.close()
            socket?.close()
        } catch (_: Exception) {
        }

        reader = null
        writer = null
        socket = null

        publishScope.launch { onDisconnect() }
    }
}
