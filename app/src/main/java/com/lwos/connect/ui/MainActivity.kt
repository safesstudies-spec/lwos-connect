package com.lwos.connect.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.lwos.connect.R
import com.lwos.connect.databinding.ActivityMainBinding
import com.lwos.connect.model.DiscoveredDevice
import com.lwos.connect.service.ContinuityService
import com.lwos.connect.transport.ContinuityServer

/**
 * Demo / control screen for LWOS Connect.
 *
 * This is intentionally a thin shell: all the real work (discovery,
 * trust, sending/receiving) lives in ContinuityService so it keeps
 * running when this screen isn't open. What this Activity does:
 *   1. Requests the runtime permissions discovery needs.
 *   2. Starts + binds to ContinuityService.
 *   3. Shows discovered devices and lets you tap one to pair or to fire
 *      a demo "Continue On" handoff (standing in for a real app's state
 *      until other LWOS apps register their own appId handlers).
 *   4. Shows a log of handoffs received from other devices.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var deviceAdapter: DeviceListAdapter

    private var service: ContinuityService? = null
    private var isBound = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            startAndBindService()
        } else {
            binding.statusText.text = getString(R.string.status_permissions_denied)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as ContinuityService.LocalBinder).getService()
            isBound = true

            service?.onDeviceListChanged = { runOnUiThread { refreshDeviceList() } }
            service?.onHandoffReceived = { packet ->
                runOnUiThread { appendHandoffLog(packet.appDisplayName, packet.sourceDeviceName) }
            }
            refreshDeviceList()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceAdapter = DeviceListAdapter(
            onSendContinueOn = ::onSendContinueOnTapped,
            onPair = ::onPairTapped
        )
        binding.deviceList.layoutManager = LinearLayoutManager(this)
        binding.deviceList.adapter = deviceAdapter

        binding.addManualDeviceButton.setOnClickListener { showManualDeviceDialog() }

        requestNeededPermissions()
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        super.onDestroy()
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_SCAN
            needed += Manifest.permission.BLUETOOTH_CONNECT
            needed += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }

        val missing = needed.filter {
            ActivityCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startAndBindService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, ContinuityService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
        binding.statusText.text = getString(R.string.status_watching)
    }

    private fun refreshDeviceList() {
        val devices = service?.discoveredDevices?.values?.toList() ?: emptyList()
        deviceAdapter.submitList(devices)
        binding.emptyStateText.visibility =
            if (devices.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    /**
     * Lets you add a device by typing its IP directly, for when mDNS
     * discovery doesn't find it — e.g. over a phone hotspot, where
     * multicast (what discovery relies on) is often unreliable even
     * though a direct connection to the IP works fine.
     */
    private fun showManualDeviceDialog() {
        val padding = (16 * resources.displayMetrics.density).toInt()

        val ipInput = EditText(this).apply {
            hint = getString(R.string.manual_device_ip_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val nameInput = EditText(this).apply {
            hint = getString(R.string.manual_device_name_hint)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ipInput)
            addView(nameInput)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.manual_device_dialog_title)
            .setView(container)
            .setPositiveButton("Add") { _, _ ->
                val ip = ipInput.text.toString().trim()
                if (ip.isNotEmpty()) {
                    service?.addManualDevice(
                        host = ip,
                        port = ContinuityServer.DEFAULT_PORT,
                        displayName = nameInput.text.toString().trim()
                    )
                    refreshDeviceList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onPairTapped(device: DiscoveredDevice) {
        // Real pairing would generate + show a short code on both
        // screens for the user to confirm matches. Stubbed here with a
        // fixed code so the flow is end-to-end testable on two devices.
        AlertDialog.Builder(this)
            .setTitle("Pair with ${device.deviceName}")
            .setMessage("Confirm the code 482913 matches on both devices, then pair.")
            .setPositiveButton("Pair") { _, _ ->
                service?.pair(device, pairingCode = "482913")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onSendContinueOnTapped(device: DiscoveredDevice) {
        service?.sendContinueOn(
            device = device,
            appId = "com.lwos.demo.notes",
            appDisplayName = "LWOS Notes (demo)",
            stateBlob = """{"note":"Picked up from ${Build.MODEL}","cursor":42}"""
        )
        binding.statusText.text = "Sent Continue On to ${device.deviceName}"
    }

    private fun appendHandoffLog(appName: String, fromDevice: String) {
        val current = binding.handoffLog.text
        binding.handoffLog.text = "Continue On from $fromDevice: $appName\n$current"
    }
}
