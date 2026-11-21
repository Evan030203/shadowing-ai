package com.shadowai.app

/** 로컬 AI에게 시키는 일들의 지시문과, 그 답을 읽는 방법. */
object Coach {
    fun levelName(level: Int): String = when {
        level <= 0 -> "분석 전"
        level <= 3 -> "쉬움"
        level <= 6 -> "보통"
        else -> "어려움"
    }

    const val TRANSCRIBE =
        "Transcribe the English speech in this audio word for word. Output only the transcript, with no notes or quotation marks. " +
            "If there is no clear English speech (only music, noise, singing or another language), output exactly NONE."

    /** 영상 대사들의 난이도를 매기게 한다. 0은 공부에 쓸 수 없다는 뜻. */
    fun ratePrompt(lines: List<String>): String = buildString {
        appendLine("You grade lines from a video for a Korean learner who practises English shadowing.")
        appendLine("For each numbered line give a difficulty from 1 (very easy) to 10 (very hard), judging vocabulary, sentence length and how natural speech would sound.")
        appendLine("Give 0 when the line is not usable for shadowing: not English, song lyrics, noise, or a meaningless fragment.")
        appendLine("Reply with one line per item in the form number: score and nothing else.")
        lines.forEachIndexed { i, t -> appendLine("${i + 1}. $t") }
    }

    fun parseScores(raw: String): Map<Int, Int> {
        val out = mutableMapOf<Int, Int>()
        for (m in Regex("(?m)^\\D{0,3}(\\d{1,2})\\s*[:.)\\-]\\s*(\\d{1,2})\\b").findAll(raw)) {
            out[m.groupValues[1].toInt()] = m.groupValues[2].toInt()
        }
        return out
    }

    /** 내 녹음을 직접 들려주고 발음을 봐 달라고 한다. */
    fun pronunciationPrompt(target: String, heard: String, missed: List<String>): String = buildString {
        appendLine("This audio is a Korean learner repeating an English sentence from a video.")
        appendLine("Target sentence: $target")
        appendLine("A transcription of the recording: $heard")
        if (missed.isNotEmpty()) appendLine("Words that were not recognised: ${missed.joinToString(", ")}")
        appendLine("Listen to the recording and coach the learner in Korean, in at most three short sentences.")
        appendLine("Say specifically which words or sounds were unclear and how to fix them (발음, 연음, 강세, 억양, 리듬).")
        append("If it sounded good, say what was good and one thing to polish. Plain text only, no lists.")
    }

    /** 소리를 들을 수 없을 때 글자만 보고 하는 코칭. */
    fun feedbackPrompt(target: String, heard: String, missed: List<String>): String = buildString {
        appendLine("A Korean learner shadowed an English sentence.")
        appendLine("Target sentence: $target")
        appendLine("What speech recognition heard: $heard")
        appendLine("Words not recognized: ${missed.joinToString(", ")}")
        appendLine("Give coaching in Korean, in at most two short sentences.")
        appendLine("Explain specifically how to pronounce or link the missed words (연음, 강세, 축약, 한국인이 자주 틀리는 소리).")
        append("Be encouraging. Plain text only, no lists, no headings.")
    }

    fun explainPrompt(sentence: String): String = buildString {
        appendLine("A Korean learner is shadowing this English sentence from a video:")
        appendLine(sentence)
        appendLine("Answer in Korean, plain text, at most three short lines:")
        appendLine("1) the natural Korean translation")
        append("2) one useful expression from the sentence and how it is used")
    }
}
