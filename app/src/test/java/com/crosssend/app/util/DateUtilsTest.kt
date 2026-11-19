package com.crosssend.app.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Calendar
import java.util.Date
import java.util.Locale

class DateUtilsTest {

    private lateinit var originalLocale: Locale

    @Before
    fun setUp() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `parseDate parses ISO-8601 RFC and local date formats and rejects invalid inputs`() {
        assertNull(DateUtils.parseDate(null))
        assertNull(DateUtils.parseDate(""))
        assertNull(DateUtils.parseDate("   "))
        assertNull(DateUtils.parseDate("not-a-valid-date"))

        val expectedUtc = Date.from(Instant.parse("2025-03-15T14:30:00Z"))
        assertEquals(expectedUtc, DateUtils.parseDate("2025-03-15T14:30:00Z"))
        assertEquals(expectedUtc, DateUtils.parseDate("  2025-03-15T14:30:00.000Z  "))
        assertEquals(expectedUtc, DateUtils.parseDate("2025-03-15T16:30:00+02:00"))
        assertEquals(expectedUtc, DateUtils.parseDate("Sat, 15 Mar 2025 14:30:00 +0000"))
        assertEquals(expectedUtc, DateUtils.parseDate("Sat, 15 Mar 2025 14:30:00 GMT"))

        val isoDateOnly = DateUtils.parseDate("2025-03-15")
        assertNotNull(isoDateOnly)
        val isoCal = Calendar.getInstance().apply { time = isoDateOnly!! }
        assertEquals(2025, isoCal.get(Calendar.YEAR))
        assertEquals(Calendar.MARCH, isoCal.get(Calendar.MONTH))
        assertEquals(15, isoCal.get(Calendar.DAY_OF_MONTH))

        val deDateOnly = DateUtils.parseDate("15.03.2025")
        assertNotNull(deDateOnly)
        val deCal = Calendar.getInstance().apply { time = deDateOnly!! }
        assertEquals(2025, deCal.get(Calendar.YEAR))
        assertEquals(Calendar.MARCH, deCal.get(Calendar.MONTH))
        assertEquals(15, deCal.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `formatForFilename and parseFilenameTimestamp round-trip cleanly`() {
        val date = Calendar.getInstance().apply {
            set(2025, Calendar.MARCH, 15, 14, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }.time

        assertEquals("20250315_1430", DateUtils.formatForFilename(date))
        assertEquals(date, DateUtils.parseFilenameTimestamp("20250315_1430_CrossPoint_Article.epub"))
        assertNull(DateUtils.parseFilenameTimestamp("CrossPoint_Article.epub"))
        assertNull(DateUtils.parseFilenameTimestamp("20251345_9999_Invalid.epub"))
    }

    @Test
    fun `formatForByline respects active locale`() {
        val date = Calendar.getInstance().apply {
            set(2025, Calendar.MARCH, 15, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.time

        Locale.setDefault(Locale.US)
        assertEquals("2025-03-15", DateUtils.formatForByline(date))

        Locale.setDefault(Locale.GERMANY)
        assertEquals("15.03.2025", DateUtils.formatForByline(date))
    }

    @Test
    fun `formatRelativeOrDate formats relative minutes yesterday same-year and older dates`() {
        val nowMs = System.currentTimeMillis()
        val justNow = Date(nowMs - 10_000L)
        val fiveMinutesAgo = Date(nowMs - 5 * 60_000L - 5_000L)

        val nowCal = Calendar.getInstance()
        val yesterdayDate = (nowCal.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, -1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 1)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time

        val sameYearDate = (nowCal.clone() as Calendar).apply {
            if (get(Calendar.DAY_OF_YEAR) > 5) {
                set(Calendar.MONTH, Calendar.JANUARY)
                set(Calendar.DAY_OF_MONTH, 1)
            } else {
                set(Calendar.MONTH, Calendar.JUNE)
                set(Calendar.DAY_OF_MONTH, 15)
            }
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time

        val oldYearDate = Calendar.getInstance().apply {
            set(2020, Calendar.MAY, 15, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.time

        Locale.setDefault(Locale.US)
        assertEquals("Just now", DateUtils.formatRelativeOrDate(justNow))
        assertEquals("5m ago", DateUtils.formatRelativeOrDate(fiveMinutesAgo))
        assertEquals("Yesterday, 00:01", DateUtils.formatRelativeOrDate(yesterdayDate))
        assertEquals(
            SimpleDateFormat("d. MMM", Locale.US).format(sameYearDate),
            DateUtils.formatRelativeOrDate(sameYearDate)
        )
        assertEquals("2020-05-15", DateUtils.formatRelativeOrDate(oldYearDate))

        if (nowCal.get(Calendar.HOUR_OF_DAY) >= 2) {
            val twoHoursAgo = Date(nowMs - 2 * 3_600_000L - 5_000L)
            assertEquals("2h ago", DateUtils.formatRelativeOrDate(twoHoursAgo))
        }
        if (nowCal.get(Calendar.HOUR_OF_DAY) < 23) {
            val laterToday = (nowCal.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.time
            assertEquals("Today, 23:59", DateUtils.formatRelativeOrDate(laterToday))
        }

        Locale.setDefault(Locale.GERMANY)
        assertEquals("Gerade eben", DateUtils.formatRelativeOrDate(justNow))
        assertEquals("Vor 5 Min.", DateUtils.formatRelativeOrDate(fiveMinutesAgo))
        assertEquals("Gestern, 00:01", DateUtils.formatRelativeOrDate(yesterdayDate))
        assertEquals(
            SimpleDateFormat("d. MMM", Locale.GERMANY).format(sameYearDate),
            DateUtils.formatRelativeOrDate(sameYearDate)
        )
        assertEquals("15.05.2020", DateUtils.formatRelativeOrDate(oldYearDate))
    }
}
