package com.shadowai.app

import android.app.DownloadManager
import android.content.Context
import android.graphics.Color as AndroidColor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

/** 지금 인터넷에 연결되어 있는지. */
fun isOnline(ctx: Context): Boolean {
    return try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: Exception) {
        false
    }
}

/** 폰에 내장된 음성 합성으로 영어 문장을 읽어 준다. 인터넷이 필요 없다. */
class Speaker(ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var onDone: (() -> Unit)? = null
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) = finish()

                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) = finish()
                })
                ready = true
            }
        }
    }

    private fun finish() {
        main.post {
            val cb = onDone
            onDone = null
            cb?.invoke()
        }
    }

    /** rate 1.0이 보통 속도. 다 읽으면 done을 메인 스레드에서 부른다. */
    fun speak(text: String, rate: Float, done: () -> Unit) {
        if (!ready) {
            main.postDelayed({ if (ready) speak(text, rate, done) else done() }, 1000)
            return
        }
        onDone = done
        tts.setSpeechRate(rate)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "shadow")
    }

    fun stop() {
        onDone = null
        if (ready) tts.stop()
    }

    fun shutdown() {
        onDone = null
        tts.shutdown()
    }
}

/**
 * 영상 없이 문장만 연습할 때 쓰는 재생기. 영상 대신 분석해 둔 문장을 합성 음성으로 읽어 준다.
 * 유튜브 영상은 인터넷이 있어야 재생되므로, 인터넷이 없을 때의 대안이다.
 */
class TextClipPlayer(ctx: Context, private val textAt: (Double) -> String) : ClipPlayer {
    private val speaker = Speaker(ctx)

    override val view: View = TextView(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        text = "영상 없이 연습하는 중\n문장은 폰의 합성 음성으로 읽어 줘요"
        gravity = Gravity.CENTER
        textSize = 15f
        setTextColor(AndroidColor.LTGRAY)
        setBackgroundColor(AndroidColor.BLACK)
    }

    override val durationSec: Double = 0.0
    override val positionSec: Double = 0.0

    override fun play(start: Double, end: Double, rate: Float, onEnd: () -> Unit) {
        val text = textAt(start)
        if (text.isBlank()) onEnd() else speaker.speak(text, rate, onEnd)
    }

    override fun stop() = speaker.stop()

    override fun release() = speaker.shutdown()
}

/** 영상 파일 주소를 폰에 저장해 두는 일. 저장한 뒤에는 인터넷 없이 재생하고 분석한다. */
object Saved {
    private fun name(item: VideoItem): String {
        val path = Uri.parse(item.key.removePrefix("file:")).lastPathSegment.orEmpty().lowercase()
        val ext = listOf(".mp4", ".webm", ".mkv", ".mov", ".m4v", ".mp3", ".m4a", ".wav", ".ogg")
            .firstOrNull { path.endsWith(it) } ?: ".mp4"
        return "v" + item.key.hashCode().toUInt() + ext
    }

    fun file(ctx: Context, item: VideoItem): File = File(ctx.getExternalFilesDir("videos"), name(item))

    fun start(ctx: Context, item: VideoItem): Long {
        // 같은 이름의 파일이 남아 있으면 다운로드 관리자가 다른 이름으로 저장하므로 먼저 지운다.
        file(ctx, item).delete()
        val req = DownloadManager.Request(Uri.parse(item.src))
            .setTitle(item.title)
            .setDescription("쉐도잉 AI에 저장하는 중")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(ctx, "videos", name(item))
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(req)
    }

    /** 받은 것이 무엇인지(예: video/mp4, text/html). 모르면 빈 글. */
    fun mime(ctx: Context, id: Long): String {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (c == null || !c.moveToFirst()) return ""
            return c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)).orEmpty()
        }
    }

    fun delete(ctx: Context, item: VideoItem) {
        try {
            file(ctx, item).delete()
        } catch (_: Exception) {
        }
    }
}

/** 주소로 넣은 영상 파일을 폰에 저장하는 동안 보여 주는 화면. 다 받으면 자동으로 영상 화면으로 넘어간다. */
@Composable
fun SaveScreen(item: VideoItem, lib: Library, onExit: () -> Unit) {
    val ctx = LocalContext.current
    var note by remember { mutableStateOf("저장을 준비하고 있어요…") }
    var progress by remember { mutableFloatStateOf(0f) }
    var failed by remember { mutableStateOf(false) }
    var round by remember { mutableIntStateOf(0) }

    LaunchedEffect(item.key, round) {
        failed = false
        if (item.downloadId == -1L) {
            try {
                item.downloadId = Saved.start(ctx, item)
                lib.save()
            } catch (_: Exception) {
                note = "이 주소는 저장할 수 없어요. 영상 파일 주소가 맞는지 확인해 주세요."
                failed = true
                return@LaunchedEffect
            }
        }
        while (true) {
            val id = item.downloadId
            val p = ModelFiles.query(ctx, id)
            if (p == null) {
                item.downloadId = -1L
                lib.save()
                note = "저장이 취소됐어요."
                failed = true
                break
            }
            if (p.status == DownloadManager.STATUS_SUCCESSFUL) {
                val kind = Saved.mime(ctx, id)
                item.downloadId = -1L
                if (kind.startsWith("text/")) {
                    Saved.delete(ctx, item)
                    lib.save()
                    note = "이 주소는 영상 파일이 아니라 웹 페이지예요. mp4 같은 영상 파일로 바로 이어지는 주소가 필요해요."
                    failed = true
                } else {
                    // 주소를 폰 안의 파일로 바꾼다. 이제부터는 인터넷 없이 열린다.
                    item.src = Uri.fromFile(Saved.file(ctx, item)).toString()
                    lib.save()
                }
                break
            }
            if (p.status == DownloadManager.STATUS_FAILED) {
                ModelFiles.cancel(ctx, id)
                item.downloadId = -1L
                lib.save()
                note = "저장하지 못했어요. 인터넷 연결과 저장 공간, 주소가 영상 파일 주소가 맞는지 확인해 주세요."
                failed = true
                break
            }
            progress = if (p.total > 0) p.done.toFloat() / p.total else 0f
            note = when (p.status) {
                DownloadManager.STATUS_PAUSED -> "인터넷 연결을 기다리는 중이에요."
                DownloadManager.STATUS_PENDING -> "저장을 준비하고 있어요…"
                else -> "%.0f / %.0f MB".format(p.done / 1e6, p.total.coerceAtLeast(0) / 1e6)
            }
            delay(1000)
        }
    }

    BackHandler { onExit() }

    Text(item.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("영상을 폰에 저장하고 있어요", fontWeight = FontWeight.Bold)
            Text("저장이 끝나면 인터넷 없이 재생하고 분석할 수 있어요. 화면을 나가도 저장은 계속돼요.")
            if (!failed) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text(note, color = if (failed) Color(0xFFD23B3B) else Color.Unspecified)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (failed) Button(onClick = { round++ }) { Text("다시 시도") }
                OutlinedButton(onClick = onExit) { Text("나가기") }
            }
        }
    }
}
