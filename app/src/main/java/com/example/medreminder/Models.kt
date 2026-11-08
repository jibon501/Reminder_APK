package com.example.medreminder

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

data class MedForm(
    val id: String,
    val emoji: String,
    val label: String,
    val unit: String,
    val verb: String
)

object Forms {
    val all = listOf(
        MedForm("TABLET", "💊", "ট্যাবলেট / ক্যাপসুল", "টি", "খেতে হবে"),
        MedForm("SYRUP", "🥄", "সিরাপ / তরল (খাওয়ার)", "মি.লি.", "খেতে হবে"),
        MedForm("SALINE", "💉", "স্যালাইন / ইনজেকশন (পুশ)", "টি", "পুশ করতে হবে"),
        MedForm("OINTMENT", "🩹", "মলম / ক্রিম / জেল (লাগানোর)", "বার", "লাগাতে হবে"),
        MedForm("DROP", "💧", "ড্রপ (চোখ / কান / নাক)", "ফোঁটা", "দিতে হবে"),
        MedForm("SPRAY", "🌬️", "ইনহেলার / স্প্রে", "পাফ", "নিতে হবে"),
        MedForm("OTHER", "📦", "অন্যান্য", "টি", "ব্যবহার করতে হবে")
    )

    fun of(id: String): MedForm = all.firstOrNull { it.id == id } ?: all[0]
}

data class Medicine(
    val id: String,
    val name: String,
    val dose: Int,
    val diseaseId: String,
    val timings: List<String>,
    val startDate: String,
    val courseDays: Int,
    val form: String = "TABLET",
    val intervalDays: Int = 1,
    val muted: Boolean = false
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("dose", dose)
        o.put("diseaseId", diseaseId)
        val arr = JSONArray()
        timings.forEach { arr.put(it) }
        o.put("timings", arr)
        o.put("startDate", startDate)
        o.put("courseDays", courseDays)
        o.put("form", form)
        o.put("intervalDays", intervalDays)
        o.put("muted", muted)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Medicine {
            val t = o.optJSONArray("timings") ?: JSONArray()
            // আগের ভার্শনের "নির্দিষ্ট সময়" (CUSTOM@) বাদ দেওয়া হলো
            val times = List(t.length()) { i -> t.getString(i) }.filter { !it.startsWith("CUSTOM@") }
            return Medicine(
                id = o.getString("id"),
                name = o.getString("name"),
                dose = o.optInt("dose", 1).coerceAtLeast(1),
                diseaseId = o.optString("diseaseId", ""),
                timings = times,
                startDate = o.optString("startDate", ""),
                courseDays = 0, // কোর্সের দিন আর ব্যবহার হয় না, ঔষধ চলমান ধরা হয়
                form = o.optString("form", "TABLET"),
                intervalDays = o.optInt("intervalDays", 1).coerceAtLeast(1),
                muted = o.optBoolean("muted", false)
            )
        }
    }
}

fun Medicine.isDoseDay(date: LocalDate): Boolean {
    val start = runCatching { LocalDate.parse(startDate) }.getOrNull() ?: return true
    val offset = java.time.temporal.ChronoUnit.DAYS.between(start, date)
    return offset >= 0 && offset % intervalDays.coerceAtLeast(1) == 0L
}

// কোর্সের মধ্যে এবং (দিন পর পর হলে) ঔষধ খাওয়ার দিন কিনা
fun Medicine.inCourseOn(date: LocalDate): Boolean {
    val start = runCatching { LocalDate.parse(startDate) }.getOrNull() ?: return true
    if (date.isBefore(start)) return false
    if (courseDays > 0 && !date.isBefore(start.plusDays(courseDays.toLong()))) return false
    return isDoseDay(date)
}

// কোর্সে কত দিন ঔষধ নিতে হবে (দিন পর পর হলে কম)
fun Medicine.doseDays(): Int {
    if (courseDays <= 0) return 0
    val iv = intervalDays.coerceAtLeast(1)
    return (courseDays + iv - 1) / iv
}

// কোর্সের জন্য মোট কতটুকু লাগবে = ঔষধ নেওয়ার দিন × দিনে কতবার × প্রতিবারে কত
fun Medicine.totalNeeded(): Int = doseDays() * timings.size * dose

// দিন সংখ্যা নিজে দিলে কতটুকু লাগবে (দিন পর পর হলে সেটাও ধরে)
fun Medicine.totalNeededFor(days: Int): Int {
    if (days <= 0) return 0
    val iv = intervalDays.coerceAtLeast(1)
    return ((days + iv - 1) / iv) * timings.size * dose
}

fun Medicine.totalFor(days: Int): String = bn(totalNeededFor(days)) + " " + Forms.of(form).unit

fun Medicine.doseLabel(): String = bn(dose) + " " + Forms.of(form).unit

// নোটিফিকেশন, widget ও আজ ট্যাবে পাওয়ার ছাড়া শুধু নাম দেখানোর জন্য ("REMMO 20 mg" -> "REMMO")
// জমানো নাম বদলায় না, শুধু দেখানোর সময় পাওয়ার অংশ (20 mg, 100 mg+200 mcg, 500/65 mg ...) বাদ যায়
private val POWER_REGEX = Regex(
    "\\s*\\d+(?:[.,]\\d+)?(?:\\s*[+/]\\s*\\d+(?:[.,]\\d+)?)*\\s*(?:mcg|mg|gm|g|ml|iu|ug|µg|%)(?![A-Za-z])" +
        "(?:\\s*[+/]\\s*\\d+(?:[.,]\\d+)?\\s*(?:(?:mcg|mg|gm|g|ml|iu|ug|µg|%)(?![A-Za-z]))?)*",
    RegexOption.IGNORE_CASE
)

fun Medicine.shortName(): String {
    val s = name.replace(POWER_REGEX, "").trim().trimEnd('-', '+', '/', ',', ' ')
    return if (s.isBlank()) name else s
}

fun Medicine.totalLabel(): String = bn(totalNeeded()) + " " + Forms.of(form).unit

data class Disease(
    val id: String,
    val name: String,
    val doctor: String,
    val date: String,
    val images: List<String>,
    val enabled: Boolean = true // রোগের সুইচ: বন্ধ থাকলে এই রোগের কোনো ঔষধ আসবে না
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("doctor", doctor)
        o.put("date", date)
        val arr = JSONArray()
        images.forEach { arr.put(it) }
        o.put("images", arr)
        o.put("enabled", enabled)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Disease {
            val im = o.optJSONArray("images") ?: JSONArray()
            return Disease(
                id = o.getString("id"),
                name = o.getString("name"),
                doctor = o.optString("doctor", ""),
                date = o.optString("date", ""),
                images = List(im.length()) { i -> im.getString(i) },
                enabled = o.optBoolean("enabled", true) // পুরোনো ডেটায় সুইচ চালু ধরা হয়
            )
        }
    }
}

// রোগের সুইচ চালু কিনা (রোগ ছাড়া ঔষধ বা অজানা রোগ হলে চালু ধরা হয়)
fun AppData.isDiseaseOn(diseaseId: String): Boolean =
    diseases.firstOrNull { it.id == diseaseId }?.enabled ?: true

data class LogEntry(
    val time: Long,
    val medName: String,
    val slotKey: String,
    val dose: Int,
    val medId: String = "",
    val id: String = UUID.randomUUID().toString()
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("time", time)
        o.put("medName", medName)
        o.put("slotKey", slotKey)
        o.put("dose", dose)
        o.put("medId", medId)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): LogEntry = LogEntry(
            time = o.getLong("time"),
            medName = o.getString("medName"),
            slotKey = o.getString("slotKey"),
            dose = o.optInt("dose", 1),
            medId = o.optString("medId", ""),
            // আগের ভার্শনের এন্ট্রিতে id নেই, তাই স্থির একটা id বানানো হয়
            id = o.optString("id", "").ifEmpty { o.getLong("time").toString() + "|" + o.getString("medName") + "|" + o.getString("slotKey") }
        )
    }
}

data class AppData(
    val morning: String = "08:00",
    val noon: String = "12:00",
    val night: String = "20:00",
    val medicines: List<Medicine> = emptyList(),
    val diseases: List<Disease> = emptyList(),
    val log: List<LogEntry> = emptyList(),
    val taken: List<String> = emptyList(),
    val lastBackup: String = "",
    val theme: String = "system",
    val currentDiseaseId: String = "",
    val tabAnim: Int = 0,
    val tabVolume: Float = 0.25f
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("morning", morning)
        o.put("noon", noon)
        o.put("night", night)
        val m = JSONArray()
        medicines.forEach { m.put(it.toJson()) }
        o.put("medicines", m)
        val d = JSONArray()
        diseases.forEach { d.put(it.toJson()) }
        o.put("diseases", d)
        val l = JSONArray()
        log.forEach { l.put(it.toJson()) }
        o.put("log", l)
        val t = JSONArray()
        taken.forEach { t.put(it) }
        o.put("taken", t)
        o.put("lastBackup", lastBackup)
        o.put("theme", theme)
        o.put("currentDiseaseId", currentDiseaseId)
        o.put("tabAnim", tabAnim)
        o.put("tabVol", tabVolume.toDouble())
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): AppData {
            val ma = o.optJSONArray("medicines") ?: JSONArray()
            val da = o.optJSONArray("diseases") ?: JSONArray()
            val la = o.optJSONArray("log") ?: JSONArray()
            val ta = o.optJSONArray("taken") ?: JSONArray()
            return AppData(
                morning = o.optString("morning", "08:00"),
                noon = o.optString("noon", "12:00"),
                night = o.optString("night", "20:00"),
                medicines = List(ma.length()) { i -> Medicine.fromJson(ma.getJSONObject(i)) },
                diseases = List(da.length()) { i -> Disease.fromJson(da.getJSONObject(i)) },
                log = List(la.length()) { i -> LogEntry.fromJson(la.getJSONObject(i)) },
                taken = List(ta.length()) { i -> ta.getString(i) },
                lastBackup = o.optString("lastBackup", ""),
                theme = o.optString("theme", "system"),
                currentDiseaseId = o.optString("currentDiseaseId", ""),
                tabAnim = o.optInt("tabAnim", 0),
                tabVolume = o.optDouble("tabVol", 0.25).toFloat().coerceIn(0f, 1f)
            )
        }
    }
}
