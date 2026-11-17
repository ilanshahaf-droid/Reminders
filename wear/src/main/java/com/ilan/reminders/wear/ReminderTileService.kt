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

/** Watch-face tile: the next few reminders; tapping opens the watch app. */
class ReminderTileService : TileService() {
    override fun onTileRequest(req: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        TaskStore.load(this)
        val tasks = TaskStore.tasks.value.sortedWith(compareBy({ it.at == 0L }, { it.at })).take(2)
        val open = ModifiersBuilders.Clickable.Builder().setId("open").setOnClick(
            ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder().setPackageName(packageName)
                    .setClassName("com.ilan.reminders.wear.MainActivity").build()
            ).build()
        ).build()

        fun text(t: String, size: Float, color: Int, bold: Boolean = false) = LayoutElementBuilders.Text.Builder()
            .setText(t).setMaxLines(1)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder().setSize(DimensionBuilders.sp(size)).setColor(argb(color))
                    .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL).build()
            ).build()

        val col = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text("⏰ תזכורות", 15f, 0xFFB9B2FF.toInt(), true))
        if (tasks.isEmpty()) {
            col.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(8f)).build())
            col.addContent(text("אין תזכורות קרובות", 14f, 0xFFFFFFFF.toInt()))
        }
        fun button(label: String, bg: Int, act: String, id: Long): LayoutElementBuilders.LayoutElement {
            val click = ModifiersBuilders.Clickable.Builder().setId("$act$id").setOnClick(
                ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder().setPackageName(packageName)
                        .setClassName("com.ilan.reminders.wear.QuickActionActivity")
                        .addKeyToExtraMapping("id", ActionBuilders.AndroidStringExtra.Builder().setValue(id.toString()).build())
                        .addKeyToExtraMapping("act", ActionBuilders.AndroidStringExtra.Builder().setValue(act).build())
                        .build()
                ).build()
            ).build()
            return LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.wrap()).setHeight(DimensionBuilders.wrap())
                .setModifiers(
                    ModifiersBuilders.Modifiers.Builder().setClickable(click)
                        .setBackground(ModifiersBuilders.Background.Builder().setColor(argb(bg))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(DimensionBuilders.dp(14f)).build()).build())
                        .setPadding(ModifiersBuilders.Padding.Builder().setStart(DimensionBuilders.dp(10f)).setEnd(DimensionBuilders.dp(10f))
                            .setTop(DimensionBuilders.dp(5f)).setBottom(DimensionBuilders.dp(5f)).build()).build()
                )
                .addContent(text(label, 13f, 0xFFFFFFFF.toInt(), true)).build()
        }
        tasks.forEachIndexed { i, t ->
            col.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(6f)).build())
            col.addContent(text(t.title, 14f, if (t.overdue) 0xFFFF8A80.toInt() else 0xFFFFFFFF.toInt(), true))
            col.addContent(text(t.whenText, 12f, 0xFFB0B0C0.toInt()))
            if (i == 0) {
                col.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(4f)).build())
                col.addContent(
                    LayoutElementBuilders.Row.Builder()
                        .addContent(button("✓ הושלמה", 0xFF6D4AFF.toInt(), "DONE", t.id))
                        .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(DimensionBuilders.dp(8f)).build())
                        .addContent(button("⏱ נודניק", 0xFF3A3358.toInt(), "SNOOZE", t.id)).build()
                )
            }
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
                .setFreshnessIntervalMillis(15 * 60 * 1000L)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
                .build()
        )
    }

    override fun onTileResourcesRequest(req: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion("1").build())
}
