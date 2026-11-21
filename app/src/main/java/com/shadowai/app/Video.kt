package com.shadowai.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

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
    val view: View
    val durationSec: Double

    /** 지금 재생 중인 위치(초). */
    val positionSec: Double

    /** 멈춘 상태에서 그 위치의 장면을 화면에 보여 준다(지원하는 재생기만). */
    fun show(sec: Double) {}
    fun play(start: Double, end: Double, rate: Float, onEnd: () -> Unit)
    fun stop()
    fun release()
}

/** 유튜브 공식 임베드 플레이어(IFrame API)를 웹뷰에 띄운다. */
@SuppressLint("SetJavaScriptEnabled")
class YouTubeClipPlayer(
    ctx: Context,
    videoId: String,
    private val onTitle: (String) -> Unit,
    private val fail: (String) -> Unit,
) : ClipPlayer {
    private val main = Handler(Looper.getMainLooper())
    private var onEnd: (() -> Unit)? = null
    override var durationSec: Double = 0.0
        private set

    @Volatile
    override var positionSec: Double = 0.0
        private set

    override val view: WebView = WebView(ctx).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        webChromeClient = WebChromeClient()
        // 웹뷰는 높이 설정이 '내용에 맞춤'이면 페이지에 화면 높이를 0으로 알려 준다.
        // 그러면 플레이어 높이가 0이 되어 소리만 나고 화면은 검게 보이므로, 반드시 '부모에 맞춤'으로 둔다.
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        setBackgroundColor(android.graphics.Color.BLACK)
        addJavascriptInterface(Bridge(), "App")
        // 유튜브 임베드는 요청한 쪽의 주소(Referer)가 있어야 재생된다.
        loadDataWithBaseURL(ORIGIN + "/", PAGE.replace("VIDEO_ID", videoId).replace("ORIGIN_URL", ORIGIN), "text/html", "utf-8", null)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onReady(duration: Double, title: String) {
            main.post {
                durationSec = duration
                if (title.isNotBlank()) onTitle(title)
            }
        }

        @JavascriptInterface
        fun onTime(t: Double) {
            positionSec = t
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
                        -1 -> "유튜브 플레이어를 불러오지 못했어요. 인터넷 연결을 확인해 주세요."
                        -3 -> "영상을 불러오는 데 오래 걸리고 있어요. 인터넷 연결을 확인해 주세요."
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
<style>html,body{margin:0;height:100%;background:#000}#p{position:absolute;left:0;top:0;width:100vw;height:56.25vw}
#msg{position:absolute;left:0;top:calc(28vw - 10px);width:100%;text-align:center;color:#bbb;font:14px sans-serif}</style>
</head><body><div id="msg">영상을 불러오는 중…</div><div id="p"></div>
<script>
var player, endAt = -1, timer = null, ready = false, pending = null, began = 0;
var tag = document.createElement('script');
tag.src = "https://www.youtube.com/iframe_api";
tag.onerror = function () { App.onError(-1); };
document.head.appendChild(tag);
setTimeout(function () { if (!ready) App.onError(-3); }, 20000);
function onYouTubeIframeAPIReady() {
  player = new YT.Player('p', {
    width: '100%', height: '100%', videoId: 'VIDEO_ID',
    playerVars: { playsinline: 1, rel: 0, cc_load_policy: 1, cc_lang_pref: 'en', origin: 'ORIGIN_URL' },
    events: {
      onReady: function () {
        ready = true;
        var d = player.getVideoData ? player.getVideoData() : null;
        App.onReady(player.getDuration(), d && d.title ? d.title : '');
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
setInterval(function () { if (ready && player.getPlayerState() === 1) App.onTime(player.getCurrentTime()); }, 250);
function stopSeg() { clearInterval(timer); pending = null; if (ready) player.pauseVideo(); }
</script></body></html>
""".trimIndent()
    }
}

/**
 * 폰에 있는 내 영상·음성 파일을 화면과 함께 구간별로 재생한다. 인터넷이 필요 없다.
 * 스크롤되는 화면 안에서는 SurfaceView(VideoView)가 검게만 나오는 일이 있어, 일반 뷰처럼 그려지는 TextureView를 쓴다.
 */
class FileClipPlayer(ctx: Context, private val uri: Uri, private val fail: (String) -> Unit) : ClipPlayer {
    private val appCtx = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var mp: MediaPlayer? = null
    private var surface: Surface? = null
    private var prepared = false
    private var seeking = false
    private var completed = false
    private var tick: Runnable? = null
    private var pending: (() -> Unit)? = null
    private var videoW = 0
    private var videoH = 0

    /** 마지막으로 보여 준 위치. 화면이 다시 만들어지면 여기를 다시 보여 준다. */
    private var lastSec = 2.0

    override var durationSec: Double = 0.0
        private set

    override val positionSec: Double
        get() = try {
            if (prepared) (mp?.currentPosition ?: 0) / 1000.0 else 0.0
        } catch (_: Exception) {
            0.0
        }

    override val view: TextureView = TextureView(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        isOpaque = false
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                open(Surface(texture))
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                fit()
            }

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                close()
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
        }
    }

    /** 영상의 가로세로 비율을 지켜 화면 가운데에 맞춘다. */
    private fun fit() {
        val vw = view.width.toFloat()
        val vh = view.height.toFloat()
        if (videoW <= 0 || videoH <= 0 || vw <= 0f || vh <= 0f) return
        val scale = minOf(vw / videoW, vh / videoH)
        val matrix = Matrix()
        matrix.setScale(videoW * scale / vw, videoH * scale / vh, vw / 2f, vh / 2f)
        view.setTransform(matrix)
    }

    private fun open(s: Surface) {
        close()
        surface = s
        try {
            val m = MediaPlayer()
            mp = m
            m.setSurface(s)
            m.setOnVideoSizeChangedListener { _, w, h ->
                videoW = w
                videoH = h
                fit()
            }
            m.setOnSeekCompleteListener { seeking = false }
            m.setOnCompletionListener { completed = true }
            m.setOnPreparedListener {
                if (mp === m) {
                    prepared = true
                    durationSec = m.duration / 1000.0
                    val p = pending
                    pending = null
                    if (p != null) p() else show(lastSec)
                }
            }
            m.setOnErrorListener { _, _, _ ->
                prepared = false
                fail("이 파일은 재생할 수 없어요. 다른 영상이나 음성 파일을 골라 주세요.")
                true
            }
            m.setDataSource(appCtx, uri)
            m.prepareAsync()
        } catch (_: Exception) {
            fail("이 파일을 열 수 없어요. 다른 영상이나 음성 파일을 골라 주세요.")
        }
    }

    /** 화면이 사라질 때(다른 앱으로 갈 때 포함) 재생기를 정리한다. 돌아오면 open()이 다시 불린다. */
    private fun close() {
        val interrupted = tick != null
        prepared = false
        tick?.let { main.removeCallbacks(it) }
        tick = null
        try {
            mp?.release()
        } catch (_: Exception) {
        }
        mp = null
        surface?.release()
        surface = null
        // 재생 도중에 끊겼으면 화면이 '듣는 중'에 멈춰 있지 않게 알린다.
        if (interrupted) fail("")
    }

    /** 멈춘 상태에서 그 위치의 장면을 보여 준다. 소리 없이 잠깐 재생했다 멈춰야 그림이 나온다. */
    override fun show(sec: Double) {
        lastSec = sec
        val m = mp ?: return
        if (!prepared || tick != null) return
        try {
            m.setVolume(0f, 0f)
            seeking = true
            m.seekTo((sec * 1000).toLong(), MediaPlayer.SEEK_CLOSEST)
            m.start()
            main.postDelayed({
                try {
                    if (mp === m && tick == null) {
                        m.pause()
                        m.setVolume(1f, 1f)
                    }
                } catch (_: Exception) {
                }
            }, 300)
        } catch (_: Exception) {
        }
    }

    override fun play(start: Double, end: Double, rate: Float, onEnd: () -> Unit) {
        stop()
        lastSec = start
        val m = mp
        if (m == null || !prepared) {
            pending = { play(start, end, rate, onEnd) }
            return
        }
        try {
            m.setVolume(1f, 1f)
            seeking = true
            completed = false
            m.seekTo((start * 1000).toLong(), MediaPlayer.SEEK_CLOSEST)
            m.start()
            m.playbackParams = m.playbackParams.setSpeed(rate)
        } catch (_: Exception) {
            fail("이 구간을 재생하지 못했어요.")
            return
        }
        val began = System.currentTimeMillis()
        val r = object : Runnable {
            override fun run() {
                if (mp !== m) {
                    tick = null
                    return
                }
                val waited = System.currentTimeMillis() - began
                val playing = try {
                    m.isPlaying
                } catch (_: Exception) {
                    false
                }
                val pos = try {
                    m.currentPosition
                } catch (_: Exception) {
                    Int.MAX_VALUE
                }
                // 위치 이동이 끝나기 전에는 예전 위치가 읽히므로, 이동이 끝난 뒤에만 끝 지점을 본다.
                val moved = !seeking || waited > 3000
                val reached = moved && pos >= end * 1000
                val stalled = !playing && waited > 3000
                if (reached || completed || stalled) {
                    try {
                        if (playing) m.pause()
                    } catch (_: Exception) {
                    }
                    tick = null
                    onEnd()
                } else {
                    main.postDelayed(this, 60)
                }
            }
        }
        tick = r
        main.postDelayed(r, 200)
    }

    override fun stop() {
        pending = null
        tick?.let { main.removeCallbacks(it) }
        tick = null
        try {
            val m = mp
            if (m != null && prepared && m.isPlaying) m.pause()
        } catch (_: Exception) {
        }
    }

    override fun release() {
        stop()
        close()
    }
}

private val Good = Color(0xFF1B8A4B)
private val Bad = Color(0xFFD23B3B)

/** 영상 하나를 다루는 화면: 먼저 AI가 고른 장면 목록을 보여 주고, 장면을 누르면 쉐도잉을 시작한다. */
@Composable
fun VideoScreen(
    item: VideoItem,
    lib: Library,
    store: Store,
    aiReady: Boolean,
    hear: Hear,
    recorder: Recorder,
    stopSignal: Int,
    onExit: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val canHear = aiReady && Llm.hasAudio

    var error by remember { mutableStateOf("") }
    // 다른 앱에 다녀와도 보던 장면과 문장으로 돌아오도록 저장해 둔다.
    var sceneIdx by rememberSaveable { mutableStateOf<Int?>(null) }
    var clipIdx by rememberSaveable { mutableIntStateOf(0) }
    var live by rememberSaveable { mutableStateOf(false) }
    var fitFirst by rememberSaveable { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var myLevel by remember { mutableIntStateOf(store.level) }

    // 학습 중 상태
    var phase by remember { mutableStateOf("idle") } // idle, playing, listening, analyzing, done
    var heard by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf("") }
    var meaning by remember { mutableStateOf("") }
    var rate by remember { mutableFloatStateOf(1f) }
    var byRecorder by remember { mutableStateOf(false) }
    var aiListening by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val aiRef = remember { mutableStateMapOf<Int, String>() }

    val player: ClipPlayer? = remember(item.key) {
        try {
            if (item.isYouTube) {
                YouTubeClipPlayer(
                    ctx,
                    item.src,
                    onTitle = {
                        if (item.title != it) {
                            item.title = it
                            lib.save()
                        }
                    },
                    fail = {
                        error = it
                        phase = "idle"
                    },
                )
            } else {
                FileClipPlayer(ctx, Uri.parse(item.src)) {
                    error = it
                    phase = "idle"
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    val scenes = remember(item.clips.toList()) { groupScenes(item.clips.toList()) }
    val liveClips = remember { List(1200) { Clip(it * 6.0, it * 6.0 + 6.0, "", 0) } }
    val studying = live || sceneIdx != null
    val cur: List<Clip> = when {
        live -> liveClips
        sceneIdx != null -> scenes.getOrNull(sceneIdx ?: 0)?.clips ?: emptyList()
        else -> emptyList()
    }
    val clip = cur.getOrNull(clipIdx)
    val ref = clip?.let { it.text.ifBlank { aiRef[clipIdx].orEmpty() } }.orEmpty()
    val score = remember(ref, heard, phase) {
        if (phase == "done" && ref.isNotBlank() && heard.isNotBlank()) scoreShadow(ref, heard) else null
    }

    fun micGranted() =
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun stopAll() {
        attempt++
        player?.stop()
        hear.cancel()
        recorder.stop()
    }

    fun resetLine() {
        stopAll()
        phase = "idle"
        heard = ""
        feedback = ""
        meaning = ""
        error = ""
    }

    /** 채점 결과를 기록하고, 문장 난이도와 정확도를 보고 내 수준을 조금씩 조절한다. */
    fun record(sc: Score, c: Clip) {
        store.record(sc)
        if (c.level > 0) {
            if (sc.accuracy >= 90 && c.level >= myLevel) myLevel = (myLevel + 1).coerceAtMost(10)
            if (sc.accuracy < 60 && c.level <= myLevel) myLevel = (myLevel - 1).coerceAtLeast(1)
            store.level = myLevel
        }
    }

    /** 녹음을 끝내고 AI가 내 말을 받아쓴 뒤, 녹음을 직접 듣고 발음을 봐 준다. */
    fun finishRecording() {
        val c = clip ?: return
        val wav = recorder.stop()
        if (wav.isEmpty()) {
            error = "녹음이 너무 짧아요. 다시 해 볼까요?"
            phase = "idle"
            return
        }
        phase = "analyzing"
        val my = attempt
        scope.launch {
            val text = try {
                Llm.transcribe(wav)
            } catch (_: Exception) {
                ""
            }
            if (my != attempt) return@launch
            if (text.isBlank()) {
                error = "목소리를 알아듣지 못했어요. 조금 더 크게 다시 해 볼까요?"
                phase = "idle"
                return@launch
            }
            heard = text
            phase = "done"
            val target = c.text.ifBlank { aiRef[clipIdx].orEmpty() }
            if (target.isBlank()) return@launch
            val sc = scoreShadow(target, text)
            record(sc, c)
            feedback = "…"
            val answer = try {
                Llm.askAudio(wav, Coach.pronunciationPrompt(target, text, sc.missed)).replace("*", "").trim()
            } catch (_: Exception) {
                ""
            }
            if (my == attempt) feedback = answer
        }
    }

    fun listen() {
        val c = clip ?: return
        phase = "listening"
        heard = ""
        if (canHear && recorder.start()) {
            byRecorder = true
            return
        }
        byRecorder = false
        val my = attempt
        hear.start(
            onPartial = { heard = it },
            onResult = { text ->
                heard = text
                phase = "done"
                val target = c.text.ifBlank { aiRef[clipIdx].orEmpty() }
                if (target.isNotBlank()) {
                    val sc = scoreShadow(target, text)
                    record(sc, c)
                    if (aiReady && sc.missed.isNotEmpty()) {
                        feedback = "…"
                        scope.launch {
                            val answer = try {
                                Llm.ask(Coach.feedbackPrompt(target, text, sc.missed)).replace("*", "").trim()
                            } catch (_: Exception) {
                                ""
                            }
                            if (my == attempt) feedback = answer
                        }
                    }
                }
            },
            fail = {
                error = it
                heard = ""
                phase = "idle"
            },
        )
    }

    /** 구간을 재생한다. 문장을 아직 모르면 재생되는 소리를 마이크로 듣고 AI가 받아쓴다. */
    fun play(thenListen: Boolean) {
        val p = player ?: return
        val c = clip ?: return
        stopAll()
        error = ""
        if (thenListen) {
            heard = ""
            feedback = ""
        }
        val at = clipIdx
        val capture = c.text.isBlank() && canHear && aiRef[at] == null && micGranted() && recorder.start()
        phase = "playing"
        p.play(c.start, c.end, rate) {
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
                        if (text.isNotBlank()) aiRef[at] = text
                        aiListening = false
                    }
                }
            }
            if (thenListen) listen() else phase = "idle"
        }
    }

    fun backToList() {
        resetLine()
        sceneIdx = null
        live = false
        clipIdx = 0
        aiRef.clear()
    }

    fun move(delta: Int) {
        resetLine()
        val next = clipIdx + delta
        when {
            next < 0 -> clipIdx = 0
            next < cur.size && (!live || player == null || player.durationSec <= 0 || cur[next].start < player.durationSec) -> clipIdx = next
            !live && sceneIdx != null && (sceneIdx ?: 0) + 1 < scenes.size -> {
                sceneIdx = (sceneIdx ?: 0) + 1
                clipIdx = 0
            }
            else -> backToList()
        }
    }

    fun startListeningAnalysis() {
        resetLine()
        Analyzer.analyzeByListening(scope, item, lib, player ?: return, recorder)
    }

    val askMicForAnalysis = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startListeningAnalysis() else error = "마이크 권한을 허용해야 AI가 영상 소리를 들을 수 있어요."
    }

    // 분석하는 동안에는 화면이 꺼지지 않게 한다.
    LaunchedEffect(Analyzer.running) { player?.view?.keepScreenOn = Analyzer.running }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) play(thenListen = true) else error = "마이크 권한을 허용해야 따라 말한 걸 들을 수 있어요."
    }

    // 말을 멈추면 알아서 녹음을 끝낸다.
    LaunchedEffect(phase, byRecorder) {
        if (phase == "listening" && byRecorder) {
            while (true) {
                delay(200)
                if (recorder.finishedSpeaking()) {
                    finishRecording()
                    break
                }
            }
        }
    }

    // 공부를 시작하기 전에 영상을 미리 분석한다. 공부하는 동안에는 멈춰 둔다.
    LaunchedEffect(studying) { Analyzer.paused = studying }

    // 문장을 옮기면 그 문장이 나오는 장면을 미리 보여 준다.
    LaunchedEffect(sceneIdx, clipIdx, live) {
        val shown = clip
        if (shown != null && phase == "idle") player?.show(shown.start)
    }

    // 다른 앱으로 가면 재생과 듣기를 멈춘다. 마이크는 앱이 화면에 있을 때만 쓸 수 있다.
    LaunchedEffect(stopSignal) {
        if (stopSignal > 0) {
            if (Analyzer.listening) Analyzer.stop()
            if (phase == "playing" || phase == "listening") resetLine() else player?.stop()
        }
    }
    LaunchedEffect(item.key, canHear) {
        if (!item.isYouTube && canHear && !item.done) Analyzer.analyzeFile(scope, ctx.applicationContext, item, lib)
    }

    BackHandler {
        if (studying) backToList() else {
            stopAll()
            onExit()
        }
    }
    DisposableEffect(item.key) {
        onDispose {
            Analyzer.stop()
            Analyzer.paused = false
            hear.cancel()
            recorder.stop()
            player?.release()
        }
    }

    Row {
        Text(
            item.title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = {
            if (studying) backToList() else {
                stopAll()
                onExit()
            }
        }) { Text(if (studying) "장면 목록" else "나가기") }
    }

    if (player == null) {
        Text("이 영상을 열 수 없어요. 다른 영상을 골라 주세요.", color = Bad)
        return
    }

    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
        AndroidView(factory = { player.view }, modifier = Modifier.fillMaxSize())
    }

    if (error.isNotEmpty()) Text(error, color = Bad)

    if (!studying) {
        // ───── 장면 목록 ─────
        if (Analyzer.running || Analyzer.note.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(Analyzer.note.ifEmpty { "분석 중이에요…" }, fontWeight = FontWeight.Bold)
                    if (Analyzer.running) Text("찾은 장면부터 바로 연습할 수 있어요. 연습하는 동안에는 분석을 잠시 멈춰요.")
                }
            }
        }

        if (scenes.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "영어 대화 장면 ${scenes.size}개 · 내 수준 $myLevel/10",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
            }
            FilterChip(
                selected = fitFirst,
                onClick = { fitFirst = !fitFirst },
                label = { Text("내 수준에 맞는 장면 먼저") },
            )
            val order = if (fitFirst) {
                scenes.indices.sortedBy { if (scenes[it].level == 0) 99 else abs(scenes[it].level - myLevel) }
            } else {
                scenes.indices.toList()
            }
            for (i in order) {
                val s = scenes[i]
                val fits = s.level > 0 && abs(s.level - myLevel) <= 1
                Card(
                    onClick = {
                        if (Analyzer.listening) Analyzer.stop()
                        resetLine()
                        sceneIdx = i
                        clipIdx = 0
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "${clock(s.start)} ~ ${clock(s.end)} · ${s.clips.size}문장 · ${Coach.levelName(s.level)}" +
                                (if (fits) " · 추천" else ""),
                            fontWeight = FontWeight.Bold,
                            color = if (fits) Good else Color.Unspecified,
                        )
                        Text(s.clips.joinToString(" ") { it.text }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        } else if (!Analyzer.running) {
            if (item.isYouTube) {
                Text("아직 분석하지 않은 영상이에요. 아래에서 영상을 재생하며 AI가 듣게 하거나, 스크립트를 붙여 넣어 분석해 주세요.")
            } else if (!canHear) {
                Text("내 파일을 미리 분석하려면 홈 화면에서 AI 모델을 먼저 받아 주세요.")
            } else if (item.done) {
                Text("이 영상에서는 따라 말할 영어 대사를 찾지 못했어요.")
            }
        }

        if (item.isYouTube) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("영상을 재생하며 미리 분석하기", fontWeight = FontWeight.Bold)
                    if (Analyzer.listening) {
                        Text("영상이 재생되는 동안 AI가 듣고 있어요. 폰을 조용한 곳에 두고 소리를 끄지 마세요. 멈춰도 찾은 장면은 남고, 나중에 이어서 할 수 있어요.")
                        Button(onClick = { Analyzer.stop() }) { Text("분석 멈추기") }
                    } else if (!canHear) {
                        Text("홈 화면에서 AI 모델을 받으면 쓸 수 있어요.")
                    } else if (item.done) {
                        Text("영상을 끝까지 분석했어요.")
                    } else {
                        Text("공부를 시작하기 전에 영상을 스피커로 틀어 두면 AI가 들으면서 영어 대화 장면을 골라요. 이어폰은 빼고 소리를 충분히 키워 주세요. 영상 길이만큼 걸려요.")
                        Button(
                            onClick = { if (micGranted()) startListeningAnalysis() else askMicForAnalysis.launch(Manifest.permission.RECORD_AUDIO) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (item.analyzedTo > 0) "${clock(item.analyzedTo)}부터 이어서 분석하기" else "재생하며 분석 시작")
                        }
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (scenes.isEmpty()) "스크립트로 미리 분석하기" else "스크립트 다시 넣기", fontWeight = FontWeight.Bold)
                    Text("유튜브에서 영상 설명란 → '스크립트 표시'의 내용을 복사해 붙여 넣으면, AI가 공부하기 좋은 장면을 골라 줘요.")
                    OutlinedTextField(
                        value = pasted,
                        onValueChange = { pasted = it },
                        modifier = Modifier.fillMaxWidth().height(130.dp),
                        label = { Text("스크립트 (0:05 같은 시간 표시 포함)") },
                    )
                    Button(onClick = {
                        val lines = parseScript(pasted)
                        if (lines.isEmpty()) {
                            error = "시간 표시(예: 0:05)가 있는 스크립트를 찾지 못했어요."
                        } else {
                            error = ""
                            resetLine()
                            Analyzer.analyzeScript(scope, item, lib, lines, aiReady)
                        }
                    }) { Text("AI로 분석하기") }
                }
            }
        }

        OutlinedButton(
            onClick = {
                if (Analyzer.listening) Analyzer.stop()
                resetLine()
                live = true
                clipIdx = 0
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("분석 없이 처음부터 6초씩 연습하기") }
        if (canHear) Text("분석 없이 연습할 때는 구간을 스피커로 재생하면 AI가 그 소리를 듣고 받아써요.")
        return
    }

    // ───── 쉐도잉 ─────
    val c = clip ?: return
    Text(
        (if (live) "구간 ${clock(c.start)} ~ ${clock(c.end)}" else "문장 ${clipIdx + 1} / ${cur.size} · ${clock(c.start)}") +
            (if (c.level > 0) " · 난이도 ${c.level}/10" else ""),
        fontWeight = FontWeight.Bold,
    )

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                ref.isNotBlank() -> {
                    if (c.text.isBlank()) Text("AI가 받아쓴 문장 (틀릴 수 있어요)", fontWeight = FontWeight.Bold)
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
                canHear -> Text("구간을 스피커로 재생하면 AI가 듣고 문장을 받아써요. 이어폰을 끼면 AI가 듣지 못해요.")
                else -> Text("이 구간의 문장을 몰라요. 영상의 자막을 보며 따라 말해 보세요.")
            }
        }
    }

    when (phase) {
        "playing" -> Text("잘 들어 보세요…", fontSize = 18.sp)
        "listening" -> {
            Text("이제 따라 말하세요", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (byRecorder) Text("AI가 듣고 있어요. 말을 멈추면 알아서 분석해요.") else if (heard.isNotEmpty()) Text(heard)
            OutlinedButton(onClick = { if (byRecorder) finishRecording() else hear.finishSpeaking() }) { Text("다 말했어요") }
        }
        "analyzing" -> Text("AI가 내 말을 분석하고 있어요…", fontSize = 18.sp)
        "done" -> {
            if (score != null) {
                Text(
                    "정확도 ${score.accuracy}%",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (score.accuracy >= 75) Good else Bad,
                )
            } else if (aiListening) {
                Text("AI가 영상 문장을 다 받아쓰면 채점해 드려요…")
            }
            Text("내가 말한 문장: $heard")
        }
    }

    if (feedback.isNotEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (byRecorder) "AI 발음 코치 (내 녹음을 듣고)" else "AI 코치", fontWeight = FontWeight.Bold)
                Text(if (feedback == "…") "내 발음을 분석하고 있어요…" else feedback)
            }
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

    if (phase == "idle" || phase == "done") {
        Button(
            onClick = { if (micGranted()) play(thenListen = true) else askMic.launch(Manifest.permission.RECORD_AUDIO) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text(if (phase == "done") "이 문장 다시 쉐도잉" else "듣고 따라 말하기", fontSize = 18.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { move(-1) }, modifier = Modifier.weight(1f)) { Text("이전") }
            OutlinedButton(onClick = { play(thenListen = false) }, modifier = Modifier.weight(1f)) { Text("듣기만") }
            OutlinedButton(onClick = { move(1) }, modifier = Modifier.weight(1f)) { Text("다음") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = rate < 1f,
                onClick = { rate = if (rate < 1f) 1f else 0.75f },
                label = { Text("느리게 재생") },
            )
        }
        val toExplain = ref.ifBlank { heard }
        if (aiReady && toExplain.isNotBlank()) {
            OutlinedButton(
                onClick = {
                    val my = attempt
                    meaning = "…"
                    scope.launch {
                        val answer = try {
                            Llm.ask(Coach.explainPrompt(toExplain)).replace("*", "").trim()
                        } catch (_: Exception) {
                            "풀이를 만들지 못했어요."
                        }
                        if (my == attempt) meaning = answer
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("AI에게 뜻과 표현 물어보기") }
        }
    }
}
