package com.lwos.connect.transport

/**
 * Splits a continuous Annex-B H.264 byte stream — what ffmpeg's `-f h264`
 * output is, and what [SecondScreenClient] receives over the socket —
 * into individual NAL units, each one handed to [onNalUnit] complete
 * with its start-code prefix still attached (MediaCodec's Annex-B/
 * byte-stream input mode expects that).
 *
 * Why this exists at all: a socket read has no idea where one NAL unit
 * ends and the next begins — a single 32KB chunk off the wire might
 * contain two and a half NAL units, or half of one. Queueing a buffer
 * into the decoder that starts or ends mid-NAL confuses it. This is the
 * same family of bug as the BufferedReader-over-reading-a-binary-
 * payload trap documented on FileTransferServer: don't let something
 * that isn't aware of your framing read across a boundary it can't see.
 *
 * Not optimized for extreme throughput (it copies on every feed/drain),
 * but at second-screen video bitrates that's not the bottleneck, and
 * the straightforward version is much easier to be sure is correct.
 */
class AnnexBNalSplitter(private val onNalUnit: (ByteArray) -> Unit) {

    // Bytes received so far that don't yet form a complete NAL unit
    // (either no start code found yet, or we're mid-way through one).
    private var pending = ByteArray(0)

    fun feed(data: ByteArray, length: Int) {
        pending += data.copyOf(length)
        drainCompleteUnits()
    }

    private fun drainCompleteUnits() {
        while (true) {
            val firstStart = findStartCode(pending, 0)
            if (firstStart == -1) {
                // No start code anywhere yet. A real stream always begins
                // with one, so this only guards against garbage input —
                // cap growth rather than buffer forever.
                if (pending.size > MAX_PENDING_WITHOUT_START_CODE) pending = ByteArray(0)
                return
            }

            val secondStart = findStartCode(pending, firstStart + START_CODE_LENGTH)
            if (secondStart == -1) {
                // This NAL unit isn't finished yet — drop any junk before
                // the start code and wait for more bytes to complete it.
                if (firstStart > 0) pending = pending.copyOfRange(firstStart, pending.size)
                return
            }

            onNalUnit(pending.copyOfRange(firstStart, secondStart))
            pending = pending.copyOfRange(secondStart, pending.size)
            // Loop again: the chunk we just fed may have contained more
            // than one complete NAL unit already.
        }
    }

    /** Finds the next 3-byte Annex-B start code (00 00 01) at or after [from]. */
    private fun findStartCode(buf: ByteArray, from: Int): Int {
        var i = from
        val limit = buf.size - START_CODE_LENGTH
        while (i <= limit) {
            if (buf[i] == ZERO && buf[i + 1] == ZERO && buf[i + 2] == ONE) return i
            i++
        }
        return -1
    }

    companion object {
        private const val START_CODE_LENGTH = 3
        private const val MAX_PENDING_WITHOUT_START_CODE = 2 * 1024 * 1024
        private const val ZERO: Byte = 0
        private const val ONE: Byte = 1
    }
}
