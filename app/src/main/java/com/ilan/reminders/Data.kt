package com.ilan.reminders

import android.content.Context
import androidx.room.*
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
    val repeat: String = "NONE",     // NONE, SCHEDULE (days + times), MONTHLY
    val days: Int = 0,               // bitmask, Sunday = bit 0 ... Saturday = bit 6
    val times: String = "",          // "08:00,20:00"
    val sound: String = "",          // "" = default, "silent", or a uri
    val vibration: String = "STRONG",// STRONG, SHORT, PULSE, NONE
    val done: Boolean = false,
    val fired: Boolean = false,
    val snoozeUntil: Long = 0
)

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY done, triggerAt")
    fun all(): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE done = 0")
    suspend fun active(): List<Task>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: Long): Task?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(t: Task): Long

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM categories ORDER BY id")
    fun categories(): Flow<List<Category>>

    @Insert
    suspend fun addCategory(c: Category): Long

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteCategory(id: Long)

    @Query("UPDATE tasks SET categoryId = 0 WHERE categoryId = :id")
    suspend fun clearCategory(id: Long)
}

@Database(entities = [Task::class, Category::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): TaskDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(c: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "reminders.db")
                .fallbackToDestructiveMigration()
                .build().also { inst = it }
        }
    }
}
