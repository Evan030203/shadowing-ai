package com.shadowai.app

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var hear: Hear
    private val recorder = Recorder()

    /** 다른 앱의 공유 버튼으로 넘어온 것: 유튜브 주소(글) 또는 영상 파일. */
    private val shared = mutableStateOf<Any?>(null)

    /** 앱이 화면에서 사라질 때마다 올라간다. 재생과 듣기를 멈추는 신호로 쓴다. */
    private val stops = mutableIntStateOf(0)

    override fun onStop() {
        stops.intValue++
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hear = Hear(Listener(this), recorder)
        takeShared(intent)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    App(hear, recorder, shared, stops.intValue)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (Llm.isLoaded) KeepAlive.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeShared(intent)
    }

    private fun takeShared(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        @Suppress("DEPRECATION")
        val stream: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
        if (stream != null) shared.value = stream else intent.getStringExtra(Intent.EXTRA_TEXT)?.let { shared.value = it }
    }

    override fun onDestroy() {
        hear.cancel()
        recorder.stop()
        super.onDestroy()
    }
}

/** 로컬 AI 모델의 현재 상태. */
enum class AiStatus { NONE, DOWNLOADING, LOADING, READY, ERROR }

private val Bad = Color(0xFFD23B3B)

/** 주소나 파일을 영상 목록에 넣는다. 유튜브 주소가 아니면 영상 파일 주소로 본다. */
private fun openLink(lib: Library, text: String): VideoItem? {
    val id = youTubeId(text)
    if (id != null) return lib.open("yt:$id", "유튜브 영상", "yt", id)
    val url = Regex("https?://\\S+").find(text)?.value ?: return null
    if (url.contains("youtube.com") || url.contains("youtu.be")) return null
    return lib.open("file:$url", Uri.parse(url).lastPathSegment ?: "영상", "file", url)
}

private fun openFile(ctx: Context, lib: Library, uri: Uri): VideoItem {
    // 앱을 다시 켜도 이 파일을 읽을 수 있게 권한을 유지한다(공유로 받은 파일은 안 될 수 있다).
    try {
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (_: Exception) {
    }
    return lib.open("file:$uri", fileTitle(ctx, uri), "file", uri.toString())
}

@Composable
fun App(hear: Hear, recorder: Recorder, shared: MutableState<Any?>, stopSignal: Int) {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    val lib = remember { Library(ctx) }

    // 다른 앱에 다녀오는 사이 시스템이 앱을 내려도, 보던 영상으로 돌아오도록 저장해 둔다.
    var currentKey by rememberSaveable { mutableStateOf<String?>(null) }
    var current by remember { mutableStateOf(lib.items.firstOrNull { it.key == currentKey }) }
    LaunchedEffect(current) { currentKey = current?.key }
    var note by remember { mutableStateOf("") }

    var ai by remember { mutableStateOf(AiStatus.NONE) }
    var aiNote by remember { mutableStateOf("") }
    var aiProgress by remember { mutableStateOf(0f) }
    var aiRetry by remember { mutableIntStateOf(0) }

    // AI 모델이 올라와 있는 동안에는 앱이 뒤로 가도 꺼지지 않게 붙잡아 둔다.
    LaunchedEffect(ai) { if (ai == AiStatus.READY) KeepAlive.start(ctx) }

    // 다른 앱에서 공유한 영상을 바로 연다.
    LaunchedEffect(shared.value) {
        val got = shared.value ?: return@LaunchedEffect
        shared.value = null
        val item = when (got) {
            is Uri -> openFile(ctx, lib, got)
            is String -> openLink(lib, got)
            else -> null
        }
        if (item == null) note = "공유된 내용에서 영상을 찾지 못했어요." else {
            note = ""
            current = item
        }
    }

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

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val item = current
        if (item != null) {
            key(item.key) {
                if (item.needsSaving) {
                    // 주소로 넣은 영상 파일은 먼저 폰에 저장한다. 다 받으면 아래 영상 화면으로 넘어간다.
                    SaveScreen(item = item, lib = lib, onExit = { current = null })
                } else {
                    VideoScreen(
                        item = item,
                        lib = lib,
                        aiReady = ai == AiStatus.READY,
                        hear = hear,
                        recorder = recorder,
                        stopSignal = stopSignal,
                        onExit = { current = null },
                    )
                }
            }
        } else {
            Home(
                lib = lib,
                ai = ai,
                aiNote = aiNote,
                aiProgress = aiProgress,
                note = note,
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
                onOpen = {
                    note = ""
                    current = it
                },
            )
        }
    }
}

@Composable
private fun Home(
    lib: Library,
    ai: AiStatus,
    aiNote: String,
    aiProgress: Float,
    note: String,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onRetryLoad: () -> Unit,
    onOpen: (VideoItem) -> Unit,
) {
    val ctx = LocalContext.current
    var link by remember { mutableStateOf("") }
    var linkNote by remember { mutableStateOf("") }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onOpen(openFile(ctx, lib, uri))
    }

    Text("쉐도잉 AI", fontSize = 30.sp, fontWeight = FontWeight.Bold)
    Text("영상을 넣으면 AI가 영어가 나오는 부분만 골라 주고, 따라 말한 내 발음을 분석해요.")

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("로컬 AI", fontWeight = FontWeight.Bold)
            when (ai) {
                AiStatus.NONE -> {
                    Text("AI 모델(Gemma 4 E2B, 약 2.6GB)을 받아야 영상 분석과 발음 분석을 쓸 수 있어요. 한 번 받으면 인터넷 없이 폰 안에서만 돌아요.")
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

                AiStatus.READY -> Text(
                    "준비됐어요 · ${Llm.backendName}로 실행 중 · " +
                        if (Llm.hasAudio) "소리 듣기 가능" else "소리 듣기 불가(영상·발음 분석이 제한돼요)"
                )

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

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("영상 넣기", fontWeight = FontWeight.Bold)
            Button(
                onClick = { pickFile.launch(arrayOf("video/*", "audio/*")) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("폰에 있는 영상 고르기") }
            Text("폰에 있는 영상은 AI가 소리를 직접 읽어 미리 분석하고, 인터넷 없이 연습할 수 있어요.")
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                label = { Text("유튜브 주소 또는 영상 파일 주소") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val shown = linkNote.ifEmpty { note }
            if (shown.isNotEmpty()) Text(shown, color = Bad)
            OutlinedButton(
                onClick = {
                    val item = openLink(lib, link)
                    if (item == null) {
                        linkNote = "영상 주소를 찾지 못했어요."
                    } else {
                        linkNote = ""
                        link = ""
                        onOpen(item)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("주소로 열기") }
            Text("영상 파일 주소(mp4 등)는 폰에 저장해서 인터넷 없이 연습해요. 유튜브 영상은 저장되지 않아요.")
            Text("유튜브 앱이나 갤러리의 공유 버튼에서 '쉐도잉 AI'를 골라도 돼요.")
        }
    }

    if (lib.items.isNotEmpty()) {
        Text("내 영상", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        for (item in lib.items.toList()) {
            Card(onClick = { onOpen(item) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            (if (item.isYouTube) "유튜브" else "내 파일") + " · " + when {
                                item.clips.isNotEmpty() && item.done -> "문장 ${item.clips.size}개 분석 완료"
                                item.clips.isNotEmpty() -> "문장 ${item.clips.size}개 · ${clock(item.analyzedTo)}까지 분석"
                                else -> "분석 전"
                            }
                        )
                    }
                    TextButton(onClick = { lib.remove(item) }) { Text("삭제") }
                }
            }
        }
    }
}
