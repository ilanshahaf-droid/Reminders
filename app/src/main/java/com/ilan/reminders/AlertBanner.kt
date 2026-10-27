package com.ilan.reminders

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A banner drawn over other apps that stays on screen until a button is pressed
 * (a normal heads-up notification disappears after a few seconds). Needs the
 * "display over other apps" permission; without it the notification is used as before.
 */
object AlertBanner {
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var shownId = -1L

    fun allowed(c: Context) = Settings.canDrawOverlays(c)

    fun show(c: Context, id: Long, title: String, text: String, minutes: Int) {
        if (!allowed(c)) return
        val app = c.applicationContext
        main.post {
            try {
                remove(app)
                val dp = app.resources.displayMetrics.density
                fun px(v: Int) = (v * dp).toInt()
                fun send(a: String, m: Int = 0) {
                    app.sendBroadcast(Intent(app, ActionReceiver::class.java).setAction(a).putExtra("id", id).putExtra("minutes", m))
                }
                fun button(label: String, primary: Boolean, onClick: () -> Unit) = TextView(app).apply {
                    this.text = label
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setPadding(px(14), px(10), px(14), px(10))
                    background = GradientDrawable().apply {
                        cornerRadius = px(20).toFloat()
                        setColor(if (primary) 0xFF6D4AFF.toInt() else 0x33FFFFFF)
                    }
                    setOnClickListener { onClick() }
                }
                val card = LinearLayout(app).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                    setPadding(px(18), px(14), px(18), px(14))
                    background = GradientDrawable().apply {
                        cornerRadius = px(26).toFloat(); setColor(0xF2222233.toInt())
                    }
                    elevation = px(12).toFloat()
                }
                card.addView(TextView(app).apply {
                    this.text = title; textSize = 20f; setTextColor(Color.WHITE)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                card.addView(TextView(app).apply { this.text = text; textSize = 14f; setTextColor(0xFFC8C8D8.toInt()) })
                val row = LinearLayout(app).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                    setPadding(0, px(12), 0, 0)
                }
                fun lp() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(px(4), 0, px(4), 0) }
                row.addView(button("✓ הושלמה", true) { send("DONE") }, lp())
                row.addView(button("נודניק $minutes", false) { send("SNOOZE", minutes) }, lp())
                row.addView(button("בחר זמן", false) {
                    app.startActivity(Intent(app, SnoozePickActivity::class.java).putExtra("id", id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                }, lp())
                card.addView(row)
                val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.TOP; x = 0; y = px(40) }
                val frame = android.widget.FrameLayout(app).apply {
                    setPadding(px(10), 0, px(10), 0); addView(card)
                }
                app.getSystemService(WindowManager::class.java).addView(frame, lp)
                view = frame; shownId = id
            } catch (_: Exception) { view = null }
        }
    }

    fun hide(c: Context, id: Long) {
        val app = c.applicationContext
        main.post { if (shownId == id) remove(app) }
    }

    private fun remove(app: Context) {
        view?.let { v -> try { app.getSystemService(WindowManager::class.java).removeView(v) } catch (_: Exception) {} }
        view = null; shownId = -1L
    }
}
