package com.lwos.connect.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.lwos.connect.R
import com.lwos.connect.model.DiscoveredDevice
import com.lwos.connect.model.Shortcut
import com.lwos.connect.service.ContinuityService
import com.lwos.connect.shortcuts.ShortcutStore

/**
 * The Shortcuts grid — a small, customizable Stream-Deck-style panel.
 * Each slot is either empty (tap to configure) or holds a label +
 * shell command that gets sent to one chosen "target" PC as a
 * SHORTCUT_TRIGGER (tap to fire, long-press to reconfigure/remove).
 *
 * Grid size comes from resources (integers.xml / values-sw600dp,
 * see ShortcutGridAdapter) rather than being computed here, so a
 * tablet automatically gets a bigger grid than a phone with zero
 * logic in this class — just Android's normal screen-width resource
 * qualifiers doing their job.
 *
 * Like MainActivity, this is a thin shell over ContinuityService: all
 * the actual sending goes through the service, same binder pattern.
 */
class ShortcutsActivity : AppCompatActivity() {

    private lateinit var store: ShortcutStore
    private lateinit var adapter: ShortcutGridAdapter
    private lateinit var targetDeviceText: android.widget.TextView

    private var service: ContinuityService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as ContinuityService.LocalBinder).getService()
            isBound = true
            refreshTargetDeviceLabel()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ShortcutStore(this)
        setContentView(R.layout.activity_shortcuts)

        targetDeviceText = findViewById(R.id.targetDeviceText)
        findViewById<android.widget.Button>(R.id.changeTargetButton).setOnClickListener {
            showTargetDevicePicker()
        }

        val columns = resources.getInteger(R.integer.shortcut_grid_columns)
        val maxShortcuts = resources.getInteger(R.integer.max_shortcuts)

        adapter = ShortcutGridAdapter(
            slotCount = maxShortcuts,
            onFire = ::fireShortcut,
            onConfigure = ::showConfigureDialog
        )
        val grid = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.shortcutGrid)
        grid.layoutManager = GridLayoutManager(this, columns)
        grid.adapter = adapter

        val intent = Intent(this, ContinuityService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        reloadShortcuts()
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        super.onDestroy()
    }

    private fun reloadShortcuts() {
        val maxShortcuts = resources.getInteger(R.integer.max_shortcuts)
        adapter.submitShortcuts(store.getShortcuts(), maxShortcuts)
    }

    private fun fireShortcut(shortcut: Shortcut) {
        val targetId = store.getTargetDeviceId()
        val target = targetId?.let { service?.discoveredDevices?.get(it) }
        if (target == null || !target.isTrusted) {
            Toast.makeText(this, R.string.shortcuts_pick_target_first, Toast.LENGTH_SHORT).show()
            return
        }
        service?.sendShortcut(target, shortcut.label, shortcut.command)
        Toast.makeText(this, getString(R.string.shortcuts_fired, shortcut.label), Toast.LENGTH_SHORT).show()
    }

    /** Add (empty slot) or edit/remove (configured slot) dialog for one grid position. */
    private fun showConfigureDialog(slotIndex: Int, existing: Shortcut?) {
        val padding = (16 * resources.displayMetrics.density).toInt()

        val labelInput = EditText(this).apply {
            hint = getString(R.string.shortcut_label_hint)
            setText(existing?.label ?: "")
        }
        val commandInput = EditText(this).apply {
            hint = getString(R.string.shortcut_command_hint)
            setText(existing?.command ?: "")
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            gravity = Gravity.CENTER_HORIZONTAL
            addView(labelInput)
            addView(commandInput)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.shortcut_add_title else R.string.shortcut_edit_title)
            .setView(container)
            .setPositiveButton(R.string.shortcut_save) { _, _ ->
                val label = labelInput.text.toString().trim()
                val command = commandInput.text.toString().trim()
                if (label.isNotEmpty() && command.isNotEmpty()) {
                    store.setShortcut(slotIndex, label, command)
                    reloadShortcuts()
                }
            }
            .setNegativeButton(R.string.shortcut_cancel, null)

        if (existing != null) {
            builder.setNeutralButton(R.string.shortcut_remove) { _, _ ->
                store.clearSlot(slotIndex)
                reloadShortcuts()
            }
        }
        builder.show()
    }

    private fun showTargetDevicePicker() {
        val trusted = service?.discoveredDevices?.values?.filter { it.isTrusted } ?: emptyList()
        if (trusted.isEmpty()) {
            Toast.makeText(this, R.string.no_trusted_devices, Toast.LENGTH_SHORT).show()
            return
        }
        val names = trusted.map { it.deviceName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.shortcuts_pick_target_title)
            .setItems(names) { _, index ->
                store.setTargetDeviceId(trusted[index].deviceId)
                refreshTargetDeviceLabel()
            }
            .show()
    }

    private fun refreshTargetDeviceLabel() {
        val targetId = store.getTargetDeviceId()
        val target: DiscoveredDevice? = targetId?.let { service?.discoveredDevices?.get(it) }
        targetDeviceText.text = if (target != null) {
            getString(R.string.shortcuts_target, target.deviceName)
        } else {
            getString(R.string.shortcuts_no_target)
        }
    }
}
