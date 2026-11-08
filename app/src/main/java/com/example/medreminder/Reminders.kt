package com.example.medreminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

object Reminders {
    // নতুন চ্যানেল: লক স্ক্রিনে পুরো তালিকা দেখানোর সেটিং নিয়ে। পুরোনোটা মুছে ফেলা হয়
    const val CHANNEL = "meds_alarm_v3"
    const val OLD_CHANNEL = "meds_alarm"
    const val OLD_CHANNEL_V2 = "meds_alarm_v2"
    // "🔇" চাপলে বাকি রিমাইন্ডার এই চ্যানেলে আসে: শব্দ ও কম্পন নেই
    const val CHANNEL_SILENT = "meds_silent"
    const val NAG_MINUTES = 10
    // না খাওয়া পর্যন্ত প্রতি ১০ মিনিটে (সর্বোচ্চ ১২ ঘণ্টা)
    const val NAG_MAX = 72

    // খাবারের আগের ঔষধের নোটিফিকেশন খাবারের সময়ের এত মিনিট আগে আসে
    const val BEFORE_LEAD = 30

    // খাবারের পরের ঔষধের নোটিফিকেশন খাবারের সময়ের এত মিনিট পরে আসে
    const val AFTER_LAG = 30

    // "২০ মিনিট পরে" বাটন চাপলে এত মিনিট পরে নোটিফিকেশন আবার আসে
    const val SNOOZE_MINUTES = 20
    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    // ---------- helpers ----------

    fun allSlotKeys(d: AppData): List<String> = d.medicines.flatMap { it.timings }.distinct()

    // সাইলেন্ট করা ঔষধ এবং বন্ধ করা রোগের ঔষধ কোথাও আসবে না (নোটিফিকেশন, আজকের তালিকা, widget)
    fun medsFor(d: AppData, key: String, date: LocalDate): List<Medicine> =
        d.medicines.filter { !it.muted && d.isDiseaseOn(it.diseaseId) && it.timings.contains(key) && it.inCourseOn(date) }

    fun medsDue(d: AppData, key: String, date: LocalDate): List<Medicine> = medsFor(d, key, date)

    fun hasBeforeMeds(d: AppData, key: String, date: LocalDate): Boolean =
        medsFor(d, Slots.mealOf(key) + "_BEFORE", date).isNotEmpty()

    // এই স্লটের নোটিফিকেশন ও "খেয়েছি" বাটন যত মিনিটে (মধ্যরাত থেকে) চালু হয়
    // আগে: খাবারের সময় - BEFORE_LEAD মিনিট, পরে: খাবারের সময় + AFTER_LAG মিনিট
    fun activeFromMinutes(d: AppData, key: String): Int {
        val meal = toMin(Slots.mealTime(d, Slots.mealOf(key)))
        return if (Slots.isBefore(key)) (meal - BEFORE_LEAD).coerceAtLeast(0) else (meal + AFTER_LAG).coerceAtMost(24 * 60 - 1)
    }

    fun alarmMinutes(d: AppData, key: String): Int = activeFromMinutes(d, key)

    fun displayMinutes(d: AppData, key: String): Int = activeFromMinutes(d, key)

    fun mealHHmm(d: AppData, key: String): String = Slots.mealTime(d, Slots.mealOf(key))

    // "খেয়েছি" বাটন এখন চাপা যাবে কিনা (সময় হয়েছে কিনা)
    fun isActive(d: AppData, key: String, date: LocalDate, now: LocalDateTime = LocalDateTime.now()): Boolean {
        if (date.isBefore(now.toLocalDate())) return true
        if (date.isAfter(now.toLocalDate())) return false
        return now.hour * 60 + now.minute >= activeFromMinutes(d, key)
    }

    // দিনের ক্রম: সকাল আগে → সকাল পরে → দুপুর আগে → দুপুর পরে → রাত আগে → রাত পরে
    val slotOrder: List<String> = Slots.meals.flatMap { listOf(it + "_BEFORE", it + "_AFTER") }

    // এই দিনে এই স্লটের ঠিক আগের যে স্লটে ঔষধ আছে (ক্রমে চলার জন্য)
    fun prevSlot(d: AppData, key: String, date: LocalDate): String? {
        val idx = slotOrder.indexOf(key)
        for (i in idx - 1 downTo 0) {
            if (medsFor(d, slotOrder[i], date).isNotEmpty()) return slotOrder[i]
        }
        return null
    }

    // পরের স্লট (যেটায় ঔষধ আছে)
    fun nextSlot(d: AppData, key: String, date: LocalDate): String? {
        val idx = slotOrder.indexOf(key)
        for (i in idx + 1 until slotOrder.size) {
            if (medsFor(d, slotOrder[i], date).isNotEmpty()) return slotOrder[i]
        }
        return null
    }

    // আগের স্লটের ঔষধ খাওয়া না হলে এটা আটকে থাকে। আগেরটা "খেয়েছি" দিলেই এটা চালু হয়
    fun afterBlocked(d: AppData, key: String, dateStr: String): Boolean {
        val date = LocalDate.parse(dateStr)
        val prev = prevSlot(d, key, date) ?: return false
        return !d.taken.contains("$dateStr|$prev")
    }

    // বাটন চাপা যাবে কিনা: শুধু ক্রম মানলেই হবে, সময়ের শর্ত নেই
    fun canTake(d: AppData, key: String, dateStr: String): Boolean = !afterBlocked(d, key, dateStr)

    fun slotVerbs(meds: List<Medicine>): String =
        meds.map { Forms.of(it.form).verb }.distinct().joinToString(" / ")

    private fun eatOnly(meds: List<Medicine>): Boolean =
        meds.all { Forms.of(it.form).id == "TABLET" || Forms.of(it.form).id == "SYRUP" }

    fun takenButtonLabel(meds: List<Medicine>): String =
        if (eatOnly(meds)) "✅ খেয়েছি" else "✅ নিয়েছি"

    fun snoozeLabel(min: Int = SNOOZE_MINUTES): String = "⏰ " + bn(min) + " মিনিট পরে"

    // একটাই বাটন: খাওয়া হলে ওপরে "খাওয়া হয়েছে", নিচে "খাইনি" (বাটনে চাপলে আবার না-খাওয়া হয়ে যায়)
    fun doneTitle(meds: List<Medicine>): String =
        if (eatOnly(meds)) "✅ খাওয়া হয়েছে" else "✅ নেওয়া হয়েছে"

    fun undoTitle(meds: List<Medicine>): String =
        if (eatOnly(meds)) "↩️ খাইনি" else "↩️ নিইনি"

    // আজ ট্যাবের একটাই বাটন: না খেলে লাল "❌ খাইনি", খেলে সবুজ "✔ খেয়েছি"
    fun yesLabel(meds: List<Medicine>): String = if (eatOnly(meds)) "✔ খেয়েছি" else "✔ নিয়েছি"
    fun noLabel(meds: List<Medicine>): String = if (eatOnly(meds)) "❌ খাইনি" else "❌ নিইনি"

    fun numberedLines(meds: List<Medicine>): List<String> =
        meds.mapIndexed { i, m ->
            bn(i + 1) + ". " + m.shortName() + " — " + m.doseLabel()
        }

    // ---------- "🔇" সাইলেন্ট: খাওয়া পর্যন্ত বাকি রিমাইন্ডার শব্দ ছাড়া আসে ----------

    private const val SIL = "med_silent"

    private fun silPrefs(ctx: Context) = ctx.getSharedPreferences(SIL, Context.MODE_PRIVATE)

    fun isSilent(ctx: Context, key: String, dateStr: String): Boolean =
        silPrefs(ctx).getString(key, null) == dateStr

    private fun clearSilent(ctx: Context, key: String) {
        silPrefs(ctx).edit().remove(key).apply()
    }

    fun silentPendingIntent(ctx: Context, key: String, dateStr: String): PendingIntent {
        val i = Intent(ctx, SilentReceiver::class.java)
        i.putExtra("key", key)
        i.putExtra("date", dateStr)
        return PendingIntent.getBroadcast(ctx, ("Q" + key).hashCode(), i, FLAGS)
    }

    fun silence(ctx: Context, key: String, dateStr: String) {
        Store.ensureLoaded(ctx)
        val d = Store.data
        if (d.taken.contains("$dateStr|$key")) return
        silPrefs(ctx).edit().putString(key, dateStr).apply()
        val date = LocalDate.parse(dateStr)
        val meds = medsDue(d, key, date)
        if (meds.isEmpty()) return
        val prev = prevSlot(d, key, date)
        val blocked = prev != null && !d.taken.contains("$dateStr|$prev")
        // চলতি আওয়াজ থামাতে নোটিফিকেশনটা সাইলেন্ট চ্যানেলে আবার বসানো হয়
        cancelNotif(ctx, key)
        showNotification(ctx, key, dateStr, meds, 0, blocked)
    }

    // ---------- হোম স্ক্রিন widget ও "২০ মিনিট পরে" ----------

    private const val SNZ = "widget_snooze"

    private fun snzPrefs(ctx: Context) = ctx.getSharedPreferences(SNZ, Context.MODE_PRIVATE)

    // এই স্লট কতক্ষণ পর্যন্ত পিছিয়ে রাখা (millis)। পিছানো না থাকলে 0
    fun snoozedUntil(ctx: Context, key: String, dateStr: String): Long {
        val v = snzPrefs(ctx).getString(key, null) ?: return 0L
        val p = v.split("|")
        if (p.size != 2 || p[0] != dateStr) return 0L
        return p[1].toLongOrNull() ?: 0L
    }

    // widget-এ এখন যে স্লট দেখাতে হবে। নোটিফিকেশনের নিয়মেই: ক্রমে প্রথম না-খাওয়া স্লট,
    // তার সময় হয়ে গেছে, আর ১৫ মিনিট পিছানো নেই। নইলে null (widget লুকানো)
    fun widgetDueSlot(ctx: Context, now: LocalDateTime = LocalDateTime.now()): String? {
        Store.ensureLoaded(ctx)
        val d = Store.data
        val date = now.toLocalDate()
        val ds = date.toString()
        val key = slotOrder.firstOrNull {
            medsFor(d, it, date).isNotEmpty() && !d.taken.contains("$ds|$it")
        } ?: return null
        if (!isActive(d, key, date, now)) return null
        if (snoozedUntil(ctx, key, ds) > System.currentTimeMillis()) return null
        return key
    }

    private fun snoozePi(ctx: Context, key: String, date: String): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
        i.putExtra("key", key)
        i.putExtra("nag", 0)
        i.putExtra("date", date)
        return PendingIntent.getBroadcast(ctx, ("S" + key).hashCode(), i, FLAGS)
    }

    fun snoozePendingIntent(ctx: Context, key: String, dateStr: String, code: String, min: Int): PendingIntent {
        val i = Intent(ctx, SnoozeReceiver::class.java)
        i.putExtra("key", key)
        i.putExtra("date", dateStr)
        i.putExtra("min", min)
        return PendingIntent.getBroadcast(ctx, (code + min + key).hashCode(), i, FLAGS)
    }

    private fun cancelSnooze(ctx: Context, key: String) {
        snzPrefs(ctx).edit().remove(key).apply()
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(snoozePi(ctx, key, ""))
    }

    // "২০ মিনিট পরে": নোটিফিকেশন লুকিয়ে যায়, ১৫ মিনিট পরে নোটিফিকেশন আবার আসে
    fun snooze(ctx: Context, key: String, dateStr: String, min: Int = SNOOZE_MINUTES) {
        Store.ensureLoaded(ctx)
        if (Store.data.taken.contains("$dateStr|$key")) return
        val at = System.currentTimeMillis() + min * 60_000L
        snzPrefs(ctx).edit().putString(key, "$dateStr|$at").apply()
        cancelNotif(ctx, key)
        cancelNag(ctx, key)
        setAlarm(ctx, at, snoozePi(ctx, key, dateStr))
        MedWidget.updateAll(ctx)
    }

    // ফোন চালু হলে / অ্যাপ আপডেট হলে পিছানো নোটিফিকেশন আবার ঠিক করা
    fun resumeSnoozes(ctx: Context) {
        Store.ensureLoaded(ctx)
        val p = snzPrefs(ctx)
        val now = System.currentTimeMillis()
        val today = LocalDate.now().toString()
        for ((key, v) in p.all) {
            val parts = (v as? String)?.split("|") ?: continue
            val until = parts.getOrNull(1)?.toLongOrNull()
            if (parts.size != 2 || until == null || parts[0] != today) {
                p.edit().remove(key).apply()
                continue
            }
            if (until > now) setAlarm(ctx, until, snoozePi(ctx, key, today)) else fire(ctx, key, today, 0)
        }
        MedWidget.updateAll(ctx)
    }

    private fun notifId(key: String): Int = (key.hashCode() and 0x7fffffff) % 100000 + 10

    private fun alarmPi(ctx: Context, key: String, nag: Int, date: String): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
        i.putExtra("key", key)
        i.putExtra("nag", nag)
        i.putExtra("date", date)
        val code = ((if (nag == 0) "M" else "N") + key).hashCode()
        return PendingIntent.getBroadcast(ctx, code, i, FLAGS)
    }

    private fun setAlarm(ctx: Context, at: Long, pi: PendingIntent) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun notifyNow(ctx: Context, id: Int, n: android.app.Notification) {
        if (!canNotify(ctx)) return
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (e: SecurityException) {
            // permission missing, nothing to do
        }
    }

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.deleteNotificationChannel(OLD_CHANNEL)
        nm.deleteNotificationChannel(OLD_CHANNEL_V2)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "ঔষধের রিমাইন্ডার", NotificationManager.IMPORTANCE_HIGH)
            ch.enableVibration(true)
            ch.vibrationPattern = longArrayOf(0, 500, 300, 500)
            ch.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            // নোটিফিকেশনের নিজস্ব সাউন্ড: res/raw/notifi.mp3
            val soundUri = android.net.Uri.parse("android.resource://" + ctx.packageName + "/" + R.raw.notifi)
            ch.setSound(soundUri, attrs)
            nm.createNotificationChannel(ch)
        }
        if (nm.getNotificationChannel(CHANNEL_SILENT) == null) {
            val sc = NotificationChannel(CHANNEL_SILENT, "ঔষধের রিমাইন্ডার (সাইলেন্ট)", NotificationManager.IMPORTANCE_LOW)
            sc.enableVibration(false)
            sc.setSound(null, null)
            sc.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(sc)
        }
    }

    // ---------- scheduling ----------

    fun scheduleAll(ctx: Context) {
        Store.ensureLoaded(ctx)
        val d = Store.data
        val now = LocalDateTime.now()
        for (key in allSlotKeys(d)) {
            val m = alarmMinutes(d, key)
            var at = LocalDate.now().atStartOfDay().plusMinutes(m.toLong())
            while (!at.isAfter(now)) at = at.plusDays(1)
            val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            setAlarm(ctx, millis, alarmPi(ctx, key, 0, ""))
        }
        MedWidget.updateAll(ctx)
    }

    // রোগের সুইচ বদলালে: যে বেলায় আর কোনো ঔষধ নেই তার নোটিফিকেশন ও পুনরাবৃত্তি বন্ধ, widget নতুন করে আঁকা
    fun refreshAfterChange(ctx: Context) {
        val d = Store.data
        val today = LocalDate.now()
        for (key in allSlotKeys(d)) {
            if (medsDue(d, key, today).isEmpty()) {
                cancelNotif(ctx, key)
                cancelNag(ctx, key)
            }
        }
        scheduleAll(ctx)
    }

    fun cancelNag(ctx: Context, key: String) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(alarmPi(ctx, key, 1, ""))
    }

    private fun cancelNotif(ctx: Context, key: String) {
        NotificationManagerCompat.from(ctx).cancel(notifId(key))
    }

    // ---------- firing ----------

    fun fire(ctx: Context, key: String, dateStr: String, nag: Int) {
        // নোটিফিকেশন এলে "১৫ মিনিট পরে"-র পিছানো শেষ
        cancelSnooze(ctx, key)
        val d = Store.data
        val date = LocalDate.parse(dateStr)
        if (d.taken.contains("$dateStr|$key")) return
        val meds = medsDue(d, key, date)
        if (meds.isEmpty()) return
        val prev = prevSlot(d, key, date)
        val blocked = prev != null && !d.taken.contains("$dateStr|$prev")
        // একই বেলার আগের ঔষধ না খেলে পরের ঔষধের নোটিফিকেশন আসবে না
        if (blocked && Slots.mealOf(prev!!) == Slots.mealOf(key)) return

        // আগের বেলার ঔষধ বাকি থাকলে শুধু জানানো হয়, বাটন ছাড়া
        showNotification(ctx, key, dateStr, meds, nag, blocked)
        if (!blocked && nag < NAG_MAX) {
            val at = System.currentTimeMillis() + NAG_MINUTES * 60_000L
            setAlarm(ctx, at, alarmPi(ctx, key, nag + 1, dateStr))
        }
    }

    private fun showNotification(
        ctx: Context,
        key: String,
        dateStr: String,
        meds: List<Medicine>,
        nag: Int,
        locked: Boolean
    ) {
        ensureChannel(ctx)
        val title = Slots.label(key)
        val lines = numberedLines(meds)
        val body = if (locked) {
            lines.joinToString("\n") + "\n\n⏳ আগের ঔষধ আগে নিন, তারপর অ্যাপে গিয়ে \"খেয়েছি\" চাপুন"
        } else {
            lines.joinToString("\n")
        }
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), FLAGS)
        val silent = isSilent(ctx, key, dateStr)
        val b = NotificationCompat.Builder(ctx, if (silent) CHANNEL_SILENT else CHANNEL)
            .setSmallIcon(R.drawable.ic_pill)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentTitle(if (nag > 0) "$title (আবার মনে করিয়ে দিচ্ছি)" else title)
            .setContentText(lines.joinToString("  "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false)
            .setContentIntent(open)
        if (!locked) {
            val takeIntent = Intent(ctx, TakenReceiver::class.java)
            takeIntent.putExtra("key", key)
            takeIntent.putExtra("date", dateStr)
            val takePi = PendingIntent.getBroadcast(ctx, ("T" + key).hashCode(), takeIntent, FLAGS)
            b.addAction(0, takenButtonLabel(meds), takePi)
            // সবচেয়ে ডানে সাইলেন্ট
            if (!silent) b.addAction(0, "🔇 Silent", silentPendingIntent(ctx, key, dateStr))
            // স্লাইড করে সরানো যাবে না, শুধু "খেয়েছি" চাপলে যাবে। কখনো আটকে না থাকতে ১২ ঘণ্টা পর নিজে সরে যায়
            b.setOngoing(true)
            b.setTimeoutAfter(12L * 60 * 60 * 1000)
        }
        notifyNow(ctx, notifId(key), b.build())
    }

    fun testNotification(ctx: Context) {
        ensureChannel(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_pill)
            .setContentTitle("টেস্ট নোটিফিকেশন")
            .setContentText("এভাবেই ঔষধের রিমাইন্ডার আসবে")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()
        notifyNow(ctx, 99, n)
    }

    // ---------- taken ----------

    // সফল হলে true। সময় না হলে বা আগের ঔষধ বাকি থাকলে false
    fun markTaken(ctx: Context, key: String, dateStr: String): Boolean {
        Store.ensureLoaded(ctx)
        val before = Store.data
        val evKey = "$dateStr|$key"
        if (before.taken.contains(evKey)) {
            cancelNotif(ctx, key)
            cancelSnooze(ctx, key)
            clearSilent(ctx, key)
            MedWidget.updateAll(ctx)
            return true
        }
        if (!canTake(before, key, dateStr)) return false

        val date = LocalDate.parse(dateStr)
        val meds = medsDue(before, key, date)
        val now = System.currentTimeMillis()
        val logs = meds.map { LogEntry(now, it.name, key, it.dose, it.id) }

        Store.update(ctx) { s ->
            s.copy(
                log = (s.log + logs).takeLast(3000),
                taken = (s.taken + evKey).takeLast(200)
            )
        }

        cancelNotif(ctx, key)
        cancelNag(ctx, key)
        cancelSnooze(ctx, key)
        clearSilent(ctx, key)

        // ক্রমে পরের স্লট চালু হলো: তার নোটিফিকেশনের সময় হয়ে গিয়ে থাকলে এখনই নোটিফিকেশন,
        // নইলে নির্ধারিত সময়ে আসবে
        val after = Store.data
        val next = nextSlot(after, key, date)
        if (next != null) {
            if (isActive(after, next, date)) fire(ctx, next, dateStr, 0)
        }
        scheduleAll(ctx)
        return true
    }

    private fun removeTaken(ctx: Context, key: String, dateStr: String) {
        val zone = ZoneId.systemDefault()
        Store.update(ctx) { s ->
            s.copy(
                taken = s.taken - "$dateStr|$key",
                log = s.log.filterNot { e ->
                    e.slotKey == key &&
                        Instant.ofEpochMilli(e.time).atZone(zone).toLocalDate().toString() == dateStr
                }
            )
        }
        cancelNotif(ctx, key)
        cancelNag(ctx, key)
    }

    // "খেয়েছি" আবার চাপলে বাতিল। খাবারের আগেরটা বাতিল করলে পরেরটাও অটো বাতিল হয়ে যায়
    fun unmarkTaken(ctx: Context, key: String, dateStr: String) {
        Store.ensureLoaded(ctx)
        removeTaken(ctx, key, dateStr)
        // এর পরের সব স্লট (দুপুর, রাত ইত্যাদি) অটো "খাইনি" হয়ে যাবে, ক্রম ঠিক রাখতে
        val idx = slotOrder.indexOf(key)
        for (i in idx + 1 until slotOrder.size) {
            if (Store.data.taken.contains(dateStr + "|" + slotOrder[i])) removeTaken(ctx, slotOrder[i], dateStr)
        }
        // খাওয়া হয়নি ধরা হলো, তাই নোটিফিকেশন আবার চালু
        val d = Store.data
        if (isActive(d, key, LocalDate.parse(dateStr))) fire(ctx, key, dateStr, 0)
        scheduleAll(ctx)
    }
}
