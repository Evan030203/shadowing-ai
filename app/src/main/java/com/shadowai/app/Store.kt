package com.shadowai.app

import android.content.Context
import org.json.JSONObject

/** 폰에 남겨 두는 학습 기록과 설정. */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("shadow", Context.MODE_PRIVATE)

    /** 1(가장 쉬움)~10(가장 어려움). 0이면 아직 고르지 않은 상태. */
    var level: Int
        get() = p.getInt("level", 5)
        set(v) = p.edit().putInt("level", v).apply()

    var topic: String
        get() = p.getString("topic", "일상 대화") ?: "일상 대화"
        set(v) = p.edit().putString("topic", v).apply()

    var simultaneous: Boolean
        get() = p.getBoolean("simultaneous", false)
        set(v) = p.edit().putBoolean("simultaneous", v).apply()

    var lastReason: String
        get() = p.getString("lastReason", "") ?: ""
        set(v) = p.edit().putString("lastReason", v).apply()

    var downloadId: Long
        get() = p.getLong("downloadId", -1L)
        set(v) = p.edit().putLong("downloadId", v).apply()

    var modelDone: Boolean
        get() = p.getBoolean("modelDone", false)
        set(v) = p.edit().putBoolean("modelDone", v).apply()

    /** GPU로 모델을 올리다 앱이 죽으면 true로 남는다. 다음 실행 때 CPU로 바로 간다. */
    var gpuCrashed: Boolean
        get() = p.getBoolean("gpuCrashed", false)
        set(v) {
            // 앱이 바로 죽어도 남아 있도록 즉시 디스크에 쓴다.
            p.edit().putBoolean("gpuCrashed", v).commit()
        }

    var totalSentences: Int
        get() = p.getInt("totalSentences", 0)
        set(v) = p.edit().putInt("totalSentences", v).apply()

    private fun weakMap(): MutableMap<String, Int> {
        val out = mutableMapOf<String, Int>()
        try {
            val o = JSONObject(p.getString("weak", "{}") ?: "{}")
            for (k in o.keys()) out[k] = o.getInt(k)
        } catch (_: Exception) {
        }
        return out
    }

    /** 자주 놓치는 단어를 많이 놓친 순서로. */
    fun topWeak(n: Int): List<String> =
        weakMap().entries.sortedByDescending { it.value }.take(n).map { it.key }

    /** 한 번의 따라 말하기 결과를 기록한다. 놓친 단어는 +1, 제대로 말한 약점 단어는 -1. */
    fun record(score: Score) {
        val m = weakMap()
        for (w in score.missed) if (w.length > 2) m[w] = (m[w] ?: 0) + 1
        for (w in score.words) {
            if (!w.ok) continue
            val k = wordKey(w.word)
            val c = m[k] ?: continue
            if (c <= 1) m.remove(k) else m[k] = c - 1
        }
        p.edit().putString("weak", JSONObject(m as Map<*, *>).toString()).apply()
        totalSentences += 1
    }
}
