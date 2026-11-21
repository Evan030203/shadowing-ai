package com.shadowai.app

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * AI 모델 파일을 찾고 받는 일.
 *
 * 모델은 이 앱 안에 두지 않고 폰의 공용 폴더(내장 저장공간/Download/LocalAI)에 한 번만 둔다.
 * 이 앱은 그 파일을 읽기만 하므로, 앱을 지워도 모델은 남고 다른 앱도 같은 파일을 쓸 수 있다.
 * 예전 버전이 앱 전용 폴더에 받아 둔 모델도 그대로 쓸 수 있고, 공용 폴더로 옮길 수 있다.
 */
object ModelFiles {
    private const val URL =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true"
    const val NAME = "gemma-4-E2B-it.litertlm"
    private const val FOLDER = "LocalAI"

    /** 이보다 작으면 받다 만 파일로 본다. */
    private const val MIN_BYTES = 500_000_000L

    data class Progress(val status: Int, val done: Long, val total: Long)

    private fun downloads(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    /** 모델을 두는 공용 폴더. */
    fun sharedDir(): File = File(downloads(), FOLDER)

    /** 공용 폴더의 파일을 읽을 수 있게 허용됐는지('모든 파일에 대한 접근'). */
    fun hasAccess(): Boolean = Environment.isExternalStorageManager()

    /** 그 허용을 켜는 설정 화면. */
    fun accessIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName))

    /** 예전 버전이 앱 전용 폴더에 받아 둔 모델. 앱을 지우면 같이 지워진다. */
    fun legacy(ctx: Context): File = File(ctx.getExternalFilesDir(null), NAME)

    fun isPrivate(file: File): Boolean = file.absolutePath.contains("/Android/data/")

    /** 공용 폴더(Download/LocalAI 또는 Download)에 있는 모델 파일. 없거나 읽을 수 없으면 null. */
    fun shared(): File? {
        if (!hasAccess()) return null
        val found = listOf(sharedDir(), downloads()).flatMap { dir ->
            try {
                dir.listFiles { f -> f.isFile && f.name.endsWith(".litertlm") && f.length() > MIN_BYTES }?.toList()
                    ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }
        return found.firstOrNull { it.name == NAME }
            ?: found.firstOrNull { it.name.contains("E2B", ignoreCase = true) }
            ?: found.firstOrNull()
    }

    /** 쓸 모델 파일: 공용 폴더의 것이 먼저, 없으면 앱 전용 폴더의 것. */
    fun find(ctx: Context): File? =
        shared() ?: legacy(ctx).takeIf { it.isFile && it.length() > MIN_BYTES }

    /** 모델을 공용 폴더에 받는다. 안드로이드 다운로드 관리자가 처리해서 앱을 닫아도 이어진다. */
    fun start(ctx: Context): Long {
        try {
            sharedDir().mkdirs()
            // 같은 이름의 파일이 남아 있으면 다운로드 관리자가 다른 이름으로 저장하므로 먼저 지운다.
            File(sharedDir(), NAME).delete()
        } catch (_: Exception) {
        }
        val req = DownloadManager.Request(Uri.parse(URL))
            .setTitle("로컬 AI 모델")
            .setDescription("Gemma 4 E2B (약 2.6GB)")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(false)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "$FOLDER/$NAME")
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(req)
    }

    /**
     * 앱 전용 폴더의 모델을 공용 폴더로 옮긴다. 다시 받지 않는다.
     * 같은 저장공간 안이라 보통 바로 옮겨지고, 안 되면 복사한 뒤 원본을 지운다.
     */
    suspend fun moveToShared(ctx: Context): File? = withContext(Dispatchers.IO) {
        val src = legacy(ctx)
        if (!src.isFile || !hasAccess()) return@withContext null
        val dst = File(sharedDir(), NAME)
        try {
            sharedDir().mkdirs()
            if (dst.isFile && dst.length() == src.length()) {
                src.delete()
                return@withContext dst
            }
            if (src.renameTo(dst)) return@withContext dst
            src.inputStream().use { input ->
                dst.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
            }
            if (dst.length() == src.length()) {
                src.delete()
                dst
            } else {
                dst.delete()
                null
            }
        } catch (_: Exception) {
            try {
                if (dst.length() != src.length()) dst.delete()
            } catch (_: Exception) {
            }
            null
        }
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

    /** 지금 올라와 있는 모델 파일. */
    var modelFile: File? = null
        private set

    /** 모델이 소리 입력을 받을 수 있게 올라갔는지. */
    var hasAudio: Boolean = false
        private set
    val isLoaded: Boolean get() = engine != null

    /** 모델 파일을 다른 곳으로 옮긴 뒤 새 위치를 알려 준다. 이미 올라온 모델은 그대로 쓴다. */
    fun movedTo(file: File) {
        modelFile = file
    }

    suspend fun load(ctx: Context, store: Store, file: File): Boolean = withContext(thread) {
        if (engine != null) return@withContext true
        val path = file.absolutePath
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
            modelFile = file
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
