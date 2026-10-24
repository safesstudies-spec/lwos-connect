package com.lwos.connect.transport

import android.util.Log
import com.lwos.connect.model.ContinuityMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

/**
 * Listens on [port] for incoming continuity connections from paired
 * devices. One line of JSON == one ContinuityMessage (newline-delimited
 * JSON, same idea as NDJSON) — simple, human-readable with `nc`/`adb`,
 * and easy to extend later.
 *
 * This only *receives*; sending is ContinuityClient's job. Both sides
 * run both a server and a client, since any device can initiate a
 * handoff to any other paired device.
 */
class ContinuityServer(
    private val port: Int,
    private val onMessage: (ContinuityMessage, fromHost: String) -> Unit
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    fun start() {
        scope.launch {
            try {
                val socket = ServerSocket(port)
                serverSocket = socket
                Log.i(TAG, "Continuity server listening on port $port")

                while (!socket.isClosed) {
                    val client = socket.accept()
                    handleClient(client)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server loop ended: ${e.message}")
            }
        }
    }

    private fun handleClient(client: Socket) {
        scope.launch {
            client.use { sock ->
                val fromHost = sock.inetAddress?.hostAddress ?: "unknown"
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                try {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val raw = line ?: continue
                        if (raw.isBlank()) continue
                        runCatching {
                            val message = json.decodeFromString<ContinuityMessage>(raw)
                            onMessage(message, fromHost)
                        }.onFailure {
                            Log.w(TAG, "Dropped malformed message from $fromHost: ${it.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.i(TAG, "Connection from $fromHost closed: ${e.message}")
                }
            }
        }
    }

    /** Sends a reply back down a still-open connection, if the caller kept one open. */
    fun reply(socket: Socket, message: ContinuityMessage) {
        runCatching {
            val writer = PrintWriter(socket.getOutputStream(), true)
            writer.println(json.encodeToString(ContinuityMessage.serializer(), message))
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    companion object {
        private const val TAG = "LwosContinuityServer"
        /** Default port LWOS Connect listens on for continuity traffic. */
        const val DEFAULT_PORT = 59213
    }
}
