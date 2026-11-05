package com.lwos.connect.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.Context
import android.util.Log
import com.lwos.connect.model.ContinuityMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Accepts incoming Bluetooth RFCOMM connections for the continuity
 * control channel — pairing/handoff/clipboard/notifications/shortcuts,
 * the same protocol as LAN (see BluetoothContinuityConnection). Mirrors
 * ContinuityServer's role, just over Bluetooth instead of TCP: both
 * sides run both a server and the ability to connect out, since either
 * device can initiate to the other.
 *
 * Can accept more than one connection over its lifetime (a new
 * BluetoothContinuityConnection per accepted socket), same as the LAN
 * server handling multiple peers — though in practice LWOS Connect
 * usually only has a phone talking to one PC at a time.
 */
class BluetoothContinuityServer(
    private val context: Context,
    private val onConnectionEstablished: (BluetoothContinuityConnection) -> Unit,
    private val onIncomingMessage: (ContinuityMessage, address: String, reply: (ContinuityMessage) -> Unit) -> Unit
) {
    private var serverSocket: BluetoothServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    @Volatile private var running = false

    @SuppressLint("MissingPermission") // caller guarantees BLUETOOTH_CONNECT/ADVERTISE are granted
    fun start() {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth not available/enabled — Bluetooth transport won't work until it is")
            return
        }

        running = true
        scope.launch {
            try {
                val listening = adapter.listenUsingRfcommWithServiceRecord(
                    BluetoothContinuityConnection.SERVICE_NAME,
                    BluetoothContinuityConnection.SERVICE_UUID
                )
                serverSocket = listening
                Log.i(TAG, "Bluetooth continuity server listening")

                while (running) {
                    val socket = try {
                        listening.accept()
                    } catch (e: IOException) {
                        if (running) Log.i(TAG, "Bluetooth accept loop ended: ${e.message}")
                        break
                    }
                    val address = socket.remoteDevice?.address ?: "unknown"
                    val connection = BluetoothContinuityConnection.fromAccepted(
                        socket,
                        onMessage = { message, reply -> onIncomingMessage(message, address, reply) },
                        onClosed = { /* ContinuityService prunes its own map by address */ }
                    )
                    onConnectionEstablished(connection)
                }
            } catch (e: IOException) {
                Log.w(TAG, "Could not start Bluetooth continuity server: ${e.message}")
            }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    companion object {
        private const val TAG = "LwosBtContinuityServer"
    }
}
