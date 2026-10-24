package com.ilan.reminders

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val notes: String = "",
    val triggerAt: Long,
    val repeat: String = "NONE", // NONE, DAILY, WEEKLY, MONTHLY
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
}

@Database(entities = [Task::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): TaskDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(c: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "reminders.db")
                .build().also { inst = it }
        }
    }
}
