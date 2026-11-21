package com.shadowai.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** Gemma 4 E2B 모델 파일을 폰에 받아 두는 일. 안드로이드 다운로드 관리자가 처리해서 앱을 닫아도 이어진다. */
object ModelFiles {
    private const val URL =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true"
    private const val NAME = "gemma-4-E2B-it.litertlm"

    data class Progress(val status: Int, val done: Long, val total: Long)

    fun file(ctx: Context): File = File(ctx.getExternalFilesDir(null), NAME)

    fun start(ctx: Context): Long {
        // 같은 이름의 파일이 남아 있으면 다운로드 관리자가 다른 이름으로 저장하므로 먼저 지운다.
        file(ctx).delete()
        val req = DownloadManager.Request(Uri.parse(URL))
            .setTitle("쉐도잉 AI 모델")
            .setDescription("Gemma 4 E2B (약 2.6GB)")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(false)
            .setDestinationInExternalFilesDir(ctx, null, NAME)
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(req)
    }

    /** 다운로드가 목록에서 사라졌으면(사용자가 취소) null. */
    fun query(ctx: Context, id: Long): Progress? {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (c == null || !c.moveToFirst()) return null
            return Progress(
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            )
        }
    }

    fun cancel(ctx: Context, id: Long) {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.remove(id)
    }
}

/** 폰 안에서 도는 언어 모델. 모든 호출은 전용 스레드 하나에서 차례로 실행된다. */
object Llm {
    private val thread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var engine: Engine? = null

    var backendName: String = ""
        private set
    var lastError: String = ""
        private set

    /** 모델이 소리 입력을 받을 수 있게 올라갔는지. */
    var hasAudio: Boolean = false
        private set
    val isLoaded: Boolean get() = engine != null

    suspend fun load(ctx: Context, store: Store): Boolean = withContext(thread) {
        if (engine != null) return@withContext true
        val path = ModelFiles.file(ctx).absolutePath
        val cache = ctx.cacheDir.path

        // 소리를 알아듣는 기능까지 켜서 올려 보고, 안 되면 글자 전용으로 올린다.
        fun open(gpu: Boolean, audio: Boolean): Boolean = try {
            val main = if (gpu) Backend.GPU() else Backend.CPU()
            val config = if (audio) {
                EngineConfig(modelPath = path, backend = main, audioBackend = Backend.CPU(), cacheDir = cache)
            } else {
                EngineConfig(modelPath = path, backend = main, cacheDir = cache)
            }
            val e = Engine(config)
            e.initialize()
            engine = e
            backendName = if (gpu) "GPU" else "CPU"
            hasAudio = audio
            true
        } catch (t: Throwable) {
            lastError = t.message ?: t.toString()
            false
        }

        if (!store.gpuCrashed) {
            store.gpuCrashed = true
            val ok = open(gpu = true, audio = true) || open(gpu = true, audio = false)
            if (ok) {
                store.gpuCrashed = false
                return@withContext true
            }
        }
        open(gpu = false, audio = true) || open(gpu = false, audio = false)
    }

    /** 녹음한 영어 음성(WAV)을 폰 안에서 받아쓴다. */
    suspend fun transcribe(wav: ByteArray): String {
        val answer = askAudio(wav, Coach.TRANSCRIBE).trim().trim('"')
        return if (answer.uppercase().startsWith("NONE")) "" else answer
    }

    /** 소리(WAV)를 들려주고 질문한다. */
    suspend fun askAudio(wav: ByteArray, prompt: String): String = withContext(thread) {
        val e = engine ?: throw IllegalStateException("AI 모델이 아직 준비되지 않았어요.")
        if (!hasAudio) throw IllegalStateException("이 모델은 소리를 듣지 못해요.")
        e.createConversation().use {
            it.sendMessage(Contents.of(Content.AudioBytes(wav), Content.Text(prompt))).toString()
        }
    }

    /** 질문 하나에 답 하나. 앞선 대화는 기억하지 않는다. */
    suspend fun ask(prompt: String): String = withContext(thread) {
        val e = engine ?: throw IllegalStateException("AI 모델이 아직 준비되지 않았어요.")
        e.createConversation().use { it.sendMessage(prompt).toString() }
    }
}
