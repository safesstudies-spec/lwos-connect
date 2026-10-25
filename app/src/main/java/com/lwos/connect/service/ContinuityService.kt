package com.lwos.connect.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.lwos.connect.discovery.BluetoothDiscoveryManager
import com.lwos.connect.discovery.LanDiscoveryManager
import com.lwos.connect.model.ContinuityMessage
import com.lwos.connect.model.ContinuityPacket
import com.lwos.connect.model.DeviceType
import com.lwos.connect.model.DiscoveredDevice
import com.lwos.connect.model.LinkType
import com.lwos.connect.model.TrustedDevice
import com.lwos.connect.trust.TrustStore
import com.lwos.connect.transport.ContinuityClient
import com.lwos.connect.transport.ContinuityServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * The always-on piece of LWOS Connect. Runs as a foreground service so
 * Android doesn't kill it, and owns:
 *   - LAN + Bluetooth discovery (who else is around)
 *   - the continuity server (accepting handoffs/pairing from peers)
 *   - the trust store (who we're allowed to accept handoffs from)
 *
 * MainActivity binds to this to show what's discovered and to trigger a
 * "Continue On" send; this service keeps running even if the UI isn't
 * open, since a handoff can arrive at any time.
 */
class ContinuityService : Service() {

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val sequenceCounter = AtomicLong(0)

    private lateinit var trustStore: TrustStore
    private lateinit var continuityServer: ContinuityServer
    private var lanDiscovery: LanDiscoveryManager? = null
    private var bluetoothDiscovery: BluetoothDiscoveryManager? = null

    // Simple in-memory view for the UI; a real app would expose this via
    // a StateFlow, kept as a plain list here to stay readable.
    val discoveredDevices = mutableMapOf<String, DiscoveredDevice>()
    val receivedHandoffs = mutableListOf<ContinuityPacket>()

    var onDeviceListChanged: (() -> Unit)? = null
    var onHandoffReceived: ((ContinuityPacket) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): ContinuityService = this@ContinuityService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        trustStore = TrustStore(this)

        startForeground(NOTIFICATION_ID, buildNotification())

        continuityServer = ContinuityServer(ContinuityServer.DEFAULT_PORT) { message, fromHost ->
            handleIncomingMessage(message, fromHost)
        }
        continuityServer.start()

        lanDiscovery = LanDiscoveryManager(
            context = this,
            localDeviceId = localDeviceId(),
            localDeviceName = Build.MODEL ?: "LWOS Device",
            localDeviceType = DeviceType.PHONE,
            servicePort = ContinuityServer.DEFAULT_PORT,
            onDeviceFound = { device -> addOrUpdateDevice(device) },
            onDeviceLost = { /* TODO: prune discoveredDevices by matching service name */ }
        ).also {
            it.startAdvertising()
            it.startDiscovery()
        }

        // Bluetooth discovery requires BLUETOOTH_SCAN/CONNECT to already be
        // granted; MainActivity is responsible for requesting those before
        // the service is started, since a Service can't prompt the user.
        bluetoothDiscovery = BluetoothDiscoveryManager(this) { device ->
            addOrUpdateDevice(device)
        }.also { it.startDiscovery() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: if Android kills us under memory pressure, restart
        // once resources free up, since continuity should feel "always on".
        return START_STICKY
    }

    override fun onDestroy() {
        continuityServer.stop()
        lanDiscovery?.stop()
        bluetoothDiscovery?.stopDiscovery()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    // --- Public API used by MainActivity -----------------------------------

    /**
     * Adds a device by its IP address directly, bypassing discovery.
     *
     * mDNS (what LanDiscoveryManager relies on) depends on multicast
     * traffic, which a lot of Wi-Fi hotspots — phone hotspots especially
     * — throttle or drop even when "client isolation" is off, so two
     * devices can often reach each other directly by IP while still
     * failing to *discover* each other automatically. This is the
     * fallback for that: the user reads the PC's IP off its terminal
     * (`ip addr show`) and types it in once.
     *
     * Re-adding the same host:port updates the existing entry instead of
     * creating a duplicate, since deviceId is derived from the address.
     */
    fun addManualDevice(host: String, port: Int, displayName: String) {
        addOrUpdateDevice(
            DiscoveredDevice(
                deviceId = "manual-$host:$port",
                deviceName = displayName.ifBlank { host },
                deviceType = DeviceType.PC,
                linkType = LinkType.LAN,
                host = host,
                port = port
            )
        )
    }

    fun pair(device: DiscoveredDevice, pairingCode: String) {
        scope.launch {
            if (device.host == null || device.port == null) return@launch // need LAN for now
            val request = ContinuityMessage.pairRequest(
                deviceId = localDeviceId(),
                deviceName = Build.MODEL ?: "LWOS Device",
                deviceType = DeviceType.PHONE.name,
                pairingCode = pairingCode
            )
            ContinuityClient.send(device.host, device.port, request)
        }
    }

    /** Sends the current "what I'm doing" state to a trusted, reachable device. */
    fun sendContinueOn(device: DiscoveredDevice, appId: String, appDisplayName: String, stateBlob: String) {
        scope.launch {
            if (device.host == null || device.port == null) return@launch
            val packet = ContinuityPacket(
                sourceDeviceId = localDeviceId(),
                sourceDeviceName = Build.MODEL ?: "LWOS Device",
                appId = appId,
                appDisplayName = appDisplayName,
                stateBlob = stateBlob,
                createdAtEpochMs = System.currentTimeMillis(),
                sequence = sequenceCounter.incrementAndGet()
            )
            ContinuityClient.send(device.host, device.port, ContinuityMessage.handoff(packet))
        }
    }

    // --- Internal ------------------------------------------------------------

    private fun handleIncomingMessage(message: ContinuityMessage, fromHost: String) {
        when (message.type) {
            ContinuityMessage.MessageType.HANDOFF -> {
                val packet = message.packet ?: return
                if (!trustStore.isTrusted(packet.sourceDeviceId)) {
                    // Untrusted device tried to hand off state to us; ignore.
                    return
                }
                receivedHandoffs.add(0, packet)
                onHandoffReceived?.invoke(packet)
            }

            ContinuityMessage.MessageType.PAIR_REQUEST -> {
                // In a full implementation this would surface a UI prompt
                // ("Device X wants to pair — codes match? Y/N"). For this
                // first pass we auto-accept and record trust, since the
                // pairing *code* (shown on both screens out-of-band) is
                // already the human verification step.
                val deviceId = message.deviceId ?: return
                val deviceName = message.deviceName ?: deviceId
                val deviceType = message.deviceType ?: DeviceType.PHONE.name
                trustStore.trust(
                    TrustedDevice(
                        deviceId = deviceId,
                        deviceName = deviceName,
                        deviceType = deviceType,
                        pairedAtEpochMs = System.currentTimeMillis()
                    )
                )
            }

            else -> { /* heartbeat / acks: nothing to do yet */ }
        }
    }

    private fun addOrUpdateDevice(device: DiscoveredDevice) {
        val withTrust = device.copy(isTrusted = trustStore.isTrusted(device.deviceId))
        discoveredDevices[device.deviceId] = withTrust
        onDeviceListChanged?.invoke()
    }

    private fun localDeviceId(): String =
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LWOS Continuity",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps Continue On and device sync active in the background" }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LWOS Connect")
            .setContentText("Watching for nearby LWOS devices")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "lwos_continuity_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
