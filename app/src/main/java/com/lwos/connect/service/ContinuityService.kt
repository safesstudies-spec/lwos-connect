package com.lwos.connect.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
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
import com.lwos.connect.transport.ContinuityClient
import com.lwos.connect.transport.ContinuityServer
import com.lwos.connect.transport.FileTransferClient
import com.lwos.connect.transport.FileTransferServer
import com.lwos.connect.trust.TrustStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * The always-on piece of LWOS Connect. Runs as a foreground service so
 * Android doesn't kill it, and owns:
 *   - LAN + Bluetooth discovery (who else is around)
 *   - the continuity server (accepting handoffs/pairing/clipboard/
 *     notifications from peers) and the file-transfer server
 *   - the trust store (who we're allowed to accept things from)
 *
 * MainActivity binds to this to show what's discovered and to trigger
 * actions; this service keeps running even if the UI isn't open, since
 * any of the above can arrive at any time.
 */
class ContinuityService : Service() {

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val sequenceCounter = AtomicLong(0)

    private lateinit var trustStore: TrustStore
    private lateinit var continuityServer: ContinuityServer
    private lateinit var fileTransferServer: FileTransferServer
    private var lanDiscovery: LanDiscoveryManager? = null
    private var bluetoothDiscovery: BluetoothDiscoveryManager? = null

    // Simple in-memory view for the UI; a real app would expose this via
    // a StateFlow, kept as a plain list here to stay readable.
    val discoveredDevices = mutableMapOf<String, DiscoveredDevice>()
    val receivedHandoffs = mutableListOf<ContinuityPacket>()

    var onDeviceListChanged: (() -> Unit)? = null
    var onHandoffReceived: ((ContinuityPacket) -> Unit)? = null
    var onClipboardReceived: ((text: String, fromDeviceName: String) -> Unit)? = null
    var onNotificationReceived: ((appLabel: String, title: String?, text: String?, fromDeviceName: String) -> Unit)? = null
    var onFileReceived: ((fileName: String, sizeBytes: Long, fromDeviceName: String) -> Unit)? = null

    // Guards against the clipboard-sync echo loop: when we apply a
    // remote clipboard update locally, Android's own clipboard listener
    // (registered in MainActivity, since reading the clipboard requires
    // foreground focus on modern Android) would otherwise see "the
    // clipboard changed" and immediately broadcast it right back out.
    // One-shot by design (see consumeClipboardEchoGuard): if it stayed
    // set, copying the exact same text twice in a row for real would get
    // silently swallowed the second time.
    @Volatile
    private var lastAppliedRemoteClipboardText: String? = null

    /**
     * Called by MainActivity's clipboard listener right after it reads a
     * newly-changed clipboard value. Returns true (and clears the guard)
     * if this value is exactly the one we just wrote ourselves because a
     * CLIPBOARD_SYNC came in — meaning MainActivity should NOT broadcast
     * it back out, since that would just echo it forever between devices.
     */
    fun consumeClipboardEchoGuard(candidateText: String): Boolean {
        return if (lastAppliedRemoteClipboardText == candidateText) {
            lastAppliedRemoteClipboardText = null
            true
        } else {
            false
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): ContinuityService = this@ContinuityService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        trustStore = TrustStore(this)

        startForeground(NOTIFICATION_ID, buildNotification())

        continuityServer = ContinuityServer(ContinuityServer.DEFAULT_PORT) { message, fromHost ->
            handleIncomingMessage(message, fromHost)
        }
        continuityServer.start()

        fileTransferServer = FileTransferServer(this, FileTransferServer.DEFAULT_PORT) { fileName, sizeBytes, fromDeviceName ->
            onFileReceived?.invoke(fileName, sizeBytes, fromDeviceName)
        }
        fileTransferServer.start()

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
        fileTransferServer.stop()
        lanDiscovery?.stop()
        bluetoothDiscovery?.stopDiscovery()
        scope.coroutineContext[Job]?.cancel()
        if (instance === this) instance = null
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

    /**
     * Pushes [text] to every currently-trusted, reachable device.
     *
     * Called from MainActivity (not triggered from inside this service)
     * because reading the clipboard on Android 10+ requires the calling
     * app to have foreground focus — a background Service generally
     * cannot read clipboard *content*, only an Activity that's actually
     * on screen can. Writing a received value back to the clipboard has
     * no such restriction, so applying incoming CLIPBOARD_SYNC messages
     * still happens here, in the background, in handleIncomingMessage.
     */
    fun broadcastClipboard(text: String) {
        lastAppliedRemoteClipboardText = null // this is an outbound (locally-copied) value, not an echo
        val message = ContinuityMessage.clipboardSync(localDeviceId(), text)
        broadcastToTrustedDevices(message)
    }

    fun broadcastNotification(appLabel: String, title: String?, text: String?) {
        val message = ContinuityMessage.notificationMirror(localDeviceId(), appLabel, title, text)
        broadcastToTrustedDevices(message)
    }

    /**
     * Fires one Shortcuts-grid button at [device]. Unlike clipboard/
     * notifications (which fan out to every trusted device), a
     * shortcut goes to exactly the one device the Shortcuts screen has
     * selected as its target — see ShortcutStore.targetDeviceId.
     */
    fun sendShortcut(device: DiscoveredDevice, label: String, command: String) {
        if (device.host == null || device.port == null) return // Bluetooth-only targets land here once that transport exists
        scope.launch {
            val message = ContinuityMessage.shortcutTrigger(localDeviceId(), label, command)
            ContinuityClient.send(device.host, device.port, message)
        }
    }

    private fun broadcastToTrustedDevices(message: ContinuityMessage) {
        val targets = discoveredDevices.values.filter { it.isTrusted && it.host != null && it.port != null }
        if (targets.isEmpty()) return
        scope.launch {
            for (device in targets) {
                ContinuityClient.send(device.host!!, device.port!!, message)
            }
        }
    }

    /** Sends a file to a trusted device's FileTransferServer. */
    fun sendFile(device: DiscoveredDevice, uri: Uri, fileName: String) {
        if (device.host == null) return
        scope.launch {
            FileTransferClient.sendFile(
                context = this@ContinuityService,
                uri = uri,
                fileName = fileName,
                host = device.host,
                port = FileTransferServer.DEFAULT_PORT,
                senderDeviceId = localDeviceId(),
                senderDeviceName = Build.MODEL ?: "LWOS Device"
            )
        }
    }

    fun openNotificationAccessSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
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
                // Trust the requester, AND send our own identity back so
                // pairing becomes mutual. Before this fix, only the
                // receiving side ever trusted anyone — the device that
                // tapped "Pair" never got trusted back, so it still
                // showed the peer as a stranger afterwards. That's what
                // made pairing look broken even when it had "worked".
                val deviceId = message.deviceId ?: return
                val deviceName = message.deviceName ?: deviceId
                val deviceType = message.deviceType ?: DeviceType.PHONE.name
                trustStore.trust(TrustedDevice(deviceId, deviceName, deviceType, System.currentTimeMillis()))
                upgradeDeviceIdentity(fromHost, deviceId, deviceName, deviceType)

                scope.launch {
                    val response = ContinuityMessage.pairResponse(
                        deviceId = localDeviceId(),
                        deviceName = Build.MODEL ?: "LWOS Device",
                        deviceType = DeviceType.PHONE.name,
                        accepted = true
                    )
                    // Reply on a FRESH connection to the requester's own
                    // listening port — by the time we're handling this,
                    // the requester already closed the connection it used
                    // to send PAIR_REQUEST (see ContinuityClient: every
                    // message is a short-lived connect/send/close).
                    ContinuityClient.send(fromHost, ContinuityServer.DEFAULT_PORT, response)
                }
            }

            ContinuityMessage.MessageType.PAIR_RESPONSE -> {
                if (message.accepted != true) return
                val deviceId = message.deviceId ?: return
                val deviceName = message.deviceName ?: deviceId
                val deviceType = message.deviceType ?: DeviceType.PC.name
                trustStore.trust(TrustedDevice(deviceId, deviceName, deviceType, System.currentTimeMillis()))
                upgradeDeviceIdentity(fromHost, deviceId, deviceName, deviceType)
            }

            ContinuityMessage.MessageType.CLIPBOARD_SYNC -> {
                val sourceId = message.deviceId ?: return
                val text = message.clipboardText ?: return
                if (!trustStore.isTrusted(sourceId)) return

                lastAppliedRemoteClipboardText = text
                val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboardManager.setPrimaryClip(ClipData.newPlainText("LWOS Connect", text))

                val fromName = discoveredDevices[sourceId]?.deviceName ?: sourceId
                onClipboardReceived?.invoke(text, fromName)
            }

            ContinuityMessage.MessageType.NOTIFICATION_MIRROR -> {
                val sourceId = message.deviceId ?: return
                if (!trustStore.isTrusted(sourceId)) return
                val fromName = discoveredDevices[sourceId]?.deviceName ?: sourceId
                onNotificationReceived?.invoke(
                    message.notifAppLabel ?: "Unknown app",
                    message.notifTitle,
                    message.notifText,
                    fromName
                )
            }

            else -> { /* heartbeat / acks: nothing to do yet */ }
        }
    }

    /**
     * Once we learn a peer's real, stable deviceId (from a pairing
     * message), replace whatever temporary entry we had for its address
     * — a manually-added "manual-host:port" entry, or a freshly
     * discovered one — with a single entry keyed by that real id and
     * marked trusted. Without this, a manually-added device would never
     * visibly become "trusted" in the device list: the trust store would
     * hold the real id, but the list entry would still be keyed by the
     * fake manual id, so the isTrusted lookup would never match.
     */
    private fun upgradeDeviceIdentity(host: String, realDeviceId: String, realDeviceName: String, realDeviceTypeRaw: String) {
        val realDeviceType = try {
            DeviceType.valueOf(realDeviceTypeRaw)
        } catch (e: IllegalArgumentException) {
            DeviceType.PC
        }

        val staleEntry = discoveredDevices.entries.find { it.value.host == host && it.key != realDeviceId }
        if (staleEntry != null) discoveredDevices.remove(staleEntry.key)

        val base = staleEntry?.value ?: DiscoveredDevice(
            deviceId = realDeviceId,
            deviceName = realDeviceName,
            deviceType = realDeviceType,
            linkType = LinkType.LAN,
            host = host,
            port = ContinuityServer.DEFAULT_PORT
        )

        discoveredDevices[realDeviceId] = base.copy(
            deviceId = realDeviceId,
            deviceName = realDeviceName,
            deviceType = realDeviceType,
            isTrusted = true
        )
        onDeviceListChanged?.invoke()
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

        /**
         * Lets NotificationMirrorListenerService (a *separate* Android
         * Service with no other way to reach this one) hand off
         * notifications to broadcast. A singleton is a blunt tool, but
         * there's only ever one ContinuityService instance in this
         * process, and the alternative (binding one service to another)
         * is substantially more ceremony for the same result.
         */
        var instance: ContinuityService? = null
            private set
    }
}
