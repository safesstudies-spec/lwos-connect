package com.lwos.connect.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.lwos.connect.model.DeviceType
import com.lwos.connect.model.DiscoveredDevice
import com.lwos.connect.model.LinkType

/**
 * Finds other LWOS devices on the same Wi-Fi/LAN using Android's Network
 * Service Discovery (NSD), which is mDNS under the hood — the same
 * mechanism KDE Connect and AirPlay use. This is the primary transport
 * for anything bandwidth-heavy (file transfer, app/screen streaming);
 * Bluetooth (see BluetoothDiscoveryManager) is the fallback when two
 * devices aren't on the same network yet.
 *
 * Every LWOS device both *advertises* itself (so others can find it) and
 * *discovers* others at the same time.
 */
class LanDiscoveryManager(
    private val context: Context,
    private val localDeviceId: String,
    private val localDeviceName: String,
    private val localDeviceType: DeviceType,
    private val servicePort: Int,
    private val onDeviceFound: (DiscoveredDevice) -> Unit,
    private val onDeviceLost: (deviceId: String) -> Unit
) {
    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /** Makes this device visible to other LWOS devices on the LAN. */
    fun startAdvertising() {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "LWOS-$localDeviceName-$localDeviceId"
            serviceType = SERVICE_TYPE
            port = servicePort
            // Extra attrs ride along in the mDNS TXT record so a peer
            // can tell what we are before it even connects.
            setAttribute("deviceId", localDeviceId)
            setAttribute("deviceType", localDeviceType.name)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "Advertising as ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Failed to advertise (error $errorCode)")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "Stopped advertising")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Failed to stop advertising (error $errorCode)")
            }
        }

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    /** Starts looking for other LWOS devices on the LAN. */
    fun startDiscovery() {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "LAN discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                // Skip our own advertisement.
                if (service.serviceName.contains(localDeviceId)) return

                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "Resolve failed for ${info.serviceName} (error $errorCode)")
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val deviceId = info.attributes["deviceId"]?.toString(Charsets.UTF_8)
                        val deviceTypeRaw = info.attributes["deviceType"]?.toString(Charsets.UTF_8)
                        if (deviceId == null) return

                        val deviceType = try {
                            DeviceType.valueOf(deviceTypeRaw ?: DeviceType.PHONE.name)
                        } catch (e: IllegalArgumentException) {
                            DeviceType.PHONE
                        }

                        onDeviceFound(
                            DiscoveredDevice(
                                deviceId = deviceId,
                                deviceName = info.serviceName.removePrefix("LWOS-").substringBeforeLast("-"),
                                deviceType = deviceType,
                                linkType = LinkType.LAN,
                                host = info.host?.hostAddress,
                                port = info.port
                            )
                        )
                    }
                })
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                onDeviceLost(service.serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "LAN discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Start discovery failed (error $errorCode)")
                nsdManager.stopServiceDiscovery(this)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed (error $errorCode)")
            }
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stop() {
        discoveryListener?.let {
            runCatching { nsdManager.stopServiceDiscovery(it) }
        }
        registrationListener?.let {
            runCatching { nsdManager.unregisterService(it) }
        }
        discoveryListener = null
        registrationListener = null
    }

    companion object {
        private const val TAG = "LwosLanDiscovery"
        // LWOS's own mDNS service type, namespaced so it never collides
        // with unrelated services on the same network.
        private const val SERVICE_TYPE = "_lwosconnect._tcp."
    }
}
