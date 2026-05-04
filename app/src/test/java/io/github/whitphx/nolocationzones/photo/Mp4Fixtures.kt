package io.github.whitphx.nolocationzones.photo

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Test helpers for building synthetic ISO BMFF byte arrays and feeding them to the walker.
 * Kept here (rather than in the production source set) so the production binary doesn't ship
 * with a `ByteArray`-backed reader that nothing references at runtime.
 */
internal class ByteArrayBoxReader(private val data: ByteArray) : BoxReader {
    override val size: Long get() = data.size.toLong()

    override fun read(position: Long, buf: ByteArray, offset: Int, length: Int): Int {
        if (position < 0 || position >= data.size) return 0
        val available = (data.size - position.toInt()).coerceAtMost(length).coerceAtLeast(0)
        if (available == 0) return 0
        System.arraycopy(data, position.toInt(), buf, offset, available)
        return available
    }
}

/** Build a 32-bit-sized ISO BMFF box: `[size:4][type:4][payload]`. */
internal fun box(type: String, payload: ByteArray = byteArrayOf()): ByteArray {
    require(type.length == 4) { "type must be 4 chars: $type" }
    val size = 8 + payload.size
    return ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        .putInt(size)
        .put(type.toByteArray(Charsets.US_ASCII))
        .put(payload)
        .array()
}

/** Build a box whose 4-byte type field is given as a packed Int (for non-ASCII types like `©xyz`). */
internal fun boxWithTypeCode(typeCode: Int, payload: ByteArray = byteArrayOf()): ByteArray {
    val size = 8 + payload.size
    return ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        .putInt(size)
        .putInt(typeCode)
        .put(payload)
        .array()
}

/**
 * Build the 4-byte payload prefix used by QuickTime `©xyz`:
 * `[u16 textLen][u16 language][text...]`.
 */
internal fun quicktimeStringPayload(text: String, language: Int = 0): ByteArray {
    val bytes = text.toByteArray(Charsets.UTF_8)
    return ByteBuffer.allocate(4 + bytes.size).order(ByteOrder.BIG_ENDIAN)
        .putShort(bytes.size.toShort())
        .putShort(language.toShort())
        .put(bytes)
        .array()
}
