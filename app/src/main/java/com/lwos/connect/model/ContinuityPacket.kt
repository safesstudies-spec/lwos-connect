package com.lwos.connect.model

import kotlinx.serialization.Serializable

/**
 * The wire format for a "Continue On" handoff.
 *
 * One device serializes the state of whatever the user is doing right now
 * (which app, and a small opaque state blob that app knows how to resume
 * from) and sends it to a paired device. The receiving side looks up an
 * app capable of handling [appId] and hands it [stateBlob] to resume from.
 *
 * Kept deliberately generic so any LWOS-aware app (not just the ones we
 * ship) can participate later by registering a handler for its own appId.
 */
@Serializable
data class ContinuityPacket(
    val packetVersion: Int = 1,
    val sourceDeviceId: String,
    val sourceDeviceName: String,
    val appId: String,
    val appDisplayName: String,
    // Free-form JSON string the target app interprets itself
    // (e.g. a URL + scroll position, a document path + cursor offset).
    val stateBlob: String,
    val createdAtEpochMs: Long,
    // Lets the receiver dedupe / ignore stale handoffs if two devices
    // raced to send one.
    val sequence: Long
)

/**
 * Every message that can cross the continuity socket, wrapped in one
 * flat, easy-to-serialize envelope instead of a Kotlin sealed class.
 *
 * Why not a sealed class? kotlinx.serialization can do polymorphism, but
 * it needs a SerializersModule registered up front, which is one more
 * moving part to get wrong. A single "type" tag + a handful of nullable
 * fields is slightly less elegant but far easier to read, debug on the
 * wire (just look at the JSON), and extend later.
 */
@Serializable
data class ContinuityMessage(
    val type: MessageType,

    // Present when type == HANDOFF
    val packet: ContinuityPacket? = null,

    // Present when type == HANDOFF_ACK
    val ackSequence: Long? = null,
    val accepted: Boolean? = null,

    // Present when type == HEARTBEAT
    val deviceId: String? = null,
    val epochMs: Long? = null,

    // Present when type == PAIR_REQUEST (identity of the device asking to
    // pair) AND when type == PAIR_RESPONSE (identity of the device that's
    // accepting — pairing only becomes mutual because BOTH messages carry
    // a full identity, so whichever side receives either one can trust
    // the sender back. See ContinuityService.handleIncomingMessage.)
    val deviceName: String? = null,
    val deviceType: String? = null, // "PHONE" | "TABLET" | "PC"
    val pairingCode: String? = null,

    // Present when type == CLIPBOARD_SYNC
    val clipboardText: String? = null,

    // Present when type == NOTIFICATION_MIRROR
    val notifAppLabel: String? = null,
    val notifTitle: String? = null,
    val notifText: String? = null,

    // Present when type == SHORTCUT_TRIGGER. `shortcutCommand` is run
    // verbatim as a shell command on the receiving device (see the
    // daemon's handle_shortcut_trigger) — deliberately a free-form
    // string, not a fixed enum of built-in actions, so shortcuts stay
    // as customizable as Ari asked for (anything you could type into a
    // terminal, a shortcut can launch). That generality is also why
    // this is gated behind the SAME trust check as every other message
    // type here: an untrusted device must never get to run a command.
    val shortcutLabel: String? = null,
    val shortcutCommand: String? = null
) {
    @Serializable
    enum class MessageType {
        HANDOFF,
        HANDOFF_ACK,
        HEARTBEAT,
        PAIR_REQUEST,
        PAIR_RESPONSE,
        CLIPBOARD_SYNC,
        NOTIFICATION_MIRROR,
        SHORTCUT_TRIGGER
    }

    companion object {
        fun handoff(packet: ContinuityPacket) =
            ContinuityMessage(type = MessageType.HANDOFF, packet = packet)

        fun handoffAck(sequence: Long, accepted: Boolean) =
            ContinuityMessage(type = MessageType.HANDOFF_ACK, ackSequence = sequence, accepted = accepted)

        fun heartbeat(deviceId: String, epochMs: Long) =
            ContinuityMessage(type = MessageType.HEARTBEAT, deviceId = deviceId, epochMs = epochMs)

        fun pairRequest(deviceId: String, deviceName: String, deviceType: String, pairingCode: String) =
            ContinuityMessage(
                type = MessageType.PAIR_REQUEST,
                deviceId = deviceId,
                deviceName = deviceName,
                deviceType = deviceType,
                pairingCode = pairingCode
            )

        /**
         * Sent back by whoever received a PAIR_REQUEST, carrying ITS OWN
         * identity (not the requester's) so the original requester can
         * trust the responder too. Without this, pairing is one-directional:
         * the requester trusts nobody back, which is exactly the bug that
         * made pairing feel broken.
         */
        fun pairResponse(deviceId: String, deviceName: String, deviceType: String, accepted: Boolean) =
            ContinuityMessage(
                type = MessageType.PAIR_RESPONSE,
                deviceId = deviceId,
                deviceName = deviceName,
                deviceType = deviceType,
                accepted = accepted
            )

        fun clipboardSync(sourceDeviceId: String, text: String) =
            ContinuityMessage(type = MessageType.CLIPBOARD_SYNC, deviceId = sourceDeviceId, clipboardText = text)

        fun notificationMirror(sourceDeviceId: String, appLabel: String, title: String?, text: String?) =
            ContinuityMessage(
                type = MessageType.NOTIFICATION_MIRROR,
                deviceId = sourceDeviceId,
                notifAppLabel = appLabel,
                notifTitle = title,
                notifText = text
            )

        fun shortcutTrigger(sourceDeviceId: String, label: String, command: String) =
            ContinuityMessage(
                type = MessageType.SHORTCUT_TRIGGER,
                deviceId = sourceDeviceId,
                shortcutLabel = label,
                shortcutCommand = command
            )
    }
}
