package com.ilan.reminders.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable

object WatchBridge {
    fun send(c: Context, path: String, payload: String) {
        runCatching {
            val app = c.applicationContext
            Wearable.getNodeClient(app).connectedNodes.addOnSuccessListener { nodes ->
                for (n in nodes) {
                    Wearable.getMessageClient(app).sendMessage(n.id, path, payload.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }
}
