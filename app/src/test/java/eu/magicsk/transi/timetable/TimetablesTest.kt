package eu.magicsk.transi.timetable

import com.google.gson.Gson
import eu.magicsk.transi.data.remote.responses.TimetableManifest
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalTime
import java.util.zip.GZIPOutputStream

class TimetablesTest {
    @Test
    fun routeTypeMapsToSection() {
        assertEquals(TimetableCategory.TRAMS, TimetableCategory.of(0, "4"))
        assertEquals(TimetableCategory.TRAINS, TimetableCategory.of(2, "S50"))
        assertEquals(TimetableCategory.TRAINS, TimetableCategory.of(100, "R"))
        assertEquals(TimetableCategory.TRAINS, TimetableCategory.of(199, "Os"))
        assertEquals(TimetableCategory.BUSES, TimetableCategory.of(3, "39"))
        assertEquals(TimetableCategory.NIGHT_LINES, TimetableCategory.of(3, "N33"))
        assertEquals(TimetableCategory.TROLLEYBUSES, TimetableCategory.of(11, "71"))
        assertEquals(TimetableCategory.TROLLEYBUSES, TimetableCategory.of(800, "72"))
        assertEquals(TimetableCategory.TROLLEYBUSES, TimetableCategory.of(50, "40"))
        assertEquals(TimetableCategory.NIGHT_LINES, TimetableCategory.of(800, "N80"))
        assertEquals(TimetableCategory.REGIONAL_BUSES, TimetableCategory.of(700, "520"))
        assertEquals(TimetableCategory.REGIONAL_BUSES, TimetableCategory.of(200, "X"))
    }

    @Test
    fun serviceMinutesFormatAsHoursAndMinutes() {
        assertEquals("00:00", formatServiceTime(0))
        assertEquals("07:05", formatServiceTime(425))
        assertEquals("23:59", formatServiceTime(1439))
        assertEquals("00:00", formatServiceTime(1440))
        assertEquals("01:30", formatServiceTime(1530))
    }

    @Test
    fun serviceDateIsBasicIso() {
        assertEquals("20261011", LocalDate.of(2026, 10, 11).serviceDate())
    }

    @Test
    fun activeServicesApplyCalendarDatesInOrder() {
        val active = activeServiceIds(
            calendar = listOf("weekday", "school"),
            exceptions = listOf("holiday" to 1, "school" to 2, "weekday" to 3, "extra" to 1, "extra" to 2),
        )
        assertEquals(setOf("weekday", "holiday"), active)
        assertEquals(emptySet<String>(), activeServiceIds(emptyList(), emptyList()))
    }

    @Test
    fun nextTripIsTheFirstAfterNow() {
        val trips = listOf(300, 420, 425, 1450)
        assertEquals(1, nextTripIndex(trips, LocalTime.of(5, 0)))
        assertEquals(2, nextTripIndex(trips, LocalTime.of(7, 0)))
        assertEquals(3, nextTripIndex(trips, LocalTime.of(23, 0)))
        assertEquals(0, nextTripIndex(listOf(300), LocalTime.of(23, 0)))
        assertEquals(0, nextTripIndex(emptyList(), LocalTime.NOON))
    }

    @Test
    fun minutesGroupByHourInFirstSeenOrder() {
        val rows = groupByHour(listOf(305, 325, 361, 1445, 1500))
        assertEquals(
            listOf("05" to listOf("05", "25"), "06" to listOf("01"), "00" to listOf("05"), "01" to listOf("00")),
            rows,
        )
    }

    @Test
    fun manifestParses() {
        val json = """
            {"schema_version":2,"generated_at":"2026-10-10T03:00:00Z","databases":{
              "base":{"version":"1","url":"/timetable/base.db.gz","size_bytes":2048,"size_bytes_gz":512,
                      "sha256":"ab","valid_from":"20261001","valid_until":"20261231"},
              "schedule":{"version":"2","url":"https://cdn.example/schedule.db.gz","size_bytes":4096,"size_bytes_gz":1024,
                      "sha256":"cd","valid_from":"20261001","valid_until":"20261231"}}}
        """.trimIndent()
        val manifest = Gson().fromJson(json, TimetableManifest::class.java)
        assertEquals("/timetable/base.db.gz", manifest.databases.base.url)
        assertEquals(1024L, manifest.databases.schedule.size_bytes_gz)
        assertEquals("cd", manifest.databases.schedule.sha256)
    }

    @Test
    fun gzipIsVerifiedThenInstalled() {
        val dir = Files.createTempDirectory("timetable").toFile()
        try {
            val content = "SQLite format 3\u0000 timetable".toByteArray()
            // The server's archives carry bytes after the gzip trailer; they are ignored like `gunzip` does.
            val gz = File(dir, "base.db.gz.part").apply { writeBytes(gzip(content) + byteArrayOf(0x26, 0x1d, 0x42, 0x3b)) }
            val destination = File(dir, "base.db").apply { writeText("previous") }

            assertThrows(IOException::class.java) { installGzip(gz, "0".repeat(64), destination) }
            assertEquals("previous", destination.readText())

            installGzip(gz, gz.readBytes().toByteString().sha256().hex(), destination)
            assertEquals(content.toList(), destination.readBytes().toList())
            assertFalse(File(dir, "base.db.part").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()
}
