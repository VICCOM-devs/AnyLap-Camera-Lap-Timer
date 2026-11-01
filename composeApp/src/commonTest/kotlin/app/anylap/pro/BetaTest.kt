package app.anylap.pro

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BetaTest {
    @Test
    fun parsesPostgrestTimestamps() {
        // 2027-01-31 23:59:59 UTC = the built-in default
        assertEquals(Beta.DEFAULT_UNTIL, Beta.parseIso("2027-01-31T23:59:59+00:00"))
        assertEquals(Beta.DEFAULT_UNTIL, Beta.parseIso("2027-01-31T23:59:59Z"))
        assertEquals(Beta.DEFAULT_UNTIL, Beta.parseIso("2027-01-31 23:59:59"))
        // an offset moves the instant: 01:00 +01:00 = 00:00 UTC
        assertEquals(Beta.parseIso("2027-02-01T00:00:00Z"), Beta.parseIso("2027-02-01T01:00:00+01:00"))
        assertEquals(Beta.parseIso("2027-02-01T00:00:00Z"), Beta.parseIso("2027-01-31T23:59:59Z")!! + 1000)
        assertNull(Beta.parseIso("soon"))
    }

    @Test
    fun formatsTheEndDate() {
        assertEquals(Beta.DEFAULT_UNTIL_LABEL, Beta.isoDate(Beta.DEFAULT_UNTIL))
        assertEquals("2026-11-01", Beta.isoDate(1_793_491_200_000L))
        assertEquals("", Beta.isoDate(Long.MAX_VALUE))
    }
}
