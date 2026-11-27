package com.fireemblem.companion.agent

import android.content.Context
import com.fireemblem.companion.content.CompanionGame
import com.fireemblem.companion.content.FuzzyMatcher
import com.fireemblem.companion.content.FuzzySuggestion
import com.fireemblem.companion.content.GameDataV1Repository
import com.fireemblem.companion.content.GamePackPageRequest
import com.fireemblem.companion.content.GamePackRepository
import com.fireemblem.companion.content.WikiDatabaseRepository
import com.fireemblem.companion.content.WikiPageRequest
import com.fireemblem.companion.content.WikiRepository
import com.fireemblem.companion.engine.EngageCharacterData
import com.fireemblem.companion.engine.EngageClassData
import com.fireemblem.companion.engine.ThreeHousesCharacterData
import com.fireemblem.companion.engine.ThreeHousesClassData
import java.util.Locale

data class AgentWikiSearchHit(
    val gameId: String,
    val gameTitle: String,
    val categoryId: String,
    val categoryTitle: String,
    val entryId: String,
    val title: String,
    val subtitle: String,
    val snippet: String,
    val score: Int,
) {
    fun toCitation(): AgentWikiCitation = AgentWikiCitation(
        gameId = gameId,
        gameTitle = gameTitle,
        categoryId = categoryId,
        entryId = entryId,
        title = title,
        subtitle = subtitle,
    )
}

data class AgentWikiEntryDetail(
    val citation: AgentWikiCitation,
    val summaryText: String,
)

/**
 * Unified offline cross-game SQLite retriever covering:
 * - 《万缕千丝》 (`fortunes-weave`, WikiDatabaseRepository)
 * - 《风花雪月》 (`three-houses`, GameDataV1Repository)
 * - 《Engage》 (`engage`, GameDataV1Repository)
 */
class CrossGameWikiRetriever(
    private val context: Context,
    private val borrowedFwRepository: WikiRepository? = null,
) : AutoCloseable {
    private var ownedFwRepository: WikiDatabaseRepository? = null
    private val gamePackCache = linkedMapOf<String, GamePackRepository?>()
    private var cachedThreeHousesPlannerData: Pair<List<ThreeHousesCharacterData>, List<ThreeHousesClassData>>? = null
    private var cachedEngagePlannerData: Pair<List<EngageCharacterData>, List<EngageClassData>>? = null
    private val lock = Any()

    fun threeHousesPlannerData(): Pair<List<ThreeHousesCharacterData>, List<ThreeHousesClassData>>? = synchronized(lock) {
        cachedThreeHousesPlannerData?.let { return@synchronized it }
        val repo = packRepo(CompanionGame.THREE_HOUSES.id) as? GameDataV1Repository ?: return@synchronized null
        runCatching { repo.loadThreeHousesPlannerData() }.getOrNull()?.also { cachedThreeHousesPlannerData = it }
    }

    fun engagePlannerData(): Pair<List<EngageCharacterData>, List<EngageClassData>>? = synchronized(lock) {
        cachedEngagePlannerData?.let { return@synchronized it }
        val repo = packRepo(CompanionGame.ENGAGE.id) as? GameDataV1Repository ?: return@synchronized null
        runCatching { repo.loadEngagePlannerData() }.getOrNull()?.also { cachedEngagePlannerData = it }
    }

    private fun fwRepo(): WikiRepository? = synchronized(lock) {
        borrowedFwRepository ?: ownedFwRepository ?: runCatching {
            WikiDatabaseRepository.open(context.applicationContext).also { ownedFwRepository = it }
        }.getOrNull()
    }

    private fun packRepo(gameId: String): GamePackRepository? = synchronized(lock) {
        if (gameId in gamePackCache) return@synchronized gamePackCache[gameId]
        val opened = runCatching { GameDataV1Repository.open(context.applicationContext, gameId) }.getOrNull()
        gamePackCache[gameId] = opened
        opened
    }

    fun suggest(
        query: String,
        gameFilter: String = "all",
        limit: Int = 8,
    ): List<FuzzySuggestion> {
        val raw = query.trim()
        if (raw.isEmpty()) return emptyList()
        val targetGames = when (gameFilter.trim().lowercase(Locale.ROOT)) {
            CompanionGame.FORTUNES_WEAVE.id, "fw", "万缕千丝" -> listOf(CompanionGame.FORTUNES_WEAVE)
            CompanionGame.THREE_HOUSES.id, "3h", "风花雪月" -> listOf(CompanionGame.THREE_HOUSES)
            CompanionGame.ENGAGE.id, "engage" -> listOf(CompanionGame.ENGAGE)
            else -> listOf(CompanionGame.FORTUNES_WEAVE, CompanionGame.THREE_HOUSES, CompanionGame.ENGAGE)
        }
        val all = mutableListOf<FuzzySuggestion>()
        for (game in targetGames) {
            val items = when (game) {
                CompanionGame.FORTUNES_WEAVE -> fwRepo()?.suggest(raw, limit = limit).orEmpty()
                CompanionGame.THREE_HOUSES, CompanionGame.ENGAGE -> packRepo(game.id)?.suggest(raw, limit = limit).orEmpty()
            }
            items.forEach { s ->
                all += s.copy(
                    badge = "${game.shortTitle} · ${s.badge}",
                    gameId = game.id,
                )
            }
        }
        return all.sortedWith(compareByDescending<FuzzySuggestion> { it.score }.thenBy { it.title.length })
            .take(limit.coerceIn(1, 16))
    }

    fun search(
        query: String,
        gameFilter: String = "all",
        categoryFilter: String? = null,
        limit: Int = 8,
    ): List<AgentWikiSearchHit> {
        val raw = query.trim()
        if (raw.isEmpty()) return emptyList()
        val cappedLimit = limit.coerceIn(1, 20)
        val tokens = tokenize(raw)
        val normalizedFull = raw.lowercase(Locale.ROOT)
        val targetGames = when (gameFilter.trim().lowercase(Locale.ROOT)) {
            CompanionGame.FORTUNES_WEAVE.id, "fw", "万缕千丝" -> listOf(CompanionGame.FORTUNES_WEAVE)
            CompanionGame.THREE_HOUSES.id, "3h", "风花雪月" -> listOf(CompanionGame.THREE_HOUSES)
            CompanionGame.ENGAGE.id, "engage" -> listOf(CompanionGame.ENGAGE)
            else -> listOf(CompanionGame.FORTUNES_WEAVE, CompanionGame.THREE_HOUSES, CompanionGame.ENGAGE)
        }

        val hitsByKey = linkedMapOf<String, AgentWikiSearchHit>()
        val probeQueries = buildList {
            add(raw)
            tokens.forEach { tok -> if (tok != raw) add(tok) }
        }.take(4)

        for (game in targetGames) {
            when (game) {
                CompanionGame.FORTUNES_WEAVE -> {
                    val repo = fwRepo() ?: continue
                    val catMap = repo.catalog.categories.associate { it.id to it.title }
                    val validCat = categoryFilter?.takeIf { it.isNotBlank() && it in catMap }
                    val probeCategories: List<String?> = if (validCat != null) {
                        listOf(validCat)
                    } else {
                        buildList {
                            if ("characters" in catMap) add("characters")
                            if ("professions" in catMap) add("professions")
                            if ("skills" in catMap) add("skills")
                            add(null)
                        }
                    }
                    for (probe in probeQueries) {
                        for (probeCat in probeCategories) {
                            val page = runCatching {
                                repo.page(
                                    WikiPageRequest(
                                        categoryId = probeCat,
                                        query = probe,
                                        offset = 0,
                                        limit = WikiPageRequest.MAX_PAGE_SIZE,
                                    ),
                                )
                            }.getOrNull() ?: continue
                            for (entry in page.entries) {
                                val catId = entry.id.substringBefore(':')
                                val snippet = entry.fields.take(4)
                                    .filter { it.value.isNotBlank() }
                                    .joinToString(" · ") { "${it.label}: ${it.value.take(40)}" }
                                val score = scoreMatch(
                                    title = entry.title,
                                    subtitle = entry.subtitle,
                                    body = snippet,
                                    categoryId = catId,
                                    fullQuery = normalizedFull,
                                    tokens = tokens,
                                )
                                val key = "${game.id}|${entry.id}"
                                val hit = AgentWikiSearchHit(
                                    gameId = game.id,
                                    gameTitle = game.shortTitle,
                                    categoryId = catId,
                                    categoryTitle = catMap[catId] ?: catId,
                                    entryId = entry.id,
                                    title = entry.title,
                                    subtitle = entry.subtitle,
                                    snippet = snippet.take(180),
                                    score = score,
                                )
                                val prev = hitsByKey[key]
                                if (prev == null || hit.score > prev.score) hitsByKey[key] = hit
                            }
                        }
                    }
                }
                CompanionGame.THREE_HOUSES, CompanionGame.ENGAGE -> {
                    val repo = packRepo(game.id) ?: continue
                    val catMap = repo.catalog.categories.associate { it.id to it.title }
                    val validCat = resolvePackCategory(categoryFilter, catMap.keys)
                    val probeCategories: List<String?> = if (validCat != null) {
                        listOf(validCat)
                    } else {
                        buildList {
                            if ("characters" in catMap) add("characters")
                            if ("classes" in catMap) add("classes")
                            if ("skills" in catMap) add("skills")
                            add(null)
                        }
                    }
                    for (probe in probeQueries) {
                        for (probeCat in probeCategories) {
                            val page = runCatching {
                                repo.page(
                                    GamePackPageRequest(
                                        categoryId = probeCat,
                                        query = probe,
                                        offset = 0,
                                        limit = GamePackPageRequest.MAX_PAGE_SIZE,
                                    ),
                                )
                            }.getOrNull() ?: continue
                            for (entry in page.entries) {
                                val extra = listOfNotNull(entry.titleEn, entry.titleJa, entry.subtitle)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" · ")
                                val score = scoreMatch(
                                    title = entry.title,
                                    subtitle = extra,
                                    body = entry.subtitle,
                                    categoryId = entry.categoryId,
                                    fullQuery = normalizedFull,
                                    tokens = tokens,
                                )
                                val key = "${game.id}|${entry.id}"
                                val hit = AgentWikiSearchHit(
                                    gameId = game.id,
                                    gameTitle = game.shortTitle,
                                    categoryId = entry.categoryId,
                                    categoryTitle = catMap[entry.categoryId] ?: entry.categoryId,
                                    entryId = entry.id,
                                    title = entry.title,
                                    subtitle = entry.subtitle,
                                    snippet = extra.take(180),
                                    score = score,
                                )
                                val prev = hitsByKey[key]
                                if (prev == null || hit.score > prev.score) hitsByKey[key] = hit
                            }
                        }
                    }
                }
            }
        }

        return hitsByKey.values
            .sortedWith(compareByDescending<AgentWikiSearchHit> { it.score }.thenBy { it.title.length }.thenBy { it.title })
            .take(cappedLimit)
    }

    fun getEntryDetail(gameId: String, entryId: String): AgentWikiEntryDetail? {
        val game = CompanionGame.fromId(gameId)
        return when (game) {
            CompanionGame.FORTUNES_WEAVE -> {
                val repo = fwRepo() ?: return null
                val entry = runCatching { repo.entry(entryId) }.getOrNull() ?: return null
                val catId = entry.id.substringBefore(':')
                val sb = StringBuilder()
                sb.appendLine("【${game.shortTitle}】${entry.title}" + if (entry.subtitle.isNotBlank()) "（${entry.subtitle}）" else "")
                entry.fields.forEach { f ->
                    if (f.value.isNotBlank() && sb.length < MAX_DETAIL_CHARS) {
                        sb.appendLine("- ${f.label}：${f.value.take(260)}")
                    }
                }
                if (catId == "characters") {
                    val routes = runCatching { repo.characterRoutes(entryId) }.getOrDefault(emptyList())
                    if (routes.isNotEmpty()) {
                        sb.appendLine("四线加入与招募：")
                        routes.forEach { r ->
                            val details = listOfNotNull(
                                r.joinChapter.takeIf { it.isNotBlank() },
                                r.initialLevel?.let { "初始 Lv.$it" },
                                r.poachCondition.takeIf { it.isNotBlank() }?.let { "挖角：$it" },
                                r.supportReq.takeIf { it.isNotBlank() }?.let { "支援：$it" },
                                r.contactLocation.takeIf { it.isNotBlank() }?.let { "地点：$it" },
                            ).joinToString("，")
                            sb.appendLine("  * ${r.routeLabel}（${r.joinKind}，声望 ${r.fameLevel}）：$details")
                        }
                    }
                    val learning = runCatching { repo.characterLearning(entryId) }.getOrNull()
                    if (learning != null && learning.skills.isNotEmpty() && sb.length < MAX_DETAIL_CHARS) {
                        val grouped = learning.skills.groupBy { it.groupLabel }
                        sb.appendLine("可习得技能：")
                        grouped.forEach { (grp, list) ->
                            val names = list.take(8).joinToString("、") {
                                if (it.requirement.isNotBlank()) "${it.name}(${it.requirement})" else it.name
                            }
                            sb.appendLine("  * $grp：$names")
                        }
                    }
                }
                val links = runCatching { repo.links(entryId) }.getOrDefault(emptyList()).take(10)
                if (links.isNotEmpty() && sb.length < MAX_DETAIL_CHARS) {
                    sb.appendLine("相关词条：${links.joinToString("、") { "${it.title}[${it.label}]" }}")
                }
                AgentWikiEntryDetail(
                    citation = AgentWikiCitation(game.id, game.shortTitle, catId, entry.id, entry.title, entry.subtitle),
                    summaryText = sb.toString().take(MAX_DETAIL_CHARS),
                )
            }
            CompanionGame.THREE_HOUSES, CompanionGame.ENGAGE -> {
                val repo = packRepo(game.id) ?: return null
                val detail = runCatching { repo.entry(entryId) }.getOrNull() ?: return null
                val s = detail.summary
                val sb = StringBuilder()
                sb.appendLine("【${game.shortTitle}】${s.title}" + if (s.subtitle.isNotBlank()) "（${s.subtitle}）" else "")
                detail.characterWiki?.let { cw ->
                    cw.affiliation?.takeIf { it.isNotBlank() }?.let { sb.appendLine("- 势力/学级：$it") }
                    cw.startingClass?.takeIf { it.isNotBlank() }?.let { sb.appendLine("- 初始职业：$it") }
                    cw.crest?.takeIf { it.isNotBlank() }?.let { sb.appendLine("- 纹章：$it") }
                    cw.personalSkillName?.takeIf { it.isNotBlank() }?.let {
                        sb.appendLine("- 个人特技：$it" + (cw.personalSkillDescription?.let { d -> "（$d）" } ?: ""))
                    }
                    cw.joinCondition?.takeIf { it.isNotBlank() }?.let { sb.appendLine("- 加入/招募条件：$it") }
                    cw.routeAvailability?.takeIf { it.isNotBlank() }?.let { sb.appendLine("- 路线可用性：$it") }
                    if (cw.likedGifts.isNotEmpty()) {
                        sb.appendLine("- 喜欢的礼物：${cw.likedGifts.take(8).joinToString("、") { it.name }}")
                    }
                    if (cw.likedMeals.isNotEmpty()) {
                        sb.appendLine("- 喜欢的料理：${cw.likedMeals.take(8).joinToString("、") { it.name }}")
                    }
                }
                detail.sections.forEach { sec ->
                    if (sb.length >= MAX_DETAIL_CHARS) return@forEach
                    sb.appendLine("${sec.title}：")
                    sec.fields.forEach { f ->
                        if (f.value.isNotBlank() && sb.length < MAX_DETAIL_CHARS) {
                            sb.appendLine("  * ${f.label}：${f.value.take(260)}")
                        }
                    }
                }
                if (detail.relations.isNotEmpty() && sb.length < MAX_DETAIL_CHARS) {
                    sb.appendLine("关联词条：${detail.relations.take(10).joinToString("、") { "${it.targetTitle}(${it.relationType})" }}")
                }
                AgentWikiEntryDetail(
                    citation = AgentWikiCitation(game.id, game.shortTitle, s.categoryId, s.id, s.title, s.subtitle),
                    summaryText = sb.toString().take(MAX_DETAIL_CHARS),
                )
            }
        }
    }

    override fun close() = synchronized(lock) {
        runCatching { ownedFwRepository?.close() }
        ownedFwRepository = null
        gamePackCache.values.forEach { runCatching { it?.close() } }
        gamePackCache.clear()
    }

    companion object {
        private const val MAX_DETAIL_CHARS = 3_400

        private val STOP_WORDS = setOf(
            "的", "了", "吗", "呢", "啊", "吧", "么", "怎么", "什么", "哪些", "哪里", "如何",
            "帮忙", "帮我", "请问", "查一下", "查询", "一下", "一下下", "和", "与", "及", "还是",
        )

        internal fun tokenize(raw: String): List<String> {
            val parts = raw.trim()
                .split(Regex("[\\s,，。！？?!、/|:：()（）\\[\\]【】·\\-]+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() && it !in STOP_WORDS }
            return parts.ifEmpty { listOf(raw.trim()) }.distinct()
        }

        private fun resolvePackCategory(categoryFilter: String?, validCats: Set<String>): String? {
            val c = categoryFilter?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (c in validCats) return c
            // Map cross-game aliases (`professions` <-> `classes`)
            if (c == "professions" && "classes" in validCats) return "classes"
            if (c == "classes" && "professions" in validCats) return "professions"
            return null
        }

        private fun scoreMatch(
            title: String,
            subtitle: String,
            body: String,
            categoryId: String,
            fullQuery: String,
            tokens: List<String>,
        ): Int {
            val t = title.lowercase(Locale.ROOT)
            val s = subtitle.lowercase(Locale.ROOT)
            val b = body.lowercase(Locale.ROOT)
            var score = when (categoryId) {
                "characters" -> 18
                "professions", "classes" -> 14
                "skills", "emblems" -> 8
                else -> 0
            }
            if (t == fullQuery) score += 120
            else if (t.startsWith(fullQuery)) score += 85
            else if (t.contains(fullQuery)) score += 65
            for (tok in tokens) {
                val lt = tok.lowercase(Locale.ROOT)
                if (t == lt) score += 60
                else if (t.contains(lt)) score += 28
                if (s.contains(lt)) score += 12
                else if (b.contains(lt)) score += 6
            }
            return score
        }
    }
}
