package com.ilan.reminders.wear

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.time.Instant
import java.time.ZoneId

/** Watch-face tile: the next diary event, its time and how long is left; tapping opens the diary screen. */
class NextEventTileService : TileService() {
    override fun onTileRequest(req: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        WatchData.load(this)
        val now = System.currentTimeMillis()
        val next = WatchData.events.value.filter { !it.allDay && it.end > now }.minByOrNull { it.begin }
        val open = ModifiersBuilders.Clickable.Builder().setId("open").setOnClick(
            ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder().setPackageName(packageName)
                    .setClassName("com.ilan.reminders.wear.MainActivity").build()
            ).build()
        ).build()

        fun text(t: String, size: Float, color: Int, bold: Boolean = false, lines: Int = 1) = LayoutElementBuilders.Text.Builder()
            .setText(t).setMaxLines(lines)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder().setSize(DimensionBuilders.sp(size)).setColor(argb(color))
                    .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL).build()
            ).build()
        fun gap(dp: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(dp)).build()

        val col = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text("📅 האירוע הבא", 15f, 0xFFB9B2FF.toInt(), true))
        if (next == null) {
            col.addContent(gap(8f)); col.addContent(text("אין אירועים קרובים", 14f, 0xFFFFFFFF.toInt()))
        } else {
            val z = ZoneId.systemDefault()
            val s = Instant.ofEpochMilli(next.begin).atZone(z)
            val e = Instant.ofEpochMilli(next.end).atZone(z)
            val mins = ((next.begin - now) / 60000).coerceAtLeast(0)
            val left = when {
                next.begin <= now -> "מתקיים עכשיו"
                mins >= 1440 -> "בעוד ${mins / 1440} ימים"
                mins >= 60 -> "בעוד ${mins / 60}:%02d ש׳".format(mins % 60)
                else -> "בעוד $mins דק׳"
            }
            col.addContent(gap(6f))
            col.addContent(text(next.title, 17f, 0xFFFFFFFF.toInt(), true, 2))
            col.addContent(gap(4f))
            col.addContent(text("%02d:%02d–%02d:%02d".format(s.hour, s.minute, e.hour, e.minute), 15f, 0xFFB0B0C0.toInt()))
            col.addContent(text(left, 15f, 0xFFFFC857.toInt(), true))
            if (next.loc.isNotBlank()) col.addContent(text(next.loc, 12f, 0xFFB0B0C0.toInt()))
        }
        val root = LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand()).setHeight(DimensionBuilders.expand())
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open).build())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(col.build()).build()

        return Futures.immediateFuture(
            TileBuilders.Tile.Builder()
                .setResourcesVersion("1")
                .setFreshnessIntervalMillis(5 * 60 * 1000L)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
                .build()
        )
    }

    override fun onTileResourcesRequest(req: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion("1").build())
}
