package com.shadowai.app

data class Sentence(val en: String, val ko: String)

/** 로컬 AI에게 시키는 세 가지 일: 문장 만들기, 발음 코칭, 난이도 조절. */
object Coach {
    const val ROUND = 5

    fun levelName(level: Int): String = when {
        level <= 3 -> "초급"
        level <= 6 -> "중급"
        else -> "고급"
    }

    /** 레벨이 오르면 음성도 빨라진다. 1레벨 0.74배 ~ 10레벨 1.10배. */
    fun speechRate(level: Int): Float = 0.70f + level * 0.04f

    private fun levelSpec(level: Int): String = when {
        level <= 2 -> "4 to 6 words per sentence, only very common everyday words, simple present or past tense"
        level <= 4 -> "6 to 9 words per sentence, common everyday vocabulary, simple grammar"
        level <= 6 -> "9 to 13 words per sentence, natural conversational English with common idioms and contractions"
        level <= 8 -> "13 to 18 words per sentence, phrasal verbs, contractions and linked sounds typical of fast native speech"
        else -> "18 to 25 words per sentence, complex clauses and advanced vocabulary as in news or lectures"
    }

    fun sentencesPrompt(topic: String, level: Int, weak: List<String>): String = buildString {
        appendLine("You are an English shadowing coach for a Korean learner.")
        appendLine("Write exactly $ROUND natural spoken English sentences for shadowing practice.")
        appendLine("Topic: $topic")
        appendLine("Difficulty: level $level of 10 (${levelSpec(level)}).")
        if (weak.isNotEmpty()) {
            appendLine("The learner often mispronounces these words, so use some of them naturally: ${weak.joinToString(", ")}")
        }
        appendLine("Output format: one sentence per line, written as: English sentence | Korean translation")
        append("No numbering, no titles, no extra text.")
    }

    fun parseSentences(raw: String): List<Sentence> =
        raw.lines().mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size < 2) return@mapNotNull null
            val en = parts[0].trim().trimStart('-', '*', '•', ' ').replace(Regex("^\\d+[.)]\\s*"), "")
                .replace("*", "").trim().trim('"')
            val ko = parts[1].replace("*", "").trim()
            val words = en.split(Regex("\\s+")).count { w -> w.any { it in 'a'..'z' || it in 'A'..'Z' } }
            if (words < 3 || ko.isEmpty()) null else Sentence(en, ko)
        }.take(ROUND)

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

    fun adaptPrompt(level: Int, results: List<Int>, weak: List<String>): String = buildString {
        appendLine("You set the difficulty of an English shadowing app. Levels go from 1 (easiest) to 10 (hardest).")
        appendLine("Current level: $level")
        appendLine("Accuracy of each sentence this round (percent): ${results.joinToString(", ")}")
        appendLine("Average: ${results.average().toInt()}")
        if (weak.isNotEmpty()) appendLine("Words the learner keeps missing: ${weak.joinToString(", ")}")
        appendLine("Goal: keep the learner around 75 to 90 percent accuracy, challenged but succeeding.")
        appendLine("Choose the level for the next round. Change it by at most 2.")
        appendLine("Reply in exactly two lines:")
        appendLine("LEVEL: <number>")
        append("REASON: <one sentence in Korean explaining the choice to the learner>")
    }

    /** AI 답에서 (새 레벨, 이유)를 꺼낸다. 형식이 깨졌으면 null. */
    fun parseLevel(raw: String, current: Int): Pair<Int, String>? {
        val n = Regex("LEVEL\\s*[:：]\\s*\\**\\s*(\\d{1,2})").find(raw)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        val reason = Regex("REASON\\s*[:：]\\s*(.+)").find(raw)?.groupValues?.get(1)
            ?.replace("*", "")?.trim().orEmpty()
        val next = n.coerceIn(current - 2, current + 2).coerceIn(1, 10)
        return next to reason
    }

    /** AI가 없거나 답이 이상할 때 쓰는 기본 규칙. */
    fun ruleLevel(level: Int, average: Int): Pair<Int, String> = when {
        average >= 90 -> (level + 1).coerceAtMost(10) to "평균 ${average}%로 여유가 있어서 한 단계 올렸어요."
        average < 65 -> (level - 1).coerceAtLeast(1) to "평균 ${average}%라 조금 쉬운 문장으로 다시 다져요."
        else -> level to "평균 ${average}%로 지금 난이도가 딱 맞아요."
    }

    fun starter(level: Int): List<Sentence> {
        val pool = when {
            level <= 3 -> EASY
            level <= 6 -> MID
            else -> HARD
        }
        return pool.shuffled().take(ROUND)
    }

    private val EASY = listOf(
        Sentence("I get up at seven every day.", "나는 매일 일곱 시에 일어나요."),
        Sentence("Can I have a cup of coffee?", "커피 한 잔 주시겠어요?"),
        Sentence("It is really cold outside today.", "오늘 밖이 정말 추워요."),
        Sentence("Where is the nearest subway station?", "가장 가까운 지하철역이 어디예요?"),
        Sentence("I like to watch movies at home.", "나는 집에서 영화 보는 걸 좋아해요."),
        Sentence("What time does the store open?", "가게는 몇 시에 열어요?"),
        Sentence("She works at a big hospital.", "그녀는 큰 병원에서 일해요."),
        Sentence("Let's meet in front of the cafe.", "카페 앞에서 만나요."),
    )

    private val MID = listOf(
        Sentence("I was going to call you, but I ran out of time.", "전화하려고 했는데 시간이 없었어요."),
        Sentence("Do you mind if I open the window for a while?", "잠깐 창문 좀 열어도 될까요?"),
        Sentence("I've been thinking about changing my job lately.", "요즘 이직을 생각하고 있어요."),
        Sentence("It took me a while to get used to the new schedule.", "새 일정에 익숙해지는 데 시간이 좀 걸렸어요."),
        Sentence("Could you let me know when you're done with it?", "다 끝나면 알려 주시겠어요?"),
        Sentence("I'm not sure what you mean, can you say that again?", "무슨 뜻인지 잘 모르겠어요, 다시 말해 줄래요?"),
        Sentence("We should have left earlier to avoid the traffic.", "차 막히는 걸 피하려면 더 일찍 나왔어야 했어요."),
        Sentence("I'll pick you up at the airport as soon as you land.", "착륙하자마자 공항으로 데리러 갈게요."),
    )

    private val HARD = listOf(
        Sentence(
            "If I had known the meeting was going to run this late, I would have rescheduled my other appointment.",
            "회의가 이렇게 늦게까지 갈 줄 알았다면 다른 약속을 다시 잡았을 거예요.",
        ),
        Sentence(
            "What really surprised me was how quickly everyone adapted to working from home.",
            "정말 놀라웠던 건 모두가 재택근무에 얼마나 빨리 적응했는가였어요.",
        ),
        Sentence(
            "The company is expected to announce its quarterly results later this week, and investors are watching closely.",
            "그 회사는 이번 주 후반에 분기 실적을 발표할 예정이고 투자자들이 주시하고 있어요.",
        ),
        Sentence(
            "You might want to double-check the figures before you send them out to the client.",
            "고객에게 보내기 전에 수치를 한 번 더 확인하는 게 좋을 거예요.",
        ),
        Sentence(
            "Even though it was raining heavily, they decided to go ahead with the outdoor event as planned.",
            "비가 많이 왔는데도 그들은 야외 행사를 계획대로 진행하기로 했어요.",
        ),
        Sentence(
            "I couldn't help but notice that you've been putting in a lot of extra hours recently.",
            "최근에 야근을 많이 하고 있다는 걸 모를 수가 없었어요.",
        ),
    )
}
