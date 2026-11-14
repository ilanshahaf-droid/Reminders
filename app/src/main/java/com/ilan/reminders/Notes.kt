package com.ilan.reminders

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A free-form note. [html] is what the rich editor shows, [plain] is the same text without formatting (previews, search, export). */
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey val id: Long,
    val title: String,
    val html: String,
    val plain: String,
    val color: String,        // "" = default, otherwise a key of NOTE_COLORS
    val pinned: Boolean,
    val categoryId: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long       // > 0 = in the recycle bin since this time (kept 30 days)
)

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY pinned DESC, updatedAt DESC")
    fun all(): Flow<List<Note>>

    @Query("SELECT * FROM notes")
    suspend fun allNow(): List<Note>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: Long): Note?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(n: Note)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM notes WHERE deletedAt > 0 AND deletedAt < :before")
    suspend fun expired(before: Long): List<Note>
}

/** key -> (light background, dark background). The same palette is used by the web page. */
val NOTE_COLORS: List<Triple<String, Color, Color>> = listOf(
    Triple("red", Color(0xFFF9D0CC), Color(0xFF5C2B29)),
    Triple("orange", Color(0xFFFCDDB5), Color(0xFF614A19)),
    Triple("yellow", Color(0xFFFFF2B3), Color(0xFF635D19)),
    Triple("green", Color(0xFFD4EFC2), Color(0xFF345128)),
    Triple("teal", Color(0xFFBFEBE4), Color(0xFF16504B)),
    Triple("blue", Color(0xFFCBE3F8), Color(0xFF2D4F6B)),
    Triple("purple", Color(0xFFE1D4F5), Color(0xFF4B3A6F)),
    Triple("pink", Color(0xFFF8D1E6), Color(0xFF5F3150)),
    Triple("gray", Color(0xFFE3E5EA), Color(0xFF3C3F44))
)

fun noteColor(key: String, dark: Boolean): Color? =
    NOTE_COLORS.firstOrNull { it.first == key }?.let { if (dark) it.third else it.second }

fun newNote(c: Context): Note {
    val now = System.currentTimeMillis()
    return Note(
        id = now, title = "", html = "", plain = "", color = "", pinned = false,
        categoryId = 0, createdAt = now, updatedAt = now, deletedAt = 0
    )
}

fun Note.isBlank() = title.isBlank() && plain.isBlank()

/** Note as plain text for sharing / saving a .txt file. */
fun Note.asText(): String = (if (title.isNotBlank()) title.trim() + "\n\n" else "") + plain

object Notes {
    private fun dao(c: Context) = AppDb.get(c).notes()

    suspend fun save(c: Context, n: Note) {
        dao(c).upsert(n.copy(updatedAt = System.currentTimeMillis()))
        Sync.schedulePush(c)
    }

    suspend fun trash(c: Context, n: Note) {
        val now = System.currentTimeMillis()
        dao(c).upsert(n.copy(deletedAt = now, updatedAt = now))
        Sync.schedulePush(c)
    }

    suspend fun restore(c: Context, n: Note) {
        dao(c).upsert(n.copy(deletedAt = 0, updatedAt = System.currentTimeMillis()))
        Sync.schedulePush(c)
    }

    /** Delete for good: gone from the phone, and from the cloud on the next sync. */
    suspend fun purge(c: Context, n: Note) {
        dao(c).delete(n.id)
        Sync.markNotePurged(c, n.id)
        Sync.schedulePush(c)
    }

    suspend fun purgeExpired(c: Context) {
        val limit = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        dao(c).expired(limit).forEach { purge(c, it) }
    }
}
