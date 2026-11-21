package com.shadowai.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** 따라 말할 한 문장: 영상의 어느 구간인지, 무슨 말인지, 얼마나 어려운지(1~10, 0은 아직 모름). */
data class Clip(val start: Double, val end: Double, val text: String, val level: Int)

/** 이어지는 문장들을 묶은 한 장면. */
class Scene(val clips: List<Clip>) {
    val start: Double get() = clips.first().start
    val end: Double get() = clips.last().end
    val level: Int
        get() {
            val rated = clips.filter { it.level > 0 }
            return if (rated.isEmpty()) 0 else rated.sumOf { it.level } / rated.size
        }
}

/** 말이 끊기지 않고 이어지는 문장끼리 한 장면으로 묶는다. 한 장면은 1분을 넘지 않게 한다. */
fun groupScenes(clips: List<Clip>): List<Scene> {
    val scenes = mutableListOf<Scene>()
    var cur = mutableListOf<Clip>()
    for (c in clips.sortedBy { it.start }) {
        if (cur.isNotEmpty() && (c.start - cur.last().end > 4.0 || c.end - cur.first().start > 60.0)) {
            scenes.add(Scene(cur))
            cur = mutableListOf()
        }
        cur.add(c)
    }
    if (cur.isNotEmpty()) scenes.add(Scene(cur))
    return scenes
}

/** 사용자가 넣은 영상 하나와 그 분석 결과. */
class VideoItem(val key: String, title: String, val kind: String, val src: String) {
    var title by mutableStateOf(title)
    val clips = mutableStateListOf<Clip>()
    var analyzedTo by mutableDoubleStateOf(0.0)
    var done by mutableStateOf(false)
    val isYouTube: Boolean get() = kind == "yt"
}

/** 넣었던 영상 목록과 분석 결과를 폰에 저장해 두어, 다시 열 때 분석을 반복하지 않는다. */
class Library(ctx: Context) {
    private val file = File(ctx.filesDir, "library.json")
    val items = mutableStateListOf<VideoItem>()

    init {
        try {
            if (file.exists()) {
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val item = VideoItem(o.getString("key"), o.getString("title"), o.getString("kind"), o.getString("src"))
                    item.analyzedTo = o.optDouble("to", 0.0)
                    item.done = o.optBoolean("done", false)
                    val cs = o.optJSONArray("clips") ?: JSONArray()
                    for (j in 0 until cs.length()) {
                        val c = cs.getJSONObject(j)
                        item.clips.add(Clip(c.getDouble("s"), c.getDouble("e"), c.getString("t"), c.optInt("l", 0)))
                    }
                    items.add(item)
                }
            }
        } catch (_: Exception) {
        }
    }

    fun save() {
        try {
            val arr = JSONArray()
            for (item in items) {
                val cs = JSONArray()
                for (c in item.clips) {
                    cs.put(JSONObject().put("s", c.start).put("e", c.end).put("t", c.text).put("l", c.level))
                }
                arr.put(
                    JSONObject().put("key", item.key).put("title", item.title).put("kind", item.kind)
                        .put("src", item.src).put("to", item.analyzedTo).put("done", item.done).put("clips", cs)
                )
            }
            file.writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    /** 이미 넣은 영상이면 그것을, 아니면 새로 만들어 맨 앞에 둔다. */
    fun open(key: String, title: String, kind: String, src: String): VideoItem {
        val old = items.firstOrNull { it.key == key }
        val item = old ?: VideoItem(key, title, kind, src)
        if (old != null) items.remove(old)
        items.add(0, item)
        save()
        return item
    }

    fun remove(item: VideoItem) {
        items.remove(item)
        save()
    }
}

/** 파일 이름을 제목으로 쓴다. */
fun fileTitle(ctx: Context, uri: Uri): String {
    try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { return it }
        }
    } catch (_: Exception) {
    }
    return uri.lastPathSegment ?: "내 영상"
}

fun pcmToWav(pcm: ByteArray, rate: Int = 16000): ByteArray {
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
    header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
    header.putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
    header.put("data".toByteArray()).putInt(pcm.size)
    return header.array() + pcm
}

private fun shortsToWav(samples: ShortArray): ByteArray {
    val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (s in samples) bytes.putShort(s)
    return pcmToWav(bytes.array())
}

/** 영상·음성 파일에서 소리만 꺼내 16kHz 모노로 차례로 읽는다. */
class AudioReader(ctx: Context, uri: Uri, startSec: Double) {
    private val ex = MediaExtractor()
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var sampleRate = 44100
    private var channels = 2
    private var inputDone = false
    private var outputDone = false
    private var carry = 0.0

    val durationSec: Double
    val positionSec: Double

    init {
        ex.setDataSource(ctx, uri, null)
        var track = -1
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                track = i
                break
            }
        }
        if (track < 0) throw IllegalStateException("이 파일에는 소리가 없어요.")
        val format = ex.getTrackFormat(track)
        ex.selectTrack(track)
        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        durationSec = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1e6 else 0.0
        if (startSec > 0) ex.seekTo((startSec * 1e6).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        positionSec = (ex.sampleTime.coerceAtLeast(0L)) / 1e6
        codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
    }

    /** 다음 소리 덩어리. 끝나면 null. */
    fun read(): ShortArray? {
        while (!outputDone) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val buf = codec.getInputBuffer(i)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o >= 0) {
                val out = codec.getOutputBuffer(o)!!
                out.position(info.offset)
                out.limit(info.offset + info.size)
                val shorts = ShortArray(info.size / 2)
                out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                if (shorts.isNotEmpty()) return convert(shorts)
            } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = codec.outputFormat
                sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
        }
        return null
    }

    /** 여러 채널을 하나로 합치고 16kHz로 낮춘다. */
    private fun convert(inp: ShortArray): ShortArray {
        val ch = channels.coerceAtLeast(1)
        val frames = inp.size / ch
        val ratio = sampleRate / 16000.0
        val out = ShortArray((frames / ratio).toInt() + 2)
        var n = 0
        var p = carry
        while (p < frames && n < out.size) {
            val a = p.toInt()
            val b = (p + ratio).toInt().coerceAtLeast(a + 1).coerceAtMost(frames)
            var sum = 0L
            for (i in a until b) for (c in 0 until ch) sum += inp[i * ch + c]
            out[n++] = (sum / ((b - a) * ch)).toInt().toShort()
            p += ratio
        }
        carry = (p - frames).coerceAtLeast(0.0)
        return out.copyOf(n)
    }

    fun close() {
        try {
            codec.stop()
        } catch (_: Exception) {
        }
        codec.release()
        ex.release()
    }
}

/** 소리를 말이 쉬는 곳에서 끊어 2~10초짜리 조각으로 나눈다. 말소리가 거의 없는 조각은 버린다. */
class Segmenter(startSec: Double) {
    class Piece(val start: Double, val end: Double, val pcm: ShortArray)

    private var total = (startSec * RATE).toLong()
    private val frame = ShortArray(FRAME)
    private var fill = 0
    private val buf = ShortArray(RATE * 12)
    private var len = 0
    private var pieceStart = 0L
    private var active = false
    private var loudFrames = 0
    private var quiet = 0

    val timeSec: Double get() = total.toDouble() / RATE

    fun feed(block: ShortArray): List<Piece> {
        val out = ArrayList<Piece>()
        for (v in block) {
            frame[fill++] = v
            total++
            if (fill == FRAME) {
                fill = 0
                onFrame(out)
            }
        }
        return out
    }

    private fun onFrame(out: MutableList<Piece>) {
        var sum = 0.0
        for (v in frame) sum += v.toDouble() * v
        val loud = sqrt(sum / FRAME) > 400
        if (!active) {
            if (!loud) return
            active = true
            len = 0
            loudFrames = 0
            quiet = 0
            pieceStart = total - FRAME
        }
        if (len + FRAME <= buf.size) {
            System.arraycopy(frame, 0, buf, len, FRAME)
            len += FRAME
        }
        if (loud) {
            loudFrames++
            quiet = 0
        } else {
            quiet++
        }
        val ms = len / 16
        // 0.4초 쉬면 끊고, 너무 짧으면 1초 쉴 때까지 기다리고, 10초가 넘으면 강제로 끊는다.
        if ((quiet >= 20 && ms >= 2500) || quiet >= 50 || ms >= 10_000) {
            val keep = (len - (quiet - 5).coerceAtLeast(0) * FRAME).coerceAtLeast(FRAME)
            if (loudFrames * 20 >= 1000) {
                out.add(Piece(pieceStart.toDouble() / RATE, (pieceStart + keep).toDouble() / RATE, buf.copyOf(keep)))
            }
            active = false
        }
    }

    companion object {
        private const val RATE = 16000
        private const val FRAME = 320 // 20ms
    }
}

/** 공부를 시작하기 전에 영상을 미리 분석해 따라 말하기 좋은 영어 대사를 골라 둔다. */
object Analyzer {
    var running by mutableStateOf(false)
        private set
    var note by mutableStateOf("")
        private set

    /** 공부하는 동안에는 AI가 내 발음 분석에 집중하도록 영상 분석을 잠시 멈춘다. */
    @Volatile
    var paused = false

    private var job: Job? = null

    /** stop()이 불릴 때마다 올라간다. 취소된 옛 작업이 새 작업의 상태를 건드리지 않게 한다. */
    private var gen = 0
    private var cleanup: (() -> Unit)? = null

    fun stop() {
        gen++
        cleanup?.invoke()
        cleanup = null
        job?.cancel()
        job = null
        running = false
        listening = false
        note = ""
    }

    private fun usable(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.uppercase().startsWith("NONE")) return false
        val letters = t.count { it in 'a'..'z' || it in 'A'..'Z' }
        val words = t.split(Regex("\\s+")).size
        return words >= 3 && letters * 2 >= t.length
    }

    /** 문장마다 난이도를 매기고, 쓸 수 없는 것(영어가 아님, 노래, 의미 없는 조각)은 뺀다. */
    suspend fun rate(clips: List<Clip>): List<Clip> {
        if (clips.isEmpty()) return clips
        val out = mutableListOf<Clip>()
        for (group in clips.chunked(15)) {
            val scores = try {
                Coach.parseScores(Llm.ask(Coach.ratePrompt(group.map { it.text })))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyMap()
            }
            group.forEachIndexed { i, c ->
                val s = scores[i + 1]
                if (s == null) out.add(c.copy(level = 5)) else if (s > 0) out.add(c.copy(level = s.coerceIn(1, 10)))
            }
        }
        return out
    }

    /** 내 파일: 소리를 직접 읽어 조각내고, 조각마다 AI가 받아쓴 뒤 난이도를 매긴다. 5분쯤 단위로 결과가 쌓인다. */
    fun analyzeFile(scope: CoroutineScope, ctx: Context, item: VideoItem, lib: Library) {
        if (running || item.done) return
        val my = ++gen
        running = true
        note = "영상의 소리를 읽고 있어요…"
        job = scope.launch {
            try {
                withContext(Dispatchers.Default) {
                    val reader = AudioReader(ctx, Uri.parse(item.src), item.analyzedTo)
                    try {
                        val seg = Segmenter(reader.positionSec)
                        val batch = mutableListOf<Clip>()
                        var lastNote = -10.0

                        suspend fun flush(upTo: Double) {
                            val rated = rate(batch)
                            batch.clear()
                            withContext(Dispatchers.Main) {
                                item.clips.addAll(rated)
                                item.analyzedTo = upTo
                                lib.save()
                            }
                        }

                        while (isActive) {
                            while (paused) delay(300)
                            val block = reader.read() ?: break
                            for (piece in seg.feed(block)) {
                                while (paused) delay(300)
                                val text = try {
                                    Llm.transcribe(shortsToWav(piece.pcm))
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    ""
                                }
                                if (usable(text)) batch.add(Clip(piece.start, piece.end, text.trim(), 0))
                                if (batch.size >= 8) flush(piece.end)
                            }
                            if (abs(seg.timeSec - lastNote) >= 5) {
                                lastNote = seg.timeSec
                                val now = clock(seg.timeSec)
                                val all = if (reader.durationSec > 0) " / " + clock(reader.durationSec) else ""
                                withContext(Dispatchers.Main) { note = "AI가 영상을 듣고 있어요 · $now$all" }
                            }
                        }
                        flush(seg.timeSec)
                        withContext(Dispatchers.Main) {
                            item.done = true
                            lib.save()
                        }
                    } finally {
                        reader.close()
                    }
                }
                note = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                note = "영상을 분석하지 못했어요: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                if (gen == my) running = false
            }
        }
    }

    /** 지금 영상을 재생하며 듣는 방식으로 분석 중인지. */
    var listening by mutableStateOf(false)
        private set

    /**
     * 유튜브: 공부를 시작하기 전에 영상을 스피커로 재생해 두고, 그 소리를 마이크로 들으며 분석한다.
     * 영상 길이만큼 시간이 걸리고, 멈췄다가 이어서 할 수 있다.
     */
    fun analyzeByListening(scope: CoroutineScope, item: VideoItem, lib: Library, player: ClipPlayer, recorder: Recorder) {
        stop()
        val my = ++gen
        val from = item.analyzedTo
        val seg = Segmenter(0.0)
        val pieces = Channel<Pair<Segmenter.Piece, Double>>(Channel.UNLIMITED)
        val started = recorder.start { block ->
            for (p in seg.feed(block)) {
                // 마이크 시간과 영상 시간의 차이를 그때그때 재서 영상 위치로 바꾼다.
                pieces.trySend(p to (player.positionSec - seg.timeSec))
            }
        }
        if (!started) {
            note = "마이크를 열 수 없어요."
            return
        }
        cleanup = {
            recorder.stop()
            try {
                player.stop()
            } catch (_: Exception) {
            }
        }
        running = true
        listening = true
        note = "영상을 재생하며 AI가 듣고 있어요…"
        var reachedEnd = false
        player.play(from, 1e9, 1f) {
            reachedEnd = true
            recorder.stop()
            pieces.close()
        }
        job = scope.launch {
            try {
                val batch = mutableListOf<Clip>()

                suspend fun flush() {
                    if (batch.isEmpty()) return
                    val upTo = batch.last().end
                    val rated = rate(batch.toList())
                    batch.clear()
                    item.clips.addAll(rated)
                    item.analyzedTo = upTo
                    lib.save()
                }

                for ((p, offset) in pieces) {
                    val text = try {
                        Llm.transcribe(shortsToWav(p.pcm))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        ""
                    }
                    val start = (p.start + offset - 0.3).coerceAtLeast(0.0)
                    val end = p.end + offset + 0.3
                    if (usable(text) && end > start) batch.add(Clip(start, end, text.trim(), 0))
                    note = "영상을 재생하며 AI가 듣고 있어요 · ${clock(player.positionSec)}" +
                        if (player.durationSec > 0) " / ${clock(player.durationSec)}" else ""
                    if (batch.size >= 6) flush()
                }
                flush()
                if (reachedEnd) {
                    item.done = true
                    lib.save()
                }
                note = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                note = "영상을 분석하지 못했어요: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                if (gen == my) {
                    cleanup?.invoke()
                    cleanup = null
                    running = false
                    listening = false
                }
            }
        }
    }

    /** 유튜브: 붙여 넣은 스크립트를 문장 단위로 다듬고 AI가 난이도를 매겨 장면을 고른다. */
    fun analyzeScript(scope: CoroutineScope, item: VideoItem, lib: Library, lines: List<Line>, useAi: Boolean) {
        stop()
        val my = ++gen
        running = true
        note = "AI가 스크립트를 분석하고 있어요…"
        job = scope.launch {
            try {
                val merged = mergeLines(lines).map { Clip(it.start, it.end, it.text, 0) }
                item.clips.clear()
                item.done = false
                for (group in merged.chunked(15)) {
                    val rated = if (useAi) rate(group) else group
                    item.clips.addAll(rated)
                    item.analyzedTo = group.last().end
                    note = "AI가 스크립트를 분석하고 있어요 · ${clock(group.last().end)}"
                }
                item.done = true
                lib.save()
                note = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                note = "스크립트를 분석하지 못했어요: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                if (gen == my) running = false
            }
        }
    }

    /** 자막의 짧은 줄들을 문장이 끝나는 곳이나 8초 안팎에서 합친다. */
    private fun mergeLines(lines: List<Line>): List<Line> {
        val out = mutableListOf<Line>()
        var start = 0.0
        var end = 0.0
        val sb = StringBuilder()
        for (l in lines) {
            if (sb.isEmpty()) start = l.start
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(l.text)
            end = l.end
            val t = sb.toString()
            val closed = t.endsWith(".") || t.endsWith("?") || t.endsWith("!")
            if ((closed && end - start >= 2.0) || end - start >= 8.0 || t.split(' ').size >= 18) {
                out.add(Line(start, end, t))
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) out.add(Line(start, end, sb.toString()))
        return out
    }
}

fun clock(sec: Double): String {
    val s = sec.toInt().coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
