package dev.backbutton

/** Validates ordered edge notifications; a reconnect read is only a baseline. */
class ButtonEvents {
    enum class Action { PRESS, RELEASE, IGNORE, GAP, INVALID }
    private data class Packet(val pressed: Boolean, val sequence: Long)
    private var previous: Packet? = null

    fun reset() { previous = null }

    fun baseline(bytes: ByteArray): Boolean {
        previous = decode(bytes)
        return previous != null
    }

    fun accept(bytes: ByteArray): Action {
        val next = decode(bytes) ?: return Action.INVALID
        val old = previous ?: run {
            previous = next
            return Action.IGNORE
        }
        val distance = (next.sequence - old.sequence) and 0xffffffffL
        if (distance == 0L || distance > 0x7fffffffL) return Action.IGNORE
        previous = next
        if (distance != 1L || next.pressed == old.pressed) return Action.GAP
        return if (next.pressed) Action.PRESS else Action.RELEASE
    }

    private fun decode(bytes: ByteArray): Packet? {
        if (bytes.size != 6 || bytes[0] != 1.toByte() || bytes[1].toInt() !in 0..1) return null
        var sequence = 0L
        for (index in 0..3) sequence = sequence or ((bytes[index + 2].toLong() and 0xff) shl (index * 8))
        return Packet(bytes[1] == 1.toByte(), sequence)
    }
}
