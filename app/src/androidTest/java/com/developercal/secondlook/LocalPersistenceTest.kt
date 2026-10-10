package com.developercal.secondlook

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.developercal.secondlook.data.BlockedNumber
import com.developercal.secondlook.data.LocalDatabase
import com.developercal.secondlook.data.LocalSettings
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
        context.getSharedPreferences("secondlook_preferences", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val settings = LocalSettings(context)
        assertFalse(settings.historyEnabled)
        settings.historyEnabled = true
        assertTrue(LocalSettings(context).historyEnabled)
        val db = Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.dao().saveScan(SavedScan(kind = "Message", level = "CAUTION", report = "Fictional test report"))
            assertEquals(1, db.dao().listScans().size)
            db.dao().clearScans()
            assertTrue(db.dao().listScans().isEmpty())
        } finally { db.close(); settings.historyEnabled = false }
    }

    @Test fun themeSettingSurvivesANewSettingsInstance() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = LocalSettings(context)
        settings.theme = "dark"
        assertEquals("dark", LocalSettings(context).theme)
        settings.theme = "system"
    }
}
