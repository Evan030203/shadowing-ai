package com.shadowai.app

import kotlin.math.abs
import kotlin.math.sqrt

/** 글을 한 문장씩 나누는 규칙. 쉐도잉은 한 번에 한 문장씩 한다. */
object Sentences {
    private val ABBREV = setOf(
        "mr.", "mrs.", "ms.", "dr.", "st.", "vs.", "etc.", "e.g.", "i.e.", "u.s.", "jr.", "sr.", "prof.", "no.",
    )

    private fun bare(word: String): String = word.trimEnd('"', '\'', ')', ']', '”', '’')

    /** 이 단어에서 문장이 끝나는지. "Mr." 같은 약어나 "J." 같은 머리글자는 끝이 아니다. */
    private fun isEnd(word: String): Boolean {
        val w = bare(word)
        if (!(w.endsWith(".") || w.endsWith("?") || w.endsWith("!"))) return false
        if (w.lowercase() in ABBREV) return false
        if (w.length == 2 && w[0].isLetter() && w[1] == '.') return false
        return true
    }

    private fun isBreak(word: String): Boolean {
        val w = bare(word)
        return w.endsWith(",") || w.endsWith(";") || w.endsWith(":")
    }

    fun endsSentence(text: String): Boolean = isEnd(text.trim().substringAfterLast(' '))

    /**
     * 글을 문장으로 나눈다. 마침표·물음표·느낌표에서 끊고,
     * 너무 긴 문장은 따라 말하기 어려우므로 쉼표(soft 단어 이상일 때)나 max 단어에서 끊는다.
     */
    fun split(text: String, soft: Int = 14, max: Int = 20): List<String> {
        val out = ArrayList<String>()
        val cur = ArrayList<String>()
        for (w in text.trim().split(Regex("\\s+"))) {
            if (w.isEmpty()) continue
            cur.add(w)
            if (isEnd(w) || cur.size >= max || (cur.size >= soft && isBreak(w))) {
                out.add(cur.joinToString(" "))
                cur.clear()
            }
        }
        if (cur.isNotEmpty()) out.add(cur.joinToString(" "))
        return out
    }

    private class Word(val text: String, val start: Double, val end: Double)

    /**
     * 시간이 붙은 글 줄들(자막 등)을 문장 단위로 다시 묶는다.
     * 줄 안에서는 글자 수에 비례해 시간을 나누고, 줄을 넘어 이어지는 문장은 합친다.
     */
    fun fromLines(lines: List<Line>): List<Line> {
        val words = ArrayList<Word>()
        for (l in lines) {
            val ws = l.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (ws.isEmpty()) continue
            val total = ws.sumOf { it.length + 1 }.toDouble()
            val dur = (l.end - l.start).coerceAtLeast(0.2)
            var acc = 0.0
            for (w in ws) {
                val s = l.start + dur * acc / total
                acc += w.length + 1
                words.add(Word(w, s, l.start + dur * acc / total))
            }
        }
        if (words.isEmpty()) return emptyList()

        // 자동 생성 자막처럼 문장 부호가 거의 없으면 10단어씩 끊는다.
        val punctuated = words.count { isEnd(it.text) } * 40 >= words.size
        val soft = if (punctuated) 14 else 10
        val max = if (punctuated) 20 else 10

        val out = ArrayList<Line>()
        val cur = ArrayList<Word>()
        for ((i, w) in words.withIndex()) {
            cur.add(w)
            val pause = i + 1 < words.size && words[i + 1].start - w.end > 1.5
            if (isEnd(w.text) || cur.size >= max || (cur.size >= soft && isBreak(w.text)) || pause) {
                out.add(Line(cur.first().start, cur.last().end, cur.joinToString(" ") { it.text }))
                cur.clear()
            }
        }
        if (cur.isNotEmpty()) out.add(Line(cur.first().start, cur.last().end, cur.joinToString(" ") { it.text }))
        return out
    }
}

/**
 * 소리 조각과 그 받아쓰기를 차례로 받아, 한 문장짜리 구간으로 다시 나눈다.
 * - 한 조각에 여러 문장이 있으면 말이 쉬는 곳을 찾아 문장마다 자른다.
 * - 문장이 조각 끝에서 끝나지 않았으면 다음 조각의 첫 부분과 이어 붙인다.
 */
class SentenceBuilder(private val padBefore: Double, private val padAfter: Double) {
    private var carryText = ""
    private var carryStart = 0.0
    private var carryEnd = 0.0

    private fun emit(out: MutableList<Clip>, start: Double, end: Double, text: String) {
        out.add(Clip((start - padBefore).coerceAtLeast(0.0), end + padAfter, text.trim()))
    }

    private fun flushCarry(out: MutableList<Clip>) {
        if (carryText.isNotEmpty()) emit(out, carryStart, carryEnd, carryText)
        carryText = ""
    }

    /** start~end는 영상에서의 위치(초), pcm은 그 조각의 16kHz 소리, text는 받아쓴 글. */
    fun add(start: Double, end: Double, pcm: ShortArray, text: String): List<Clip> {
        val out = ArrayList<Clip>()
        val parts = Sentences.split(text)
        if (parts.isEmpty()) {
            flushCarry(out)
            return out
        }
        val cuts = cutPoints(parts, pcm)
        var from = 0
        if (carryText.isNotEmpty()) {
            if (start - carryEnd <= 0.8 && end - carryStart <= 16.0) {
                val joined = carryText + " " + parts[0]
                val joinedEnd = start + cuts[1]
                if (Sentences.endsSentence(parts[0]) || parts.size > 1) {
                    emit(out, carryStart, joinedEnd, joined)
                    carryText = ""
                } else {
                    carryText = joined
                    carryEnd = joinedEnd
                }
                from = 1
            } else {
                flushCarry(out)
            }
        }
        for (i in from until parts.size) {
            val s = start + cuts[i]
            val e = start + cuts[i + 1]
            if (i == parts.size - 1 && !Sentences.endsSentence(parts[i])) {
                carryText = parts[i]
                carryStart = s
                carryEnd = e
            } else {
                emit(out, s, e, parts[i])
            }
        }
        return out
    }

    /** 마지막에 남은 문장을 내보낸다. */
    fun finish(): List<Clip> {
        val out = ArrayList<Clip>()
        flushCarry(out)
        return out
    }

    /**
     * 조각 안에서 문장이 바뀌는 시점들을 찾는다(조각 시작 기준 초).
     * 말소리가 있는 시간이 글자 수에 비례한다고 보고 대략의 위치를 잡은 뒤,
     * 그 앞뒤 0.6초 안에서 가장 조용한 곳(말이 쉬는 곳)으로 옮긴다.
     */
    private fun cutPoints(parts: List<String>, pcm: ShortArray): DoubleArray {
        val dur = pcm.size / RATE.toDouble()
        val cuts = DoubleArray(parts.size + 1)
        cuts[parts.size] = dur
        if (parts.size == 1) return cuts

        val frames = pcm.size / FRAME
        if (frames < 25) {
            for (i in 1 until parts.size) cuts[i] = dur * i / parts.size
            return cuts
        }
        val energy = DoubleArray(frames) { f ->
            var sum = 0.0
            for (k in 0 until FRAME) {
                val v = pcm[f * FRAME + k].toDouble()
                sum += v * v
            }
            sqrt(sum / FRAME)
        }
        val voiced = IntArray(frames + 1)
        for (f in 0 until frames) voiced[f + 1] = voiced[f] + if (energy[f] > 400) 1 else 0
        val totalVoiced = voiced[frames].coerceAtLeast(1)
        val totalChars = parts.sumOf { it.length }.coerceAtLeast(1)

        var chars = 0
        for (i in 1 until parts.size) {
            chars += parts[i - 1].length
            val target = totalVoiced.toDouble() * chars / totalChars
            var guess = 0
            while (guess < frames - 1 && voiced[guess + 1] < target) guess++

            var best = guess
            var bestScore = Double.MAX_VALUE
            val lo = maxOf(guess - 30, ((cuts[i - 1] + 0.4) * 50).toInt(), 2)
            val hi = minOf(guess + 30, frames - 23)
            for (g in lo..hi) {
                var e = 0.0
                for (k in -2..2) e += energy[g + k]
                // 조용할수록, 짐작한 곳에 가까울수록 좋다.
                val score = e / 5 + abs(g - guess) * 4.0
                if (score < bestScore) {
                    bestScore = score
                    best = g
                }
            }
            cuts[i] = best * FRAME / RATE.toDouble()
            if (cuts[i] <= cuts[i - 1]) cuts[i] = minOf(dur, cuts[i - 1] + 0.3)
        }
        return cuts
    }

    companion object {
        private const val RATE = 16000
        private const val FRAME = 320 // 20ms
    }
}
