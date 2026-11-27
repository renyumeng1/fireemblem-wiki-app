package com.fireemblem.companion.content

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class WikiDatabaseException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal fun interface WikiAssetSource {
    fun open(name: String): InputStream
}

class WikiDatabaseRepository private constructor(
    private val database: SQLiteDatabase,
    updateNotice: String? = null,
) : WikiRepository {
    override val catalog: WikiCatalog

    init {
        val categories = database.rawQuery(
            "SELECT category.id,category.title,category.description,COUNT(entry.id)," +
                "(SELECT sample.image_asset FROM wiki_entries sample " +
                "WHERE sample.category_id=category.id AND sample.image_asset IS NOT NULL AND sample.image_asset!='' " +
                "ORDER BY sample.source_row,sample.id LIMIT 1) " +
                "FROM wiki_categories category " +
                "LEFT JOIN wiki_entries entry ON entry.category_id=category.id GROUP BY category.id ORDER BY category.position",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    WikiCategorySummary(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getInt(3),
                        cursor.nullableString(4),
                    ),
                )
            }
        }
        check(categories.size == metadata("category_count").toInt()) { "分类数量不匹配" }
        check(categories.sumOf(WikiCategorySummary::entryCount) == metadata("entry_count").toInt()) { "条目数量不匹配" }
        catalog = WikiCatalog(metadata("source_title"), metadata("source_revision"), metadata("content_version"), categories, updateNotice)
    }

    override fun entry(id: String): WikiEntry? = database.rawQuery(
        "SELECT entry.id,entry.title,entry.subtitle,entry.image_asset,category.source_sheet_name,entry.source_row " +
            "FROM wiki_entries entry JOIN wiki_categories category ON category.id=entry.category_id WHERE entry.id=? LIMIT 1",
        arrayOf(id),
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else readEntry(cursor, fields(id))
    }

    override fun page(request: WikiPageRequest): WikiEntryPage {
        val definitions = request.categoryId?.let(::options).orEmpty()
        val filter = request.filterField?.let { key -> definitions.firstOrNull { it.key == key }
            ?: throw IllegalArgumentException("Unknown filter") }
        val sort = request.sortField?.let { key -> definitions.firstOrNull { it.key == key && it.numeric }
            ?: throw IllegalArgumentException("Unknown numeric sort") }
        val table = request.categoryId?.replace('-', '_')?.plus("_details")
        val routeKey = request.routeKey?.takeIf {
            request.categoryId == "characters" && it.isNotBlank() && hasTable("character_route_order")
        }
        val detailJoin = if (filter != null || sort != null) " JOIN $table detail ON detail.entry_id=entry.id " else " "
        val routeJoin = if (routeKey != null) {
            " JOIN character_route_order cro ON cro.character_entry_id=entry.id AND cro.route_key=? AND cro.join_kind!='unrecruitable' "
        } else {
            " "
        }
        val join = detailJoin + routeJoin
        val query = request.query.trim()
        val fuzzyTokens = if (query.isNotEmpty()) FuzzyMatcher.tokenize(query) else emptyList()
        val filters = buildList {
            if (request.categoryId != null) add("entry.category_id=?")
            if (query.isNotEmpty()) {
                val subClause = if (fuzzyTokens.size >= 2) {
                    fuzzyTokens.joinToString(" AND ") { "instr(lower(entry.search_text),lower(?))>0" }
                } else {
                    "instr(lower(entry.search_text),lower(?))>0"
                }
                add("(($subClause) OR lower(entry.title) LIKE ? ESCAPE '\\')")
            }
            if (filter != null) add("detail.${filter.key}=?")
        }.joinToString(" AND ").ifEmpty { "1=1" }
        val parameters = buildList {
            routeKey?.let(::add)
            request.categoryId?.let(::add)
            if (query.isNotEmpty()) {
                if (fuzzyTokens.size >= 2) fuzzyTokens.forEach(::add) else add(query)
                add(FuzzyMatcher.sqlSubsequenceLikePattern(query))
            }
            if (filter != null) add(requireNotNull(request.filterValue))
        }.toTypedArray()
        var total = database.rawQuery("SELECT COUNT(*) FROM wiki_entries entry $join WHERE $filters", parameters).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
        val subtitleSelect = if (routeKey != null) {
            "CASE " +
                "WHEN cro.join_kind='story' THEN '剧情加入 · '||cro.join_chapter||COALESCE(' · Lv.'||cro.initial_level,'') " +
                "WHEN cro.join_kind='free' THEN '免费招募(名声2) · '||cro.join_chapter||COALESCE(' · Lv.'||cro.initial_level,'')||(CASE WHEN cro.recommended=1 THEN ' · ✅' ELSE '' END) " +
                "WHEN cro.join_kind='fame' THEN '名声 Lv.'||cro.fame_level||' · '||cro.join_chapter||CASE WHEN cro.support_req!='' THEN ' · '||cro.support_req ELSE '' END||CASE WHEN cro.recommended=1 THEN ' · ✅' ELSE '' END " +
                "WHEN cro.join_kind='prologue' THEN cro.join_chapter " +
                "WHEN cro.join_kind='part2' THEN cro.join_chapter||' · 第2部加入' " +
                "WHEN cro.join_kind='part3' THEN cro.join_chapter||' · 第3部加入' " +
                "ELSE COALESCE(NULLIF(cro.join_chapter,''),entry.subtitle) END"
        } else {
            "entry.subtitle"
        }
        if (query.isNotEmpty() && total == 0) {
            // Typo-tolerant fallback scan (e.g. "皮特路" -> "皮特鲁", "圣天吗" -> "圣天马骑士")
            val baseFilters = buildList {
                if (request.categoryId != null) add("entry.category_id=?")
                if (filter != null) add("detail.${filter.key}=?")
            }.joinToString(" AND ").ifEmpty { "1=1" }
            val baseParams = buildList {
                routeKey?.let(::add)
                request.categoryId?.let(::add)
                if (filter != null) add(requireNotNull(request.filterValue))
            }.toTypedArray()
            val fuzzyMatched = database.rawQuery(
                "SELECT entry.id,entry.title,$subtitleSelect,entry.image_asset,category.source_sheet_name,entry.source_row,entry.search_text " +
                    "FROM wiki_entries entry JOIN wiki_categories category ON category.id=entry.category_id $join WHERE $baseFilters",
                baseParams,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val title = cursor.getString(1)
                        val subtitle = cursor.getString(2)
                        val searchTxt = cursor.getString(6)
                        val sc = FuzzyMatcher.score(query, title, subtitle, searchTxt)
                        if (sc > 0) add(sc to readEntry(cursor, emptyList()))
                    }
                }.sortedWith(compareByDescending<Pair<Int, WikiEntry>> { it.first }.thenBy { it.second.title })
                    .map { it.second }
            }
            total = fuzzyMatched.size
            val slice = fuzzyMatched.drop(request.offset).take(request.limit)
            return WikiEntryPage(slice, total, request.offset, request.limit)
        }
        val orderBy = buildString {
            if (sort != null) {
                append("detail.${sort.key}_numeric IS NULL,detail.${sort.key}_numeric ")
                append(if (request.descending) "DESC," else "ASC,")
            }
            if (routeKey != null) {
                if (routeKey == "other") {
                    append("cro.fame_level ASC,cro.join_chapter_num ASC,cro.source_order ASC,")
                } else {
                    val fameDir = if (request.fameDescending) "DESC" else "ASC"
                    append("cro.fame_level $fameDir,cro.join_chapter_num ASC,cro.source_order ASC,")
                }
            }
            if (query.isNotEmpty()) {
                append("CASE WHEN lower(entry.title)=lower(?) THEN 0 WHEN lower(entry.title) LIKE lower(?)||'%' THEN 1 WHEN instr(lower(entry.title),lower(?))>0 THEN 2 ELSE 3 END,")
            }
            append("entry.title COLLATE NOCASE,entry.id")
        }
        val queryOrderParams = if (query.isNotEmpty()) arrayOf(query, query, query) else emptyArray()
        val entries = database.rawQuery(
            "SELECT entry.id,entry.title,$subtitleSelect,entry.image_asset,category.source_sheet_name,entry.source_row " +
                "FROM wiki_entries entry JOIN wiki_categories category ON category.id=entry.category_id $join WHERE $filters " +
                "ORDER BY $orderBy LIMIT ? OFFSET ?",
            parameters + queryOrderParams + arrayOf(request.limit.toString(), request.offset.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(readEntry(cursor, emptyList()))
            }
        }
        return WikiEntryPage(entries, total, request.offset, request.limit)
    }

    override fun suggest(query: String, categoryId: String?, limit: Int): List<FuzzySuggestion> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val catTitles = catalog.categories.associate { it.id to it.title }
        val capped = limit.coerceIn(1, 16)
        val likePattern = FuzzyMatcher.sqlSubsequenceLikePattern(trimmed)
        val catWhere = if (categoryId != null) "entry.category_id=? AND " else ""
        val args = buildList {
            if (categoryId != null) add(categoryId)
            add(likePattern)
            add(trimmed)
        }.toTypedArray()

        val scored = mutableListOf<FuzzySuggestion>()
        database.rawQuery(
            "SELECT entry.id,entry.category_id,entry.title,entry.subtitle,entry.image_asset,entry.search_text " +
                "FROM wiki_entries entry WHERE $catWhere(lower(entry.title) LIKE ? ESCAPE '\\' OR instr(lower(entry.search_text),lower(?))>0) " +
                "LIMIT 160",
            args,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val cid = cursor.getString(1)
                val title = cursor.getString(2)
                val subtitle = cursor.getString(3)
                val img = cursor.nullableString(4)
                val st = cursor.getString(5)
                val sc = FuzzyMatcher.score(trimmed, title, subtitle, st)
                if (sc > 0) {
                    val catBoost = when (cid) {
                        "characters" -> 30
                        "professions" -> 20
                        "skills", "combat-arts", "magic" -> 12
                        else -> 0
                    }
                    scored += FuzzySuggestion(
                        id = id,
                        title = title,
                        subtitle = subtitle,
                        badge = catTitles[cid] ?: cid,
                        imageAsset = img,
                        score = sc + catBoost,
                        gameId = CompanionGame.FORTUNES_WEAVE.id,
                        categoryId = cid,
                    )
                }
            }
        }

        // Typo-tolerant fallback scan over titles if direct/subsequence returned too few hits
        if (scored.size < 3) {
            val existingIds = scored.mapTo(mutableSetOf()) { it.id }
            val fallbackArgs = if (categoryId != null) arrayOf(categoryId) else null
            val fallbackWhere = if (categoryId != null) "WHERE entry.category_id=?" else ""
            database.rawQuery(
                "SELECT entry.id,entry.category_id,entry.title,entry.subtitle,entry.image_asset " +
                    "FROM wiki_entries entry $fallbackWhere",
                fallbackArgs,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    if (id in existingIds) continue
                    val cid = cursor.getString(1)
                    val title = cursor.getString(2)
                    val subtitle = cursor.getString(3)
                    val sc = FuzzyMatcher.score(trimmed, title, subtitle)
                    if (sc >= 140) {
                        scored += FuzzySuggestion(
                            id = id,
                            title = title,
                            subtitle = subtitle,
                            badge = catTitles[cid] ?: cid,
                            imageAsset = cursor.nullableString(4),
                            score = sc,
                            gameId = CompanionGame.FORTUNES_WEAVE.id,
                            categoryId = cid,
                        )
                    }
                }
            }
        }

        return scored
            .sortedWith(compareByDescending<FuzzySuggestion> { it.score }.thenBy { it.title.length }.thenBy { it.title })
            .take(capped)
    }

    override fun affiliationPortraits(): Map<String, String> {
        if (!hasTable("characters_details")) return emptyMap()
        return runCatching {
            database.rawQuery(
                "SELECT cd.affiliation, entry.image_asset " +
                    "FROM characters_details cd JOIN wiki_entries entry ON entry.id=cd.entry_id " +
                    "WHERE cd.affiliation IS NOT NULL AND trim(cd.affiliation)!='' " +
                    "AND entry.image_asset IS NOT NULL AND entry.image_asset!='' " +
                    "ORDER BY entry.source_row, entry.id",
                null,
            ).use { cursor ->
                val map = linkedMapOf<String, String>()
                while (cursor.moveToNext()) {
                    val aff = cursor.getString(0)
                    val img = cursor.getString(1)
                    if (aff !in map) map[aff] = img
                }
                map
            }
        }.getOrDefault(emptyMap())
    }

    override fun characterRoutes(characterId: String): List<CharacterRouteSummary> {
        if (!hasTable("character_route_order")) return emptyList()
        return database.rawQuery(
            "SELECT character_entry_id,character_name,route_key,route_label,fame_level,join_kind,join_chapter," +
                "join_chapter_num,initial_level,support_req,poach_condition,contact_location,recommended " +
                "FROM character_route_order WHERE character_entry_id=? " +
                "ORDER BY CASE route_key WHEN 'kai' THEN 1 WHEN 'dit' THEN 2 WHEN 'seo' THEN 3 WHEN 'leda' THEN 4 ELSE 5 END",
            arrayOf(characterId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        CharacterRouteSummary(
                            characterEntryId = cursor.getString(0),
                            characterName = cursor.getString(1),
                            routeKey = cursor.getString(2),
                            routeLabel = cursor.getString(3),
                            fameLevel = cursor.getInt(4),
                            joinKind = cursor.getString(5),
                            joinChapter = cursor.getString(6),
                            joinChapterNum = cursor.getInt(7),
                            initialLevel = if (cursor.isNull(8)) null else cursor.getInt(8),
                            supportReq = cursor.getString(9),
                            poachCondition = cursor.getString(10),
                            contactLocation = cursor.getString(11),
                            recommended = cursor.getInt(12) == 1,
                        ),
                    )
                }
            }
        }
    }

    override fun options(categoryId: String): List<WikiFieldOptions> {
        if (catalog.categories.none { it.id == categoryId }) return emptyList()
        require(Regex("[a-z][a-z0-9-]*").matches(categoryId))
        val table = categoryId.replace('-', '_') + "_details"
        return database.rawQuery("SELECT field_key,label,is_numeric FROM wiki_field_definitions WHERE category_id=? ORDER BY position",
            arrayOf(categoryId)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val key = cursor.getString(0)
                    check(Regex("[a-z][a-z0-9_]*").matches(key))
                    val numeric = cursor.getInt(2) == 1
                    val values = if (numeric) emptyList() else database.rawQuery(
                        "SELECT DISTINCT $key FROM $table WHERE $key IS NOT NULL AND length($key) BETWEEN 1 AND 60 ORDER BY $key LIMIT 31", null,
                    ).use { values -> buildList { while (values.moveToNext()) add(values.getString(0)) } }
                    if (numeric || values.size in 1..30) add(WikiFieldOptions(key, cursor.getString(1), numeric, values))
                }
            }
        }
    }

    override fun links(id: String): List<WikiLink> {
        if (database.version < 2) return emptyList()
        return database.rawQuery(
            "SELECT target.id,target.title,relation.label,relation.evidence,target.image_asset FROM wiki_relations relation " +
                "JOIN wiki_entries target ON target.id=relation.target_id WHERE relation.source_id=? " +
                "UNION SELECT source.id,source.title,'相关条目 · '||relation.label,relation.evidence,source.image_asset FROM wiki_relations relation " +
                "JOIN wiki_entries source ON source.id=relation.source_id WHERE relation.target_id=? ORDER BY 3,2,1",
            arrayOf(id, id),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(WikiLink(cursor.getString(0), cursor.getString(1),
            cursor.getString(2), cursor.getString(3), cursor.nullableString(4))) } }
    }

    private fun hasTable(name: String): Boolean = database.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
        arrayOf(name),
    ).use { it.moveToFirst() }

    override fun characterLearning(characterId: String): WikiCharacterLearningData {
        if (database.version < 3) return WikiCharacterLearningData()
        val hasRangeTables = hasTable("combat_arts_details") && hasTable("magic_details")
        val rangeSelect = if (hasRangeTables) ",COALESCE(ca.range,mg.range)" else ",NULL"
        val rangeJoins = if (hasRangeTables) {
            " LEFT JOIN combat_arts_details ca ON ca.entry_id=learning.skill_entry_id " +
                "LEFT JOIN magic_details mg ON mg.entry_id=learning.skill_entry_id "
        } else " "
        return database.rawQuery(
            "SELECT source.source_sheet_id,source.source_sheet_name,source.source_row,learning.skill_entry_id, " +
                "learning.skill_name,learning.group_label,learning.requirement,learning.group_position,learning.position,skill.image_asset$rangeSelect " +
                "FROM character_learning_sources source LEFT JOIN character_skill_learnings learning " +
                "ON learning.character_id=source.character_id LEFT JOIN wiki_entries skill ON skill.id=learning.skill_entry_id" +
                rangeJoins +
                "WHERE source.character_id=? " +
                "ORDER BY learning.group_position,learning.position",
            arrayOf(characterId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use WikiCharacterLearningData()
            val sourceSheetId = cursor.getString(0)
            val sourceSheetName = cursor.getString(1)
            val sourceRowNumber = cursor.getInt(2)
            val skills = buildList {
                do {
                    if (!cursor.isNull(4)) add(WikiCharacterSkill(
                        targetId = cursor.nullableString(3),
                        name = cursor.getString(4),
                        groupLabel = cursor.getString(5),
                        requirement = cursor.getString(6),
                        groupPosition = cursor.getInt(7),
                        position = cursor.getInt(8),
                        imageAsset = cursor.nullableString(9),
                        range = cursor.nullableString(10)?.trim()?.takeIf { it.isNotEmpty() && it != "-" && it != "--" },
                    ))
                } while (cursor.moveToNext())
            }
            WikiCharacterLearningData(
                sourceSheetId = sourceSheetId,
                sourceSheetName = sourceSheetName,
                sourceRowNumber = sourceRowNumber,
                skills = skills,
            )
        }
    }

    override fun giftPreferencesForCharacter(characterId: String): CharacterGiftGuideData? {
        if (database.version < 4) return null
        val source = database.rawQuery(
            "SELECT source.source_sheet_id,source.source_sheet_name,source.source_row,source.category_preferences " +
                "FROM character_gift_sources source WHERE source.character_id=? LIMIT 1",
            arrayOf(characterId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) CharacterGiftGuideData() else CharacterGiftGuideData(
                sourceSheetId = cursor.getString(0),
                sourceSheetName = cursor.getString(1),
                sourceRowNumber = cursor.getInt(2),
                categoryPreferenceText = cursor.nullableString(3),
            )
        }
        val preferences = database.rawQuery(
            "SELECT preference.character_id,character.title,character.image_asset,preference.gift_entry_id, " +
                "gift.title,gift.image_asset,preference.preference,preference.evidence,preference.source_sheet_id, " +
                "preference.source_sheet_name,preference.source_row,preference.source_column " +
                "FROM character_gift_preferences preference " +
                "JOIN wiki_entries character ON character.id=preference.character_id " +
                "JOIN wiki_entries gift ON gift.id=preference.gift_entry_id " +
                "WHERE preference.character_id=? ORDER BY preference.preference,gift.title COLLATE NOCASE,gift.id",
            arrayOf(characterId),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(readGiftPreference(cursor)) } }
        return source.copy(preferences = preferences)
    }

    override fun giftPreferencesForGift(giftEntryId: String): List<GiftPreferenceRecord>? {
        if (database.version < 4) return null
        return database.rawQuery(
            "SELECT preference.character_id,character.title,character.image_asset,preference.gift_entry_id, " +
                "gift.title,gift.image_asset,preference.preference,preference.evidence,preference.source_sheet_id, " +
                "preference.source_sheet_name,preference.source_row,preference.source_column " +
                "FROM character_gift_preferences preference " +
                "JOIN wiki_entries character ON character.id=preference.character_id " +
                "JOIN wiki_entries gift ON gift.id=preference.gift_entry_id " +
                "WHERE preference.gift_entry_id=? ORDER BY preference.preference,character.title COLLATE NOCASE,character.id",
            arrayOf(giftEntryId),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(readGiftPreference(cursor)) } }
    }

    override fun mealPreferencesForCharacter(characterId: String): CharacterMealGuideData? {
        if (database.version < 5) return null
        val source = database.rawQuery(
            "SELECT source_sheet_id,source_sheet_name,source_row FROM character_meal_sources " +
                "WHERE character_id=? LIMIT 1",
            arrayOf(characterId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) CharacterMealGuideData() else CharacterMealGuideData(
                sourceSheetId = cursor.getString(0),
                sourceSheetName = cursor.getString(1),
                sourceRowNumber = cursor.getInt(2),
            )
        }
        val preferences = database.rawQuery(
            "SELECT preference.character_id,character.title,character.image_asset,preference.meal_entry_id, " +
                "meal.title,meal.image_asset,preference.preference,preference.evidence,preference.source_sheet_id, " +
                "preference.source_sheet_name,preference.source_row,preference.source_column " +
                "FROM character_meal_preferences preference " +
                "JOIN wiki_entries character ON character.id=preference.character_id " +
                "JOIN wiki_entries meal ON meal.id=preference.meal_entry_id " +
                "WHERE preference.character_id=? ORDER BY preference.preference,meal.title COLLATE NOCASE,meal.id",
            arrayOf(characterId),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(readMealPreference(cursor)) } }
        return source.copy(preferences = preferences)
    }

    override fun mealPreferencesForMeal(mealEntryId: String): List<MealPreferenceRecord>? {
        if (database.version < 5) return null
        return database.rawQuery(
            "SELECT preference.character_id,character.title,character.image_asset,preference.meal_entry_id, " +
                "meal.title,meal.image_asset,preference.preference,preference.evidence,preference.source_sheet_id, " +
                "preference.source_sheet_name,preference.source_row,preference.source_column " +
                "FROM character_meal_preferences preference " +
                "JOIN wiki_entries character ON character.id=preference.character_id " +
                "JOIN wiki_entries meal ON meal.id=preference.meal_entry_id " +
                "WHERE preference.meal_entry_id=? ORDER BY preference.preference,character.title COLLATE NOCASE,character.id",
            arrayOf(mealEntryId),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(readMealPreference(cursor)) } }
    }

    override fun diningTrio(characterIds: List<String>): DiningTrioRecord? {
        if (database.version < 5) return null
        require(characterIds.size == 3 && characterIds.toSet().size == 3) {
            "Dining combinations require three different characters"
        }
        val selection = List(3) { "character_${it + 1}_id IN (?,?,?)" }.joinToString(" AND ")
        val parameters = (0..2).flatMap { characterIds }.toTypedArray()
        return database.rawQuery(
            "SELECT first.id,first.title,first.image_asset,second.id,second.title,second.image_asset, " +
                "third.id,third.title,third.image_asset,trio.relationship_12,trio.relationship_13, " +
                "trio.relationship_23,trio.minimum_level,trio.evidence,trio.source_sheet_id, " +
                "trio.source_sheet_name,trio.source_row " +
                "FROM dining_trios trio " +
                "JOIN wiki_entries first ON first.id=trio.character_1_id " +
                "JOIN wiki_entries second ON second.id=trio.character_2_id " +
                "JOIN wiki_entries third ON third.id=trio.character_3_id " +
                "WHERE $selection LIMIT 1",
            parameters,
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else DiningTrioRecord(
                first = DiningTrioMember(cursor.getString(0), cursor.getString(1), cursor.nullableString(2)),
                second = DiningTrioMember(cursor.getString(3), cursor.getString(4), cursor.nullableString(5)),
                third = DiningTrioMember(cursor.getString(6), cursor.getString(7), cursor.nullableString(8)),
                relationship12 = cursor.nullableString(9),
                relationship13 = cursor.nullableString(10),
                relationship23 = cursor.nullableString(11),
                minimumLevel = cursor.nullableString(12),
                evidence = cursor.getString(13),
                sourceSheetId = cursor.getString(14),
                sourceSheetName = cursor.getString(15),
                sourceRowNumber = cursor.getInt(16),
            )
        }
    }

    override fun birdDialogues(request: BirdDialogueRequest): BirdDialoguePage? {
        if (database.version < 6) return null
        val query = request.query.trim()
        val group = request.characterGroup?.trim()?.takeIf(String::isNotEmpty)
        val filters = buildList {
            if (query.isNotEmpty()) add("instr(lower(entry.search_text),lower(?))>0")
            if (group != null) add("bird.character_group=?")
            when (request.reviewFilter) {
                BirdDialogueReviewFilter.ALL -> Unit
                BirdDialogueReviewFilter.MARKED_CORRECTED -> add("bird.marked_corrected=1")
                BirdDialogueReviewFilter.UNMARKED -> add("bird.marked_corrected=0")
            }
        }.joinToString(" AND ").ifEmpty { "1=1" }
        val parameters = buildList {
            if (query.isNotEmpty()) add(query)
            group?.let(::add)
        }.toTypedArray()
        val total = database.rawQuery(
            "SELECT COUNT(*) FROM bird_dialogues bird JOIN wiki_entries entry ON entry.id=bird.entry_id WHERE $filters",
            parameters,
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
        val entries = database.rawQuery(
            birdDialogueSelect("WHERE $filters ORDER BY bird.character_group IS NULL, bird.character_group, " +
                "COALESCE(character.title,bird.character_name) COLLATE NOCASE,entry.source_row,entry.id " +
                "LIMIT ? OFFSET ?"),
            parameters + arrayOf(request.limit.toString(), request.offset.toString()),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(readBirdDialogue(cursor)) } }
        return BirdDialoguePage(
            entries = entries,
            totalMatches = total,
            offset = request.offset,
            limit = request.limit,
            sourceNote = metadata("bird_dialogues_source_note"),
        )
    }

    override fun birdDialogue(entryId: String): BirdDialogueRecord? {
        if (database.version < 6) return null
        return database.rawQuery(
            birdDialogueSelect("WHERE entry.id=? LIMIT 1"), arrayOf(entryId),
        ).use { cursor -> if (cursor.moveToFirst()) readBirdDialogue(cursor) else null }
    }

    override fun restorationGuide(): RestorationGuideData? {
        if (database.version < 9) return null
        val source = database.rawQuery("SELECT key,value FROM restoration_metadata", null).use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
        }
        if (source.isEmpty()) return null
        val materials = database.rawQuery(
            "SELECT material.material_id,material.title,material.item_entry_id,entry.image_asset,material.match_status " +
                "FROM restoration_materials material LEFT JOIN wiki_entries entry ON entry.id=material.item_entry_id " +
                "ORDER BY material.title,material.material_id", null,
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(RestorationMaterial(
                cursor.getString(0), cursor.getString(1), cursor.nullableString(2), cursor.nullableString(3), cursor.getString(4),
            )) }
        }
        val requirements = database.rawQuery(
            "SELECT location_id,material_id,quantity FROM restoration_requirements ORDER BY location_id,position", null,
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) to RestorationRequirement(cursor.getString(1), cursor.getInt(2))) }
                .groupBy({ it.first }, { it.second })
        }
        val locations = database.rawQuery(
            "SELECT location_id,title,region,source_row,evidence FROM restoration_locations ORDER BY source_row,location_id", null,
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(RestorationLocation(
                cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getInt(3), cursor.getString(4),
                requirements.getValue(cursor.getString(0)),
            )) }
        }
        return RestorationGuideData(materials, locations, source.getValue("source_sheet_id"),
            source.getValue("source_sheet_name"), source.getValue("source_revision"), source.getValue("source_uri"),
            source.getValue("access_note"))
    }

    override fun theaterGuide(): TheaterGuideData? {
        if (database.version < 10) return null
        val metadata = database.rawQuery("SELECT key,value FROM theater_metadata", null).use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
        }
        if (metadata.isEmpty()) return null
        val cues = database.rawQuery("SELECT play_id,position,source_row,cell_address,raw_text,options_json FROM theater_play_cues ORDER BY play_id,position", null).use { cursor ->
            buildList { while (cursor.moveToNext()) {
                val options = JSONArray(cursor.getString(5))
                add(cursor.getString(0) to TheaterCue(cursor.getInt(1), cursor.getInt(2), cursor.getString(3), cursor.getString(4),
                    List(options.length()) { options.getString(it) }))
            } }.groupBy({ it.first }, { it.second })
        }
        val rewards = database.rawQuery("SELECT play_id,rating,source_row,cell_address,reward_text FROM theater_play_rewards ORDER BY play_id,position", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) to TheaterReward(
                TheaterRating.valueOf(cursor.getString(1)), cursor.getInt(2), cursor.getString(3), cursor.nullableString(4),
            )) }.groupBy({ it.first }, { it.second })
        }
        val plays = database.rawQuery("SELECT play_id,title FROM theater_plays ORDER BY position", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(TheaterPlay(cursor.getString(0), cursor.getString(1),
                cues[cursor.getString(0)].orEmpty(), rewards[cursor.getString(0)].orEmpty())) }
        }
        val notes = database.rawQuery("SELECT source_row,cell_address,kind,text FROM theater_notes ORDER BY position", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(TheaterNote(cursor.getInt(0), cursor.getString(1), cursor.getString(2), cursor.getString(3))) }
        }
        val activities = database.rawQuery("SELECT activity_id,label,participants,effect,note,source_row,cell_address,source_sheet_id FROM theater_activities ORDER BY source_row", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(TheaterActivity(cursor.getString(0), cursor.getString(1), cursor.getInt(2),
                cursor.getString(3), cursor.getString(4), cursor.getInt(5), cursor.getString(6), cursor.getString(7))) }
        }
        val preferences = database.rawQuery("SELECT character_entry_id,play_id,preference_label,evidence,source_sheet_id,source_row FROM theater_character_preferences ORDER BY character_entry_id,play_id", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(TheaterCharacterPreference(cursor.getString(0), cursor.getString(1),
                cursor.getString(2), cursor.getString(3), cursor.getString(4), cursor.getInt(5))) }
        }
        return TheaterGuideData(plays, notes, activities, preferences, metadata.getValue("preference_status"),
            metadata.getValue("context_note"), metadata.getValue("related_sidequest_id").ifBlank { null },
            metadata.getValue("related_sidequest_title"), metadata.getValue("source_sheet_id"), metadata.getValue("source_sheet_name"),
            metadata.getValue("source_revision"), metadata.getValue("source_uri"), metadata.getValue("activity_sheet_id"), metadata.getValue("activity_sheet_name"),
            metadata.getValue("weekly_reset_note"))
    }

    override fun plantingGuide(): PlantingGuideData? {
        if (database.version < 7) return null
        val sourceUri = plantingMetadata("source_uri") ?: return null
        val sourcePageSha256 = requireNotNull(plantingMetadata("page_sha256"))
        val sourceRevision = requireNotNull(plantingMetadata("source_revision"))
        val harvestRows = database.rawQuery(
            "SELECT parameter_key,value_numeric,verification_status,source_evidence " +
                "FROM planting_harvest_parameters ORDER BY parameter_key",
            null,
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), Triple(
                    cursor.getDouble(1), plantingVerificationStatus(cursor.getString(2)), cursor.getString(3),
                ))
            }
        }
        val cycle = requireNotNull(harvestRows["cycle_turns"])
        val yield = requireNotNull(harvestRows["yield_count"])
        val probabilities = database.rawQuery(
            "SELECT harvest_number,probability_text,probability_percent,verification_status " +
                "FROM planting_harvest_probabilities ORDER BY harvest_number",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(PlantingHarvestProbability(
                    harvestNumber = cursor.getInt(0),
                    probabilityText = cursor.getString(1),
                    probabilityPercent = cursor.getDouble(2),
                    verificationStatus = plantingVerificationStatus(cursor.getString(3)),
                ))
            }
        }
        val plants = database.rawQuery(
            "SELECT plant_id,title,item_entry_id,image_asset,match_status,source_row " +
                "FROM planting_plants ORDER BY title COLLATE NOCASE,plant_id",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(readPlantingPlant(cursor))
            }
        }
        val plantsById = plants.associateBy(PlantingPlant::id)
        val locations = database.rawQuery(
            "SELECT location.location_id,location.title,location.source_row,location.plant_id," +
                "location.plant_probability_text,location.plant_probability_percent,location.plant_description " +
                "FROM planting_locations location ORDER BY location.title COLLATE NOCASE,location.location_id",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    add(PlantingLocation(
                        id = id,
                        title = cursor.getString(1),
                        sourceRowNumber = cursor.getInt(2),
                        plant = requireNotNull(plantsById[cursor.getString(3)]),
                        plantProbabilityText = cursor.getString(4),
                        plantProbabilityPercent = cursor.getDouble(5),
                        plantDescription = cursor.getString(6),
                        mapMarkers = plantingMarkers("location_id", id),
                    ))
                }
            }
        }
        val fertilizers = database.rawQuery(
            "SELECT fertilizer_id,title,item_entry_id,image_asset,match_status,parameter_text,parameter_percent," +
                "description,acquisition_hint,source_row FROM planting_fertilizers " +
                "ORDER BY title COLLATE NOCASE,fertilizer_id",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    add(PlantingFertilizer(
                        id = id,
                        title = cursor.getString(1),
                        itemEntryId = cursor.nullableString(2),
                        imageAsset = cursor.nullableString(3),
                        matchStatus = plantingItemMatchStatus(cursor.getString(4)),
                        parameterText = cursor.getString(5),
                        parameterPercent = cursor.getDouble(6),
                        description = cursor.getString(7),
                        acquisitionHint = cursor.getString(8),
                        sourceRowNumber = cursor.getInt(9),
                        mapMarkers = plantingMarkers("fertilizer_id", id),
                    ))
                }
            }
        }
        return PlantingGuideData(
            sourceUri = sourceUri,
            sourcePageSha256 = sourcePageSha256,
            sourceRevision = sourceRevision,
            harvest = PlantingHarvestParameters(
                cycleTurns = cycle.first.toInt(),
                cycleEvidence = cycle.third,
                yieldCount = yield.first.toInt(),
                yieldCountStatus = yield.second,
                yieldEvidence = yield.third,
                probabilityNote = requireNotNull(plantingMetadata("probability_note")),
                usageNote = requireNotNull(plantingMetadata("usage_note")),
                fertilizerNote = requireNotNull(plantingMetadata("fertilizer_note")),
            ),
            harvestProbabilities = probabilities,
            plants = plants,
            locations = locations,
            fertilizers = fertilizers,
        )
    }

    override fun supportGuide(): SupportGuideData? {
        if (database.version < 8) return null
        val sourceUri = supportMetadata("source_uri") ?: return null
        val types = database.rawQuery(
            "SELECT support_type,support_type_label,support_level,physical_attack,magic_attack,physical_defense," +
                "magic_defense,hit,avoid,critical,critical_avoid,attack_speed FROM support_type_bonuses " +
                "ORDER BY support_type,support_level",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    SupportTypeLevelBonus(
                        supportLevel = cursor.getInt(2),
                        values = SupportBonusVector((3..11).map(cursor::getInt)),
                    ) to (cursor.getString(0) to cursor.getString(1)),
                )
            }
        }.groupBy { it.second.first }.map { (typeId, rows) ->
            SupportTypeBonus(
                id = typeId,
                label = rows.first().second.second,
                levels = rows.map { it.first },
            )
        }
        val people = database.rawQuery(
            "SELECT person.source_name,person.display_name,person.character_entry_id,character.image_asset," +
                "person.match_status FROM support_people person " +
                "LEFT JOIN wiki_entries character ON character.id=person.character_entry_id " +
                "ORDER BY person.display_name COLLATE NOCASE,person.source_name",
            null,
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(readSupportPerson(cursor, 0)) }
        }
        val jobs = database.rawQuery(
            "SELECT source_job_id,job_entry_id,name,display_name,support_type,support_type_label,tiers_json," +
                "image_asset,match_status FROM support_jobs ORDER BY name COLLATE NOCASE,source_job_id",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    SupportJob(
                        sourceId = cursor.getString(0),
                        wikiEntryId = cursor.nullableString(1),
                        name = cursor.getString(2),
                        displayName = cursor.getString(3),
                        supportTypeId = cursor.getString(4),
                        supportTypeLabel = cursor.getString(5),
                        tiers = cursor.getString(6).jsonStringArray(),
                        imageAsset = cursor.nullableString(7),
                        matchStatus = supportMatchStatus(cursor.getString(8)),
                    ),
                )
            }
        }
        return SupportGuideData(
            sourceUri = sourceUri,
            bundleUri = requireNotNull(supportMetadata("bundle_uri")),
            sourceRevision = requireNotNull(supportMetadata("source_revision")),
            statNames = requireNotNull(supportMetadata("stat_names")).jsonStringArray(),
            supportThresholds = requireNotNull(supportMetadata("thresholds")).jsonIntArray(),
            supportTypes = types,
            people = people,
            jobs = jobs,
        )
    }

    override fun mechanicsGuide(): MechanicsGuideData? {
        val archiveUri = database.rawQuery(
            "SELECT value FROM mechanics_metadata WHERE key='archive_source_uri' LIMIT 1",
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: return null
        val growthUri = database.rawQuery(
            "SELECT value FROM mechanics_metadata WHERE key='growth_source_uri' LIMIT 1",
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: return null
        val revision = database.rawQuery(
            "SELECT value FROM mechanics_metadata WHERE key='source_revision' LIMIT 1",
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: return null

        val groups = database.rawQuery(
            "SELECT id,title,category,description,href,count FROM mechanics_groups ORDER BY position",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    MechanicsGroup(
                        id = cursor.getString(0),
                        title = cursor.getString(1),
                        category = cursor.getString(2),
                        description = cursor.getString(3),
                        href = cursor.nullableString(4),
                        count = cursor.getInt(5),
                    ),
                )
            }
        }
        val entries = database.rawQuery(
            "SELECT id,title,group_id,kind,paragraphs_json,sources_json FROM mechanics_entries ORDER BY position",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    MechanicsEntry(
                        id = cursor.getString(0),
                        title = cursor.getString(1),
                        groupId = cursor.getString(2),
                        kind = cursor.getString(3),
                        paragraphs = cursor.getString(4).jsonStringArray(),
                        sources = cursor.getString(5).jsonStringArray(),
                    ),
                )
            }
        }
        val sections = database.rawQuery(
            "SELECT id,title,blocks_json FROM growth_mechanics_sections ORDER BY position",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val blocksArray = JSONArray(cursor.getString(2))
                    val blocks = List(blocksArray.length()) { index ->
                        val obj = blocksArray.getJSONObject(index)
                        GrowthMechanicsBlock(obj.getString("kind"), obj.getString("text"))
                    }
                    add(GrowthMechanicsSection(cursor.getString(0), cursor.getString(1), blocks))
                }
            }
        }
        return MechanicsGuideData(
            groups = groups,
            entries = entries,
            growthSections = sections,
            archiveSourceUri = archiveUri,
            growthSourceUri = growthUri,
            sourceRevision = revision,
        )
    }

    override fun growthSimulatorCatalog(): GrowthSimulatorCatalog? {
        val sourceUri = database.rawQuery(
            "SELECT value FROM growth_sim_metadata WHERE key='source_uri' LIMIT 1",
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: return null
        val revision = database.rawQuery(
            "SELECT value FROM growth_sim_metadata WHERE key='source_revision' LIMIT 1",
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: return null

        val characters = database.rawQuery(
            "SELECT native_id,code,record_id,name,faction,gender,start_class,start_level," +
                "base_json,growth_json,caps_json,guarantee_pre,guarantee_part3 FROM growth_sim_characters ORDER BY position",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    GrowthSimCharacter(
                        nativeId = cursor.getString(0),
                        code = cursor.getString(1),
                        recordId = cursor.getInt(2),
                        name = cursor.getString(3),
                        faction = cursor.getString(4),
                        gender = cursor.getString(5),
                        startClass = cursor.getString(6),
                        startLevel = cursor.getInt(7),
                        base = cursor.getString(8).jsonIntArray(),
                        growth = cursor.getString(9).jsonIntArray(),
                        caps = cursor.getString(10).jsonIntArray(),
                        guaranteePre = cursor.getInt(11),
                        guaranteePart3 = cursor.getInt(12),
                    ),
                )
            }
        }
        val classes = database.rawQuery(
            "SELECT id,record_id,name,tier,base_json,growth_bonus_json,base_growth_json,modifier_json," +
                "caps_json,mount_type,mount_multiplier,allowed_records_json,forbidden_people_json " +
                "FROM growth_sim_classes ORDER BY position",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    GrowthSimClass(
                        id = cursor.getString(0),
                        recordId = cursor.getInt(1),
                        name = cursor.getString(2),
                        tier = cursor.getString(3),
                        base = cursor.getString(4).jsonIntArray(),
                        growthBonus = cursor.getString(5).jsonIntArray(),
                        baseGrowth = cursor.getString(6).jsonIntArray(),
                        modifier = cursor.getString(7).jsonIntArray(),
                        caps = cursor.getString(8).jsonIntArray(),
                        mountType = cursor.getString(9),
                        mountMultiplier = cursor.getInt(10),
                        allowedRecords = cursor.getString(11).jsonIntArray(),
                        forbiddenPeople = cursor.getString(12).jsonStringArray(),
                    ),
                )
            }
        }
        val mounts = database.rawQuery(
            "SELECT id,mount_type,name,growth_json,attr_bonus_json,effect,location FROM growth_sim_mounts ORDER BY position",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    GrowthSimMount(
                        id = cursor.getString(0),
                        type = cursor.getString(1),
                        name = cursor.getString(2),
                        growth = cursor.getString(3).jsonIntArray(),
                        attrBonus = cursor.getString(4).jsonIntArray(),
                        effect = cursor.getString(5),
                        location = cursor.getString(6),
                    ),
                )
            }
        }
        return GrowthSimulatorCatalog(
            characters = characters,
            classes = classes,
            mounts = mounts,
            sourceUri = sourceUri,
            sourceRevision = revision,
        )
    }

    override fun supportPairs(request: SupportPairRequest): SupportPairPage? {
        if (database.version < 8 || supportMetadata("source_uri") == null) return null
        val query = request.query.trim()
        val filters = buildList {
            if (query.isNotEmpty()) add("instr(lower(pair.search_text),lower(?))>0")
            if (request.characterName != null) add(
                "(pair.first_person_name=? OR pair.second_person_name=?)",
            )
            when (request.filter) {
                SupportRelationFilter.ALL -> Unit
                SupportRelationFilter.WITH_COMBINATION_BONUS -> add(
                    "NOT (${SUPPORT_BONUS_COLUMNS.joinToString("=0 AND ")}=0)",
                )
                SupportRelationFilter.WITH_DIALOGUES -> add(
                    "EXISTS (SELECT 1 FROM support_dialogues dialogue WHERE dialogue.pair_id=pair.pair_id)",
                )
                SupportRelationFilter.WITHOUT_DIALOGUES -> add(
                    "NOT EXISTS (SELECT 1 FROM support_dialogues dialogue WHERE dialogue.pair_id=pair.pair_id)",
                )
            }
        }.joinToString(" AND ").ifEmpty { "1=1" }
        val parameters = buildList {
            if (query.isNotEmpty()) add(query)
            request.characterName?.let { name -> add(name); add(name) }
        }.toTypedArray()
        val total = database.rawQuery(
            "SELECT COUNT(*) FROM support_pairs pair WHERE $filters", parameters,
        ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
        val entries = database.rawQuery(
            supportPairSelect("WHERE $filters ORDER BY first_person.display_name COLLATE NOCASE, " +
                "second_person.display_name COLLATE NOCASE,pair.pair_id LIMIT ? OFFSET ?"),
            parameters + arrayOf(request.limit.toString(), request.offset.toString()),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(readSupportPairSummary(cursor)) }
        }
        return SupportPairPage(entries, total, request.offset, request.limit)
    }

    override fun supportPair(pairId: String): SupportPairDetail? {
        if (database.version < 8) return null
        val sourceUri = supportMetadata("source_uri") ?: return null
        val sourceRevision = requireNotNull(supportMetadata("source_revision"))
        val supportRecord = database.rawQuery(
            supportPairSelect(
                "WHERE pair.pair_id=? LIMIT 1",
                extraColumns = ",pair.affinity,${SUPPORT_BONUS_COLUMNS.joinToString(",") { "pair.$it" }}",
            ),
            arrayOf(pairId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            Triple(readSupportPairSummary(cursor), cursor.getInt(14), (15..23).map(cursor::getInt))
        } ?: return null
        val dialogues = database.rawQuery(
            "SELECT dialogue_id,rank,start_part,start_chapter,end_part,end_chapter,route_id," +
                "any_joined_json,conditions_json,pending,date_pending FROM support_dialogues " +
                "WHERE pair_id=? ORDER BY rank,start_part,start_chapter,route_id,dialogue_id",
            arrayOf(pairId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    SupportDialogueCondition(
                        dialogueId = cursor.getString(0),
                        rank = requireNotNull(SupportDialogueRank.fromSourceRank(cursor.getInt(1))),
                        startPart = cursor.getInt(2),
                        startChapter = cursor.getInt(3),
                        endPart = cursor.getInt(4),
                        endChapter = cursor.getInt(5),
                        routeId = cursor.getInt(6),
                        anyJoined = cursor.getString(7).jsonStringArray(),
                        conditions = cursor.getString(8).jsonStringArray(),
                        pendingConditions = cursor.getInt(9),
                        datePending = cursor.getInt(10) == 1,
                    ),
                )
            }
        }
        return SupportPairDetail(
            summary = supportRecord.first,
            affinityCode = supportRecord.second,
            combinationBonus = SupportBonusVector(supportRecord.third),
            dialogues = dialogues,
            sourceUri = sourceUri,
            sourceRevision = sourceRevision,
        )
    }

    private fun supportPairSelect(suffix: String, extraColumns: String = ""): String =
        "SELECT pair.pair_id,pair.initial_level,CASE WHEN " +
            SUPPORT_BONUS_COLUMNS.joinToString("!=0 OR ") { "pair.$it" } +
            "!=0 THEN 1 ELSE 0 END,COALESCE(dialogue_counts.dialogue_count,0)," +
            "first_person.source_name,first_person.display_name,first_person.character_entry_id," +
            "first_character.image_asset,first_person.match_status," +
            "second_person.source_name,second_person.display_name,second_person.character_entry_id," +
            "second_character.image_asset,second_person.match_status$extraColumns " +
            "FROM support_pairs pair " +
            "JOIN support_people first_person ON first_person.source_name=pair.first_person_name " +
            "JOIN support_people second_person ON second_person.source_name=pair.second_person_name " +
            "LEFT JOIN wiki_entries first_character ON first_character.id=first_person.character_entry_id " +
            "LEFT JOIN wiki_entries second_character ON second_character.id=second_person.character_entry_id " +
            "LEFT JOIN (SELECT pair_id,COUNT(*) AS dialogue_count FROM support_dialogues GROUP BY pair_id) " +
            "dialogue_counts ON dialogue_counts.pair_id=pair.pair_id $suffix"

    private fun readSupportPairSummary(cursor: Cursor): SupportPairSummary = SupportPairSummary(
        pairId = cursor.getString(0),
        initialLevel = cursor.getInt(1),
        hasCombinationBonus = cursor.getInt(2) == 1,
        dialogueCount = cursor.getInt(3),
        first = readSupportPerson(cursor, 4),
        second = readSupportPerson(cursor, 9),
    )

    private fun readSupportPerson(cursor: Cursor, offset: Int): SupportPersonSummary = SupportPersonSummary(
        sourceName = cursor.getString(offset),
        displayName = cursor.getString(offset + 1),
        wikiCharacterId = cursor.nullableString(offset + 2),
        imageAsset = cursor.nullableString(offset + 3),
        matchStatus = supportMatchStatus(cursor.getString(offset + 4)),
    )

    private fun supportMetadata(key: String): String? = database.rawQuery(
        "SELECT value FROM support_metadata WHERE key=? LIMIT 1", arrayOf(key),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun supportMatchStatus(key: String): SupportWikiMatchStatus =
        requireNotNull(SupportWikiMatchStatus.fromKey(key))

    private fun plantingMetadata(key: String): String? = database.rawQuery(
        "SELECT value FROM planting_metadata WHERE key=? LIMIT 1", arrayOf(key),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun plantingMarkers(ownerKey: String, ownerId: String): List<PlantingMapMarker> {
        require(ownerKey == "location_id" || ownerKey == "fertilizer_id")
        return database.rawQuery(
            "SELECT source_map_id,chapter_id,chapter_source_token,location_name,source_uri,source_row " +
                "FROM planting_map_markers WHERE $ownerKey=? ORDER BY chapter_id,location_name,source_map_id",
            arrayOf(ownerId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(PlantingMapMarker(
                    sourceMapId = cursor.getString(0),
                    chapterId = cursor.getString(1),
                    chapterSourceToken = cursor.getString(2),
                    locationName = cursor.getString(3),
                    sourceUri = cursor.getString(4),
                    sourceRowNumber = cursor.getInt(5),
                ))
            }
        }
    }

    private fun readPlantingPlant(cursor: Cursor): PlantingPlant = PlantingPlant(
        id = cursor.getString(0),
        title = cursor.getString(1),
        itemEntryId = cursor.nullableString(2),
        imageAsset = cursor.nullableString(3),
        matchStatus = plantingItemMatchStatus(cursor.getString(4)),
        sourceRowNumber = cursor.getInt(5),
    )

    private fun plantingVerificationStatus(key: String): PlantingVerificationStatus =
        requireNotNull(PlantingVerificationStatus.entries.firstOrNull { it.key == key })

    private fun plantingItemMatchStatus(key: String): PlantingItemMatchStatus =
        requireNotNull(PlantingItemMatchStatus.entries.firstOrNull { it.key == key })

    private fun birdDialogueSelect(suffix: String): String =
        "SELECT entry.id,bird.character_entry_id,bird.character_name,bird.character_name_english," +
            "character.image_asset,bird.character_group,bird.original_prompt,bird.translated_prompt," +
            "bird.original_reaction,bird.translated_reaction,bird.marked_corrected,category.source_sheet_id," +
            "category.source_sheet_name,entry.source_row " +
            "FROM bird_dialogues bird JOIN wiki_entries entry ON entry.id=bird.entry_id " +
            "JOIN wiki_categories category ON category.id=entry.category_id " +
            "LEFT JOIN wiki_entries character ON character.id=bird.character_entry_id $suffix"

    private fun readBirdDialogue(cursor: Cursor): BirdDialogueRecord = BirdDialogueRecord(
        entryId = cursor.getString(0),
        characterEntryId = cursor.nullableString(1),
        characterName = cursor.getString(2),
        characterNameEnglish = cursor.nullableString(3),
        characterImageAsset = cursor.nullableString(4),
        characterGroup = cursor.nullableString(5),
        originalPrompt = cursor.nullableString(6),
        translatedPrompt = cursor.getString(7),
        originalReaction = cursor.nullableString(8),
        recommendedReaction = cursor.getString(9),
        markedCorrected = cursor.getInt(10) == 1,
        sourceSheetId = cursor.getString(11),
        sourceSheetName = cursor.getString(12),
        sourceRowNumber = cursor.getInt(13),
    )

    private fun readMealPreference(cursor: Cursor): MealPreferenceRecord = MealPreferenceRecord(
        characterId = cursor.getString(0),
        characterName = cursor.getString(1),
        characterImageAsset = cursor.nullableString(2),
        mealEntryId = cursor.getString(3),
        mealName = cursor.getString(4),
        mealImageAsset = cursor.nullableString(5),
        level = requireNotNull(MealPreferenceLevel.fromSourceLevel(cursor.getInt(6))),
        evidence = cursor.getString(7),
        sourceSheetId = cursor.getString(8),
        sourceSheetName = cursor.getString(9),
        sourceRowNumber = cursor.getInt(10),
        sourceColumn = cursor.getInt(11),
    )

    private fun readGiftPreference(cursor: Cursor): GiftPreferenceRecord = GiftPreferenceRecord(
        characterId = cursor.getString(0),
        characterName = cursor.getString(1),
        characterImageAsset = cursor.nullableString(2),
        giftEntryId = cursor.getString(3),
        giftName = cursor.getString(4),
        giftImageAsset = cursor.nullableString(5),
        level = requireNotNull(GiftPreferenceLevel.fromSourceLevel(cursor.getInt(6))),
        evidence = cursor.getString(7),
        sourceSheetId = cursor.getString(8),
        sourceSheetName = cursor.getString(9),
        sourceRowNumber = cursor.getInt(10),
        sourceColumn = cursor.getInt(11),
    )

    private fun fields(id: String): List<WikiField> = database.rawQuery(
        "SELECT label,COALESCE(value,''),image_asset FROM wiki_fields WHERE entry_id=? ORDER BY position",
        arrayOf(id),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(WikiField(cursor.getString(0), cursor.getString(1), cursor.nullableString(2)))
        }
    }

    private fun readEntry(cursor: Cursor, fields: List<WikiField>): WikiEntry = WikiEntry(
        cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.nullableString(3), fields,
        cursor.getString(4), cursor.getInt(5),
    )

    private fun metadata(key: String): String = database.rawQuery(
        "SELECT value FROM content_metadata WHERE key=? LIMIT 1", arrayOf(key),
    ).use { cursor ->
        check(cursor.moveToFirst()) { "资料库缺少版本信息" }
        cursor.getString(0)
    }

    override fun close() = database.close()

    companion object {
        private const val APPLICATION_ID = 0x46455749
        private const val SCHEMA_VERSION = 13
        private val SUPPORT_BONUS_COLUMNS = listOf(
            "physical_attack", "magic_attack", "physical_defense", "magic_defense", "hit", "avoid",
            "critical", "critical_avoid", "attack_speed",
        )
        private val installLock = Any()

        fun open(context: Context): WikiDatabaseRepository = open(context) { name ->
            context.assets.open("wiki/$name")
        }

        internal fun open(context: Context, source: WikiAssetSource): WikiDatabaseRepository = synchronized(installLock) {
            val directory = File(context.noBackupFilesDir, "wiki-databases").apply { mkdirs() }
            val active = AtomicFile(File(directory, "active.sha256"))
            val previousHash = runCatching { active.openRead().bufferedReader().use { it.readText().trim() } }
                .getOrNull()?.takeIf { Regex("[0-9a-f]{64}").matches(it) }
            try {
                val expectedHash = source.open("content.sha256").bufferedReader().use { it.readText().trim() }
                check(Regex("[0-9a-f]{64}").matches(expectedHash)) { "资料库校验信息不正确" }
                val installed = File(directory, "content-$expectedHash.db")
                if (!installed.isFile || sha256(installed) != expectedHash) {
                    val staged = File.createTempFile("install-", ".db", directory)
                    try {
                        source.open("content.db").use { input ->
                            FileOutputStream(staged).use { target ->
                                input.copyTo(target)
                                target.fd.sync()
                            }
                        }
                        check(sha256(staged) == expectedHash) { "资料库校验失败" }
                        openRepository(staged).close()
                        Files.move(staged.toPath(), installed.toPath(), StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING)
                    } finally {
                        staged.delete()
                    }
                }
                val repository = openRepository(installed)
                try {
                    val stream = active.startWrite()
                    try {
                        stream.write(expectedHash.toByteArray(Charsets.US_ASCII))
                        active.finishWrite(stream)
                    } catch (error: Exception) {
                        active.failWrite(stream)
                        throw error
                    }
                    repository
                } catch (error: Exception) {
                    repository.close()
                    throw error
                }
            } catch (error: Exception) {
                if (previousHash != null) {
                    val previous = File(directory, "content-$previousHash.db")
                    val fallback = runCatching {
                        check(previous.isFile && sha256(previous) == previousHash)
                        openRepository(previous, "未能更新离线资料，正在使用上次可用版本。")
                    }.getOrNull()
                    if (fallback != null) return@synchronized fallback
                }
                throw WikiDatabaseException("无法打开离线资料，请重试；已有个人记录未改动。", error)
            }
        }

        private fun openRepository(file: File, updateNotice: String? = null): WikiDatabaseRepository {
            val database = openValidated(file)
            return try {
                WikiDatabaseRepository(database, updateNotice)
            } catch (error: Exception) {
                database.close()
                throw error
            }
        }

        private fun openValidated(file: File): SQLiteDatabase {
            val database = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                check(database.version in 1..SCHEMA_VERSION) { "资料库版本不兼容" }
                database.rawQuery("PRAGMA application_id", null).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getInt(0) == APPLICATION_ID) { "资料库身份不正确" }
                }
                database.rawQuery("PRAGMA quick_check", null).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getString(0) == "ok") { "资料库结构校验失败" }
                }
                database.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                    check(!cursor.moveToFirst()) { "资料库关联校验失败" }
                }
                return database
            } catch (error: Exception) {
                database.close()
                throw error
            }
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(64 * 1024)
                var length = stream.read(buffer)
                while (length >= 0) {
                    digest.update(buffer, 0, length)
                    length = stream.read(buffer)
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}

private fun Cursor.nullableString(column: Int): String? = if (isNull(column)) null else getString(column)

private fun String.jsonStringArray(): List<String> = JSONArray(this).let { values ->
    List(values.length()) { index -> values.getString(index) }
}

private fun String.jsonIntArray(): List<Int> = JSONArray(this).let { values ->
    List(values.length()) { index -> values.getInt(index) }
}
