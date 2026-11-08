package com.ilan.reminders

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Receives com.ilan.reminders://auth?code=... after Google sign-in and finishes the login. */
class AuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext
        val data = intent?.data
        val code = data?.getQueryParameter("code")
        val err = data?.getQueryParameter("error_description") ?: data?.getQueryParameter("error")
        CoroutineScope(Dispatchers.Main).launch {
            val msg = when {
                code != null -> {
                    val r = withContext(Dispatchers.IO) { Sync.finishGoogle(app, code) }
                    if (r == "ok") withContext(Dispatchers.IO) { Sync.syncNow(app) } else r
                }
                else -> "❌ " + (err ?: "ההתחברות בוטלה")
            }
            Toast.makeText(app, if (msg.startsWith("❌")) msg else "התחברת עם Google · $msg", Toast.LENGTH_LONG).show()
        }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
