package com.shadowai.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** 원어민 음성 재생(폰에 내장된 음성 합성). */
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

    /** rate 1.0이 보통 속도. 재생이 끝나면 done을 메인 스레드에서 부른다. */
    fun speak(text: String, rate: Float, done: () -> Unit) {
        if (!ready) {
            main.postDelayed({ if (ready) speak(text, rate, done) else done() }, 800)
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

/** 따라 말한 목소리를 글자로 바꾼다(폰의 음성 인식, 가능하면 오프라인). */
class Listener(private val ctx: Context) {
    private var sr: SpeechRecognizer? = null

    fun start(
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        fail: (String) -> Unit,
        offline: Boolean = true,
    ) {
        cancel()
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            fail("이 폰에서 음성 인식을 쓸 수 없어요. Google 앱이 켜져 있는지 확인해 주세요.")
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        sr = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isBlank()) fail("목소리를 알아듣지 못했어요. 다시 해 볼까요?") else onResult(text)
            }

            override fun onPartialResults(partial: Bundle?) {
                val text = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isNotBlank()) onPartial(text)
            }

            override fun onError(code: Int) {
                // 오프라인 영어 인식팩이 없으면 온라인 인식으로 한 번 더 시도한다.
                val noOfflinePack = code == SpeechRecognizer.ERROR_NETWORK ||
                    code == SpeechRecognizer.ERROR_SERVER ||
                    code == 12 || code == 13
                if (offline && noOfflinePack) {
                    start(onPartial, onResult, fail, offline = false)
                    return
                }
                fail(
                    when (code) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            "목소리를 알아듣지 못했어요. 조금 더 크게 다시 해 볼까요?"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                            "마이크 권한이 필요해요."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_SERVER, 12, 13 ->
                            "영어 음성 인식을 쓸 수 없어요. 인터넷을 켜거나, 설정에서 영어(미국) 오프라인 음성 인식을 받아 주세요."
                        else -> "음성 인식 오류가 났어요 (코드 $code). 다시 시도해 주세요."
                    }
                )
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
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        r.startListening(intent)
    }

    /** 말하기를 끝냈다고 알려 결과를 바로 받는다. */
    fun finishSpeaking() {
        sr?.stopListening()
    }

    fun cancel() {
        sr?.let {
            it.cancel()
            it.destroy()
        }
        sr = null
    }
}
