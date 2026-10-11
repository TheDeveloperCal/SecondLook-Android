package com.developercal.secondlook.analysis

import com.developercal.secondlook.data.FtcReportCsvParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class FtcReportCsvParserTest {
    private val header = "Company_Phone_Number,Created_Date,Violation_Date,Consumer_City,Consumer_State,Consumer_Area_Code,Subject,Recorded_Message_Or_Robocall"

    @Test fun parsesAndAggregatesThePublishedCsvShapeWithoutRetainingLocationOrSubject() {
        val csv = """$header
2025550123,2026-10-08 10:00:00,2026-10-07 09:00:00,"A city, ignored",Virginia,703,"Subject, ignored",Y
12025550123,2026-10-08 11:00:00,2026-10-08 10:00:00,,,,,N
,2026-10-08 12:00:00,2026-10-08 11:00:00,,,,,Y
""".trimIndent().toByteArray()
        val parsed = FtcReportCsvParser.parse(csv, LocalDate.parse("2026-10-09"))

        assertEquals(3, parsed.inputRows)
        assertEquals(1, parsed.ignoredRows)
        assertEquals(1, parsed.records.size)
        assertEquals("+12025550123", parsed.records.single().normalized)
        assertEquals(2, parsed.records.single().reportCount)
        assertNotNull(parsed.records.single().latestReportAt)
        assertEquals(64, parsed.sha256.length)
    }

    @Test fun rejectsUnknownSchemaMalformedRowsAndExcessiveMalformedNumbers() {
        val changedHeader = "$header\n".replace("Subject", "Different_Subject")
        assertThrows(IllegalArgumentException::class.java) {
            FtcReportCsvParser.parse((changedHeader + "2025550123,2026-10-08 10:00:00,2026-10-07 09:00:00,,,,,Y\n").toByteArray(), LocalDate.parse("2026-10-09"))
        }
        val malformedQuote = "$header\n2025550123,2026-10-08 10:00:00,2026-10-07 09:00:00,,,,\"broken,Y\n"
        assertThrows(IllegalArgumentException::class.java) {
            FtcReportCsvParser.parse(malformedQuote.toByteArray(), LocalDate.parse("2026-10-09"))
        }
        val malformedNumbers = (1..4).joinToString("\n") { "215,2026-10-08 10:00:00,2026-10-07 09:00:00,,,,,Y" }
        assertThrows(IllegalArgumentException::class.java) {
            FtcReportCsvParser.parse("$header\n$malformedNumbers\n".toByteArray(), LocalDate.parse("2026-10-09"))
        }
    }
}
