package com.lwos.connect.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.lwos.connect.model.ContinuityMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.UUID

/**
 * One persistent, bidirectional Bluetooth RFCOMM connection speaking
 * the exact same line-delimited-JSON ContinuityMessage protocol as the
 * LAN transport (ContinuityServer/ContinuityClient) — same message
 * types, same trust checks, same handling in ContinuityService. Only
 * the wire underneath is different.
 *
 * Why persistent here when LAN is deliberately one-shot-per-message?
 * Setting up a Bluetooth RFCOMM connection (SDP lookup + channel
 * establishment) has real overhead — reconnecting for every single
 * message would make Shortcuts, which are supposed to feel instant,
 * noticeably laggy. So this connection is opened once (accepted by
 * BluetoothContinuityServer, or initiated with [connectTo]) and kept
 * open, with messages flowing in either direction over the same
 * socket for as long as it stays connected.
 *
 * One nice consequence: pairing's "receiver calls back out on a new
 * connection" trick (see ContinuityService and the daemon's
 * handle_pair_request) isn't needed over Bluetooth — both sides
 * already share one open, bidirectional socket, so a PAIR_RESPONSE
 * just gets written back down it directly via the `reply` callback.
 */
class BluetoothContinuityConnection private constructor(
    private val socket: BluetoothSocket,
    val remoteAddress: String,
    private val onMessage: (ContinuityMessage, reply: (ContinuityMessage) -> Unit) -> Unit,
    private val onClosed: (String) -> Unit
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    @Volatile private var open = true

    private fun startReading() {
        scope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(socket.inputStream))
                while (open) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    runCatching {
                        val message = json.decodeFromString<ContinuityMessage>(line)
                        onMessage(message) { reply -> send(reply) }
                    }.onFailure {
                        Log.w(TAG, "Dropped malformed Bluetooth message from $remoteAddress: ${it.message}")
                    }
                }
            } catch (e: IOException) {
                Log.i(TAG, "Bluetooth connection to $remoteAddress ended: ${e.message}")
            } finally {
                close()
            }
        }
    }

    fun send(message: ContinuityMessage) {
        scope.launch {
            try {
                val writer = PrintWriter(socket.outputStream, true)
                writer.println(json.encodeToString(ContinuityMessage.serializer(), message))
                writer.flush()
            } catch (e: IOException) {
                Log.w(TAG, "Failed to send over Bluetooth to $remoteAddress: ${e.message}")
                close()
            }
        }
    }

    fun close() {
        if (!open) return
        open = false
        runCatching { socket.close() }
        onClosed(remoteAddress)
    }

    companion object {
        private const val TAG = "LwosBtContinuity"

        /**
         * Must match BLUETOOTH_CONTROL_UUID on the PC daemon exactly —
         * it's what Bluetooth's SDP lookup uses to find the right RFCOMM
         * channel on the remote device, standing in for a "port number"
         * the way LAN uses one.
         */
        val SERVICE_UUID: UUID = UUID.fromString("8ab9a0f4-9c0b-4a1e-9c7f-6e1f9b9a1a01")
        const val SERVICE_NAME = "LWOSConnectControl"

        /** Wraps an already-accepted server-side socket — see BluetoothContinuityServer. */
        fun fromAccepted(
            socket: BluetoothSocket,
            onMessage: (ContinuityMessage, reply: (ContinuityMessage) -> Unit) -> Unit,
            onClosed: (String) -> Unit
        ): BluetoothContinuityConnection {
            val address = socket.remoteDevice?.address ?: "unknown"
            return BluetoothContinuityConnection(socket, address, onMessage, onClosed).also { it.startReading() }
        }

        /**
         * Actively connects out to [device] as a client — used for
         * pairing over Bluetooth and for reaching a Bluetooth-only
         * target (Shortcuts, Second Screen). Blocks until connected or
         * failed, so call this off the main thread.
         */
        @SuppressLint("MissingPermission") // caller guarantees BLUETOOTH_CONNECT is granted
        @Throws(IOException::class)
        fun connectTo(
            device: BluetoothDevice,
            onMessage: (ContinuityMessage, reply: (ContinuityMessage) -> Unit) -> Unit,
            onClosed: (String) -> Unit
        ): BluetoothContinuityConnection {
            // Discovery and an active connection attempt fight over the
            // same radio; Android's own docs recommend cancelling it first.
            BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
            val socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
            socket.connect()
            return BluetoothContinuityConnection(socket, device.address, onMessage, onClosed).also { it.startReading() }
        }
    }
}
