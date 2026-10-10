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

@Entity(tableName = "blocked_numbers")
data class BlockedNumber(@PrimaryKey val normalized: String, val label: String = "")

@Entity(tableName = "saved_scans")
data class SavedScan(@PrimaryKey(autoGenerate = true) val id: Long = 0, val kind: String, val level: String, val report: String, val createdAt: Long = System.currentTimeMillis())

@Dao
interface LocalDao {
    @Query("SELECT * FROM blocked_numbers ORDER BY normalized") fun listBlocked(): List<BlockedNumber>
    @Query("SELECT EXISTS(SELECT 1 FROM blocked_numbers WHERE normalized = :number)") fun isBlocked(number: String): Boolean
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun addBlocked(number: BlockedNumber)
    @Query("DELETE FROM blocked_numbers WHERE normalized = :number") fun removeBlocked(number: String)
    @Query("SELECT * FROM saved_scans ORDER BY createdAt DESC") fun listScans(): List<SavedScan>
    @Insert fun saveScan(scan: SavedScan)
    @Query("DELETE FROM saved_scans") fun clearScans()
}

@Database(entities = [BlockedNumber::class, SavedScan::class], version = 1, exportSchema = false)
abstract class LocalDatabase : RoomDatabase() {
    abstract fun dao(): LocalDao
    companion object {
        @Volatile private var instance: LocalDatabase? = null
        fun get(context: Context): LocalDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LocalDatabase::class.java, "secondlook-local.db").build().also { instance = it }
        }
    }
}

class LocalSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("secondlook_preferences", Context.MODE_PRIVATE)
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
}
