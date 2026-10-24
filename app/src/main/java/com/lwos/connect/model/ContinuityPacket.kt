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

    // Present when type == PAIR_REQUEST
    val deviceName: String? = null,
    val deviceType: String? = null, // "phone" | "tablet" | "pc"
    val pairingCode: String? = null
) {
    @Serializable
    enum class MessageType {
        HANDOFF,
        HANDOFF_ACK,
        HEARTBEAT,
        PAIR_REQUEST,
        PAIR_RESPONSE
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

        fun pairResponse(deviceId: String, accepted: Boolean) =
            ContinuityMessage(type = MessageType.PAIR_RESPONSE, deviceId = deviceId, accepted = accepted)
    }
}
