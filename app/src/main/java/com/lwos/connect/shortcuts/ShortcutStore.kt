package com.lwos.connect.shortcuts

import android.content.Context
import android.content.SharedPreferences
import com.lwos.connect.model.Shortcut
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * This device's own Shortcuts layout — which slots are configured and
 * what each one runs. Same SharedPreferences-backed pattern as
 * TrustStore, and deliberately per-device rather than synced: a real
 * Stream Deck's layout is a per-controller thing too, and Ari may well
 * want a different set of shortcuts on his phone than his tablet.
 *
 * Also remembers which trusted device shortcuts currently fire at
 * ([getTargetDeviceId]/[setTargetDeviceId]), so tapping a configured
 * slot is a single immediate send — no "which device?" prompt on every
 * tap, which would defeat the point of a quick-access grid.
 */
class ShortcutStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    fun getShortcuts(): List<Shortcut> {
        val raw = prefs.getString(KEY_SHORTCUTS, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<Shortcut>>(raw).sortedBy { it.slotIndex }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Creates or replaces whatever shortcut currently occupies [slotIndex]. */
    fun setShortcut(slotIndex: Int, label: String, command: String) {
        val current = getShortcuts().toMutableList()
        current.removeAll { it.slotIndex == slotIndex }
        current.add(Shortcut(id = UUID.randomUUID().toString(), slotIndex = slotIndex, label = label, command = command))
        save(current)
    }

    fun clearSlot(slotIndex: Int) {
        val current = getShortcuts().toMutableList()
        current.removeAll { it.slotIndex == slotIndex }
        save(current)
    }

    fun getTargetDeviceId(): String? = prefs.getString(KEY_TARGET_DEVICE, null)

    fun setTargetDeviceId(deviceId: String) {
        prefs.edit().putString(KEY_TARGET_DEVICE, deviceId).apply()
    }

    private fun save(shortcuts: List<Shortcut>) {
        prefs.edit().putString(KEY_SHORTCUTS, json.encodeToString(shortcuts)).apply()
    }

    companion object {
        private const val PREFS_NAME = "lwos_connect_shortcuts"
        private const val KEY_SHORTCUTS = "shortcuts"
        private const val KEY_TARGET_DEVICE = "target_device_id"
    }
}
