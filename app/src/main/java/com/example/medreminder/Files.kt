package com.example.medreminder

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

fun toast(ctx: Context, msg: String) {
    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
}

fun imageDir(ctx: Context): File {
    val dir = File(ctx.filesDir, "prescriptions")
    dir.mkdirs()
    return dir
}

fun newImageFile(ctx: Context): File = File(imageDir(ctx), "rx_" + System.currentTimeMillis() + ".jpg")

fun copyUriToFile(ctx: Context, uri: Uri, dest: File) {
    ctx.contentResolver.openInputStream(uri)?.use { input ->
        dest.outputStream().use { out -> input.copyTo(out) }
    }
}

fun openImage(ctx: Context, name: String) {
    try {
        val f = File(imageDir(ctx), name)
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(uri, "image/*")
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(i)
    } catch (e: Exception) {
        toast(ctx, "ছবি খোলা যায়নি")
    }
}

fun isDarkNow(ctx: Context): Boolean = when (Store.data.theme) {
    "dark" -> true
    "light" -> false
    else -> (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
}

fun dialogTheme(ctx: Context): Int =
    if (isDarkNow(ctx)) android.R.style.Theme_Material_Dialog_Alert
    else android.R.style.Theme_Material_Light_Dialog_Alert

fun pickTime(ctx: Context, initial: String, onPicked: (String) -> Unit) {
    val p = initial.split(":")
    TimePickerDialog(ctx, dialogTheme(ctx), { _, h, m -> onPicked(hhmm(h, m)) }, p[0].toInt(), p[1].toInt(), false).show()
}

fun pickDate(ctx: Context, initial: String, onPicked: (String) -> Unit) {
    val d = runCatching { LocalDate.parse(initial) }.getOrDefault(LocalDate.now())
    DatePickerDialog(
        ctx,
        dialogTheme(ctx),
        { _, y, m, day -> onPicked(LocalDate.of(y, m + 1, day).toString()) },
        d.year, d.monthValue - 1, d.dayOfMonth
    ).show()
}

// ব্যাকআপের ডিফল্ট ফোল্ডার: Documents/ঔষধের রিমাইন্ডার
const val BACKUP_FOLDER = "ঔষধের রিমাইন্ডার"

// ফাইল-পিকার ছাড়াই সরাসরি Documents/ঔষধের রিমাইন্ডার ফোল্ডারে ব্যাকআপ সেভ করে (ফোল্ডার না থাকলে নিজে বানায়)
fun backupToDefaultFolder(ctx: Context): Boolean {
    return try {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
        val name = "medicine-backup-$stamp.zip"
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/" + BACKUP_FOLDER)
            }
            ctx.contentResolver.insert(MediaStore.Files.getContentUri("external"), v)
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), BACKUP_FOLDER)
            dir.mkdirs()
            Uri.fromFile(File(dir, name))
        }
        uri != null && exportZip(ctx, uri)
    } catch (e: Exception) {
        false
    }
}

// রিস্টোরের ফাইল-পিকার সরাসরি ব্যাকআপ ফোল্ডারে খোলে
class OpenBackupDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        val i = super.createIntent(context, input)
        i.putExtra(
            DocumentsContract.EXTRA_INITIAL_URI,
            DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:Documents/" + BACKUP_FOLDER
            )
        )
        return i
    }
}

fun exportZip(ctx: Context, uri: Uri): Boolean {
    return try {
        ctx.contentResolver.openOutputStream(uri)?.use { os ->
            ZipOutputStream(os).use { z ->
                z.putNextEntry(ZipEntry("data.json"))
                z.write(Store.data.toJson().toString().toByteArray(Charsets.UTF_8))
                z.closeEntry()
                imageDir(ctx).listFiles()?.forEach { f ->
                    z.putNextEntry(ZipEntry("images/" + f.name))
                    f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                }
            }
        }
        Store.update(ctx) { it.copy(lastBackup = LocalDate.now().toString()) }
        true
    } catch (e: Exception) {
        false
    }
}

fun importZip(ctx: Context, uri: Uri): Boolean {
    return try {
        var json: String? = null
        val dir = imageDir(ctx)
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            ZipInputStream(ins).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    if (e.name == "data.json") {
                        json = z.readBytes().toString(Charsets.UTF_8)
                    } else if (e.name.startsWith("images/") && !e.isDirectory) {
                        val name = File(e.name).name
                        File(dir, name).outputStream().use { out -> z.copyTo(out) }
                    }
                    z.closeEntry()
                    e = z.nextEntry
                }
            }
        }
        val j = json
        if (j == null) {
            false
        } else {
            val obj = JSONObject(j)
            if (obj.optBoolean("partial", false)) {
                // একটা রোগের এক্সপোর্ট: বাকি সব ঠিক রেখে ওই রোগ ও তার ঔষধ যোগ/আপডেট হবে
                val parsed = AppData.fromJson(obj)
                Store.update(ctx) { s ->
                    s.copy(
                        diseases = s.diseases.filter { o -> parsed.diseases.none { it.id == o.id } } + parsed.diseases,
                        medicines = s.medicines.filter { o -> parsed.medicines.none { it.id == o.id } } + parsed.medicines
                    )
                }
            } else {
                val parsed = AppData.fromJson(obj)
                Store.update(ctx) { parsed }
            }
            Reminders.scheduleAll(ctx)
            true
        }
    } catch (e: Exception) {
        false
    }
}

// পড়ার মতো সাদামাটা লেখা: রোগ ও তার সব ঔষধের ডিটেলস
fun diseaseText(ds: Disease, meds: List<Medicine>): String = buildString {
    append("রোগ: ").append(ds.name).append("\n")
    append("প্রেসক্রিপশনের তারিখ: ").append(prettyDate(ds.date)).append("\n")
    append("প্রেসক্রিপশনের ছবি: ").append(bn(ds.images.size)).append("টি\n")
    append("মোট ঔষধ: ").append(bn(meds.size)).append("টি\n\n")
    meds.forEachIndexed { i, m ->
        append(bn(i + 1)).append(". ").append(m.name).append("\n")
        append("   ধরন: ").append(Forms.of(m.form).label).append("\n")
        append("   প্রতিবারে: ").append(m.doseLabel()).append("\n")
        append("   কখন: ")
            .append(m.timings.sortedBy { Reminders.slotOrder.indexOf(it) }.joinToString(", ") { Slots.label(it) })
            .append("\n")
        if (m.intervalDays > 1) append("   প্রতি ").append(bn(m.intervalDays)).append(" দিন পর পর\n")
        append("   শুরুর তারিখ: ").append(prettyDate(m.startDate)).append("\n")
        append("\n")
    }
}

// একটা রোগ + তার আন্ডারের সব ঔষধ (ডিটেলস সহ) + প্রেসক্রিপশনের ছবি এক zip এ
fun exportDiseaseZip(ctx: Context, uri: Uri, disease: Disease): Boolean {
    return try {
        val d = Store.data
        val ds = d.diseases.firstOrNull { it.id == disease.id } ?: disease
        val meds = d.medicines.filter { it.diseaseId == ds.id }
        val o = JSONObject()
        o.put("partial", true)
        o.put("diseases", JSONArray().put(ds.toJson()))
        val ma = JSONArray()
        meds.forEach { ma.put(it.toJson()) }
        o.put("medicines", ma)
        ctx.contentResolver.openOutputStream(uri)?.use { os ->
            ZipOutputStream(os).use { z ->
                z.putNextEntry(ZipEntry("data.json"))
                z.write(o.toString().toByteArray(Charsets.UTF_8))
                z.closeEntry()
                z.putNextEntry(ZipEntry("details.txt"))
                z.write(diseaseText(ds, meds).toByteArray(Charsets.UTF_8))
                z.closeEntry()
                ds.images.forEach { name ->
                    val f = File(imageDir(ctx), name)
                    if (f.exists()) {
                        z.putNextEntry(ZipEntry("images/" + f.name))
                        f.inputStream().use { it.copyTo(z) }
                        z.closeEntry()
                    }
                }
            }
        } != null
    } catch (e: Exception) {
        false
    }
}
