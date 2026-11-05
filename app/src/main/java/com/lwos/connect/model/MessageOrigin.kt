package com.lwos.connect.model

/**
 * Where an incoming ContinuityMessage arrived from — used by
 * ContinuityService.upgradeDeviceIdentity to know whether to match a
 * stale device-list entry by LAN host or by Bluetooth address, since
 * those are two different identifiers for "the same peer we were
 * already tracking before we learned its real deviceId".
 *
 * The reply itself (e.g. a PAIR_RESPONSE) doesn't need to branch on
 * this — see the `reply` callback threaded through
 * ContinuityService.handleIncomingMessage, which already does the
 * right thing for either transport (open a new LAN connection, or
 * write back on the already-open Bluetooth socket).
 */
sealed class MessageOrigin {
    data class Lan(val host: String) : MessageOrigin()
    data class Bluetooth(val address: String) : MessageOrigin()
}
