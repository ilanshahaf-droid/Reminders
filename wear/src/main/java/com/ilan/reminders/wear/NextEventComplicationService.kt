package com.ilan.reminders.wear

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeRange
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import java.time.Instant
import java.time.ZoneId

/** Watch-face complication: time and title of the next diary event (data comes from the phone via /agenda). */
class NextEventComplicationService : SuspendingComplicationDataSourceService() {

    private fun tap(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun build(type: ComplicationType, time: String, title: String, until: Instant? = null): ComplicationData? = when (type) {
        ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(time).build(),
            PlainComplicationText.Builder("$time $title").build()
        ).setTitle(PlainComplicationText.Builder(title.take(7)).build()).setTapAction(tap()).also { b -> if (until != null) b.setValidTimeRange(TimeRange.between(Instant.now(), until)) }.build()
        ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
            PlainComplicationText.Builder(title).build(),
            PlainComplicationText.Builder("$time $title").build()
        ).setTitle(PlainComplicationText.Builder(time).build()).setTapAction(tap()).also { b -> if (until != null) b.setValidTimeRange(TimeRange.between(Instant.now(), until)) }.build()
        else -> null
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type, "10:00", "ישיבת צוות")

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        WatchData.load(this)
        val now = System.currentTimeMillis()
        // only events that have not started yet (an event that already began is "previous", not "next")
        val next = WatchData.events.value.filter { !it.allDay && it.begin > now }.minByOrNull { it.begin }
        if (next == null) return build(request.complicationType, "--:--", "אין אירועים", null)
        val s = Instant.ofEpochMilli(next.begin).atZone(ZoneId.systemDefault())
        return build(request.complicationType, "%02d:%02d".format(s.hour, s.minute), next.title, Instant.ofEpochMilli(next.begin))
    }

    companion object {
        fun refresh(c: android.content.Context) = runCatching {
            ComplicationDataSourceUpdateRequester.create(c, ComponentName(c, NextEventComplicationService::class.java)).requestUpdateAll()
        }
    }
}
