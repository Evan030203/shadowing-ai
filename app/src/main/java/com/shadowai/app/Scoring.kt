package com.shadowai.app

data class WordResult(val word: String, val ok: Boolean)

data class Score(val words: List<WordResult>, val accuracy: Int, val missed: List<String>)

/** 비교용 단어 형태: 소문자, 글자와 숫자만. "Don't," -> "dont" */
fun wordKey(w: String): String = w.lowercase().filter { it.isLetterOrDigit() }

/**
 * 목표 문장과 인식된 말을 단어 순서를 지켜 맞춰 본다(최장 공통 부분열).
 * 순서대로 말한 단어만 맞은 것으로 치므로, 빼먹거나 다르게 말한 단어가 드러난다.
 */
fun scoreShadow(target: String, spoken: String): Score {
    val shown = target.split(Regex("\\s+")).filter { it.isNotEmpty() }
    val t = shown.map { wordKey(it) }
    val s = spoken.split(Regex("\\s+")).map { wordKey(it) }.filter { it.isNotEmpty() }

    val n = t.size
    val m = s.size
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            dp[i][j] = if (t[i].isNotEmpty() && t[i] == s[j]) dp[i + 1][j + 1] + 1
            else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
    }

    val ok = BooleanArray(n)
    var i = 0
    var j = 0
    while (i < n && j < m) {
        if (t[i].isNotEmpty() && t[i] == s[j]) {
            ok[i] = true
            i++
            j++
        } else if (dp[i + 1][j] >= dp[i][j + 1]) {
            i++
        } else {
            j++
        }
    }

    val real = t.indices.filter { t[it].isNotEmpty() }
    val hit = real.count { ok[it] }
    val accuracy = if (real.isEmpty()) 0 else hit * 100 / real.size
    return Score(
        words = shown.indices.map { WordResult(shown[it], ok[it] || t[it].isEmpty()) },
        accuracy = accuracy,
        missed = real.filter { !ok[it] }.map { t[it] }.distinct(),
    )
}
