package com.ilan.reminders

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Long
)

@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val notes: String = "",
    val categoryId: Long = 0,
    val triggerAt: Long = 0,
    val repeat: String = "NONE",     // NONE, SCHEDULE (weekly: days + times), DAILY, MONTHLY, LIST
    val days: Int = 0,               // bitmask, Sunday = bit 0 ... Saturday = bit 6
    val times: String = "",          // "08:00,20:00"
    val sound: String = "",          // "" = default, "silent", or a uri
    val vibration: String = "STRONG",// STRONG, SHORT, PULSE, NONE
    val done: Boolean = false,
    val fired: Boolean = false,
    val snoozeUntil: Long = 0,
    // per-day schedule: "0=08:00,20:00;2=09:00" (0 = Sunday)
    @ColumnInfo(defaultValue = "''") val slots: String = "",
    // last user/web edit time (ms), used to decide who wins when the phone and the web both changed a task
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
    // ONCE = normal alert, NONE = shown in the list only, no notification at all
    @ColumnInfo(defaultValue = "'ONCE'") val alertMode: String = "ONCE",
    // starred reminders are shown first and have their own filter
    @ColumnInfo(defaultValue = "0") val important: Boolean = false,
    // > 0 = in the recycle bin since this time (kept 30 days)
    @ColumnInfo(defaultValue = "0") val deletedAt: Long = 0,
    // repeat every N days / weeks / months (SCHEDULE = weeks, MONTHLY = months, DAILY = days)
    @ColumnInfo(defaultValue = "1") val every: Int = 1,
    // for "every N weeks": any moment inside the first week of the cycle
    @ColumnInfo(defaultValue = "0") val anchor: Long = 0,
    // for list-only (no alert) reminders: the sub-list they belong to, e.g. "קניות"; blank = general
    @ColumnInfo(defaultValue = "''") val listName: String = ""
)

fun Task.touch() = copy(updatedAt = System.currentTimeMillis())

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY done, triggerAt")
    fun all(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE done = 0 AND deletedAt = 0")
    suspend fun active(): List<Task>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: Long): Task?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(t: Task): Long

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM categories ORDER BY id")
    fun categories(): Flow<List<Category>>

    @Query("SELECT * FROM tasks")
    suspend fun allNow(): List<Task>

    @Query("SELECT * FROM tasks WHERE deletedAt > 0 AND deletedAt < :before")
    suspend fun expired(before: Long): List<Task>

    @Query("SELECT * FROM categories")
    suspend fun categoriesNow(): List<Category>

    @Insert
    suspend fun addCategory(c: Category): Long

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteCategory(id: Long)

    @Query("UPDATE tasks SET categoryId = 0 WHERE categoryId = :id")
    suspend fun clearCategory(id: Long)
}

@Database(entities = [Task::class, Category::class], version = 8, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): TaskDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(c: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "reminders.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .fallbackToDestructiveMigration()
                .build().also { inst = it }
        }
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN slots TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN alertMode TEXT NOT NULL DEFAULT 'ONCE'")
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN listName TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN every INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE tasks ADD COLUMN anchor INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN important INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE tasks ADD COLUMN deletedAt INTEGER NOT NULL DEFAULT 0")
    }
}

/** One alert time of a weekly schedule. Stored as "HH:mm" or, for "every N weeks", "HH:mm/N/anchorEpochDay". */
data class Slot(val time: String, val every: Int = 1, val anchorDay: Long = 0) {
    fun token() = if (every <= 1) time else "$time/$every/$anchorDay"
}

fun parseSlot(tok: String): Slot {
    val p = tok.split("/")
    return Slot(p[0], p.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1, p.getOrNull(2)?.toLongOrNull() ?: 0L)
}

/** weekday (0 = Sunday) -> times. Falls back to the older days + times fields. */
fun Task.schedule(): Map<Int, List<String>> {
    if (slots.isNotBlank()) {
        // v35 stored one "every N weeks" for the whole task: fold it into the times
        val legacy = if (repeat == "SCHEDULE" && every > 1 && anchor > 0)
            java.time.Instant.ofEpochMilli(anchor).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay() else 0L
        return slots.split(";").filter { it.contains("=") }.associate {
            val p = it.split("=", limit = 2)
            p[0].toInt() to p[1].split(",").filter { x -> x.isNotBlank() }
                .map { x -> if (legacy > 0 && '/' !in x) "$x/$every/$legacy" else x }.sorted()
        }
    }
    val ts = times.split(",").filter { it.isNotBlank() }.sorted()
    return (0..6).filter { days and (1 shl it) != 0 }.associateWith { ts }
}

/** For repeat == "LIST": the exact alert moments (epoch ms), stored comma separated in [slots]. */
fun Task.dateList(): List<Long> =
    if (repeat != "LIST") emptyList() else slots.split(",").mapNotNull { it.trim().toLongOrNull() }.sorted()

fun encodeSchedule(m: Map<Int, List<String>>): String =
    m.toSortedMap().filter { it.value.isNotEmpty() }.entries
        .joinToString(";") { "${it.key}=${it.value.sorted().joinToString(",")}" }
