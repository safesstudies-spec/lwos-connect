package com.lwos.connect.transport

import android.util.Log
import com.lwos.connect.model.ContinuityMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.PrintWriter
import java.net.Socket

/**
 * Opens a short-lived connection to a peer and sends it one
 * [ContinuityMessage]. We don't keep a persistent connection per peer
 * for "Continue On" — a handoff is an infrequent, small event, so a
 * connect/send/close per message keeps things simple and avoids having
 * to manage long-lived socket state across screen-off, Doze, etc.
 * (Streaming features that come later, like app/screen streaming, will
 * want a persistent connection — that's a separate class.)
 */
object ContinuityClient {

    private val json = Json { ignoreUnknownKeys = true }
    private const val TAG = "LwosContinuityClient"
    private const val CONNECT_TIMEOUT_MS = 4000

    /**
     * Sends [message] to [host]:[port]. Returns true if the message was
     * written successfully (does NOT guarantee the peer acted on it —
     * use HANDOFF_ACK for that).
     */
    suspend fun send(host: String, port: Int, message: ContinuityMessage): Boolean =
        withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    writer.println(json.encodeToString(ContinuityMessage.serializer(), message))
                    writer.flush()
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send to $host:$port: ${e.message}")
                false
            }
        }
}
