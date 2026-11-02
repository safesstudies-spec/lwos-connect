package com.lwos.connect.transport

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket

/**
 * Connects to a LWOS Second Screen server — the desktop daemon's
 * run_second_screen_server() — and streams what it sends: one JSON
 * header line describing the video, then a continuous raw Annex-B
 * H.264 byte stream (no further framing). See [AnnexBNalSplitter] for
 * why that raw stream needs splitting before it can be decoded.
 *
 * An instance, not an object, because — unlike FileTransferClient's
 * one-shot "send a file and return" — a live stream needs to be
 * stoppable from another thread (e.g. the Activity closing while
 * frames are still arriving). [stop] closes the socket, which breaks
 * the blocking read in [connectAndStream] and lets it return cleanly.
 */
class SecondScreenClient {

    @Serializable
    data class StreamHeader(val width: Int, val height: Int, val framerate: Int)

    private var socket: Socket? = null
    @Volatile private var running = false

    /**
     * Blocks the calling thread for as long as the stream is connected
     * — call this from a background thread, never the main/UI thread.
     * Returns normally once the connection ends (including a clean
     * [stop]); throws IOException on a connection/read failure.
     */
    @Throws(IOException::class)
    fun connectAndStream(
        host: String,
        port: Int,
        onHeader: (StreamHeader) -> Unit,
        onNalUnit: (ByteArray) -> Unit
    ) {
        running = true
        Socket(host, port).use { sock ->
            socket = sock
            sock.soTimeout = SOCKET_TIMEOUT_MS
            val input = sock.getInputStream()

            val headerLine = readLineRaw(input)
                ?: throw IOException("Connection closed before the stream header arrived")
            val header = json.decodeFromString(StreamHeader.serializer(), headerLine)
            onHeader(header)

            val splitter = AnnexBNalSplitter(onNalUnit)
            val buffer = ByteArray(READ_CHUNK_SIZE)
            while (running) {
                val read = input.read(buffer)
                if (read == -1) break // server closed the stream
                splitter.feed(buffer, read)
            }
        }
    }

    /** Ends the stream from another thread; safe to call even if it was never started. */
    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (e: IOException) {
            // Already closing/closed — nothing to do.
        }
    }

    /**
     * Same hand-rolled byte-by-byte reader used by FileTransferServer,
     * for the same reason: a BufferedReader would read ahead looking
     * for the newline and could silently swallow binary H.264 bytes
     * that immediately follow the header line.
     */
    private fun readLineRaw(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (out.size() == 0) null else out.toString("UTF-8")
            if (b == '\n'.code) return out.toString("UTF-8")
            out.write(b)
        }
    }

    companion object {
        const val DEFAULT_PORT = 59215 // must match SECOND_SCREEN_PORT in the Python daemon
        private const val SOCKET_TIMEOUT_MS = 10_000
        private const val READ_CHUNK_SIZE = 32 * 1024
        private val json = Json { ignoreUnknownKeys = true }
    }
}
