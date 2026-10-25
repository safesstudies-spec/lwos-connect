package com.lwos.connect.transport

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Sends a file to a peer's [FileTransferServer]: one JSON header line,
 * then the file's raw bytes with no further framing. See
 * FileTransferServer's class doc for why this is a dedicated protocol
 * rather than reusing the JSON control channel.
 */
object FileTransferClient {
    private const val TAG = "LwosFileTransferClient"
    private const val CONNECT_TIMEOUT_MS = 5000

    @Serializable
    private data class FileHeader(
        val fileName: String,
        val fileSize: Long,
        val senderDeviceId: String,
        val senderDeviceName: String
    )

    suspend fun sendFile(
        context: Context,
        uri: Uri,
        fileName: String,
        host: String,
        port: Int,
        senderDeviceId: String,
        senderDeviceName: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val fileSize = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            if (fileSize < 0) {
                Log.e(TAG, "Could not determine size of $uri")
                return@withContext false
            }

            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                val out = socket.getOutputStream()

                val header = FileHeader(fileName, fileSize, senderDeviceId, senderDeviceName)
                val headerLine = Json.encodeToString(FileHeader.serializer(), header) + "\n"
                out.write(headerLine.toByteArray(Charsets.UTF_8))
                out.flush()

                val input = resolver.openInputStream(uri)
                if (input == null) {
                    Log.e(TAG, "Could not open $uri for reading")
                    return@withContext false
                }
                input.use { stream ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                    }
                    out.flush()
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "File send failed: ${e.message}")
            false
        }
    }
}
