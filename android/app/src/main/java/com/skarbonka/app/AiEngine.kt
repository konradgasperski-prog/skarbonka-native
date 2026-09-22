package com.skarbonka.app

import android.app.ActivityManager
import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Lokalne AI: Qwen 2.5 1.5B (Google MediaPipe LLM Inference), wbudowane w apke - dziala bez internetu.
 * Model lezy w APK (assets/models). MediaPipe potrzebuje zwyklego pliku, wiec przy pierwszym
 * uruchomieniu model jest raz kopiowany do pamieci apki, potem ladowany od razu.
 * Wszystko dzieje sie na jednym watku w tle - interfejs sie nie zacina.
 */
object AiEngine {
    const val ASSET = "models/qwen2.5-1.5b-instruct-q8.task"
    private const val FILE_NAME = "qwen2.5-1.5b-instruct-q8.task"
    private val worker = Executors.newSingleThreadExecutor()
    private var llm: LlmInference? = null

    // none | copying | loading | ready | error | lowmem | missing | nospace
    @Volatile var state = "none"; private set
    @Volatile var progress = 0; private set
    @Volatile var error = ""; private set

    fun statusJson(): String =
        JSONObject().put("state", state).put("progress", progress).put("error", error).toString()

    fun init(ctx: Context, onStatus: () -> Unit) {
        if (state == "ready" || state == "copying" || state == "loading") { onStatus(); return }
        val app = ctx.applicationContext
        state = "loading"
        worker.execute {
            try {
                // Za malo RAM = model moglby zamknac apke; lepiej uczciwie powiedziec
                val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val mem = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mem)
                if (mem.totalMem < 5L * 1024 * 1024 * 1024) { state = "lowmem"; onStatus(); return@execute }

                val size = try { app.assets.openFd(ASSET).use { it.length } } catch (e: Exception) { -1L }
                if (size <= 0) { state = "missing"; onStatus(); return@execute }

                val out = File(app.filesDir, FILE_NAME)
                if (!out.exists() || out.length() != size) {
                    if (app.filesDir.usableSpace < size + 150L * 1024 * 1024) { state = "nospace"; onStatus(); return@execute }
                    state = "copying"; progress = 0; onStatus()
                    val tmp = File(app.filesDir, "$FILE_NAME.part")
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
                    if (!tmp.renameTo(out)) throw IllegalStateException("Nie udało się zapisać modelu")
                }

                state = "loading"; progress = 100; onStatus()
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(out.absolutePath)
                    .setMaxTokens(1280)
                    .build()
                llm = LlmInference.createFromOptions(app, options)
                state = "ready"; onStatus()
            } catch (t: Throwable) {
                state = "error"
                error = t.message ?: t.toString()
                onStatus()
            }
        }
    }

    /** Odpowiedz modelu na gotowy prompt (format czatu Qwen buduje strona). */
    fun generate(prompt: String, done: (String?, String?) -> Unit) {
        worker.execute {
            val model = llm
            if (model == null) { done(null, "AI nie jest gotowe"); return@execute }
            try {
                done(model.generateResponse(prompt), null)
            } catch (t: Throwable) {
                done(null, t.message ?: t.toString())
            }
        }
    }
}
