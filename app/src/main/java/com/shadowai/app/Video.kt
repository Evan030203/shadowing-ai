package com.shadowai.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

/** 쉐도잉할 영상: 유튜브 영상 ID 또는 폰에 있는 내 파일. */
sealed class VideoSource {
    data class YouTube(val id: String) : VideoSource()
    data class LocalFile(val uri: Uri) : VideoSource()
}

/** 유튜브 주소나 공유된 글에서 영상 ID(11자)를 찾는다. */
fun youTubeId(text: String): String? {
    val patterns = listOf(
        Regex("[?&]v=([A-Za-z0-9_-]{11})"),
        Regex("youtu\\.be/([A-Za-z0-9_-]{11})"),
        Regex("youtube\\.com/(?:shorts|embed|live)/([A-Za-z0-9_-]{11})"),
    )
    for (p in patterns) p.find(text)?.let { return it.groupValues[1] }
    return null
}

data class Line(val start: Double, val end: Double, val text: String)

/**
 * 유튜브의 '스크립트 표시'에서 복사한 글을 시간별 문장으로 나눈다.
 * "0:05" 줄 다음에 문장이 오는 형태와 "0:05 문장" 형태를 모두 받는다.
 */
fun parseScript(raw: String): List<Line> {
    val stamp = Regex("^\\(?(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\)?")
    val found = mutableListOf<Pair<Double, StringBuilder>>()
    for (l in raw.lines()) {
        val line = l.trim()
        if (line.isEmpty()) continue
        val m = stamp.find(line)
        if (m != null) {
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val c = m.groupValues[3].toIntOrNull()
            val sec = if (c == null) a * 60 + b else a * 3600 + b * 60 + c
            found.add(sec.toDouble() to StringBuilder(line.substring(m.range.last + 1).trim()))
        } else if (found.isNotEmpty()) {
            val sb = found.last().second
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(line)
        }
    }
    val items = found.filter { it.second.isNotBlank() }
    return items.mapIndexed { i, p ->
        val end = if (i + 1 < items.size) items[i + 1].first else p.first + 8.0
        Line(p.first, end, p.second.toString())
    }
}

/** 영상의 한 구간만 재생하고 멈추는 재생기. */
interface ClipPlayer {
    val durationSec: Double
    fun play(start: Double, end: Double, rate: Float, onEnd: () -> Unit)
    fun stop()
    fun release()
}

/** 유튜브 공식 임베드 플레이어(IFrame API)를 웹뷰에 띄운다. */
@SuppressLint("SetJavaScriptEnabled")
class YouTubeClipPlayer(ctx: Context, videoId: String, private val fail: (String) -> Unit) : ClipPlayer {
    private val main = Handler(Looper.getMainLooper())
    private var onEnd: (() -> Unit)? = null
    override var durationSec: Double = 0.0
        private set

    val view: WebView = WebView(ctx).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        webChromeClient = WebChromeClient()
        setBackgroundColor(android.graphics.Color.BLACK)
        addJavascriptInterface(Bridge(), "App")
        // 유튜브 임베드는 요청한 쪽의 주소(Referer)가 있어야 재생된다.
        loadDataWithBaseURL(ORIGIN + "/", PAGE.replace("VIDEO_ID", videoId).replace("ORIGIN_URL", ORIGIN), "text/html", "utf-8", null)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onReady(duration: Double) {
            main.post { durationSec = duration }
        }

        @JavascriptInterface
        fun onEnd() {
            main.post {
                val cb = onEnd
                onEnd = null
                cb?.invoke()
            }
        }

        @JavascriptInterface
        fun onError(code: Int) {
            main.post {
                onEnd = null
                fail(
                    when (code) {
                        100 -> "영상을 찾을 수 없어요. 삭제됐거나 비공개일 수 있어요."
                        101, 150, 153 -> "이 영상은 올린 사람이 다른 앱에서 재생하지 못하게 막아 두었어요. 다른 영상을 골라 주세요."
                        else -> "영상을 재생할 수 없어요 (유튜브 오류 $code)."
                    }
                )
            }
        }
    }

    override fun play(start: Double, end: Double, rate: Float, onEnd: () -> Unit) {
        this.onEnd = onEnd
        view.evaluateJavascript("playSeg($start,$end,$rate)", null)
    }

    override fun stop() {
        onEnd = null
        view.evaluateJavascript("stopSeg()", null)
    }

    override fun release() {
        onEnd = null
        view.loadUrl("about:blank")
        view.destroy()
    }

    companion object {
        private const val ORIGIN = "https://com.shadowai.app"
        private val PAGE = """
<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="strict-origin-when-cross-origin">
<style>html,body{margin:0;height:100%;background:#000}#p{width:100%;height:100%}</style>
</head><body><div id="p"></div>
<script>
var player, endAt = -1, timer = null, ready = false, pending = null, began = 0;
var tag = document.createElement('script');
tag.src = "https://www.youtube.com/iframe_api";
document.head.appendChild(tag);
function onYouTubeIframeAPIReady() {
  player = new YT.Player('p', {
    width: '100%', height: '100%', videoId: 'VIDEO_ID',
    playerVars: { playsinline: 1, rel: 0, cc_load_policy: 1, cc_lang_pref: 'en', origin: 'ORIGIN_URL' },
    events: {
      onReady: function () {
        ready = true;
        App.onReady(player.getDuration());
        if (pending) { var a = pending; pending = null; playSeg(a[0], a[1], a[2]); }
      },
      onError: function (e) { clearInterval(timer); App.onError(e.data); }
    }
  });
}
function playSeg(s, e, r) {
  if (!ready) { pending = [s, e, r]; return; }
  clearInterval(timer);
  endAt = e; began = Date.now();
  player.setPlaybackRate(r);
  player.seekTo(s, true);
  player.playVideo();
  timer = setInterval(function () {
    if (Date.now() - began < 500) return;
    if (player.getCurrentTime() >= endAt || player.getPlayerState() === 0) {
      clearInterval(timer);
      player.pauseVideo();
      App.onEnd();
    }
  }, 100);
}
function stopSeg() { clearInterval(timer); pending = null; if (ready) player.pauseVideo(); }
</script></body></html>
""".trimIndent()
    }
}

/** 폰에 있는 내 영상·음성 파일의 소리를 구간별로 재생한다. 인터넷이 필요 없다. */
class FileClipPlayer(ctx: Context, uri: Uri) : ClipPlayer {
    private val main = Handler(Looper.getMainLooper())
    private val mp = MediaPlayer()
    private var tick: Runnable? = null

    init {
        mp.setDataSource(ctx, uri)
        mp.prepare()
    }

    override val durationSec: Double get() = mp.duration / 1000.0

    override fun play(start: Double, end: Double, rate: Float, onEnd: () -> Unit) {
        stop()
        mp.seekTo((start * 1000).toLong(), MediaPlayer.SEEK_CLOSEST)
        mp.start()
        try {
            mp.playbackParams = mp.playbackParams.setSpeed(rate)
        } catch (_: Exception) {
        }
        val r = object : Runnable {
            override fun run() {
                if (!mp.isPlaying || mp.currentPosition >= end * 1000) {
                    if (mp.isPlaying) mp.pause()
                    tick = null
                    onEnd()
                } else {
                    main.postDelayed(this, 80)
                }
            }
        }
        tick = r
        main.postDelayed(r, 400)
    }

    override fun stop() {
        tick?.let { main.removeCallbacks(it) }
        tick = null
        try {
            if (mp.isPlaying) mp.pause()
        } catch (_: Exception) {
        }
    }

    override fun release() {
        stop()
        mp.release()
    }
}

private val Good = Color(0xFF1B8A4B)
private val Bad = Color(0xFFD23B3B)

private fun clock(sec: Double): String {
    val s = sec.toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun VideoPractice(
    source: VideoSource,
    aiReady: Boolean,
    hear: Hear,
    recorder: Recorder,
    onExit: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var error by remember { mutableStateOf("") }
    var phase by remember { mutableStateOf("idle") } // idle, playing, listening, done
    var heard by remember { mutableStateOf("") }
    var meaning by remember { mutableStateOf("") }
    var rate by remember { mutableFloatStateOf(1f) }
    var segLen by remember { mutableIntStateOf(6) }
    var pos by remember { mutableDoubleStateOf(0.0) }
    var lines by remember { mutableStateOf(listOf<Line>()) }
    var idx by remember { mutableIntStateOf(0) }
    var showPaste by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var aiListening by remember { mutableStateOf(false) }
    val aiRef = remember { mutableStateMapOf<Int, String>() }

    val player: ClipPlayer? = remember(source) {
        try {
            when (source) {
                is VideoSource.YouTube -> YouTubeClipPlayer(ctx, source.id) {
                    error = it
                    phase = "idle"
                }
                is VideoSource.LocalFile -> FileClipPlayer(ctx, source.uri)
            }
        } catch (_: Exception) {
            null
        }
    }

    val canTranscribe = aiReady && Llm.hasAudio
    val segStart = if (lines.isNotEmpty()) lines[idx].start else pos
    val segEnd = if (lines.isNotEmpty()) lines[idx].end else pos + segLen
    val key = (segStart * 10).toInt() * 100 + segLen
    val ref = if (lines.isNotEmpty()) lines[idx].text else aiRef[key].orEmpty()
    val score = remember(ref, heard, phase) {
        if (phase == "done" && ref.isNotBlank() && heard.isNotBlank()) scoreShadow(ref, heard) else null
    }

    fun micGranted() =
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun stopAll() {
        player?.stop()
        hear.cancel()
        recorder.stop()
    }

    fun listen() {
        phase = "listening"
        heard = ""
        hear.start(
            onPartial = { heard = it },
            onResult = {
                heard = it
                phase = "done"
            },
            fail = {
                error = it
                heard = ""
                phase = "idle"
            },
        )
    }

    /** 구간을 재생한다. 스크립트가 없으면 재생되는 소리를 마이크로 듣고 AI가 받아쓴다. */
    fun play(thenListen: Boolean) {
        val p = player ?: return
        stopAll()
        error = ""
        if (thenListen) {
            heard = ""
            meaning = ""
        }
        val k = key
        val capture = lines.isEmpty() && canTranscribe && aiRef[k] == null && micGranted() && recorder.start()
        phase = "playing"
        p.play(segStart, segEnd, rate) {
            if (capture) {
                val wav = recorder.stop()
                if (wav.isNotEmpty()) {
                    aiListening = true
                    scope.launch {
                        val text = try {
                            Llm.transcribe(wav)
                        } catch (_: Exception) {
                            ""
                        }
                        if (text.isNotBlank()) aiRef[k] = text
                        aiListening = false
                    }
                }
            }
            if (thenListen) listen() else phase = "idle"
        }
    }

    fun move(delta: Int) {
        stopAll()
        phase = "idle"
        heard = ""
        meaning = ""
        error = ""
        if (lines.isNotEmpty()) {
            idx = (idx + delta).coerceIn(0, lines.size - 1)
        } else {
            val max = (player?.durationSec ?: 0.0).let { if (it > 0) it - 1 else Double.MAX_VALUE }
            pos = (pos + delta * segLen).coerceIn(0.0, max)
        }
    }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) play(thenListen = true) else error = "마이크 권한을 허용해야 따라 말한 걸 들을 수 있어요."
    }

    BackHandler {
        stopAll()
        onExit()
    }
    DisposableEffect(player) {
        onDispose {
            hear.cancel()
            recorder.stop()
            player?.release()
        }
    }

    Row {
        Text(
            if (source is VideoSource.YouTube) "유튜브 쉐도잉" else "내 파일 쉐도잉",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = {
            stopAll()
            onExit()
        }) { Text("나가기") }
    }

    if (player == null) {
        Text("이 파일을 열 수 없어요. 다른 영상이나 음성 파일을 골라 주세요.", color = Bad)
        return
    }

    if (player is YouTubeClipPlayer) {
        AndroidView(
            factory = { player.view },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        )
    }

    Text(
        if (lines.isNotEmpty()) "스크립트 ${idx + 1} / ${lines.size} · ${clock(segStart)}"
        else "구간 ${clock(segStart)} ~ ${clock(segEnd)}",
        fontWeight = FontWeight.Bold,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (lines.isEmpty()) {
            for (n in listOf(4, 6, 8, 10)) {
                FilterChip(selected = segLen == n, onClick = { segLen = n }, label = { Text("${n}초") })
            }
        }
        FilterChip(
            selected = rate < 1f,
            onClick = { rate = if (rate < 1f) 1f else 0.75f },
            label = { Text("느리게") },
        )
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                ref.isNotBlank() -> {
                    Text(
                        if (lines.isNotEmpty()) "스크립트" else "AI가 받아쓴 문장 (틀릴 수 있어요)",
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        buildAnnotatedString {
                            if (score == null) {
                                append(ref)
                            } else {
                                score.words.forEachIndexed { i, w ->
                                    if (i > 0) append(" ")
                                    withStyle(SpanStyle(color = if (w.ok) Good else Bad, fontWeight = FontWeight.Bold)) {
                                        append(w.word)
                                    }
                                }
                            }
                        },
                        fontSize = 22.sp,
                        lineHeight = 32.sp,
                    )
                }
                aiListening -> Text("AI가 방금 구간을 받아쓰고 있어요…")
                canTranscribe -> Text("구간을 스피커로 재생하면 AI가 듣고 문장을 받아써요. 이어폰을 끼면 AI가 듣지 못해요.")
                else -> Text("스크립트가 없어요. 영상의 자막을 보며 따라 말하거나, 아래에서 스크립트를 붙여 넣어 주세요. AI 모델을 받으면 AI가 직접 받아써요.")
            }
        }
    }

    when (phase) {
        "playing" -> Text("잘 들어 보세요…", fontSize = 18.sp)
        "listening" -> {
            Text("이제 따라 말하세요", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (heard.isNotEmpty()) Text(heard)
            OutlinedButton(onClick = { hear.finishSpeaking() }) { Text("다 말했어요") }
        }
        "done" -> {
            if (score != null) {
                Text(
                    "정확도 ${score.accuracy}%",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (score.accuracy >= 75) Good else Bad,
                )
            } else if (aiListening) {
                Text("AI가 받아쓰기를 마치면 채점해 드려요…")
            }
            Text("내가 말한 문장: $heard")
        }
    }

    if (meaning.isNotEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("AI 풀이", fontWeight = FontWeight.Bold)
                Text(if (meaning == "…") "풀이를 쓰고 있어요…" else meaning)
            }
        }
    }

    if (error.isNotEmpty()) Text(error, color = Bad)

    if (phase == "idle" || phase == "done") {
        Button(
            onClick = { if (micGranted()) play(thenListen = true) else askMic.launch(Manifest.permission.RECORD_AUDIO) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text(if (phase == "done") "이 구간 다시 쉐도잉" else "듣고 따라 말하기", fontSize = 18.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { move(-1) }, modifier = Modifier.weight(1f)) { Text("이전") }
            OutlinedButton(onClick = { play(thenListen = false) }, modifier = Modifier.weight(1f)) { Text("듣기만") }
            OutlinedButton(onClick = { move(1) }, modifier = Modifier.weight(1f)) { Text("다음") }
        }
        val toExplain = ref.ifBlank { heard }
        if (aiReady && toExplain.isNotBlank()) {
            OutlinedButton(
                onClick = {
                    val at = key
                    meaning = "…"
                    scope.launch {
                        val answer = try {
                            Llm.ask(Coach.explainPrompt(toExplain)).replace("*", "").trim()
                        } catch (_: Exception) {
                            "풀이를 만들지 못했어요."
                        }
                        if (key == at) meaning = answer
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("AI에게 뜻과 표현 물어보기") }
        }

        TextButton(onClick = { showPaste = !showPaste }) {
            Text(if (lines.isNotEmpty()) "스크립트 바꾸기" else "스크립트 붙여넣기")
        }
        if (showPaste) {
            Text("유튜브에서 영상 설명란의 '스크립트 표시'를 열어 내용을 복사한 뒤 붙여 넣으세요. 0:05 같은 시간 표시가 함께 있어야 해요.")
            OutlinedTextField(
                value = pasted,
                onValueChange = { pasted = it },
                modifier = Modifier.fillMaxWidth().height(160.dp),
                label = { Text("스크립트") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val parsed = parseScript(pasted)
                    if (parsed.isEmpty()) {
                        error = "시간 표시(예: 0:05)가 있는 스크립트를 찾지 못했어요."
                    } else {
                        stopAll()
                        lines = parsed
                        idx = 0
                        phase = "idle"
                        heard = ""
                        meaning = ""
                        error = ""
                        showPaste = false
                    }
                }) { Text("적용") }
                if (lines.isNotEmpty()) {
                    OutlinedButton(onClick = {
                        stopAll()
                        pos = lines[idx].start
                        lines = emptyList()
                        idx = 0
                        phase = "idle"
                        showPaste = false
                    }) { Text("스크립트 지우기") }
                }
            }
        }
    }
}
