package com.lwos.connect.trust

import android.content.Context
import android.content.SharedPreferences
import com.lwos.connect.model.TrustedDevice
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The list of devices we trust enough to accept a "Continue On" handoff
 * from. Pairing happens once per device (see ContinuityService's
 * PAIR_REQUEST / PAIR_RESPONSE handling); after that, this is the gate
 * every incoming handoff is checked against.
 *
 * Backed by SharedPreferences for now. This is deliberately simple so
 * it's easy to follow — if LWOS later needs this synced across the
 * user's own devices, swap the storage, not the call sites.
 */
class TrustStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    fun getTrustedDevices(): List<TrustedDevice> {
        val raw = prefs.getString(KEY_DEVICES, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<TrustedDevice>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun isTrusted(deviceId: String): Boolean =
        getTrustedDevices().any { it.deviceId == deviceId }

    fun trust(device: TrustedDevice) {
        val current = getTrustedDevices().toMutableList()
        current.removeAll { it.deviceId == device.deviceId }
        current.add(device)
        save(current)
    }

    fun revoke(deviceId: String) {
        val current = getTrustedDevices().toMutableList()
        current.removeAll { it.deviceId == deviceId }
        save(current)
    }

    private fun save(devices: List<TrustedDevice>) {
        prefs.edit().putString(KEY_DEVICES, json.encodeToString(devices)).apply()
    }

    companion object {
        private const val PREFS_NAME = "lwos_connect_trust_store"
        private const val KEY_DEVICES = "trusted_devices"
    }
}
