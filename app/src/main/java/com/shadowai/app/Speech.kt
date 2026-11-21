package com.shadowai.app

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * 폰의 음성 인식으로 따라 말한 목소리를 글자로 바꾼다.
 * 폰마다 되는 인식 서비스가 달라서(코드 11 등), 실패하면 다른 서비스로 차례로 바꿔 시도하고
 * 한 번 성공한 서비스를 기억해 둔다.
 */
class Listener(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private var session = 0

    private fun create(kind: Int): SpeechRecognizer? = try {
        when (kind) {
            0 -> SpeechRecognizer.createSpeechRecognizer(ctx)
            1 -> SpeechRecognizer.createSpeechRecognizer(
                ctx,
                ComponentName(
                    "com.google.android.tts",
                    "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService",
                ),
            )
            2 -> SpeechRecognizer.createSpeechRecognizer(
                ctx,
                ComponentName(
                    "com.google.android.googlequicksearchbox",
                    "com.google.android.voicesearch.serviceapi.GoogleRecognitionService",
                ),
            )
            else ->
                if (SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
                } else {
                    null
                }
        }
    } catch (_: Exception) {
        null
    }

    /** fail의 두 번째 값이 true면 말소리 문제가 아니라 인식 서비스 자체가 안 되는 경우다. */
    fun start(
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        fail: (String, Boolean) -> Unit,
    ) {
        cancel()
        attempt(0, onPartial, onResult, fail)
    }

    private fun attempt(
        n: Int,
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        fail: (String, Boolean) -> Unit,
    ) {
        release()
        val my = ++session
        if (n >= KINDS) {
            fail("폰의 영어 음성 인식을 쓸 수 없어요. Google 앱과 'Speech Recognition & Synthesis' 앱을 업데이트해 주세요.", true)
            return
        }
        val kind = (good + n) % KINDS
        val r = create(kind)
        if (r == null) {
            attempt(n + 1, onPartial, onResult, fail)
            return
        }
        sr = r

        fun retry() {
            main.postDelayed({ if (my == session) attempt(n + 1, onPartial, onResult, fail) }, 350)
        }

        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                if (my != session) return
                good = kind
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isBlank()) fail("목소리를 알아듣지 못했어요. 다시 해 볼까요?", false) else onResult(text)
            }

            override fun onPartialResults(partial: Bundle?) {
                if (my != session) return
                val text = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isNotBlank()) onPartial(text)
            }

            override fun onError(code: Int) {
                if (my != session) return
                when (code) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        good = kind
                        fail("목소리를 알아듣지 못했어요. 조금 더 크게 다시 해 볼까요?", false)
                    }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail("마이크 권한이 필요해요.", false)
                    // 11(서비스 연결 끊김), 네트워크, 언어팩 없음 등: 다른 인식 서비스로 넘어간다.
                    else -> retry()
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        try {
            r.startListening(intent)
        } catch (_: Exception) {
            retry()
        }
    }

    /** 말하기를 끝냈다고 알려 결과를 바로 받는다. */
    fun finishSpeaking() {
        sr?.stopListening()
    }

    private fun release() {
        sr?.let {
            try {
                it.cancel()
                it.destroy()
            } catch (_: Exception) {
            }
        }
        sr = null
    }

    fun cancel() {
        session++
        release()
    }

    companion object {
        private const val KINDS = 4
        private var good = 0
    }
}

/** 마이크 소리를 16kHz 모노 WAV로 녹음한다. 로컬 AI 받아쓰기에 넣는 용도. */
class Recorder {
    private val out = ByteArrayOutputStream()
    private var rec: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        stop()
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val r = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                min * 4,
            )
        } catch (_: Exception) {
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        synchronized(out) { out.reset() }
        rec = r
        spoke = false
        startedAt = System.currentTimeMillis()
        lastLoud = startedAt
        running = true
        r.startRecording()
        worker = Thread {
            val buf = ByteArray(min)
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n < 0) break
                if (n > 0) {
                    synchronized(out) { if (out.size() < MAX_BYTES) out.write(buf, 0, n) }
                    var peak = 0
                    var i = 0
                    while (i + 1 < n) {
                        val v = kotlin.math.abs((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff))
                        if (v > peak) peak = v
                        i += 2
                    }
                    if (peak > 2500) {
                        spoke = true
                        lastLoud = System.currentTimeMillis()
                    }
                }
            }
        }.also { it.start() }
        return true
    }

    /** 녹음을 끝내고 WAV를 돌려준다. 너무 짧으면 빈 배열. */
    fun stop(): ByteArray {
        running = false
        try {
            worker?.join(800)
        } catch (_: InterruptedException) {
        }
        worker = null
        rec?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        rec = null
        val pcm = synchronized(out) {
            val b = out.toByteArray()
            out.reset()
            b
        }
        if (pcm.size < RATE) return ByteArray(0) // 0.5초 미만
        return pcmToWav(pcm, RATE)
    }

    @Volatile
    private var spoke = false

    @Volatile
    private var lastLoud = 0L

    @Volatile
    private var startedAt = 0L

    /** 말을 시작한 뒤 1.5초쯤 조용하면(또는 25초가 지나면) 다 말한 것으로 본다. */
    fun finishedSpeaking(): Boolean {
        if (!running) return false
        val now = System.currentTimeMillis()
        return (spoke && now - lastLoud > 1500) || now - startedAt > 25_000
    }

    companion object {
        private const val RATE = 16000
        private const val MAX_BYTES = RATE * 2 * 30 // 최대 30초
    }
}

/**
 * 따라 말한 걸 듣는 창구. 먼저 폰의 음성 인식을 쓰고,
 * 그게 아예 안 되는 폰이면 녹음해서 로컬 AI가 받아쓰게 한다.
 */
class Hear(private val listener: Listener, private val recorder: Recorder) {
    private val scope = MainScope()
    private var session = 0
    private var aiActive = false
    private var partialCb: ((String) -> Unit)? = null
    private var resultCb: ((String) -> Unit)? = null
    private var failCb: ((String) -> Unit)? = null

    fun start(onPartial: (String) -> Unit, onResult: (String) -> Unit, fail: (String) -> Unit) {
        cancel()
        partialCb = onPartial
        resultCb = onResult
        failCb = fail
        if (preferAi && Llm.hasAudio) {
            startAi()
            return
        }
        listener.start(onPartial, onResult) { msg, broken ->
            if (broken && Llm.hasAudio) {
                preferAi = true
                startAi()
            } else {
                fail(msg)
            }
        }
    }

    private fun startAi() {
        if (!recorder.start()) {
            failCb?.invoke("마이크를 열 수 없어요.")
            return
        }
        aiActive = true
        partialCb?.invoke("AI가 듣고 있어요. 다 말한 뒤 '다 말했어요'를 눌러 주세요.")
    }

    fun finishSpeaking() {
        if (!aiActive) {
            listener.finishSpeaking()
            return
        }
        aiActive = false
        val wav = recorder.stop()
        val my = session
        val ok = resultCb
        val bad = failCb
        if (wav.isEmpty()) {
            bad?.invoke("녹음이 너무 짧아요. 다시 해 볼까요?")
            return
        }
        partialCb?.invoke("AI가 받아쓰는 중이에요…")
        scope.launch {
            val text = try {
                Llm.transcribe(wav)
            } catch (_: Exception) {
                ""
            }
            if (my != session) return@launch
            if (text.isBlank()) bad?.invoke("목소리를 알아듣지 못했어요. 다시 해 볼까요?") else ok?.invoke(text)
        }
    }

    fun cancel() {
        session++
        listener.cancel()
        if (aiActive) {
            recorder.stop()
            aiActive = false
        }
    }

    companion object {
        private var preferAi = false
    }
}
