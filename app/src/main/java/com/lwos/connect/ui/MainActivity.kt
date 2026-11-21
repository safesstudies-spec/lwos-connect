package com.lwos.connect.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
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
 * Control screen for LWOS Connect.
 *
 * This is intentionally a thin shell: all the real work (discovery,
 * trust, sending/receiving) lives in ContinuityService so it keeps
 * running when this screen isn't open. What this Activity does:
 *   1. Requests the runtime permissions discovery needs.
 *   2. Starts + binds to ContinuityService.
 *   3. Shows discovered devices and lets you tap one to pair or to fire
 *      a demo "Continue On" handoff (standing in for a real app's state
 *      until other LWOS apps register their own appId handlers).
 *   4. Watches the clipboard WHILE VISIBLE and pushes changes to paired
 *      devices — this has to live here rather than in the background
 *      service, because Android 10+ only lets the app with current
 *      foreground focus read clipboard content; a background Service
 *      can still *write* the clipboard (used when a remote update comes
 *      in) but can't reliably *read* it.
 *   5. Lets you send a file to a paired device, and deep-links to the
 *      system settings screen for granting notification-mirroring
 *      access (which Android doesn't offer via a normal permission
 *      dialog).
 *   6. Shows a log of everything received from other devices.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var deviceAdapter: DeviceListAdapter
    private lateinit var clipboardManager: ClipboardManager

    private var service: ContinuityService? = null
    private var isBound = false

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        onLocalClipboardChanged()
    }

    private var pendingFileSendUri: Uri? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            startAndBindService()
        } else {
            binding.statusText.text = getString(R.string.status_permissions_denied)
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingFileSendUri = uri
            promptForFileDestination(uri)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as ContinuityService.LocalBinder).getService()
            isBound = true

            service?.onDeviceListChanged = { runOnUiThread { refreshDeviceList() } }
            service?.onHandoffReceived = { packet ->
                runOnUiThread { appendActivityLog("Continue On from ${packet.sourceDeviceName}: ${packet.appDisplayName}") }
            }
            service?.onClipboardReceived = { text, fromDeviceName ->
                runOnUiThread { appendActivityLog("Clipboard from $fromDeviceName: ${text.take(60)}") }
            }
            service?.onNotificationReceived = { appLabel, title, text, fromDeviceName ->
                runOnUiThread {
                    val summary = listOfNotNull(title, text).joinToString(" — ")
                    appendActivityLog("[$fromDeviceName] $appLabel: $summary")
                }
            }
            service?.onFileReceived = { fileName, sizeBytes, fromDeviceName ->
                runOnUiThread { appendActivityLog("File from $fromDeviceName: $fileName (${sizeBytes / 1024} KB) saved to Downloads/LWOSConnect") }
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
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        deviceAdapter = DeviceListAdapter(
            onSendContinueOn = ::onSendContinueOnTapped,
            onPair = ::onPairTapped
        )
        binding.deviceList.layoutManager = LinearLayoutManager(this)
        binding.deviceList.adapter = deviceAdapter

        binding.addManualDeviceButton.setOnClickListener { showManualDeviceDialog() }
        binding.sendFileButton.setOnClickListener { filePickerLauncher.launch(arrayOf("*/*")) }
        binding.enableNotificationMirrorButton.setOnClickListener {
            service?.openNotificationAccessSettings()
                ?: Toast.makeText(this, "Service not ready yet", Toast.LENGTH_SHORT).show()
        }
        binding.secondScreenButton.setOnClickListener {
            // No pairing/trust check here on purpose — this is the wired
            // (USB/adb) transport, so physical cable access already is
            // the trust boundary, same as it would be for any other
            // adb-based tool. SecondScreenActivity always connects to
            // 127.0.0.1, which only reaches the PC because of the
            // daemon's adb-reverse tunnel.
            startActivity(Intent(this, SecondScreenActivity::class.java))
        }
        binding.shortcutsButton.setOnClickListener {
            startActivity(Intent(this, ShortcutsActivity::class.java))
        }

        requestNeededPermissions()
    }

    override fun onResume() {
        super.onResume()
        // Only register while this screen is actually visible — see the
        // class doc for why that's required, not just a convenience.
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
    }

    override fun onPause() {
        clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        super.onPause()
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
            // Wi-Fi Direct peer discovery's runtime gate on 13+.
            needed += Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            // 12/12L (API 31-32) predate NEARBY_WIFI_DEVICES; Wi-Fi Direct
            // peer discovery there needs fine location instead.
            needed += Manifest.permission.ACCESS_FINE_LOCATION
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
     * Fires whenever the clipboard changes WHILE this Activity is
     * resumed. Two things can cause that: the user genuinely copying
     * something, or us having just applied a remote CLIPBOARD_SYNC to
     * the local clipboard (which immediately re-triggers this same
     * listener). consumeClipboardEchoGuard tells the two apart.
     */
    private fun onLocalClipboardChanged() {
        val clip = clipboardManager.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString()
        if (text.isNullOrBlank()) return

        val svc = service ?: return
        if (svc.consumeClipboardEchoGuard(text)) return // this is an echo of what we just received, don't resend

        svc.broadcastClipboard(text)
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

    /** Asks which trusted device to send the just-picked file to, then sends it. */
    private fun promptForFileDestination(uri: Uri) {
        val trusted = service?.discoveredDevices?.values?.filter { it.isTrusted } ?: emptyList()
        if (trusted.isEmpty()) {
            Toast.makeText(this, R.string.no_trusted_devices, Toast.LENGTH_SHORT).show()
            return
        }

        val fileName = queryDisplayName(uri) ?: "shared_file"

        if (trusted.size == 1) {
            sendFileTo(trusted.first(), uri, fileName)
            return
        }

        val names = trusted.map { it.deviceName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_device_dialog_title)
            .setItems(names) { _, index -> sendFileTo(trusted[index], uri, fileName) }
            .show()
    }

    private fun sendFileTo(device: DiscoveredDevice, uri: Uri, fileName: String) {
        service?.sendFile(device, uri, fileName)
        binding.statusText.text = "Sending $fileName to ${device.deviceName}…"
    }

    private fun queryDisplayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            }
        } finally {
            cursor?.close()
        }
    }

    private fun appendActivityLog(line: String) {
        val current = binding.handoffLog.text
        binding.handoffLog.text = "$line\n$current"
    }
}
