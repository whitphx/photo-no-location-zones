package io.github.whitphx.nolocationzones.photo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class Mp4GpsReaderTest {

    @Test
    fun `parses prefixed QuickTime payload`() {
        val xyz = boxWithTypeCode(Mp4Atoms.XYZ, quicktimeStringPayload("+35.6895+139.6917/"))
        val reader = ByteArrayBoxReader(box("moov", payload = box("udta", payload = xyz)))
        val coords = Mp4GpsReader.readGps(reader)
        assertNotNull(coords)
        assertEquals(35.6895, coords!![0], 1e-9)
        assertEquals(139.6917, coords[1], 1e-9)
    }

    @Test
    fun `falls back to raw payload when prefix is absent`() {
        // Some non-conformant writers emit just the ISO 6709 text with no length prefix.
        // The prefixed parse skips the first 4 bytes and ends up with a string that the
        // regex either doesn't match or matches with out-of-range numbers (rejected by the
        // range guard); the fallback then re-runs against the whole payload.
        val raw = "+12.34+56.78/".toByteArray(Charsets.UTF_8)
        val xyz = boxWithTypeCode(Mp4Atoms.XYZ, raw)
        val reader = ByteArrayBoxReader(box("moov", payload = box("udta", payload = xyz)))
        val coords = Mp4GpsReader.readGps(reader)
        assertNotNull(coords)
        assertEquals(12.34, coords!![0], 1e-9)
        assertEquals(56.78, coords[1], 1e-9)
    }

    @Test
    fun `negative coordinates round-trip`() {
        val xyz = boxWithTypeCode(Mp4Atoms.XYZ, quicktimeStringPayload("-33.8688-151.2093/"))
        val reader = ByteArrayBoxReader(box("moov", payload = box("udta", payload = xyz)))
        val coords = Mp4GpsReader.readGps(reader)
        assertNotNull(coords)
        assertEquals(-33.8688, coords!![0], 1e-9)
        assertEquals(-151.2093, coords[1], 1e-9)
    }

    @Test
    fun `out-of-range pair is rejected`() {
        // 999 is outside [-90, 90] for lat; both range guard and fallback should fail.
        val xyz = boxWithTypeCode(Mp4Atoms.XYZ, quicktimeStringPayload("+999.0+0.0/"))
        val reader = ByteArrayBoxReader(box("moov", payload = box("udta", payload = xyz)))
        assertNull(Mp4GpsReader.readGps(reader))
    }

    @Test
    fun `returns null when no xyz atom is present`() {
        val data = box("moov", payload = box("udta", payload = box("loci", ByteArray(8))))
        val reader = ByteArrayBoxReader(data)
        assertNull(Mp4GpsReader.readGps(reader))
    }

    @Test
    fun `returns null when moov is absent`() {
        val reader = ByteArrayBoxReader(box("ftyp", ByteArray(16)))
        assertNull(Mp4GpsReader.readGps(reader))
    }

    @Test
    fun `parseIso6709 accepts integer-only coordinates`() {
        val coords = Mp4GpsReader.parseIso6709("+35+139/")
        assertNotNull(coords)
        assertArrayEquals(doubleArrayOf(35.0, 139.0), coords!!, 1e-9)
    }

    @Test
    fun `parseIso6709 rejects strings without a leading sign on either number`() {
        assertNull(Mp4GpsReader.parseIso6709("35.0 139.0"))
    }

    @Test
    fun `parseIso6709 enforces lat-lon ranges`() {
        assertNull(Mp4GpsReader.parseIso6709("+91.0+0.0/"))
        assertNull(Mp4GpsReader.parseIso6709("+0.0+181.0/"))
        assertNotNull(Mp4GpsReader.parseIso6709("-90.0-180.0/"))
        assertNotNull(Mp4GpsReader.parseIso6709("+90.0+180.0/"))
    }
}
