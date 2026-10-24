package com.lwos.connect.discovery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.lwos.connect.model.DeviceType
import com.lwos.connect.model.DiscoveredDevice
import com.lwos.connect.model.LinkType

/**
 * Bluetooth-based discovery, used as the complement to LAN discovery:
 * it works when two devices aren't on the same Wi-Fi network yet (e.g.
 * your phone just walked up to your desk), and it's what LWOS uses to
 * find a peer and exchange just enough info to then open a Wi-Fi/LAN
 * connection for anything heavier like a file transfer.
 *
 * Needs BLUETOOTH_SCAN / BLUETOOTH_CONNECT granted at runtime on
 * Android 12+ (the caller is responsible for requesting these before
 * calling startDiscovery()).
 */
class BluetoothDiscoveryManager(
    private val context: Context,
    private val onDeviceFound: (DiscoveredDevice) -> Unit
) {
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var receiver: BroadcastReceiver? = null

    @SuppressLint("MissingPermission") // caller guarantees permissions are granted
    fun startDiscovery(): Boolean {
        val adapter = bluetoothAdapter ?: return false
        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth is off; skipping Bluetooth discovery")
            return false
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_FOUND) return

                val device: BluetoothDevice? =
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                device ?: return

                // We only care about devices that already identify as LWOS
                // peers; everything else (headphones, random nearby phones)
                // is ignored. A real "LWOS" device will set its Bluetooth
                // name to start with this prefix while advertising/pairing
                // (see ContinuityService).
                val name = device.name ?: return
                if (!name.startsWith(LWOS_BT_NAME_PREFIX)) return

                onDeviceFound(
                    DiscoveredDevice(
                        deviceId = device.address, // MAC acts as the id for BT-only sightings
                        deviceName = name.removePrefix(LWOS_BT_NAME_PREFIX),
                        deviceType = DeviceType.PHONE, // refined once paired/resolved over LAN
                        linkType = LinkType.BLUETOOTH,
                        bluetoothAddress = device.address
                    )
                )
            }
        }

        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
        return adapter.startDiscovery()
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        bluetoothAdapter?.cancelDiscovery()
        receiver?.let {
            runCatching { context.unregisterReceiver(it) }
        }
        receiver = null
    }

    companion object {
        private const val TAG = "LwosBtDiscovery"
        const val LWOS_BT_NAME_PREFIX = "LWOS-"
    }
}
