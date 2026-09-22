package com.skarbonka.app

import android.app.ActivityManager
import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Lokalne AI: Qwen 2.5 1.5B (Google MediaPipe LLM Inference), wbudowane w apke - dziala bez internetu.
 * Model jest w APK (assets/models). MediaPipe potrzebuje zwyklego pliku, wiec przy pierwszym
 * uruchomieniu apki model jest raz, w tle, kopiowany do pamieci apki - potem AI jest od razu gotowe.
 * (Zapas: gdyby modelu nie bylo w APK, pobiera sie raz z internetu.)
 */
object AiEngine {
    private const val MODEL_URL =
        "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task"
    const val ASSET = "models/qwen2.5-1.5b-instruct-q8-ekv4096.task"
    private const val FILE_NAME = "qwen2.5-1.5b-instruct-q8-ekv4096.task"
    // pliki z poprzedniej wersji apki (model byl w APK) - usuwamy, zeby nie zajmowaly miejsca
    private val OLD_FILES = listOf("qwen2.5-1.5b-instruct-q8.task", "qwen2.5-1.5b-instruct-q8.task.part")
    private const val MAX_TOKENS = 4096   // dluzsze paragony mieszcza sie w calosci
    private val worker = Executors.newSingleThreadExecutor()
    private var llm: LlmInference? = null

    // none | copying | downloading | loading | ready | error | lowmem | nospace
    @Volatile var state = "none"; private set
    @Volatile var progress = 0; private set
    @Volatile var error = ""; private set

    private class NoSpace : Exception()

    private fun assetSize(ctx: Context): Long =
        try { ctx.assets.openFd(ASSET).use { it.length } } catch (e: Exception) { -1L }

    /** Czy model jest na telefonie (skopiowany albo w APK) - AI mozna uzyc bez internetu. */
    fun isDownloaded(ctx: Context): Boolean =
        File(ctx.applicationContext.filesDir, FILE_NAME).length() > 1_000_000_000L || assetSize(ctx.applicationContext) > 0

    /** Przy pierwszym uruchomieniu apki: kopiuje model z APK w tle, zeby AI bylo od razu gotowe. */
    fun prepare(ctx: Context) {
        val app = ctx.applicationContext
        if (state != "none") return
        worker.execute {
            try {
                OLD_FILES.forEach { File(app.filesDir, it).delete() }
                val out = File(app.filesDir, FILE_NAME)
                val size = assetSize(app)
                if (size > 0 && out.length() != size && app.filesDir.usableSpace > size + 150L * 1024 * 1024) {
                    copyFromApk(app, size, out) { }
                }
            } catch (t: Throwable) { /* sprobuje ponownie przy uzyciu AI */ }
        }
    }

    private fun copyFromApk(app: Context, size: Long, out: File, onStatus: () -> Unit) {
        val tmp = File(out.path + ".copy")
        app.assets.open(ASSET).use { input ->
            tmp.outputStream().use { output ->
                val buf = ByteArray(1 shl 20)
                var done = 0L
                var last = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    val p = (done * 100 / size).toInt()
                    if (p != last) { last = p; progress = p; onStatus() }
                }
            }
        }
        out.delete()
        if (!tmp.renameTo(out)) throw IOException("Nie udało się zapisać modelu")
    }

    fun statusJson(): String =
        JSONObject().put("state", state).put("progress", progress).put("error", error).toString()

    fun init(ctx: Context, onStatus: () -> Unit) {
        if (state == "ready" || state == "downloading" || state == "copying" || state == "loading") { onStatus(); return }
        val app = ctx.applicationContext
        state = "loading"
        worker.execute {
            try {
                val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val mem = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mem)
                if (mem.totalMem < 5L * 1024 * 1024 * 1024) { state = "lowmem"; onStatus(); return@execute }

                OLD_FILES.forEach { File(app.filesDir, it).delete() }
                val out = File(app.filesDir, FILE_NAME)
                val size = assetSize(app)
                if (size > 0 && out.length() != size) {
                    // model z APK -> pamiec apki (jeden raz)
                    if (app.filesDir.usableSpace < size + 150L * 1024 * 1024) throw NoSpace()
                    state = "copying"; progress = 0; onStatus()
                    copyFromApk(app, size, out, onStatus)
                } else if (size <= 0 && out.length() < 1_000_000_000L) {
                    // zapas: brak modelu w APK -> pobranie z internetu (jeden raz)
                    state = "downloading"; progress = 0; onStatus()
                    download(out, onStatus)
                }

                state = "loading"; progress = 100; onStatus()
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(out.absolutePath)
                    .setMaxTokens(MAX_TOKENS)
                    .build()
                llm = LlmInference.createFromOptions(app, options)
                state = "ready"; onStatus()
            } catch (e: NoSpace) {
                state = "nospace"; onStatus()
            } catch (t: Throwable) {
                state = "error"
                error = t.message ?: t.toString()
                onStatus()
            }
        }
    }

    /** Pobieranie z wznawianiem - przerwane (np. brak zasiegu) zaczyna sie od miejsca, gdzie skonczylo. */
    private fun download(out: File, onStatus: () -> Unit) {
        val part = File(out.path + ".part")
        var attempt = 0
        while (true) {
            try {
                val have = if (part.exists()) part.length() else 0L
                val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 20000
                    readTimeout = 60000
                    if (have > 0) setRequestProperty("Range", "bytes=$have-")
                }
                val code = conn.responseCode
                if (code != 200 && code != 206) throw IOException("HTTP $code")
                val append = code == 206
                val len = conn.contentLengthLong
                val total = if (len > 0) (if (append) have + len else len) else -1L
                val need = if (len > 0) len else 1_700_000_000L
                if (out.parentFile!!.usableSpace < need + 150L * 1024 * 1024) throw NoSpace()
                var done = if (append) have else 0L
                conn.inputStream.use { input ->
                    FileOutputStream(part, append).use { output ->
                        val buf = ByteArray(1 shl 16)
                        var last = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val p = (done * 100 / total).toInt()
                                if (p != last) { last = p; progress = p; onStatus() }
                            }
                        }
                    }
                }
                if (total > 0 && part.length() < total) throw IOException("Pobieranie przerwane")
                if (part.length() < 1_000_000_000L) throw IOException("Plik modelu jest niekompletny")
                out.delete()
                if (!part.renameTo(out)) throw IOException("Nie udało się zapisać modelu")
                return
            } catch (e: NoSpace) {
                throw e
            } catch (e: IOException) {
                attempt++
                if (attempt >= 4) throw e
                Thread.sleep(3000L * attempt)
            }
        }
    }

    /** Odpowiedz modelu na gotowy prompt (format czatu Qwen buduje strona). */
    fun generate(prompt: String, temperature: Float, done: (String?, String?) -> Unit) {
        worker.execute {
            val model = llm
            if (model == null) { done(null, "AI nie jest gotowe"); return@execute }
            try {
                val session = try {
                    LlmInferenceSession.createFromOptions(
                        model,
                        LlmInferenceSession.LlmInferenceSessionOptions.builder()
                            .setTemperature(temperature)
                            .setTopK(if (temperature < 0.3f) 5 else 40)
                            .build()
                    )
                } catch (t: Throwable) { null }
                val text = if (session != null) {
                    try {
                        session.addQueryChunk(prompt)
                        session.generateResponse()
                    } finally {
                        try { session.close() } catch (t: Throwable) { }
                    }
                } else {
                    model.generateResponse(prompt)
                }
                done(text, null)
            } catch (t: Throwable) {
                done(null, t.message ?: t.toString())
            }
        }
    }
}
