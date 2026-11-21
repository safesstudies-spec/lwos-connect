package com.lwos.connect.discovery

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.util.Log
import com.lwos.connect.model.DeviceType
import com.lwos.connect.model.DiscoveredDevice
import com.lwos.connect.model.LinkType
import com.lwos.connect.transport.ContinuityServer

/**
 * Wi-Fi Direct: the third wireless way two LWOS devices can find and
 * reach each other, for when they aren't on the same Wi-Fi network (or
 * there's no router/AP around at all) but are still physically near
 * each other — the gap Bluetooth already covers but at much lower
 * throughput. Once a Wi-Fi Direct group forms, it's just a private IP
 * link like any other, so everything downstream — the continuity
 * protocol, file transfer — reuses the EXACT SAME LAN transport code
 * (ContinuityServer/ContinuityClient). This class only does the new
 * part: discover LWOS peers over Wi-Fi Direct, decide which ones are
 * worth auto-connecting to, and hand back a reachable host:port once a
 * group actually forms.
 *
 * Uses WifiP2pManager's local-service (DNS-SD) discovery — the Wi-Fi
 * Direct equivalent of the mDNS discovery LanDiscoveryManager already
 * does on plain Wi-Fi: advertise a small key/value record (deviceId,
 * deviceType) and listen for the same from others, instead of just
 * seeing "some Wi-Fi Direct device" with no idea whether it's an LWOS
 * peer at all.
 *
 * One important asymmetry once two devices connect: Wi-Fi Direct
 * negotiates a single Group Owner (GO), and only the GO's address
 * (`WifiP2pInfo.groupOwnerAddress`) is easy to obtain through the
 * standard API — a GO has no simple built-in way to read back a
 * specific client's IP. So this only calls [onPeerReachable] when WE
 * end up as the non-owner side (we know exactly who to dial: the GO).
 * When we end up AS the group owner, nothing extra is needed at all —
 * ContinuityService's own ContinuityServer already listens on
 * 0.0.0.0:DEFAULT_PORT, which covers the new Wi-Fi Direct interface the
 * moment the group exists, so the peer just dials in on its own.
 *
 * This is the single least-tested part of the "remember and
 * auto-connect" work: Wi-Fi Direct's group-negotiation behavior is
 * known to vary across Android OEM skins, and there is no way to
 * exercise any of this without two real devices physically near each
 * other — something this was written without access to. If
 * auto-connect doesn't kick in on real hardware, start by grepping
 * logcat for this class's TAG.
 */
class WifiDirectManager(
    private val context: Context,
    private val localDeviceId: String,
    private val localDeviceName: String,
    private val isDeviceTrusted: (deviceId: String) -> Boolean,
    private val onPeerReachable: (DiscoveredDevice) -> Unit
) {
    private val manager: WifiP2pManager? by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    // deviceAddress (MAC) -> TXT record, populated as DNS-SD responses
    // arrive; the service-available and TXT-record callbacks fire
    // separately for the same peer, so we stitch them together here.
    private val pendingTxtRecords = mutableMapOf<String, Map<String, String>>()

    // deviceAddress (MAC) -> the LWOS identity we were trying to reach,
    // so that once WIFI_P2P_CONNECTION_CHANGED_ACTION fires we know
    // which trusted device we just connected to.
    private val connectingTo = mutableMapOf<String, Pair<String, String>>() // address -> (deviceId, deviceName)

    @SuppressLint("MissingPermission") // caller guarantees NEARBY_WIFI_DEVICES/ACCESS_FINE_LOCATION are granted
    fun start() {
        val mgr = manager
        if (mgr == null) {
            Log.w(TAG, "No Wi-Fi Direct support on this device — skipping")
            return
        }
        val ch = mgr.initialize(context, context.mainLooper, null)
        channel = ch

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> handleConnectionChanged(mgr, ch)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, filter)

        advertiseSelf(mgr, ch)
        discoverPeers(mgr, ch)
    }

    @SuppressLint("MissingPermission")
    private fun advertiseSelf(mgr: WifiP2pManager, ch: WifiP2pManager.Channel) {
        val record = WifiP2pDnsSdServiceInfo.newInstance(
            SERVICE_INSTANCE,
            SERVICE_TYPE,
            mapOf("deviceId" to localDeviceId, "deviceName" to localDeviceName)
        )
        mgr.addLocalService(ch, record, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Advertising over Wi-Fi Direct as $localDeviceName")
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "Could not advertise over Wi-Fi Direct (reason=$reason)")
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun discoverPeers(mgr: WifiP2pManager, ch: WifiP2pManager.Channel) {
        mgr.setDnsSdResponseListeners(
            ch,
            { _, _, srcDevice -> onServiceAvailable(mgr, ch, srcDevice) },
            { _, txtRecordMap, srcDevice ->
                pendingTxtRecords[srcDevice.deviceAddress] = txtRecordMap
                onServiceAvailable(mgr, ch, srcDevice)
            }
        )

        val serviceRequest = WifiP2pDnsSdServiceRequest.newInstance()
        mgr.addServiceRequest(ch, serviceRequest, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                mgr.discoverServices(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.i(TAG, "Wi-Fi Direct service discovery started")
                    }

                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "Wi-Fi Direct discoverServices failed (reason=$reason)")
                    }
                })
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "Wi-Fi Direct addServiceRequest failed (reason=$reason)")
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun onServiceAvailable(mgr: WifiP2pManager, ch: WifiP2pManager.Channel, srcDevice: WifiP2pDevice) {
        val txt = pendingTxtRecords[srcDevice.deviceAddress] ?: return // wait for the TXT record half to arrive too
        val deviceId = txt["deviceId"] ?: return
        val deviceName = txt["deviceName"] ?: deviceId

        if (!isDeviceTrusted(deviceId)) return // only auto-connect to a device we've already paired with
        if (connectingTo.containsKey(srcDevice.deviceAddress)) return // already connecting/connected to this one

        connectingTo[srcDevice.deviceAddress] = deviceId to deviceName
        val config = WifiP2pConfig().apply { deviceAddress = srcDevice.deviceAddress }
        mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Wi-Fi Direct connect() requested for already-trusted $deviceName")
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "Wi-Fi Direct connect() failed for $deviceName (reason=$reason)")
                connectingTo.remove(srcDevice.deviceAddress)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun handleConnectionChanged(mgr: WifiP2pManager, ch: WifiP2pManager.Channel) {
        mgr.requestConnectionInfo(ch) { info ->
            if (!info.groupFormed) return@requestConnectionInfo

            if (info.isGroupOwner) {
                // We ARE the group owner: nothing to do here at all — our
                // own ContinuityServer already listens on 0.0.0.0, which
                // now includes this Wi-Fi Direct interface, so the peer
                // (who knows OUR address, not the other way around) just
                // dials straight in. See the class doc for why.
                Log.i(TAG, "Became Wi-Fi Direct group owner — waiting for the peer to connect in")
                return@requestConnectionInfo
            }

            val host = info.groupOwnerAddress?.hostAddress ?: return@requestConnectionInfo
            // We don't get told directly WHICH srcDevice this connection
            // corresponds to — only that *a* connection now exists. With
            // LWOS Connect only ever auto-connecting to one trusted peer
            // at a time in practice, taking whichever entry is still
            // pending is a reasonable simplification; worth revisiting if
            // this ever needs to juggle several simultaneous attempts.
            val (deviceId, deviceName) = connectingTo.values.firstOrNull() ?: return@requestConnectionInfo
            onPeerReachable(
                DiscoveredDevice(
                    deviceId = deviceId,
                    deviceName = deviceName,
                    deviceType = DeviceType.PC, // refined once paired/resolved, same as other transports
                    linkType = LinkType.WIFI_DIRECT,
                    host = host,
                    port = ContinuityServer.DEFAULT_PORT
                )
            )
        }
    }

    fun stop() {
        val mgr = manager
        val ch = channel
        if (mgr != null && ch != null) {
            runCatching { mgr.clearLocalServices(ch, null) }
            runCatching { mgr.clearServiceRequests(ch, null) }
        }
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        channel = null
        pendingTxtRecords.clear()
        connectingTo.clear()
    }

    companion object {
        private const val TAG = "LwosWifiDirect"
        private const val SERVICE_INSTANCE = "_lwosconnect"
        private const val SERVICE_TYPE = "_presence._tcp"
    }
}
