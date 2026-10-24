package com.lwos.connect.model

import kotlinx.serialization.Serializable

/** How we currently know how to reach a device. */
enum class LinkType {
    LAN,
    BLUETOOTH
}

enum class DeviceType {
    PHONE,
    TABLET,
    PC
}

/**
 * A device we've discovered on the network or over Bluetooth, whether or
 * not we've paired with it yet. [isTrusted] is what actually gates
 * whether we'll accept a handoff from it — see TrustStore.
 */
data class DiscoveredDevice(
    val deviceId: String,
    val deviceName: String,
    val deviceType: DeviceType,
    val linkType: LinkType,
    // For LAN devices: host + port reachable right now.
    val host: String? = null,
    val port: Int? = null,
    // For Bluetooth devices: the MAC address to connect to.
    val bluetoothAddress: String? = null,
    val isTrusted: Boolean = false
)

/** Persisted record of a device we've paired with, independent of link type. */
@Serializable
data class TrustedDevice(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String, // DeviceType.name
    val pairedAtEpochMs: Long
)
