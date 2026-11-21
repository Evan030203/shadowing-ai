package com.shadowai.app

import android.Manifest
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.runtime.MutableState
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var speaker: Speaker
    private lateinit var hear: Hear
    private val recorder = Recorder()

    /** 유튜브 앱의 공유 버튼으로 넘어온 글(영상 주소). */
    private val shared = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        speaker = Speaker(this)
        hear = Hear(Listener(this), recorder)
        takeShared(intent)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    App(speaker, hear, recorder, shared)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeShared(intent)
    }

    private fun takeShared(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { shared.value = it }
        }
    }

    override fun onDestroy() {
        hear.cancel()
        recorder.stop()
        speaker.shutdown()
        super.onDestroy()
    }
}

/** 로컬 AI 모델의 현재 상태. */
enum class AiStatus { NONE, DOWNLOADING, LOADING, READY, ERROR }

private val Good = Color(0xFF1B8A4B)
private val Bad = Color(0xFFD23B3B)

@Composable
fun App(speaker: Speaker, hear: Hear, recorder: Recorder, shared: MutableState<String?>) {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf(if (store.level == 0) "onboard" else "home") }
    var video by remember { mutableStateOf<VideoSource?>(null) }
    var shareNote by remember { mutableStateOf("") }

    // 유튜브 앱에서 공유한 영상을 바로 연다.
    LaunchedEffect(shared.value) {
        val text = shared.value ?: return@LaunchedEffect
        shared.value = null
        val id = youTubeId(text)
        if (id == null) {
            shareNote = "공유된 내용에서 유튜브 영상 주소를 찾지 못했어요."
        } else {
            shareNote = ""
            video = VideoSource.YouTube(id)
            screen = "video"
        }
    }
    var level by remember { mutableIntStateOf(store.level) }
    var sentences by remember { mutableStateOf(listOf<Sentence>()) }
    var fromAi by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(listOf<Int>()) }

    var ai by remember { mutableStateOf(AiStatus.NONE) }
    var aiNote by remember { mutableStateOf("") }
    var aiProgress by remember { mutableStateOf(0f) }
    var aiRetry by remember { mutableIntStateOf(0) }

    // 모델 다운로드와 로딩을 1초마다 살핀다.
    LaunchedEffect(aiRetry) {
        while (true) {
            if (Llm.isLoaded) {
                ai = AiStatus.READY
                break
            }
            val id = store.downloadId
            if (store.modelDone && ModelFiles.file(ctx).exists()) {
                ai = AiStatus.LOADING
                if (Llm.load(ctx, store)) {
                    ai = AiStatus.READY
                } else {
                    ai = AiStatus.ERROR
                    aiNote = "모델을 불러오지 못했어요: ${Llm.lastError}"
                }
                break
            } else if (id != -1L) {
                val p = ModelFiles.query(ctx, id)
                when {
                    p == null -> {
                        store.downloadId = -1L
                        ai = AiStatus.NONE
                        aiNote = "다운로드가 취소됐어요."
                    }
                    p.status == DownloadManager.STATUS_SUCCESSFUL -> {
                        store.modelDone = true
                        store.downloadId = -1L
                    }
                    p.status == DownloadManager.STATUS_FAILED -> {
                        ModelFiles.cancel(ctx, id)
                        store.downloadId = -1L
                        ai = AiStatus.NONE
                        aiNote = "다운로드에 실패했어요. 저장 공간과 와이파이를 확인하고 다시 받아 주세요."
                    }
                    else -> {
                        ai = AiStatus.DOWNLOADING
                        aiProgress = if (p.total > 0) p.done.toFloat() / p.total else 0f
                        aiNote = when (p.status) {
                            DownloadManager.STATUS_PAUSED -> "와이파이 연결을 기다리는 중이에요."
                            DownloadManager.STATUS_PENDING -> "다운로드를 준비하는 중이에요."
                            else -> "%.2f / %.2f GB".format(p.done / 1e9, p.total.coerceAtLeast(0) / 1e9)
                        }
                    }
                }
            } else {
                store.modelDone = false
                ai = AiStatus.NONE
            }
            delay(1000)
        }
    }

    fun startRound() {
        screen = "loading"
        scope.launch {
            var list = emptyList<Sentence>()
            if (ai == AiStatus.READY) {
                try {
                    val raw = Llm.ask(Coach.sentencesPrompt(store.topic, level, store.topWeak(4)))
                    list = Coach.parseSentences(raw)
                } catch (_: Exception) {
                }
            }
            fromAi = list.size >= 3
            sentences = if (fromAi) list else Coach.starter(level)
            screen = "practice"
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (screen) {
            "onboard" -> Onboard { chosen ->
                store.level = chosen
                level = chosen
                screen = "home"
            }

            "home" -> Home(
                store = store,
                level = level,
                ai = ai,
                aiNote = aiNote,
                aiProgress = aiProgress,
                onDownload = {
                    aiNote = ""
                    store.modelDone = false
                    store.downloadId = ModelFiles.start(ctx)
                    aiRetry++
                },
                onCancelDownload = {
                    ModelFiles.cancel(ctx, store.downloadId)
                    store.downloadId = -1L
                    aiNote = ""
                    ai = AiStatus.NONE
                },
                onRetryLoad = {
                    aiNote = ""
                    aiRetry++
                },
                onStart = { startRound() },
                shareNote = shareNote,
                onVideo = {
                    video = it
                    screen = "video"
                },
            )

            "video" -> video?.let { v ->
                VideoPractice(
                    source = v,
                    aiReady = ai == AiStatus.READY,
                    hear = hear,
                    recorder = recorder,
                    onExit = { screen = if (level == 0) "onboard" else "home" },
                )
            }

            "loading" -> Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 120.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator()
                Text(
                    if (ai == AiStatus.READY) "AI가 레벨 ${level}에 맞는 문장을 만들고 있어요…"
                    else "문장을 준비하고 있어요…"
                )
            }

            "practice" -> Practice(
                sentences = sentences,
                fromAi = fromAi,
                level = level,
                simultaneous = store.simultaneous,
                aiReady = ai == AiStatus.READY,
                speaker = speaker,
                hear = hear,
                store = store,
                onFinish = { r ->
                    results = r
                    screen = if (r.isEmpty()) "home" else "result"
                },
            )

            "result" -> Result(
                results = results,
                level = level,
                aiReady = ai == AiStatus.READY,
                store = store,
                onLevel = { level = it },
                onNext = { startRound() },
                onHome = { screen = "home" },
            )
        }
    }
}

@Composable
private fun Onboard(onChoose: (Int) -> Unit) {
    Spacer(Modifier.height(24.dp))
    Text("쉐도잉 AI", fontSize = 30.sp, fontWeight = FontWeight.Bold)
    Text("처음 시작할 난이도를 골라 주세요. 그다음부터는 AI가 따라 말한 결과를 보고 스스로 조절해요.")
    Spacer(Modifier.height(8.dp))
    LevelChoice("초급", "짧고 쉬운 일상 문장, 느린 속도") { onChoose(2) }
    LevelChoice("중급", "자연스러운 대화 문장, 보통 속도") { onChoose(5) }
    LevelChoice("고급", "긴 문장과 빠른 원어민 속도") { onChoose(8) }
}

@Composable
private fun LevelChoice(title: String, desc: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(desc)
        }
    }
}

@Composable
private fun Home(
    store: Store,
    level: Int,
    ai: AiStatus,
    aiNote: String,
    aiProgress: Float,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onRetryLoad: () -> Unit,
    onStart: () -> Unit,
    shareNote: String,
    onVideo: (VideoSource) -> Unit,
) {
    val ctx = LocalContext.current
    var link by remember { mutableStateOf("") }
    var linkNote by remember { mutableStateOf("") }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onVideo(VideoSource.LocalFile(uri))
    }
    var topic by remember { mutableStateOf(store.topic) }
    var simultaneous by remember { mutableStateOf(store.simultaneous) }
    val weak = remember { store.topWeak(6) }

    Text("쉐도잉 AI", fontSize = 30.sp, fontWeight = FontWeight.Bold)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("레벨 $level / 10 · ${Coach.levelName(level)}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(progress = { level / 10f }, modifier = Modifier.fillMaxWidth())
            if (store.lastReason.isNotEmpty()) Text(store.lastReason)
            Text("지금까지 따라 말한 문장: ${store.totalSentences}개")
            if (weak.isNotEmpty()) Text("자주 놓치는 단어: ${weak.joinToString(", ")}")
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("로컬 AI", fontWeight = FontWeight.Bold)
            when (ai) {
                AiStatus.NONE -> {
                    Text("AI 모델(Gemma 4 E2B, 약 2.6GB)을 받으면 내 관심사와 약점에 맞춘 문장, 발음 코칭, 자동 난이도 조절을 인터넷 없이 쓸 수 있어요. 받기 전에는 기본 문장으로 연습해요.")
                    if (aiNote.isNotEmpty()) Text(aiNote, color = Bad)
                    Button(onClick = onDownload) { Text("AI 모델 받기 (와이파이)") }
                }

                AiStatus.DOWNLOADING -> {
                    Text("모델을 받는 중이에요. 앱을 닫아도 계속 받아요.")
                    LinearProgressIndicator(progress = { aiProgress }, modifier = Modifier.fillMaxWidth())
                    Text(aiNote)
                    TextButton(onClick = onCancelDownload) { Text("다운로드 취소") }
                }

                AiStatus.LOADING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(22.dp).height(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("AI를 깨우는 중이에요… (처음엔 1분쯤 걸릴 수 있어요)")
                }

                AiStatus.READY -> Text("준비됐어요 · ${Llm.backendName}로 실행 중 · 인터넷 없이 동작해요")

                AiStatus.ERROR -> {
                    Text(aiNote, color = Bad)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRetryLoad) { Text("다시 시도") }
                        OutlinedButton(onClick = onDownload) { Text("모델 다시 받기") }
                    }
                }
            }
        }
    }

    OutlinedTextField(
        value = topic,
        onValueChange = {
            topic = it
            store.topic = it
        },
        label = { Text("연습하고 싶은 주제 (AI가 이 주제로 문장을 만들어요)") },
        singleLine = true,
        enabled = ai == AiStatus.READY,
        modifier = Modifier.fillMaxWidth(),
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("동시 쉐도잉", fontWeight = FontWeight.Bold)
            Text(
                if (simultaneous) "음성과 동시에 따라 말해요. 이어폰을 꼭 껴 주세요."
                else "문장을 끝까지 들은 다음 따라 말해요."
            )
        }
        Switch(
            checked = simultaneous,
            onCheckedChange = {
                simultaneous = it
                store.simultaneous = it
            },
        )
    }

    Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text("연습 시작 (${Coach.ROUND}문장)", fontSize = 18.sp)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("영상으로 쉐도잉", fontWeight = FontWeight.Bold)
            Text("유튜브 앱에서 공유 → 쉐도잉 AI를 고르거나, 영상 주소를 붙여 넣으세요.")
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                label = { Text("유튜브 영상 주소") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val note = linkNote.ifEmpty { shareNote }
            if (note.isNotEmpty()) Text(note, color = Bad)
            Button(
                onClick = {
                    val id = youTubeId(link)
                    if (id == null) {
                        linkNote = "유튜브 영상 주소가 아니에요."
                    } else {
                        linkNote = ""
                        onVideo(VideoSource.YouTube(id))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("유튜브 영상 열기") }
            Text("폰에 저장된 내 영상·음성 파일은 인터넷 없이 연습할 수 있어요.")
            OutlinedButton(
                onClick = { pickFile.launch(arrayOf("video/*", "audio/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("내 파일로 연습 (오프라인)") }
        }
    }
}

@Composable
private fun Practice(
    sentences: List<Sentence>,
    fromAi: Boolean,
    level: Int,
    simultaneous: Boolean,
    aiReady: Boolean,
    speaker: Speaker,
    hear: Hear,
    store: Store,
    onFinish: (List<Int>) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val results = remember { mutableStateListOf<Int>() }

    var idx by remember { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf("idle") } // idle, speaking, listening, scored
    var heard by remember { mutableStateOf("") }
    var score by remember { mutableStateOf<Score?>(null) }
    var best by remember { mutableIntStateOf(-1) }
    var tip by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var showKo by remember { mutableStateOf(false) }

    val s = sentences[idx]
    val rate = Coach.speechRate(level)

    fun stopAll() {
        speaker.stop()
        hear.cancel()
    }

    fun scored(text: String) {
        val sc = scoreShadow(s.en, text)
        score = sc
        heard = text
        phase = "scored"
        if (sc.accuracy > best) best = sc.accuracy
        store.record(sc)
        tip = ""
        if (aiReady && sc.missed.isNotEmpty()) {
            val at = idx
            tip = "…"
            scope.launch {
                val answer = try {
                    Llm.ask(Coach.feedbackPrompt(s.en, text, sc.missed)).replace("*", "").trim()
                } catch (_: Exception) {
                    ""
                }
                if (idx == at) tip = answer
            }
        }
    }

    fun listen() {
        phase = "listening"
        heard = ""
        hear.start(
            onPartial = { heard = it },
            onResult = { scored(it) },
            fail = {
                error = it
                phase = "idle"
                speaker.stop()
            },
        )
    }

    fun begin() {
        error = ""
        score = null
        tip = ""
        if (simultaneous) {
            listen()
            speaker.speak(s.en, rate) {}
        } else {
            phase = "speaking"
            speaker.speak(s.en, rate) { if (phase == "speaking") listen() }
        }
    }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) begin() else error = "마이크 권한을 허용해야 따라 말한 걸 들을 수 있어요."
    }

    fun beginWithPermission() {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
        else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun next() {
        stopAll()
        if (best >= 0) results.add(best)
        if (idx + 1 >= sentences.size) {
            onFinish(results.toList())
        } else {
            idx += 1
            phase = "idle"
            heard = ""
            score = null
            best = -1
            tip = ""
            error = ""
            showKo = false
        }
    }

    BackHandler {
        stopAll()
        onFinish(emptyList())
    }
    DisposableEffect(Unit) { onDispose { stopAll() } }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${idx + 1} / ${sentences.size}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(if (fromAi) "AI가 만든 문장 · 레벨 $level" else "기본 문장 · 레벨 $level")
    }
    LinearProgressIndicator(progress = { (idx + 1f) / sentences.size }, modifier = Modifier.fillMaxWidth())

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val sc = score
            Text(
                buildAnnotatedString {
                    if (sc == null) {
                        append(s.en)
                    } else {
                        sc.words.forEachIndexed { i, w ->
                            if (i > 0) append(" ")
                            withStyle(SpanStyle(color = if (w.ok) Good else Bad, fontWeight = FontWeight.Bold)) {
                                append(w.word)
                            }
                        }
                    }
                },
                fontSize = 24.sp,
                lineHeight = 34.sp,
            )
            if (showKo) Text(s.ko) else TextButton(onClick = { showKo = true }) { Text("뜻 보기") }
        }
    }

    when (phase) {
        "speaking" -> Text("잘 들어 보세요…", fontSize = 18.sp)
        "listening" -> {
            Text(if (simultaneous) "음성에 맞춰 따라 말하세요" else "이제 따라 말하세요", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (heard.isNotEmpty()) Text(heard)
            OutlinedButton(onClick = { hear.finishSpeaking() }) { Text("다 말했어요") }
        }
    }

    score?.let { sc ->
        Text("정확도 ${sc.accuracy}%", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = if (sc.accuracy >= 75) Good else Bad)
        Text("들린 말: $heard")
        if (tip.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("AI 코치", fontWeight = FontWeight.Bold)
                    Text(if (tip == "…") "발음을 분석하고 있어요…" else tip)
                }
            }
        }
    }

    if (error.isNotEmpty()) Text(error, color = Bad)

    if (phase == "idle" || phase == "scored") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { speaker.speak(s.en, rate) {} }, modifier = Modifier.weight(1f)) {
                Text("듣기만")
            }
            OutlinedButton(onClick = { speaker.speak(s.en, rate * 0.75f) {} }, modifier = Modifier.weight(1f)) {
                Text("천천히")
            }
        }
        Button(onClick = { beginWithPermission() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(if (phase == "scored") "다시 쉐도잉" else "쉐도잉 시작", fontSize = 18.sp)
        }
        OutlinedButton(onClick = { next() }, modifier = Modifier.fillMaxWidth()) {
            Text(
                when {
                    idx + 1 >= sentences.size -> "결과 보기"
                    phase == "scored" -> "다음 문장"
                    else -> "건너뛰기"
                }
            )
        }
    }
}

@Composable
private fun Result(
    results: List<Int>,
    level: Int,
    aiReady: Boolean,
    store: Store,
    onLevel: (Int) -> Unit,
    onNext: () -> Unit,
    onHome: () -> Unit,
) {
    val average = remember { results.average().toInt() }
    val before = remember { level }
    var after by remember { mutableIntStateOf(-1) }
    var reason by remember { mutableStateOf("") }
    var byAi by remember { mutableStateOf(false) }

    // 라운드가 끝나면 AI가 결과를 보고 다음 난이도를 정한다.
    LaunchedEffect(Unit) {
        var decided = Coach.ruleLevel(before, average)
        if (aiReady) {
            try {
                val raw = Llm.ask(Coach.adaptPrompt(before, results, store.topWeak(5)))
                Coach.parseLevel(raw, before)?.let {
                    decided = if (it.second.isEmpty()) it.first to decided.second else it
                    byAi = true
                }
            } catch (_: Exception) {
            }
        }
        store.level = decided.first
        store.lastReason = decided.second
        reason = decided.second
        after = decided.first
        onLevel(decided.first)
    }

    BackHandler { onHome() }

    Text("라운드 결과", fontSize = 26.sp, fontWeight = FontWeight.Bold)
    Text("평균 정확도 $average%", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = if (average >= 75) Good else Bad)
    Text("문장별: ${results.joinToString(" · ") { "$it%" }}")

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (after == -1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(22.dp).height(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("AI가 다음 난이도를 정하고 있어요…")
                }
            } else {
                Text(if (byAi) "AI의 판단" else "난이도 조절", fontWeight = FontWeight.Bold)
                Text(
                    when {
                        after > before -> "레벨 $before → $after 로 올렸어요"
                        after < before -> "레벨 $before → $after 로 내렸어요"
                        else -> "레벨 $after 유지"
                    },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(reason)
            }
        }
    }

    if (after != -1) {
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("다음 라운드", fontSize = 18.sp)
        }
        OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("홈으로") }
    }
}
