package com.developercal.secondlook

import androidx.room.Room
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.developercal.secondlook.data.BlockedNumber
import com.developercal.secondlook.data.LocalDatabase
import com.developercal.secondlook.data.LocalSettings
import com.developercal.secondlook.data.IntelligenceCache
import com.developercal.secondlook.data.IntelligenceSnapshot
import com.developercal.secondlook.data.ReportedNumber
import com.developercal.secondlook.data.SavedScan
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalPersistenceTest {
    @Test fun blocklistEntriesCanBeCreatedListedAndDeleted() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.dao().addBlocked(BlockedNumber("+442079460000", "Example"))
            assertTrue(db.dao().isBlocked("+442079460000"))
            assertEquals("Example", db.dao().listBlocked().single().label)
            db.dao().removeBlocked("+442079460000")
            assertTrue(db.dao().listBlocked().isEmpty())
        } finally { db.close() }
    }

    @Test fun scanHistoryIsOptInAndCanBeCleared() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferenceFile = "secondlook_test_history_${System.nanoTime()}"
        val settings = LocalSettings(context, preferenceFile)
        assertFalse(settings.historyEnabled)
        settings.historyEnabled = true
        assertTrue(LocalSettings(context, preferenceFile).historyEnabled)
        val db = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.dao().saveScan(SavedScan(kind = "Message", level = "CAUTION", report = "Fictional test report"))
            assertEquals(1, db.dao().listScans().size)
            db.dao().clearScans()
            assertTrue(db.dao().listScans().isEmpty())
        } finally {
            db.close()
            settings.historyEnabled = false
            context.getSharedPreferences(preferenceFile, android.content.Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun themeSettingSurvivesANewSettingsInstance() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferenceFile = "secondlook_test_theme_${System.nanoTime()}"
        val settings = LocalSettings(context, preferenceFile)
        settings.theme = "dark"
        assertEquals("dark", LocalSettings(context, preferenceFile).theme)
        settings.theme = "system"
        context.getSharedPreferences(preferenceFile, android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun versionOneDatabaseMigratesWithoutLosingBlocklistOrHistory() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "migration-${System.nanoTime()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val old = SQLiteDatabase.openOrCreateDatabase(file, null)
        old.execSQL("CREATE TABLE blocked_numbers (normalized TEXT NOT NULL PRIMARY KEY, label TEXT NOT NULL)")
        old.execSQL("CREATE TABLE saved_scans (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, kind TEXT NOT NULL, level TEXT NOT NULL, report TEXT NOT NULL, createdAt INTEGER NOT NULL)")
        old.execSQL("INSERT INTO blocked_numbers VALUES ('+12025550123', 'Migration check')")
        old.execSQL("INSERT INTO saved_scans VALUES (1, 'Message', 'CAUTION', 'Existing local summary', 1234)")
        old.version = 1
        old.close()
        val migrated = Room.databaseBuilder(context, LocalDatabase::class.java, name).addMigrations(LocalDatabase.MIGRATION_1_2, LocalDatabase.MIGRATION_2_3).allowMainThreadQueries().build()
        try {
            assertEquals("Migration check", migrated.dao().listBlocked().single().label)
            assertEquals("Existing local summary", migrated.dao().listScans().single().report)
            assertNull(migrated.dao().getSnapshot(IntelligenceCache.FTC_REPORTS_SOURCE_ID))
            migrated.dao().addBlocked(BlockedNumber("+12025550124", "Post migration"))
            assertTrue(migrated.dao().isBlocked("+12025550124"))
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun intelligenceSnapshotIsAtomicAndLookupOnlyLabelsUnverifiedReports() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).allowMainThreadQueries().build()
        try {
            val cache = IntelligenceCache(db.dao())
            val absent = cache.lookup("+1 (202) 555-0123")!!
            assertNull(absent.snapshot)
            assertNull(absent.reportedNumber)
            val now = System.currentTimeMillis()
            val number = ReportedNumber(IntelligenceCache.FTC_REPORTS_SOURCE_ID, "+12025550123", 2, now - 60_000)
            cache.installAuthorizedSnapshot(
                IntelligenceSnapshot(
                    sourceId = IntelligenceCache.FTC_REPORTS_SOURCE_ID,
                    sourceName = "FTC Do Not Call reported calls",
                    sourceUrl = "https://www.ftc.gov/policy-notices/open-government/data-sets/do-not-call-data",
                    evidenceLabel = IntelligenceCache.FTC_UNVERIFIED_EVIDENCE_LABEL,
                    retrievedAt = now,
                    dataAsOf = now - 60_000,
                    contentSha256 = "a".repeat(64),
                    recordCount = 1
                ),
                listOf(number),
                now
            )
            assertEquals(2, cache.lookup("+1 (202) 555-0123")?.reportedNumber?.reportCount)
            assertEquals(IntelligenceCache.FTC_UNVERIFIED_EVIDENCE_LABEL, cache.lookup("+12025550123")?.snapshot?.evidenceLabel)
        } finally { db.close() }
    }

    @Test fun intelligenceSnapshotRejectsUnverifiedLabelChangesAndUnsafeUrls() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).allowMainThreadQueries().build()
        try {
            val now = System.currentTimeMillis()
            val cache = IntelligenceCache(db.dao())
            val base = IntelligenceSnapshot(
                sourceId = IntelligenceCache.FTC_REPORTS_SOURCE_ID,
                sourceName = "FTC DNC",
                sourceUrl = "https://www.ftc.gov/data",
                evidenceLabel = "Confirmed scam reports",
                retrievedAt = now,
                dataAsOf = now,
                contentSha256 = "b".repeat(64),
                recordCount = 0
            )
            try { cache.installAuthorizedSnapshot(base, emptyList(), now); fail("Unsupported evidence label must be rejected") } catch (_: IllegalArgumentException) { }
            try { cache.installAuthorizedSnapshot(base.copy(evidenceLabel = IntelligenceCache.FTC_UNVERIFIED_EVIDENCE_LABEL, sourceUrl = "http://example.com"), emptyList(), now); fail("Non-HTTPS source must be rejected") } catch (_: IllegalArgumentException) { }
            assertNull(db.dao().getSnapshot(IntelligenceCache.FTC_REPORTS_SOURCE_ID))
        } finally { db.close() }
    }
}
