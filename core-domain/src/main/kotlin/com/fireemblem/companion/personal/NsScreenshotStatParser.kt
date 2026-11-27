package com.fireemblem.companion.personal

import com.fireemblem.companion.engine.BuddyCampaignLine
import com.fireemblem.companion.engine.GrowthStat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class OcrTextBox(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float,
) {
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f
    val height: Float get() = (bottom - top).coerceAtLeast(0.001f)
    val width: Float get() = (right - left).coerceAtLeast(0.001f)
}

data class OcrSlotText(
    val text: String,
    val confidence: Float,
)

data class OcrRecognizedImage(
    val sourceUri: String,
    val width: Int = 1920,
    val height: Int = 1080,
    val boxes: List<OcrTextBox> = emptyList(),
    val detectedCrestLine: BuddyCampaignLine? = null,
    val crestReason: String? = null,
    val templateSlots: Map<String, OcrSlotText> = emptyMap(),
)

data class OcrCharacterRef(
    val id: String,
    val canonicalName: String,
    val exclusiveRoute: BuddyCampaignLine? = null,
)

data class OcrWikiDictionary(
    val characters: List<OcrCharacterRef>,
    val professions: List<String>,
)

enum class FieldConfidence {
    HIGH,
    REVIEW_NEEDED,
    MISSING,
}

data class ParsedRouteMergeDraft(
    val sourceUri: String,
    val characterId: String?,
    val characterName: String,
    val characterConfidence: FieldConfidence,
    val detectedLine: BuddyCampaignLine?,
    val lineReason: String?,
    val level: Int?,
    val levelConfidence: FieldConfidence,
    val profession: String,
    val professionConfidence: FieldConfidence,
    val stats: List<Int?>,
    val statConfidences: List<FieldConfidence>,
    val checksumDeclared: Int? = null,
    val checksumComputed: Int? = null,
    val checksumMatched: Boolean? = null,
    val rawLines: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    val hasAllRequiredStats: Boolean
        get() = stats.size == GrowthStat.entries.size && stats.all { it != null && it in 0..999 }

    val hasValidCoreFields: Boolean
        get() = !characterId.isNullOrBlank() &&
            level != null && level in 1..99 &&
            hasAllRequiredStats

    val isHighConfidenceReady: Boolean
        get() = hasValidCoreFields &&
            detectedLine != null &&
            characterConfidence == FieldConfidence.HIGH &&
            levelConfidence == FieldConfidence.HIGH &&
            statConfidences.all { it == FieldConfidence.HIGH } &&
            checksumMatched != false
}

object NsScreenshotStatParser {
    private val TRAD_TO_SIMP = mapOf(
        '劍' to '剑', '槍' to '枪', '騎' to '骑', '禦' to '御', '運' to '运',
        '級' to '级', '凱' to '凯', '賽' to '赛', '奧' to '奥', '朵' to '朵',
        '達' to '达', '亞' to '亚', '歷' to '历', '德' to '德', '羅' to '罗',
        '衛' to '卫', '戰' to '战', '車' to '车', '將' to '将', '領' to '领',
        '師' to '师', '聖' to '圣', '賢' to '贤', '俠' to '侠', '術' to '术',
        '無' to '无', '綜' to '综', '確' to '确', '認' to '认', '過' to '过',
        '關' to '关', '數' to '数', '據' to '据', '狀' to '状', '態' to '态',
        '團' to '团', '陣' to '阵', '營' to '营', '駝' to '驼', '龍' to '龙',
        '飛' to '飞', '裝' to '装', '點' to '点', '開' to '开', '時' to '时',
        '遊' to '游', '靈' to '灵', '壓' to '压', '麗' to '丽', '蘭' to '兰',
        '瑪' to '玛', '貝' to '贝', '爾' to '尔', '維' to '维', '薩' to '萨',
        '蘇' to '苏', '盧' to '卢', '萊' to '莱', '妮' to '妮', '強' to '强',
        '體' to '体', '氣' to '气', '聲' to '声', '義' to '义', '萬' to '万',
    )

    private val STAT_LABELS: Map<GrowthStat, List<String>> = mapOf(
        GrowthStat.HP to listOf("HP", "生命", "体力"),
        GrowthStat.STRENGTH to listOf("力量", "力"),
        GrowthStat.MAGIC to listOf("魔力", "魔"),
        GrowthStat.DEXTERITY to listOf("技巧", "技术", "技"),
        GrowthStat.SPEED to listOf("速度", "速"),
        GrowthStat.LUCK to listOf("幸运", "运气", "幸"),
        GrowthStat.DEFENSE to listOf("防守", "防御", "物防", "守备", "防"),
        GrowthStat.RESISTANCE to listOf("魔防", "抗魔"),
        GrowthStat.CHARM to listOf("魅力", "魅"),
    )

    private val NON_HP_STATS = listOf(
        GrowthStat.STRENGTH,
        GrowthStat.MAGIC,
        GrowthStat.DEXTERITY,
        GrowthStat.SPEED,
        GrowthStat.LUCK,
        GrowthStat.DEFENSE,
        GrowthStat.RESISTANCE,
        GrowthStat.CHARM,
    )

    private val ROUTE_TEXT_CUES: Map<BuddyCampaignLine, List<String>> = mapOf(
        BuddyCampaignLine.KAI to listOf("凯伊篇", "凯伊线", "凯伊队", "凯伊军"),
        BuddyCampaignLine.DIT to listOf("迪托利希篇", "迪托利希线", "迪托篇", "迪托线"),
        BuddyCampaignLine.SEO to listOf("赛奥朵拉篇", "赛奥朵拉线", "赛奥篇", "赛奥线"),
        BuddyCampaignLine.LEDA to listOf("蕾达篇", "蕾达线", "蕾达队"),
    )

    private val SLOT_KEY_BY_STAT: Map<GrowthStat, String> = mapOf(
        GrowthStat.HP to "hp",
        GrowthStat.STRENGTH to "STR",
        GrowthStat.MAGIC to "MAG",
        GrowthStat.DEXTERITY to "DEX",
        GrowthStat.SPEED to "SPD",
        GrowthStat.LUCK to "LCK",
        GrowthStat.DEFENSE to "DEF",
        GrowthStat.RESISTANCE to "RES",
        GrowthStat.CHARM to "CHM",
    )

    private val UI_NOISE_WORDS = setOf(
        "基本能力", "综合能力", "技能LV", "技能", "移动", "确认过关数据", "确认状态",
        "力量", "魔力", "速度", "技巧", "防守", "魔防", "幸运", "魅力", "HP", "LV",
    )

    fun parse(
        image: OcrRecognizedImage,
        dictionary: OcrWikiDictionary,
    ): ParsedRouteMergeDraft {
        val warnings = mutableListOf<String>()
        val normalizedBoxes = image.boxes
            .map { it.copy(text = normalizeChinese(it.text).trim()) }
            .filter { it.text.isNotEmpty() }

        val rawLines = buildList {
            if (image.templateSlots.isNotEmpty()) {
                image.templateSlots.forEach { (k, v) ->
                    if (v.text.isNotBlank()) add("$k: ${v.text}")
                }
            }
            normalizedBoxes.forEach { add(it.text) }
        }

        val hasTemplate = image.templateSlots.isNotEmpty()

        // 1. Extract Character
        val charMatch = if (hasTemplate) {
            matchCharacterFromText(image.templateSlots["name"], dictionary)
                ?: matchCharacterFromBoxes(normalizedBoxes, dictionary)
        } else {
            matchCharacterFromBoxes(normalizedBoxes, dictionary)
        }

        // 2. Extract Profession
        val profMatch = if (hasTemplate) {
            matchProfessionFromText(image.templateSlots["prof"], dictionary)
                ?: matchProfessionFromBoxes(normalizedBoxes, dictionary)
        } else {
            matchProfessionFromBoxes(normalizedBoxes, dictionary)
        }

        // 3. Extract Level
        val (level, levelConf) = if (hasTemplate) {
            parseIntSlot(image.templateSlots["lv"], 1..99)
                ?: extractLevelFromBoxes(normalizedBoxes)
        } else {
            extractLevelFromBoxes(normalizedBoxes)
        }

        // 4. Extract 9 Stats (template slots first, spatial box fallback per missing stat)
        val boxStats = extractStatsFromBoxes(normalizedBoxes)
        val parsedStats = MutableList<Int?>(GrowthStat.entries.size) { null }
        val statConfs = MutableList(GrowthStat.entries.size) { FieldConfidence.MISSING }
        GrowthStat.entries.forEach { stat ->
            val idx = stat.ordinal
            val slotKey = SLOT_KEY_BY_STAT[stat]
            val slotVal = if (hasTemplate && slotKey != null) {
                parseIntSlot(image.templateSlots[slotKey], 0..999)
            } else null
            val chosen = slotVal ?: boxStats[idx]
            if (chosen != null) {
                parsedStats[idx] = chosen.first
                statConfs[idx] = chosen.second
            }
        }

        // 5. Extract & Verify Built-in `综合能力` (Sum of 8 non-HP stats) Checksum
        val declaredSum8 = if (hasTemplate) {
            parseIntSlot(image.templateSlots["sum8"], 1..9999)?.first
                ?: extractSum8FromBoxes(normalizedBoxes)
        } else {
            extractSum8FromBoxes(normalizedBoxes)
        }
        val allNonHpPresent = NON_HP_STATS.all { parsedStats[it.ordinal] != null }
        val computedSum8 = if (allNonHpPresent) NON_HP_STATS.sumOf { parsedStats[it.ordinal]!! } else null
        val checksumMatched: Boolean? = if (declaredSum8 != null && computedSum8 != null) {
            declaredSum8 == computedSum8
        } else null

        if (checksumMatched == true) {
            // Mathematical proof that all 8 non-HP stats match the game's own checksum!
            NON_HP_STATS.forEach { stat ->
                if (statConfs[stat.ordinal] == FieldConfidence.REVIEW_NEEDED) {
                    statConfs[stat.ordinal] = FieldConfidence.HIGH
                }
            }
        } else if (checksumMatched == false) {
            warnings += "8 项能力之和（$computedSum8）与画面「综合能力 $declaredSum8」不一致，请核对高亮数值。"
            NON_HP_STATS.forEach { stat ->
                if (statConfs[stat.ordinal] == FieldConfidence.HIGH) {
                    statConfs[stat.ordinal] = FieldConfidence.REVIEW_NEEDED
                }
            }
        }

        // 6. 3-Tier Route Inference: Crest visual signature -> Explicit text cues -> Exclusive character route
        var detectedLine: BuddyCampaignLine? = null
        var lineReason: String? = null
        if (image.detectedCrestLine != null) {
            detectedLine = image.detectedCrestLine
            lineReason = image.crestReason ?: "画面路线徽章识别：${image.detectedCrestLine.label}"
        }
        if (detectedLine == null) {
            val allText = rawLines.joinToString(" ") { normalizeChinese(it) }
            for ((line, cues) in ROUTE_TEXT_CUES) {
                if (cues.any { allText.contains(it) }) {
                    detectedLine = line
                    lineReason = "画面文本识别：${line.label}"
                    break
                }
            }
        }
        if (detectedLine == null && charMatch?.ref?.exclusiveRoute != null) {
            detectedLine = charMatch.ref.exclusiveRoute
            lineReason = "角色专属：仅${charMatch.ref.exclusiveRoute.label}可加入"
        }

        if (charMatch == null) {
            warnings += "未能自动识别角色名，请手动选择角色。"
        } else if (charMatch.confidence == FieldConfidence.REVIEW_NEEDED) {
            warnings += "角色名「${charMatch.ref.canonicalName}」为模糊匹配，请确认无误。"
        }
        if (level == null) {
            warnings += "未能识别等级，请手动补全。"
        }
        if (parsedStats.any { it == null }) {
            val missing = GrowthStat.entries.filter { parsedStats[it.ordinal] == null }.joinToString("、") { it.label }
            warnings += "以下属性未识别出数值：$missing。"
        }
        if (detectedLine == null) {
            warnings += "未能自动判定所属路线，请手动选择或使用顶部批量设线。"
        }

        return ParsedRouteMergeDraft(
            sourceUri = image.sourceUri,
            characterId = charMatch?.ref?.id,
            characterName = charMatch?.ref?.canonicalName ?: "",
            characterConfidence = charMatch?.confidence ?: FieldConfidence.MISSING,
            detectedLine = detectedLine,
            lineReason = lineReason,
            level = level,
            levelConfidence = levelConf,
            profession = profMatch?.first.orEmpty(),
            professionConfidence = profMatch?.second ?: FieldConfidence.MISSING,
            stats = parsedStats,
            statConfidences = statConfs,
            checksumDeclared = declaredSum8,
            checksumComputed = computedSum8,
            checksumMatched = checksumMatched,
            rawLines = rawLines,
            warnings = warnings,
        )
    }

    /**
     * Detects duplicate (characterId, route) entries within a single import batch.
     * Returns map of index -> firstIndex it duplicates (0-based).
     */
    fun findBatchDuplicates(keys: List<Pair<String?, BuddyCampaignLine?>>): Map<Int, Int> {
        val seen = linkedMapOf<Pair<String, BuddyCampaignLine>, Int>()
        val duplicates = linkedMapOf<Int, Int>()
        keys.forEachIndexed { idx, (charId, line) ->
            if (!charId.isNullOrBlank() && line != null) {
                val k = charId to line
                val first = seen[k]
                if (first != null) {
                    duplicates[idx] = first
                } else {
                    seen[k] = idx
                }
            }
        }
        return duplicates
    }

    internal fun normalizeChinese(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            sb.append(TRAD_TO_SIMP[ch] ?: ch)
        }
        return sb.toString()
    }

    internal fun cleanNumericToken(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when (ch) {
                in '0'..'9' -> sb.append(ch)
                'O', 'o', 'Q', 'D' -> sb.append('0')
                'I', 'l', '|' -> sb.append('1')
                'S', 's' -> sb.append('5')
                'B' -> sb.append('8')
                '/' -> sb.append('/')
            }
        }
        return sb.toString()
    }

    private fun parseIntSlot(slot: OcrSlotText?, validRange: IntRange): Pair<Int, FieldConfidence>? {
        if (slot == null || slot.text.isBlank()) return null
        val cleaned = cleanNumericToken(slot.text)
        // Handle "38/38" HP format -> take max (right side)
        val numStr = if ('/' in cleaned) cleaned.substringAfterLast('/') else cleaned
        val digits = numStr.filter(Char::isDigit)
        val v = digits.toIntOrNull() ?: return null
        if (v !in validRange) return null
        val conf = if (slot.confidence >= 0.80f && digits.length == slot.text.trim().length) {
            FieldConfidence.HIGH
        } else {
            FieldConfidence.REVIEW_NEEDED
        }
        return v to conf
    }

    private data class CharacterMatch(val ref: OcrCharacterRef, val confidence: FieldConfidence, val score: Float)

    private fun matchCharacterFromText(slot: OcrSlotText?, dictionary: OcrWikiDictionary): CharacterMatch? {
        if (slot == null || slot.text.isBlank()) return null
        val cleaned = normalizeChinese(slot.text).replace(Regex("[^\\u4e00-\\u9fffA-Za-z·・]"), "").trim()
        if (cleaned.isEmpty()) return null
        return bestCharacterMatch(cleaned, slot.confidence, dictionary)
    }

    private fun matchCharacterFromBoxes(boxes: List<OcrTextBox>, dictionary: OcrWikiDictionary): CharacterMatch? {
        var best: CharacterMatch? = null
        // Character name normally sits in upper-left half (top < 0.35, left < 0.45)
        for (box in boxes) {
            if (box.top > 0.45f) continue
            val cleaned = box.text.replace(Regex("[^\\u4e00-\\u9fffA-Za-z·・]"), "").trim()
            if (cleaned.isEmpty() || cleaned in UI_NOISE_WORDS) continue
            if (cleaned.all(Char::isDigit)) continue
            val match = bestCharacterMatch(cleaned, box.confidence, dictionary) ?: continue
            val posBonus = if (box.left < 0.30f && box.top in 0.12f..0.24f) 0.15f else 0f
            val weighted = match.copy(score = match.score + posBonus)
            if (best == null || weighted.score > best.score) best = weighted
        }
        return best
    }

    private fun bestCharacterMatch(
        candidate: String,
        ocrConfidence: Float,
        dictionary: OcrWikiDictionary,
    ): CharacterMatch? {
        var bestRef: OcrCharacterRef? = null
        var bestSim = 0f
        for (ref in dictionary.characters) {
            val target = normalizeChinese(ref.canonicalName).trim()
            if (target.isEmpty()) continue
            val sim = when {
                candidate == target -> 1.0f
                candidate.length >= 2 && target.contains(candidate) -> 0.85f * (candidate.length.toFloat() / target.length)
                target.length >= 2 && candidate.contains(target) -> 0.90f * (target.length.toFloat() / candidate.length)
                else -> normalizedSimilarity(candidate, target)
            }
            if (sim > bestSim) {
                bestSim = sim
                bestRef = ref
            }
        }
        val ref = bestRef ?: return null
        return when {
            bestSim >= 0.95f && ocrConfidence >= 0.70f -> CharacterMatch(ref, FieldConfidence.HIGH, bestSim)
            bestSim >= 0.55f -> CharacterMatch(ref, FieldConfidence.REVIEW_NEEDED, bestSim)
            else -> null
        }
    }

    private fun matchProfessionFromText(slot: OcrSlotText?, dictionary: OcrWikiDictionary): Pair<String, FieldConfidence>? {
        if (slot == null || slot.text.isBlank()) return null
        val cleaned = normalizeChinese(slot.text).replace(Regex("[^\\u4e00-\\u9fff]"), "").trim()
        if (cleaned.isEmpty()) return null
        return bestProfessionMatch(cleaned, slot.confidence, dictionary)
    }

    private fun matchProfessionFromBoxes(boxes: List<OcrTextBox>, dictionary: OcrWikiDictionary): Pair<String, FieldConfidence>? {
        var best: Triple<String, FieldConfidence, Float>? = null
        for (box in boxes) {
            if (box.top > 0.50f) continue
            val cleaned = box.text.replace(Regex("[^\\u4e00-\\u9fff]"), "").trim()
            if (cleaned.length < 2 || cleaned in UI_NOISE_WORDS) continue
            val match = bestProfessionMatch(cleaned, box.confidence, dictionary) ?: continue
            val sim = if (match.second == FieldConfidence.HIGH) 1.0f else 0.7f
            val posBonus = if (box.top in 0.36f..0.46f && box.left < 0.35f) 0.15f else 0f
            val score = sim + posBonus
            if (best == null || score > best.third) best = Triple(match.first, match.second, score)
        }
        return best?.let { it.first to it.second }
    }

    private fun bestProfessionMatch(
        candidate: String,
        ocrConfidence: Float,
        dictionary: OcrWikiDictionary,
    ): Pair<String, FieldConfidence>? {
        var bestProf: String? = null
        var bestSim = 0f
        for (prof in dictionary.professions) {
            val norm = normalizeChinese(prof).trim()
            if (norm.isEmpty()) continue
            val sim = when {
                candidate == norm -> 1.0f
                candidate.contains(norm) -> 0.92f
                norm.contains(candidate) && candidate.length >= 2 -> 0.80f
                else -> normalizedSimilarity(candidate, norm)
            }
            if (sim > bestSim) {
                bestSim = sim
                bestProf = prof
            }
        }
        val prof = bestProf ?: return null
        return when {
            bestSim >= 0.90f && ocrConfidence >= 0.65f -> prof to FieldConfidence.HIGH
            bestSim >= 0.60f -> prof to FieldConfidence.REVIEW_NEEDED
            else -> null
        }
    }

    private fun extractLevelFromBoxes(boxes: List<OcrTextBox>): Pair<Int?, FieldConfidence> {
        // Inline match: "LV 39" / "Lv.39" / "等级39"
        val inlineRegex = Regex("(?:LV|Lv|lv|等级)[.：:\\s]*([0-9OoIl|]{1,2})")
        for (box in boxes) {
            if (box.top > 0.45f) continue
            if (box.text.contains("技能")) continue
            val m = inlineRegex.find(box.text)
            if (m != null) {
                val v = cleanNumericToken(m.groupValues[1]).toIntOrNull()
                if (v != null && v in 1..99) {
                    val conf = if (box.confidence >= 0.80f) FieldConfidence.HIGH else FieldConfidence.REVIEW_NEEDED
                    return v to conf
                }
            }
        }
        // Adjacent right-hand number box next to bare "LV" / "Lv" / "等级" label
        val lvBox = boxes.firstOrNull {
            it.top < 0.45f && !it.text.contains("技能") &&
                it.text.trim().uppercase() in setOf("LV", "LV.", "等级")
        }
        if (lvBox != null) {
            val neighbor = findRightNumberBox(lvBox, boxes, 1..99)
            if (neighbor != null) return neighbor
        }
        return null to FieldConfidence.MISSING
    }

    private fun extractSum8FromBoxes(boxes: List<OcrTextBox>): Int? {
        val inlineRegex = Regex("综合能力[:：\\s]*([0-9OoIl|]{2,4})")
        for (box in boxes) {
            val m = inlineRegex.find(box.text)
            if (m != null) {
                val v = cleanNumericToken(m.groupValues[1]).toIntOrNull()
                if (v != null && v in 1..9999) return v
            }
        }
        val labelBox = boxes.firstOrNull { it.text.contains("综合能力") }
        if (labelBox != null) {
            return findRightNumberBox(labelBox, boxes, 1..9999)?.first
        }
        return null
    }

    private fun extractStatsFromBoxes(boxes: List<OcrTextBox>): List<Pair<Int, FieldConfidence>?> {
        val upperBoxes = boxes.filter { it.top < 0.55f }
        return GrowthStat.entries.map { stat ->
            val labels = STAT_LABELS.getValue(stat)
            val candidateLabelBox = upperBoxes.firstOrNull { box ->
                val cleaned = box.text.replace(Regex("[()（）【】\\s]"), "")
                labels.any { lbl ->
                    if (lbl.length == 1) cleaned == lbl
                    else cleaned.startsWith(lbl) || (cleaned.contains(lbl) && cleaned.length <= lbl.length + 4)
                } && !cleaned.contains("技能") && !cleaned.contains("综合")
            } ?: return@map null

            // CRITICAL: Prefer a clean adjacent pure-number box to the right over a possibly truncated
            // trailing digit inside the label box (e.g. "魔力2" adjacent to "20" -> prefer 20!)
            val rightNeighbor = findRightNumberBox(candidateLabelBox, upperBoxes, 0..999)
            if (rightNeighbor != null) return@map rightNeighbor

            // Otherwise extract inline number from the same box ("力量23", "HP 44/44")
            val matchedLabel = labels.firstOrNull { candidateLabelBox.text.contains(it, ignoreCase = true) } ?: ""
            val tail = candidateLabelBox.text.substringAfter(matchedLabel, "")
            val cleaned = cleanNumericToken(tail)
            val numStr = if ('/' in cleaned) cleaned.substringAfterLast('/') else cleaned
            val v = numStr.filter(Char::isDigit).toIntOrNull()
            if (v != null && v in 0..999) {
                val conf = if (candidateLabelBox.confidence >= 0.80f) FieldConfidence.HIGH else FieldConfidence.REVIEW_NEEDED
                v to conf
            } else null
        }
    }

    private fun findRightNumberBox(
        labelBox: OcrTextBox,
        boxes: List<OcrTextBox>,
        validRange: IntRange,
    ): Pair<Int, FieldConfidence>? {
        val candidates = boxes.filter { other ->
            if (other === labelBox) return@filter false
            val sameRow = abs(other.centerY - labelBox.centerY) <= max(labelBox.height, other.height) * 0.85f
            val toRight = other.centerX > labelBox.centerX && (other.left - labelBox.right) in -0.025f..0.11f
            sameRow && toRight
        }.sortedBy { it.left }

        for (other in candidates) {
            val cleaned = cleanNumericToken(other.text)
            val numStr = if ('/' in cleaned) cleaned.substringAfterLast('/') else cleaned
            val digits = numStr.filter(Char::isDigit)
            if (digits.isEmpty() || digits.length < other.text.trim().length - 1) continue
            val v = digits.toIntOrNull() ?: continue
            if (v in validRange) {
                val conf = if (other.confidence >= 0.80f) FieldConfidence.HIGH else FieldConfidence.REVIEW_NEEDED
                return v to conf
            }
        }
        return null
    }

    private fun normalizedSimilarity(a: String, b: String): Float {
        if (a == b) return 1f
        val maxLen = max(a.length, b.length)
        if (maxLen == 0) return 0f
        val dist = levenshtein(a, b)
        return (1f - dist.toFloat() / maxLen.toFloat()).coerceIn(0f, 1f)
    }

    private fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)
        for (i in 1..m) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[n]
    }
}
