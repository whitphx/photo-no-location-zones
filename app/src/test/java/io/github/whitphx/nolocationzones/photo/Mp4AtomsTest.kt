package io.github.whitphx.nolocationzones.photo

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp4AtomsTest {

    @Test
    fun `walks a single top-level box`() {
        val data = box("moov", payload = ByteArray(16) { 0 })
        val reader = ByteArrayBoxReader(data)
        val seen = mutableListOf<Int>()
        Mp4Atoms.walkBoxes(reader, 0L, reader.size) { type, _, _, _ -> seen += type }
        assertEquals(listOf(Mp4Atoms.MOOV), seen)
    }

    @Test
    fun `walks multiple top-level boxes in order`() {
        val data = box("ftyp", ByteArray(8)) + box("moov", ByteArray(8)) + box("free")
        val reader = ByteArrayBoxReader(data)
        val seen = mutableListOf<Int>()
        Mp4Atoms.walkBoxes(reader, 0L, reader.size) { type, _, _, _ -> seen += type }
        assertEquals(
            listOf(Mp4Atoms.atom("ftyp"), Mp4Atoms.MOOV, Mp4Atoms.FREE),
            seen,
        )
    }

    @Test
    fun `nested walk recurses into containers`() {
        val xyzBox = boxWithTypeCode(Mp4Atoms.XYZ, ByteArray(4))
        val udtaBox = box("udta", payload = xyzBox)
        val moovBox = box("moov", payload = udtaBox)
        val reader = ByteArrayBoxReader(moovBox)

        val foundXyz = mutableListOf<Long>()
        Mp4Atoms.walkBoxes(reader, 0L, reader.size) { t1, p1s, p1e, _ ->
            if (t1 != Mp4Atoms.MOOV) return@walkBoxes
            Mp4Atoms.walkBoxes(reader, p1s, p1e) { t2, p2s, p2e, _ ->
                if (t2 != Mp4Atoms.UDTA) return@walkBoxes
                Mp4Atoms.walkBoxes(reader, p2s, p2e) { t3, _, _, t3o ->
                    if (t3 == Mp4Atoms.XYZ) foundXyz += t3o
                }
            }
        }
        assertEquals(1, foundXyz.size)
    }

    @Test
    fun `typeOffset points at the four bytes after size`() {
        val data = box("ftyp", ByteArray(4)) + box("moov", ByteArray(4))
        val reader = ByteArrayBoxReader(data)
        val typeOffsets = mutableListOf<Long>()
        Mp4Atoms.walkBoxes(reader, 0L, reader.size) { _, _, _, off -> typeOffsets += off }
        // First box: size at 0, type at 4. Second box: starts at 12 (size 12), type at 16.
        assertEquals(listOf(4L, 16L), typeOffsets)
        // Verify the type-tag bytes at those offsets are the ASCII codes themselves.
        val typeBuf = ByteArray(4)
        reader.read(typeOffsets[0], typeBuf, 0, 4)
        assertArrayEquals("ftyp".toByteArray(Charsets.US_ASCII), typeBuf)
        reader.read(typeOffsets[1], typeBuf, 0, 4)
        assertArrayEquals("moov".toByteArray(Charsets.US_ASCII), typeBuf)
    }

    @Test
    fun `size equals zero extends to end of scope`() {
        // A box declared with size=0 must extend to the end of the surrounding scope.
        val payload = ByteArray(20) { it.toByte() }
        val type = "moov".toByteArray(Charsets.US_ASCII)
        val data = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.BIG_ENDIAN)
            .putInt(0)              // size = 0
            .put(type)
            .put(payload)
            .array()
        val reader = ByteArrayBoxReader(data)
        var seenEnd: Long = -1
        Mp4Atoms.walkBoxes(reader, 0L, reader.size) { _, _, payloadEnd, _ -> seenEnd = payloadEnd }
        assertEquals(reader.size, seenEnd)
    }

    @Test
    fun `extended 64-bit size is decoded`() {
        // size=1 sentinel + actual 64-bit size in the next 8 bytes. Header is 16 bytes total.
        val payload = ByteArray(8) { 0xAB.toByte() }
        val totalSize = 16L + payload.size
        val data = ByteBuffer.allocate(totalSize.toInt()).order(ByteOrder.BIG_ENDIAN)
            .putInt(1)                              // size sentinel
            .put("moov".toByteArray(Charsets.US_ASCII))
            .putLong(totalSize)                     // actual 64-bit size
            .put(payload)
            .array()
        val reader = ByteArrayBoxReader(data)
        var seenStart: Long = -1
        var seenEnd: Long = -1
        Mp4Atoms.walkBoxes(reader, 0L, reader.size) { _, payloadStart, payloadEnd, _ ->
            seenStart = payloadStart
            seenEnd = payloadEnd
        }
        assertEquals(16L, seenStart)
        assertEquals(totalSize, seenEnd)
    }

    @Test
    fun `malformed size below header length is rejected`() {
        // size=4 is too small to even contain the header (8 bytes). Walker must abort cleanly.
        val data = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putInt(4)
            .put("moov".toByteArray(Charsets.US_ASCII))
            .array()
        val reader = ByteArrayBoxReader(data)
        var seen = 0
        Mp4Atoms.walkBoxes(reader, 0L, reader.size) { _, _, _, _ -> seen++ }
        assertEquals(0, seen)
    }

    @Test
    fun `size running past end aborts`() {
        // size=16 but the file is only 12 bytes long.
        val data = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
            .putInt(16)
            .put("moov".toByteArray(Charsets.US_ASCII))
            .putInt(0)
            .array()
        val reader = ByteArrayBoxReader(data)
        var seen = 0
        Mp4Atoms.walkBoxes(reader, 0L, reader.size) { _, _, _, _ -> seen++ }
        assertEquals(0, seen)
    }

    @Test
    fun `under eight bytes does not call the callback`() {
        val reader = ByteArrayBoxReader(ByteArray(7))
        var seen = 0
        Mp4Atoms.walkBoxes(reader, 0L, reader.size) { _, _, _, _ -> seen++ }
        assertEquals(0, seen)
    }

    @Test
    fun `walk skips deeper into a nested udta with multiple children`() {
        // moov / udta { loci, ©xyz, free } — the walker should surface all three children.
        val loci = box("loci", ByteArray(4))
        val xyz = boxWithTypeCode(Mp4Atoms.XYZ, ByteArray(4))
        val free = box("free", ByteArray(4))
        val udtaPayload = loci + xyz + free
        val moovPayload = box("udta", payload = udtaPayload)
        val data = box("moov", payload = moovPayload)
        val reader = ByteArrayBoxReader(data)

        val children = mutableListOf<Int>()
        Mp4Atoms.walkBoxes(reader, 0L, reader.size) { t1, p1s, p1e, _ ->
            if (t1 != Mp4Atoms.MOOV) return@walkBoxes
            Mp4Atoms.walkBoxes(reader, p1s, p1e) { t2, p2s, p2e, _ ->
                if (t2 != Mp4Atoms.UDTA) return@walkBoxes
                Mp4Atoms.walkBoxes(reader, p2s, p2e) { t3, _, _, _ -> children += t3 }
            }
        }
        assertEquals(listOf(Mp4Atoms.LOCI, Mp4Atoms.XYZ, Mp4Atoms.FREE), children)
    }

    @Test
    fun `findLocationAtomTypeOffsets locates xyz and loci nested under moov udta`() {
        val xyz = boxWithTypeCode(Mp4Atoms.XYZ, quicktimeStringPayload("+35.0+139.0/"))
        val loci = box("loci", ByteArray(8))
        val udta = box("udta", payload = xyz + loci)
        val moov = box("moov", payload = udta)
        val data = box("ftyp", ByteArray(8)) + moov
        val reader = ByteArrayBoxReader(data)

        val offsets = Mp4GpsStripper.findLocationAtomTypeOffsets(reader)
        assertEquals(2, offsets.size)
        // Verify the type bytes at the reported offsets are the actual location atom types.
        val buf = ByteArray(4)
        reader.read(offsets[0], buf, 0, 4)
        assertEquals(Mp4Atoms.XYZ, ((buf[0].toInt() and 0xFF) shl 24) or
            ((buf[1].toInt() and 0xFF) shl 16) or
            ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF))
        reader.read(offsets[1], buf, 0, 4)
        assertArrayEquals("loci".toByteArray(Charsets.US_ASCII), buf)
    }

    @Test
    fun `findLocationAtomTypeOffsets returns empty when moov udta absent`() {
        val data = box("ftyp", ByteArray(8)) + box("free", ByteArray(8))
        val reader = ByteArrayBoxReader(data)
        assertTrue(Mp4GpsStripper.findLocationAtomTypeOffsets(reader).isEmpty())
    }
}
