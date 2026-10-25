package com.lwos.connect.transport

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * Receives files sent by a peer's [FileTransferClient]. Runs on its own
 * port, separate from ContinuityServer's line-delimited JSON control
 * channel, because file transfer needs to switch from text to raw binary
 * partway through one connection. Reading that with a BufferedReader
 * would be a real bug: BufferedReader pulls ahead into its own internal
 * buffer while scanning for a newline, which can silently swallow the
 * first few bytes of the file that follows. [readLineRaw] below reads
 * the header one byte at a time specifically to avoid that trap — slower,
 * but the header is only a few dozen bytes, so it costs nothing real.
 */
class FileTransferServer(
    private val context: Context,
    private val port: Int,
    private val onFileReceived: (fileName: String, sizeBytes: Long, fromDeviceName: String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class FileHeader(
        val fileName: String,
        val fileSize: Long,
        val senderDeviceId: String,
        val senderDeviceName: String
    )

    fun start() {
        scope.launch {
            try {
                val socket = ServerSocket(port)
                serverSocket = socket
                Log.i(TAG, "File transfer server listening on port $port")
                while (!socket.isClosed) {
                    val client = socket.accept()
                    handleIncoming(client)
                }
            } catch (e: IOException) {
                Log.i(TAG, "File transfer server loop ended: ${e.message}")
            }
        }
    }

    private fun handleIncoming(socket: Socket) {
        scope.launch {
            socket.use { sock ->
                try {
                    val input = sock.getInputStream()
                    val headerLine = readLineRaw(input) ?: return@use
                    val header = json.decodeFromString(FileHeader.serializer(), headerLine)

                    saveIncomingFile(input, header.fileName, header.fileSize)
                    Log.i(TAG, "Received ${header.fileName} (${header.fileSize} bytes) from ${header.senderDeviceName}")
                    onFileReceived(header.fileName, header.fileSize, header.senderDeviceName)
                } catch (e: Exception) {
                    Log.e(TAG, "File receive failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Streams exactly [sizeBytes] from [input] into a new entry in the
     * device's Downloads collection via MediaStore, under a LWOSConnect
     * subfolder — this needs no storage permission on modern Android,
     * since it's a scoped insert rather than a direct filesystem write.
     */
    private fun saveIncomingFile(input: InputStream, fileName: String, sizeBytes: Long) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LWOSConnect")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create destination entry for $fileName")

        val out = resolver.openOutputStream(uri)
            ?: throw IOException("Could not open output stream for $fileName")
        out.use { stream ->
            val buffer = ByteArray(32 * 1024)
            var remaining = sizeBytes
            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = input.read(buffer, 0, toRead)
                if (read == -1) throw IOException("Connection closed early ($remaining bytes missing)")
                stream.write(buffer, 0, read)
                remaining -= read
            }
        }

        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    companion object {
        private const val TAG = "LwosFileTransferServer"
        const val DEFAULT_PORT = 59214

        fun readLineRaw(input: InputStream): String? {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b == -1) return if (bytes.size() == 0) null else bytes.toString(Charsets.UTF_8.name())
                if (b == '\n'.code) return bytes.toString(Charsets.UTF_8.name())
                bytes.write(b)
            }
        }
    }
}
