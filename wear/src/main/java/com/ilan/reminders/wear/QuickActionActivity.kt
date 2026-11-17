package com.ilan.reminders.wear

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import org.json.JSONObject

/** Invisible activity started by the tile's buttons: sends "done" / "snooze" for a reminder to the phone and closes. */
class QuickActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("id")?.toLongOrNull() ?: -1L
        val act = intent.getStringExtra("act") ?: "SNOOZE"
        if (id > 0) {
            val minutes = getSharedPreferences("watch", MODE_PRIVATE).getInt("snoozeMin", 10)
            WatchBridge.send(this, "/reminder/action", JSONObject().put("id", id).put("action", act).put("minutes", if (act == "DONE") 0 else minutes).toString())
            Toast.makeText(this, if (act == "DONE") "התזכורת הושלמה" else "נדחה ל-$minutes דק׳", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
