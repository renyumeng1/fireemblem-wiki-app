package com.fireemblem.companion.content

import java.util.Locale

data class FuzzySuggestion(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val badge: String = "",
    val imageAsset: String? = null,
    val score: Int = 0,
    val gameId: String? = null,
    val categoryId: String? = null,
)

/**
 * Pure-Kotlin CJK + Latin fuzzy matcher supporting:
 * - Exact, prefix, and substring match
 * - Ordered subsequence match (e.g. "皮鲁" -> "皮特鲁", "天马骑" -> "圣天马骑士")
 * - Multi-token unordered match (e.g. "凯伊 勇士", "圣天马 成长")
 * - Typo tolerance via bounded Levenshtein edit distance (e.g. "凯一" -> "凯伊", "圣天吗" -> "圣天马骑士")
 * - Character-overlap recall for longer queries
 */
object FuzzyMatcher {
    private val SPLIT_REGEX = Regex("[\\s,，、/·|・:：;；]+")
    private val PUNCT_STRIP = Regex("[\\s·・\\-_()（）\\[\\]【】「」『』/\\\\,:;，。、：；！？!?'\"`~]+")

    fun normalize(raw: String): String =
        raw.trim().lowercase(Locale.ROOT).replace(PUNCT_STRIP, "")

    fun tokenize(raw: String): List<String> =
        raw.trim()
            .lowercase(Locale.ROOT)
            .split(SPLIT_REGEX)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /**
     * Escapes SQLite LIKE special chars and inserts `%` between characters for ordered subsequence recall.
     * Example: `"皮鲁"` -> `"%皮%鲁%"`.
     */
    fun sqlSubsequenceLikePattern(raw: String, maxChars: Int = 10): String {
        val compact = normalize(raw).take(maxChars)
        if (compact.isEmpty()) return "%"
        val sb = StringBuilder("%")
        for (ch in compact) {
            if (ch == '%' || ch == '_' || ch == '\\') sb.append('\\')
            sb.append(ch).append('%')
        }
        return sb.toString()
    }

    fun score(
        query: String,
        title: String,
        subtitle: String = "",
        searchText: String = "",
    ): Int {
        val rawQ = query.trim().lowercase(Locale.ROOT)
        if (rawQ.isEmpty()) return 0
        val normQ = normalize(rawQ)
        if (normQ.isEmpty()) return 0

        val normTitle = normalize(title)
        val normSub = normalize(subtitle)
        val lowerTitle = title.lowercase(Locale.ROOT)
        val lowerSearch = searchText.lowercase(Locale.ROOT)

        var best = 0

        // 1. Direct exact / prefix / substring on title
        when {
            normTitle == normQ -> best = maxOf(best, 1000)
            normTitle.startsWith(normQ) -> best = maxOf(best, 760 - (normTitle.length - normQ.length).coerceAtMost(80))
            normTitle.contains(normQ) -> best = maxOf(best, 580 - normTitle.indexOf(normQ).coerceAtMost(60))
        }

        // 2. Ordered subsequence on title (e.g. "皮鲁" -> "皮特鲁", "天马骑" -> "圣天马骑士")
        if (normQ.length >= 2 && normTitle.length >= normQ.length) {
            val subseqGap = subsequenceGapPenalty(normQ, normTitle)
            if (subseqGap >= 0) {
                best = maxOf(best, (460 - subseqGap * 14).coerceAtLeast(240))
            }
        }

        // 3. Multi-token match across title + subtitle + searchText
        val tokens = tokenize(rawQ)
        if (tokens.size >= 2) {
            var titleHits = 0
            var anyHits = 0
            for (tok in tokens) {
                val nt = normalize(tok)
                if (nt.isEmpty()) continue
                val inTitle = normTitle.contains(nt) || (nt.length >= 2 && subsequenceGapPenalty(nt, normTitle) >= 0)
                val inAny = inTitle || normSub.contains(nt) || lowerSearch.contains(tok)
                if (inTitle) titleHits++
                if (inAny) anyHits++
            }
            if (anyHits == tokens.size) {
                best = maxOf(best, 340 + titleHits * 65)
            } else if (titleHits > 0 && anyHits * 2 >= tokens.size) {
                best = maxOf(best, 190 + titleHits * 45)
            }
        }

        // 4. Subtitle / searchText substring or subsequence
        if (best == 0) {
            when {
                normSub.contains(normQ) -> best = 260
                lowerSearch.contains(rawQ) -> best = 220
                normQ.length >= 2 && normSub.isNotEmpty() && subsequenceGapPenalty(normQ, normSub) >= 0 -> best = 200
            }
        }

        // 5. Typo tolerance on title via Levenshtein edit distance (only when no direct/subseq hit yet)
        if (best < 240 && normQ.length >= 2 && normTitle.isNotEmpty()) {
            val maxDist = if (normQ.length <= 4) 1 else 2
            val windowDist = bestWindowEditDistance(normQ, normTitle, maxDist)
            if (windowDist in 0..maxDist) {
                val typoScore = 300 - windowDist * 75 - kotlin.math.abs(normTitle.length - normQ.length).coerceAtMost(20) * 3
                best = maxOf(best, typoScore.coerceAtLeast(140))
            }
        }

        // 6. CJK character overlap fallback for partial misspellings (e.g. "皮特路" -> "皮特鲁")
        if (best == 0 && normQ.length >= 2 && normTitle.isNotEmpty()) {
            val qChars = normQ.toSet()
            val overlap = qChars.count { it in normTitle }
            val ratio = overlap.toFloat() / qChars.size.toFloat()
            if (overlap >= 2 && ratio >= 0.6f) {
                best = (130 + (ratio * 80).toInt()).coerceAtMost(210)
            } else if (normQ.length == 1 && lowerTitle.contains(rawQ)) {
                best = 400
            }
        }

        return best
    }

    fun matches(query: String, title: String, subtitle: String = "", searchText: String = ""): Boolean =
        score(query, title, subtitle, searchText) > 0

    /**
     * Returns character indices in [title] that match [query] (contiguous substring first,
     * falling back to ordered subsequence or shared character set) for bold visual highlighting.
     */
    fun highlightIndices(query: String, title: String): Set<Int> {
        val normQ = normalize(query)
        if (normQ.isEmpty() || title.isEmpty()) return emptySet()
        val lowerTitle = title.lowercase(Locale.ROOT)
        val rawQ = query.trim().lowercase(Locale.ROOT)

        // Exact contiguous substring highlight first
        val directIdx = lowerTitle.indexOf(rawQ)
        if (directIdx >= 0) {
            return (directIdx until (directIdx + rawQ.length).coerceAtMost(title.length)).toSet()
        }

        // Ordered subsequence indices
        val subseq = linkedSetOf<Int>()
        var qi = 0
        for (ti in lowerTitle.indices) {
            if (qi < normQ.length && lowerTitle[ti] == normQ[qi]) {
                subseq += ti
                qi++
            }
        }
        if (qi == normQ.length) return subseq

        // Fallback: highlight all matching characters from query tokens
        val charSet = normQ.toSet()
        return lowerTitle.indices.filterTo(linkedSetOf()) { lowerTitle[it] in charSet }
    }

    /** Returns total skipped character gap if [needle] is an ordered subsequence of [haystack], else -1. */
    private fun subsequenceGapPenalty(needle: String, haystack: String): Int {
        var ni = 0
        var firstPos = -1
        var lastPos = -1
        for (hi in haystack.indices) {
            if (ni < needle.length && haystack[hi] == needle[ni]) {
                if (firstPos < 0) firstPos = hi
                lastPos = hi
                ni++
            }
        }
        if (ni < needle.length) return -1
        val span = lastPos - firstPos + 1
        return (span - needle.length).coerceAtLeast(0)
    }

    /** Minimum Levenshtein distance between [query] and [target] (or any length-matched window in [target]). */
    private fun bestWindowEditDistance(query: String, target: String, maxAllowed: Int): Int {
        val m = query.length
        val n = target.length
        if (n <= m + maxAllowed) {
            return levenshtein(query, target, maxAllowed)
        }
        var best = maxAllowed + 1
        val winMin = (m - maxAllowed).coerceAtLeast(1)
        val winMax = (m + maxAllowed).coerceAtMost(n)
        for (start in 0..(n - winMin)) {
            for (len in winMin..winMax) {
                if (start + len > n) break
                val d = levenshtein(query, target.substring(start, start + len), best - 1)
                if (d < best) best = d
                if (best == 0) return 0
            }
        }
        return best
    }

    private fun levenshtein(a: String, b: String, limit: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = i
            val ca = a[i - 1]
            for (j in 1..b.length) {
                val cost = if (ca == b[j - 1]) 0 else 1
                val v = minOf(
                    prev[j] + 1,
                    cur[j - 1] + 1,
                    prev[j - 1] + cost,
                )
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > limit) return limit + 1
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[b.length]
    }
}
