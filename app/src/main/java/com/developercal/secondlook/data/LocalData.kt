package com.developercal.secondlook.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "blocked_numbers")
data class BlockedNumber(@PrimaryKey val normalized: String, val label: String = "")

@Entity(tableName = "saved_scans")
data class SavedScan(@PrimaryKey(autoGenerate = true) val id: Long = 0, val kind: String, val level: String, val report: String, val createdAt: Long = System.currentTimeMillis())

/** Aggregated source metadata; no complaint text, reporter details, location, or caller history is retained. */
@Entity(tableName = "intelligence_snapshots")
data class IntelligenceSnapshot(
    @PrimaryKey val sourceId: String,
    val sourceName: String,
    val sourceUrl: String,
    val evidenceLabel: String,
    val retrievedAt: Long,
    val dataAsOf: Long?,
    val contentSha256: String,
    val recordCount: Int,
    val ignoredRows: Int = 0
)

/** Only aggregate counts of unverified reports are cached. These records never cause call blocking. */
@Entity(tableName = "reported_numbers", primaryKeys = ["sourceId", "normalized"])
data class ReportedNumber(
    val sourceId: String,
    val normalized: String,
    val reportCount: Int,
    val latestReportAt: Long?
)

@Dao
interface LocalDao {
    @Query("SELECT * FROM blocked_numbers ORDER BY normalized") fun listBlocked(): List<BlockedNumber>
    @Query("SELECT EXISTS(SELECT 1 FROM blocked_numbers WHERE normalized = :number)") fun isBlocked(number: String): Boolean
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun addBlocked(number: BlockedNumber)
    @Query("DELETE FROM blocked_numbers WHERE normalized = :number") fun removeBlocked(number: String)
    @Query("SELECT * FROM saved_scans ORDER BY createdAt DESC") fun listScans(): List<SavedScan>
    @Insert fun saveScan(scan: SavedScan)
    @Query("DELETE FROM saved_scans") fun clearScans()
    @Query("SELECT * FROM intelligence_snapshots WHERE sourceId = :sourceId") fun getSnapshot(sourceId: String): IntelligenceSnapshot?
    @Query("SELECT * FROM reported_numbers WHERE sourceId = :sourceId AND normalized = :number") fun getReportedNumber(sourceId: String, number: String): ReportedNumber?
    @Query("SELECT * FROM reported_numbers WHERE sourceId = :sourceId AND normalized = :number") fun findReportedNumber(sourceId: String, number: String): ReportedNumber?
    @Query("DELETE FROM reported_numbers WHERE sourceId = :sourceId") fun clearReportedNumbers(sourceId: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertSnapshot(snapshot: IntelligenceSnapshot)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertReportedNumbers(numbers: List<ReportedNumber>)

    @Transaction
    fun replaceIntelligenceSnapshot(snapshot: IntelligenceSnapshot, numbers: List<ReportedNumber>) {
        clearReportedNumbers(snapshot.sourceId)
        insertReportedNumbers(numbers)
        insertSnapshot(snapshot)
    }
}

@Database(entities = [BlockedNumber::class, SavedScan::class, IntelligenceSnapshot::class, ReportedNumber::class], version = 3, exportSchema = false)
abstract class LocalDatabase : RoomDatabase() {
    abstract fun dao(): LocalDao
    companion object {
        @Volatile private var instance: LocalDatabase? = null
        fun get(context: Context): LocalDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LocalDatabase::class.java, "secondlook-local.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also { instance = it }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `intelligence_snapshots` (`sourceId` TEXT NOT NULL, `sourceName` TEXT NOT NULL, `sourceUrl` TEXT NOT NULL, `evidenceLabel` TEXT NOT NULL, `retrievedAt` INTEGER NOT NULL, `dataAsOf` INTEGER, `contentSha256` TEXT NOT NULL, `recordCount` INTEGER NOT NULL, PRIMARY KEY(`sourceId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `reported_numbers` (`sourceId` TEXT NOT NULL, `normalized` TEXT NOT NULL, `reportCount` INTEGER NOT NULL, `latestReportAt` INTEGER, PRIMARY KEY(`sourceId`, `normalized`))")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `intelligence_snapshots` ADD COLUMN `ignoredRows` INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}

/** Transactional validation boundary for a future authorized source importer. Empty cache is intentional. */
class IntelligenceCache(private val dao: LocalDao) {
    fun lookup(number: String): LocalIntelligenceLookup? {
        val normalized = com.developercal.secondlook.analysis.CallDecisionEngine.normalize(number) ?: return null
        val snapshot = dao.getSnapshot(FTC_REPORTS_SOURCE_ID) ?: return LocalIntelligenceLookup(normalized, null, null)
        val digits = normalized.removePrefix("+")
        val ftcNumber = when {
            digits.length == 10 -> "+1$digits"
            digits.length == 11 && digits.startsWith("1") -> "+$digits"
            else -> normalized
        }
        return LocalIntelligenceLookup(normalized, snapshot, dao.findReportedNumber(FTC_REPORTS_SOURCE_ID, ftcNumber))
    }

    fun installAuthorizedSnapshot(snapshot: IntelligenceSnapshot, records: List<ReportedNumber>, now: Long = System.currentTimeMillis()) {
        require(snapshot.sourceId == FTC_REPORTS_SOURCE_ID) { "Unsupported intelligence source" }
        val uri = java.net.URI(snapshot.sourceUrl)
        require(uri.scheme == "https" && uri.host in setOf("www.ftc.gov", "search.ftc.gov", "api.ftc.gov")) { "Snapshot source is not allowlisted HTTPS" }
        require(snapshot.evidenceLabel == FTC_UNVERIFIED_EVIDENCE_LABEL) { "FTC consumer reports must remain labeled unverified" }
        require(snapshot.retrievedAt in 1..now && snapshot.recordCount == records.size && snapshot.ignoredRows >= 0) { "Snapshot metadata is invalid" }
        require(snapshot.contentSha256.matches(Regex("^[a-fA-F0-9]{64}$"))) { "Snapshot digest is invalid" }
        require(records.size <= MAX_FTC_SNAPSHOT_RECORDS) { "Snapshot exceeds the supported record limit" }
        require(records.all { it.sourceId == FTC_REPORTS_SOURCE_ID && it.reportCount in 1..MAX_FTC_SNAPSHOT_RECORDS && com.developercal.secondlook.analysis.CallDecisionEngine.normalize(it.normalized) == it.normalized }) { "Snapshot contains invalid number records" }
        require(records.map { it.normalized }.distinct().size == records.size) { "Snapshot contains duplicate numbers" }
        require(snapshot.dataAsOf == null || snapshot.dataAsOf <= now) { "Snapshot data date is in the future" }
        dao.replaceIntelligenceSnapshot(snapshot, records)
    }

    companion object {
        const val FTC_REPORTS_SOURCE_ID = "ftc_dnc_reported_calls"
        const val FTC_UNVERIFIED_EVIDENCE_LABEL = "Unverified consumer reports"
        const val MAX_FTC_SNAPSHOT_RECORDS = 1_000_000
    }
}

data class LocalIntelligenceLookup(val normalized: String, val snapshot: IntelligenceSnapshot?, val reportedNumber: ReportedNumber?)

class LocalSettings(context: Context, preferencesName: String = "secondlook_preferences") {
    private val prefs = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    var historyEnabled: Boolean
        get() = prefs.getBoolean("history_enabled", false)
        set(value) { prefs.edit().putBoolean("history_enabled", value).apply() }
    var protectionEnabled: Boolean
        get() = prefs.getBoolean("protection_enabled", false)
        set(value) { prefs.edit().putBoolean("protection_enabled", value).apply() }
    var blocklistEnabled: Boolean
        get() = prefs.getBoolean("blocklist_enabled", true)
        set(value) { prefs.edit().putBoolean("blocklist_enabled", value).apply() }
    var privateNumbersEnabled: Boolean
        get() = prefs.getBoolean("private_numbers_enabled", false)
        set(value) { prefs.edit().putBoolean("private_numbers_enabled", value).apply() }
    var theme: String
        get() = prefs.getString("theme", "system") ?: "system"
        set(value) { prefs.edit().putString("theme", value).apply() }
    var intelligenceUpdateStatus: String
        get() = prefs.getString("intelligence_update_status", "never") ?: "never"
        set(value) { prefs.edit().putString("intelligence_update_status", value.take(80)).putLong("intelligence_update_attempt", System.currentTimeMillis()).apply() }
    val intelligenceUpdateAttempt: Long get() = prefs.getLong("intelligence_update_attempt", 0L)
}
