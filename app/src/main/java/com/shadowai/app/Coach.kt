package com.shadowai.app

/** 로컬 AI에게 시키는 일들의 지시문과, 그 답을 읽는 방법. */
object Coach {
    const val TRANSCRIBE =
        "Transcribe the English speech in this audio word for word. Output only the transcript, with no notes or quotation marks. " +
            "If there is no clear English speech (only music, noise, singing or another language), output exactly NONE."

    /** 영상에서 받아쓴 줄들 가운데 공부할 가치가 있는 영어만 고르게 한다. */
    fun keepPrompt(lines: List<String>): String = buildString {
        appendLine("You pick lines from a video transcript for a Korean learner who practises English shadowing.")
        appendLine("For each numbered line answer KEEP or DROP.")
        appendLine("KEEP: a meaningful English sentence or phrase that a person actually says and that is worth repeating aloud.")
        appendLine("DROP: anything that is not English, song lyrics, sound descriptions such as [Music], lines that are only names or single words, filler such as \"uh\" or \"okay okay\", and broken fragments that make no sense.")
        appendLine("Reply with one line per item in the form number: KEEP or number: DROP and nothing else.")
        lines.forEachIndexed { i, t -> appendLine("${i + 1}. $t") }
    }

    /** 줄 번호 -> 남길지(true) 버릴지(false). 답에 없는 번호는 들어 있지 않다. */
    fun parseKeep(raw: String): Map<Int, Boolean> {
        val out = mutableMapOf<Int, Boolean>()
        for (m in Regex("(?mi)^\\D{0,3}(\\d{1,2})\\s*[:.)\\-]\\s*\\**\\s*(KEEP|DROP)").findAll(raw)) {
            out[m.groupValues[1].toInt()] = m.groupValues[2].uppercase() == "KEEP"
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
