package com.example.medreminder

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate

// হোম স্ক্রিনের widget: ঔষধের নোটিফিকেশনের সময় না হলে খালি ও স্বচ্ছ (লুকানো) থাকে,
// সময় হলে শুধু সেই বেলার ঔষধের তালিকা নিয়ে ভেসে ওঠে (কোনো বাটন নেই), চাপলে কিছুই হয় না
class MedWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) render(context, manager, id)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        newOptions: android.os.Bundle
    ) {
        render(context, manager, id)
    }

    companion object {
        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        fun updateAll(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, MedWidget::class.java))
                for (id in ids) render(ctx, mgr, id)
            } catch (e: Exception) {
                // widget হালনাগাদ ব্যর্থ হলেও রিমাইন্ডার চলবে
            }
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_med)
            val key = Reminders.widgetDueSlot(ctx)
            val meds = if (key == null) emptyList() else Reminders.medsFor(Store.data, key, LocalDate.now())

            if (key == null || meds.isEmpty()) {
                rv.setViewVisibility(R.id.w_card, View.GONE)
            } else {
                rv.setViewVisibility(R.id.w_card, View.VISIBLE)
                rv.setTextViewText(R.id.w_title, "🔔 " + Slots.label(key))
                // ঔষধের নামগুলো পাশাপাশি (লাইন ভরে গেলে পরের লাইনে), প্রতিটা "নাম — ডোজ" একসাথে থাকে
                val line = meds.joinToString("   •   ") { it.shortName() + "\u00A0—\u00A0" + it.doseLabel() }
                rv.setTextViewText(R.id.w_lines, line)

            }
            mgr.updateAppWidget(id, rv)
        }
    }
}
