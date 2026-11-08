package com.example.ytdl

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

enum class Status { QUEUED, DOWNLOADING, PAUSED, DONE, ERROR }

data class Item(
    val id: String,
    val url: String,
    val quality: String,
    val title: String,
    val status: Status,
    val progress: Float = 0f,
    val info: String = "",
    val saved: String = ""
)

object Downloads {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val stopping = ConcurrentHashMap.newKeySet<String>()
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items = _items.asStateFlow()
    val folder = MutableStateFlow<String?>(null)
    private var ready = false

    private val prefs get() = app.getSharedPreferences("ytdl", Context.MODE_PRIVATE)

    fun init(c: Context) {
        app = c.applicationContext
        folder.value = prefs.getString("tree", null)
        val arr = JSONArray(prefs.getString("items", "[]"))
        _items.value = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            var st = Status.valueOf(o.getString("status"))
            if (st == Status.DOWNLOADING || st == Status.QUEUED) st = Status.PAUSED
            Item(
                o.getString("id"), o.getString("url"), o.getString("quality"),
                o.getString("title"), st, o.optDouble("progress", 0.0).toFloat(),
                o.optString("info"), o.optString("saved")
            )
        }
    }

    private fun save() {
        val a = JSONArray()
        _items.value.forEach {
            a.put(
                JSONObject().put("id", it.id).put("url", it.url).put("quality", it.quality)
                    .put("title", it.title).put("status", it.status.name)
                    .put("progress", it.progress.toDouble()).put("info", it.info).put("saved", it.saved)
            )
        }
        prefs.edit().putString("items", a.toString()).apply()
    }

    private fun update(id: String, f: (Item) -> Item) {
        _items.update { l -> l.map { if (it.id == id) f(it) else it } }
        save()
    }

    fun setFolder(uri: String?) {
        folder.value = uri
        prefs.edit().apply { if (uri == null) remove("tree") else putString("tree", uri) }.apply()
    }

    fun folderName(uri: String): String =
        runCatching { DocumentFile.fromTreeUri(app, Uri.parse(uri))?.name }.getOrNull() ?: uri

    fun add(url: String, quality: String) {
        val u = url.trim()
        val item = Item(UUID.randomUUID().toString().take(8), u, quality, u, Status.QUEUED)
        _items.update { listOf(item) + it }
        save()
        start(item.id)
    }

    fun start(id: String) {
        if (jobs[id]?.isActive == true) return
        update(id) { it.copy(status = Status.DOWNLOADING, info = "") }
        DownloadService.start(app)
        jobs[id] = scope.launch {
            try {
                run(id)
            } finally {
                jobs.remove(id, coroutineContext[Job])
                if (jobs.isEmpty()) DownloadService.stop(app)
            }
        }
    }

    fun pause(id: String) {
        scope.launch {
            stopping.add(id)
            runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
            jobs[id]?.join()
            stopping.remove(id)
            update(id) { it.copy(status = Status.PAUSED) }
        }
    }

    fun remove(id: String, withFile: Boolean) {
        val item = _items.value.find { it.id == id } ?: return
        _items.update { l -> l.filter { it.id != id } }
        save()
        scope.launch {
            stopping.add(id)
            runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
            jobs[id]?.join()
            stopping.remove(id)
            File(app.getExternalFilesDir(null), "tmp/$id").deleteRecursively()
            if (withFile && item.saved.isNotBlank()) {
                runCatching {
                    val u = Uri.parse(item.saved)
                    if (u.authority == "media") app.contentResolver.delete(u, null, null)
                    else DocumentFile.fromSingleUri(app, u)?.delete()
                }
            }
        }
    }

    @Synchronized
    private fun ensureLibs() {
        if (ready) return
        YoutubeDL.getInstance().init(app)
        FFmpeg.getInstance().init(app)
        runCatching { YoutubeDL.getInstance().updateYoutubeDL(app) }
        ready = true
    }

    private fun run(id: String) {
        val item = _items.value.find { it.id == id } ?: return
        val dir = File(app.getExternalFilesDir(null), "tmp/$id").apply { mkdirs() }
        try {
            ensureLibs()
            val r = YoutubeDLRequest(item.url)
            r.addOption("-o", "${dir.absolutePath}/%(title).150B.%(ext)s")
            r.addOption("--continue")
            r.addOption("--no-playlist")
            r.addOption("--no-mtime")
            val h = item.quality.removeSuffix("p").toIntOrNull()
            when {
                item.quality.startsWith("Только аудио") -> {
                    r.addOption("-f", "ba/b")
                    r.addOption("-x")
                    r.addOption("--audio-format", "mp3")
                }
                h != null -> {
                    r.addOption("-f", "bv*[height<=$h][ext=mp4]+ba[ext=m4a]/b[height<=$h][ext=mp4]/bv*[height<=$h]+ba/b")
                    r.addOption("--merge-output-format", "mp4")
                }
                else -> {
                    r.addOption("-f", "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b")
                    r.addOption("--merge-output-format", "mp4")
                }
            }
            YoutubeDL.getInstance().execute(r, id) { p, _, line -> progress(id, p, line) }
            if (id in stopping) return
            val file = dir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                ?.maxByOrNull { it.length() } ?: error("Файл не найден после загрузки")
            update(id) { it.copy(info = "Сохранение…") }
            val saved = export(file)
            dir.deleteRecursively()
            update(id) {
                it.copy(status = Status.DONE, progress = 1f, title = file.nameWithoutExtension, saved = saved, info = "")
            }
        } catch (e: Exception) {
            if (id in stopping || _items.value.none { it.id == id }) return
            update(id) { it.copy(status = Status.ERROR, info = (e.message ?: "Ошибка").take(200)) }
        }
    }

    private fun progress(id: String, p: Float, line: String) {
        val t = Regex("""Destination: (.+)""").find(line)?.groupValues?.get(1)
            ?.let { File(it).nameWithoutExtension.replace(Regex("""\.f\d+$"""), "") }
        _items.update { l ->
            l.map {
                if (it.id == id) it.copy(
                    progress = if (p >= 0) p / 100f else it.progress,
                    title = if (t != null) t else it.title
                ) else it
            }
        }
    }

    private fun export(f: File): String {
        val mime = when (f.extension.lowercase()) {
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            else -> "application/octet-stream"
        }
        val tree = folder.value?.let {
            runCatching { DocumentFile.fromTreeUri(app, Uri.parse(it)) }.getOrNull()
        }?.takeIf { it.canWrite() }

        if (tree != null) {
            val out = tree.createFile(mime, f.nameWithoutExtension) ?: error("Не удалось создать файл в папке")
            app.contentResolver.openOutputStream(out.uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
            return out.uri.toString()
        }

        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/YT")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            ?: error("Не удалось сохранить в Загрузки")
        app.contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
        cv.clear()
        cv.put(MediaStore.Downloads.IS_PENDING, 0)
        app.contentResolver.update(uri, cv, null, null)
        return uri.toString()
    }
}
