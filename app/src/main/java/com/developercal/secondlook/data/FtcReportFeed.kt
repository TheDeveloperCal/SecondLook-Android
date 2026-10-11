package com.developercal.secondlook.data

import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import android.content.Context
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ParsedFtcReportFile(
    val records: List<ReportedNumber>,
    val sourceFileDate: LocalDate,
    val dataAsOf: Long,
    val ignoredRows: Int,
    val sha256: String,
    val inputRows: Int
)

/** Parses only the FTC's published daily reported-calls CSV, dropping all complaint text and location fields. */
object FtcReportCsvParser {
    private val expectedHeader = listOf(
        "Company_Phone_Number", "Created_Date", "Violation_Date", "Consumer_City",
        "Consumer_State", "Consumer_Area_Code", "Subject", "Recorded_Message_Or_Robocall"
    )
    private val timestamp = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.US)
    private const val MAX_FILE_BYTES = 15 * 1024 * 1024
    private const val MAX_ROWS = 100_000

    fun parse(bytes: ByteArray, sourceFileDate: LocalDate, now: Long = System.currentTimeMillis()): ParsedFtcReportFile {
        require(bytes.isNotEmpty() && bytes.size <= MAX_FILE_BYTES) { "FTC file is empty or too large" }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = decoder.decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        val table = parseCsv(text)
        require(table.size in 2..(MAX_ROWS + 1)) { "FTC file has no records or exceeds the row limit" }
        require(table.first() == expectedHeader) { "FTC CSV schema changed" }
        val byNumber = linkedMapOf<String, Pair<Int, Long>>()
        var ignoredRows = 0
        var malformedNumbers = 0
        var latestCreated = 0L
        val notFutureBeyondTolerance = now + TimeUnit.DAYS.toMillis(1)
        for (row in table.drop(1)) {
            require(row.size == expectedHeader.size) { "FTC row has an unexpected column count" }
            val created = parseDate(row[1])
            val violation = parseDate(row[2])
            require(created <= notFutureBeyondTolerance && violation <= notFutureBeyondTolerance) { "FTC row contains a future date" }
            latestCreated = maxOf(latestCreated, created)
            val sourcePhone = row[0].trim()
            if (sourcePhone.isEmpty()) {
                ignoredRows++
                continue
            }
            val normalized = normalizeFtcNorthAmericanNumber(sourcePhone)
            if (normalized == null) {
                malformedNumbers++
                ignoredRows++
                continue
            }
            val previous = byNumber[normalized]
            byNumber[normalized] = (previous?.first ?: 0) + 1 to maxOf(previous?.second ?: 0L, violation)
        }
        require(byNumber.isNotEmpty()) { "FTC file contains no usable phone numbers" }
        require(malformedNumbers * 100L <= table.size.toLong()) { "Too many malformed FTC phone numbers" }
        return ParsedFtcReportFile(
            records = byNumber.map { (number, value) -> ReportedNumber(IntelligenceCache.FTC_REPORTS_SOURCE_ID, number, value.first, value.second) },
            sourceFileDate = sourceFileDate,
            dataAsOf = latestCreated,
            ignoredRows = ignoredRows,
            sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
            inputRows = table.size - 1
        )
    }

    private fun parseDate(value: String): Long = try {
        LocalDateTime.parse(value.trim(), timestamp).atZone(ZoneId.of("America/New_York")).toInstant().toEpochMilli()
    } catch (e: Exception) {
        throw IllegalArgumentException("FTC row contains a malformed timestamp", e)
    }

    private fun normalizeFtcNorthAmericanNumber(value: String): String? {
        val digits = buildString {
            value.forEach { c ->
                when {
                    c.isDigit() -> append(c)
                    c.isWhitespace() || c in "()+-." -> Unit
                    else -> return null
                }
            }
        }
        val national = when {
            digits.length == 10 -> digits
            digits.length == 11 && digits.startsWith('1') -> digits.drop(1)
            else -> return null
        }
        return "+1$national"
    }

    /** RFC 4180 row parser with escaped quotes, commas inside fields, and CRLF/LF support. */
    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closedQuote = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"' && i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ }
                else if (c == '"') { quoted = false; closedQuote = true }
                else field.append(c)
            } else when (c) {
                '"' -> {
                    require(field.isEmpty() && !closedQuote) { "Malformed quoted FTC field" }
                    quoted = true
                }
                ',' -> { row += field.toString(); field.setLength(0); closedQuote = false }
                '\n' -> {
                    row += field.toString().removeSuffix("\r")
                    rows += row
                    row = mutableListOf(); field.setLength(0); closedQuote = false
                    require(rows.size <= MAX_ROWS + 1) { "FTC file exceeds the row limit" }
                }
                else -> {
                    require(!closedQuote || c == '\r') { "Unexpected content after a quoted FTC field" }
                    field.append(c)
                }
            }
            i++
        }
        require(!quoted) { "FTC CSV contains an unterminated quoted field" }
        if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString().removeSuffix("\r"); rows += row }
        return rows
    }
}

class FtcReportUpdater(private val context: Context) {
    private val dao get() = LocalDatabase.get(context).dao()
    private val settings get() = LocalSettings(context)

    fun refresh(): String {
        val now = System.currentTimeMillis()
        return try {
            val today = LocalDate.now(ZoneId.of("America/New_York"))
            var lastError: Exception? = null
            var parsed: ParsedFtcReportFile? = null
            for (daysAgo in 0L..14L) {
                val date = today.minusDays(daysAgo)
                val url = "$FTC_DAILY_FILE_ROOT/DNC_Complaint_Numbers_${date}.csv"
                try {
                    val bytes = download(url)
                    parsed = FtcReportCsvParser.parse(bytes, date, now)
                    break
                } catch (e: java.io.FileNotFoundException) {
                    lastError = e
                } catch (e: Exception) {
                    lastError = e
                    break
                }
            }
            val file = parsed ?: throw lastError ?: IllegalStateException("No recent FTC daily file was found")
            val snapshot = IntelligenceSnapshot(
                sourceId = IntelligenceCache.FTC_REPORTS_SOURCE_ID,
                sourceName = "FTC Do Not Call reported calls",
                sourceUrl = "$FTC_DAILY_FILE_ROOT/DNC_Complaint_Numbers_${file.sourceFileDate}.csv",
                evidenceLabel = IntelligenceCache.FTC_UNVERIFIED_EVIDENCE_LABEL,
                retrievedAt = now,
                dataAsOf = file.dataAsOf,
                contentSha256 = file.sha256,
                recordCount = file.records.size,
                ignoredRows = file.ignoredRows
            )
            IntelligenceCache(dao).installAuthorizedSnapshot(snapshot, file.records, now)
            settings.intelligenceUpdateStatus = "success"
            "Updated from FTC file ${file.sourceFileDate}: ${file.records.size} unique numbers; ${file.ignoredRows} rows omitted"
        } catch (e: Exception) {
            settings.intelligenceUpdateStatus = "failed"
            "Update unavailable. Your last valid local report data, if any, was kept."
        }
    }

    private fun download(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 25_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "SecondLookShield/1.0 (FTC public daily report CSV)")
            setRequestProperty("Accept", "text/csv")
        }
        try {
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND || connection.responseCode == HttpURLConnection.HTTP_GONE) throw java.io.FileNotFoundException(url)
            require(connection.responseCode == HttpURLConnection.HTTP_OK) { "FTC returned HTTP ${connection.responseCode}" }
            require(connection.contentType?.lowercase(Locale.US)?.contains("text/csv") == true) { "FTC response was not CSV" }
            val declared = connection.contentLengthLong
            require(declared == -1L || declared in 1..15L * 1024 * 1024) { "FTC file exceeds the size limit" }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size().toLong() + count <= 15L * 1024 * 1024) { "FTC file exceeds the size limit" }
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        } finally { connection.disconnect() }
    }

    companion object {
        const val FTC_DAILY_FILE_ROOT = "https://www.ftc.gov/sites/default/files"
    }
}

class FtcReportRefreshWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val status = FtcReportUpdater(applicationContext).refresh()
        return if (status.startsWith("Updated from FTC")) Result.success() else Result.retry()
    }
}

object ThreatUpdateScheduler {
    private const val UNIQUE_WORK = "secondlook-ftc-report-refresh"
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<FtcReportRefreshWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
