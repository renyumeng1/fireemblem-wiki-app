package com.fireemblem.companion.content

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.fireemblem.companion.engine.EngageCharacterData
import com.fireemblem.companion.engine.EngageClassData
import com.fireemblem.companion.engine.EngageStat
import com.fireemblem.companion.engine.ThreeHousesCharacterData
import com.fireemblem.companion.engine.ThreeHousesClassData
import com.fireemblem.companion.engine.ThreeHousesRules
import com.fireemblem.companion.engine.ThreeHousesStat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class GamePackDatabaseException(message: String, cause: Throwable? = null) : Exception(message, cause)

class GameDataV1Repository private constructor(
    private val gameId: String,
    private val database: SQLiteDatabase,
) : GamePackRepository {
    override val catalog: GamePackCatalog
    override val statBenchmarks: GameStatBenchmarks

    init {
        val ingridPortraitAsset = if (gameId == "three-houses") {
            database.rawQuery(
                "SELECT ca.app_file FROM content_entries e " +
                    "JOIN entry_assets ea ON ea.entry_id=e.id " +
                    "JOIN content_assets ca ON ca.asset_id=ea.asset_id " +
                    "WHERE e.category_id='characters' AND e.title_en='Ingrid' AND ca.app_file IS NOT NULL " +
                    "ORDER BY $ASSET_ROLE_PRIORITY_SQL, ea.role LIMIT 1",
                null,
            ).use { c -> if (c.moveToFirst()) c.nullableText(0)?.let { "games/$gameId/$it" } else null }
        } else null
        val categories = database.rawQuery(
            "SELECT c.id,c.title,c.description,c.position,c.coverage_status,c.source_count,c.record_count," +
                "(SELECT ca.app_file FROM content_entries e " +
                "JOIN entry_assets ea ON ea.entry_id=e.id " +
                "JOIN content_assets ca ON ca.asset_id=ea.asset_id " +
                "WHERE e.category_id=c.id AND ca.app_file IS NOT NULL AND ea.role NOT IN ($EXCLUDED_SCREENSHOT_ROLES_SQL) " +
                "ORDER BY CASE WHEN e.title IN ('里贝拉锡翁','铁剑','天帝之剑','曲射','火焰','剑术资质','炎之纹章','神龙之王','刀剑将领','琉尔（男）','贝雷特 / 贝雷丝','贝雷特','赛罗司骑士团','赛罗司佣兵团','艾吉尔星辰骑士团') THEN 0 ELSE 1 END, " +
                "$ASSET_ROLE_PRIORITY_SQL,e.title COLLATE NOCASE,e.id LIMIT 1) " +
                "FROM categories c WHERE c.applicable=1 ORDER BY c.position",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val catId = cursor.getString(0)
                    val resolvedIcon = when {
                        gameId == "three-houses" && catId == "recruitment" ->
                            "games/$gameId/assets/app/category_recruitment_rhea.webp"
                        gameId == "three-houses" && catId == "supports" && ingridPortraitAsset != null ->
                            ingridPortraitAsset
                        else -> cursor.nullableText(7)?.let { "games/$gameId/$it" }
                    }
                    add(
                        GamePackCategory(
                            id = catId,
                            title = cursor.getString(1),
                            description = cursor.getString(2),
                            position = cursor.getInt(3),
                            coverageStatus = cursor.getString(4),
                            sourceCount = cursor.getInt(5),
                            recordCount = cursor.getInt(6),
                            iconAsset = resolvedIcon,
                        ),
                    )
                }
            }
        }
        val sources = database.rawQuery(
            "SELECT source_id,source_type,title,url,language,rights_status,notes FROM sources ORDER BY source_id",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    GamePackSourceInfo(
                        sourceId = cursor.getString(0),
                        sourceType = cursor.getString(1),
                        title = cursor.getString(2),
                        url = cursor.nullableText(3),
                        language = cursor.nullableText(4),
                        rightsStatus = cursor.getString(5),
                        notes = cursor.getString(6),
                    ),
                )
            }
        }
        val contentVersion = metadata("content_version") ?: "v1"
        catalog = GamePackCatalog(
            gameId = gameId,
            contentVersion = contentVersion,
            categories = categories,
            sources = sources,
        )
        statBenchmarks = computeStatBenchmarks()
    }

    override fun filterOptions(categoryId: String): List<GamePackFilterOption> = when (categoryId) {
        "characters" -> {
            val raw = database.rawQuery(
                "SELECT cd.affiliation, COUNT(*) " +
                    "FROM character_details cd JOIN content_entries e ON e.id = cd.entry_id " +
                    "WHERE e.category_id = 'characters' AND cd.affiliation IS NOT NULL AND trim(cd.affiliation) != '' " +
                    "GROUP BY cd.affiliation",
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val aff = cursor.getString(0).trim()
                        add(GamePackFilterOption(value = aff, label = aff, count = cursor.getInt(1)))
                    }
                }
            }
            val preferredOrder = when (gameId) {
                "three-houses" -> listOf("黑鹫学级", "青狮子学级", "金鹿学级", "灰狼学级", "赛罗司圣教会", "主角与独立")
                "engage" -> listOf("里特司", "费列聂", "布罗帝亚", "伊尔席翁", "索尔姆", "邪龙之章·其他")
                else -> emptyList()
            }
            raw.sortedWith(
                compareBy<GamePackFilterOption> { opt ->
                    val idx = preferredOrder.indexOf(opt.value)
                    if (idx >= 0) idx else preferredOrder.size
                }.thenByDescending { it.count }.thenBy { it.label },
            )
        }
        "classes" -> {
            val raw = database.rawQuery(
                "SELECT cd.tier, COUNT(*) " +
                    "FROM class_details cd JOIN content_entries e ON e.id = cd.entry_id " +
                    "WHERE e.category_id = 'classes' AND cd.tier IS NOT NULL AND trim(cd.tier) != '' " +
                    "GROUP BY cd.tier",
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val tier = cursor.getString(0).trim()
                        if (tier != "转职机制说明") {
                            add(GamePackFilterOption(value = tier, label = tier, count = cursor.getInt(1)))
                        }
                    }
                }
            }
            val tierOrder = listOf("基础", "初级", "中级", "高级", "最高级", "专属", "特殊", "特殊 / 魔物")
            raw.sortedWith(
                compareBy<GamePackFilterOption> { opt ->
                    val idx = tierOrder.indexOf(opt.value)
                    if (idx >= 0) idx else tierOrder.size
                }.thenByDescending { it.count },
            )
        }
        "skills" -> {
            val preferredTypes = listOf("战技", "黑魔法", "暗魔法", "白魔法", "个人特技", "职业特技", "职业精通特技", "技能等级特技", "特殊 / 敌方特技")
            val fromSkillType = database.rawQuery(
                "SELECT sd.skill_type, COUNT(*) " +
                    "FROM skill_details sd JOIN content_entries e ON e.id = sd.entry_id " +
                    "WHERE e.category_id = 'skills' AND sd.skill_type IS NOT NULL AND trim(sd.skill_type) != '' " +
                    "GROUP BY sd.skill_type HAVING COUNT(*) >= 3 ORDER BY COUNT(*) DESC LIMIT 12",
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val st = cursor.getString(0).trim()
                        add(GamePackFilterOption(value = "stype:$st", label = st, count = cursor.getInt(1)))
                    }
                }
            }
            if (fromSkillType.size >= 2) {
                fromSkillType.sortedWith(
                    compareBy<GamePackFilterOption> { opt ->
                        val idx = preferredTypes.indexOf(opt.label)
                        if (idx >= 0) idx else preferredTypes.size
                    }.thenByDescending { it.count },
                )
            } else {
                database.rawQuery(
                    "SELECT e.subtitle, COUNT(*) " +
                        "FROM content_entries e " +
                        "WHERE e.category_id = 'skills' AND e.subtitle IS NOT NULL AND trim(e.subtitle) != '' " +
                        "GROUP BY e.subtitle HAVING COUNT(*) >= 3 ORDER BY COUNT(*) DESC LIMIT 10",
                    null,
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            val sub = cursor.getString(0).trim()
                            add(GamePackFilterOption(value = sub, label = localizeSubtitle("skills", sub), count = cursor.getInt(1)))
                        }
                    }
                }
            }
        }
        "equipment", "weapons" -> {
            val preferred = listOf("黑魔法", "暗魔法", "白魔法", "剑术", "枪术", "斧术", "弓术", "格斗", "剑", "枪", "斧", "弓", "短剑", "魔道书", "杖", "体术", "装备", "道具")
            val raw = database.rawQuery(
                "SELECT ed.subtype, COUNT(*) " +
                    "FROM equipment_details ed JOIN content_entries e ON e.id = ed.entry_id " +
                    "WHERE e.category_id = ? AND ed.subtype IS NOT NULL AND trim(ed.subtype) != '' " +
                    "GROUP BY ed.subtype HAVING COUNT(*) >= 2",
                arrayOf(categoryId),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val sub = cursor.getString(0).trim()
                        add(GamePackFilterOption(value = "eqsub:$sub", label = sub, count = cursor.getInt(1)))
                    }
                }
            }
            raw.sortedWith(
                compareBy<GamePackFilterOption> { opt ->
                    val idx = preferred.indexOf(opt.label)
                    if (idx >= 0) idx else preferred.size
                }.thenByDescending { it.count },
            ).take(10)
        }
        "chapters", "battles" -> {
            val fromRoute = database.rawQuery(
                "SELECT cd.route, COUNT(*) " +
                    "FROM chapter_details cd JOIN content_entries e ON e.id = cd.entry_id " +
                    "WHERE e.category_id = ? AND cd.route IS NOT NULL AND trim(cd.route) != '' " +
                    "GROUP BY cd.route ORDER BY COUNT(*) DESC LIMIT 8",
                arrayOf(categoryId),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val route = cursor.getString(0).trim()
                        add(GamePackFilterOption(value = "route:$route", label = route, count = cursor.getInt(1)))
                    }
                }
            }
            if (fromRoute.size >= 2) {
                fromRoute
            } else {
                database.rawQuery(
                    "SELECT e.subtitle, COUNT(*) " +
                        "FROM content_entries e " +
                        "WHERE e.category_id = ? AND e.subtitle IS NOT NULL AND trim(e.subtitle) != '' " +
                        "GROUP BY e.subtitle HAVING COUNT(*) >= 2 ORDER BY COUNT(*) DESC LIMIT 8",
                    arrayOf(categoryId),
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            val sub = cursor.getString(0).trim()
                            add(GamePackFilterOption(value = sub, label = localizeSubtitle(categoryId, sub), count = cursor.getInt(1)))
                        }
                    }
                }
            }
        }
        else -> emptyList()
    }

    override fun page(request: GamePackPageRequest): GamePackPage {
        val query = request.query.trim()
        val group = request.filterGroup?.trim()?.takeIf { it.isNotEmpty() }
        val groupArg = when {
            group == null -> null
            group.startsWith("route:") -> group.removePrefix("route:")
            group.startsWith("stype:") -> group.removePrefix("stype:")
            group.startsWith("eqsub:") -> group.removePrefix("eqsub:")
            else -> group
        }
        val fuzzyTokens = if (query.isNotEmpty()) FuzzyMatcher.tokenize(query) else emptyList()
        val filters = buildList {
            if (request.categoryId != null) add("e.category_id=?")
            if (group != null) {
                when {
                    request.categoryId == "characters" -> add("EXISTS (SELECT 1 FROM character_details cd WHERE cd.entry_id=e.id AND cd.affiliation=?)")
                    request.categoryId == "classes" -> add("EXISTS (SELECT 1 FROM class_details cd WHERE cd.entry_id=e.id AND cd.tier=?)")
                    request.categoryId == "skills" && group.startsWith("stype:") ->
                        add("EXISTS (SELECT 1 FROM skill_details sd WHERE sd.entry_id=e.id AND sd.skill_type=?)")
                    (request.categoryId == "equipment" || request.categoryId == "weapons") && group.startsWith("eqsub:") ->
                        add("EXISTS (SELECT 1 FROM equipment_details ed WHERE ed.entry_id=e.id AND ed.subtype=?)")
                    (request.categoryId == "chapters" || request.categoryId == "battles") && group.startsWith("route:") ->
                        add("EXISTS (SELECT 1 FROM chapter_details cd WHERE cd.entry_id=e.id AND cd.route=?)")
                    else -> add("e.subtitle=?")
                }
            }
            if (query.isNotEmpty()) {
                val hay = "lower(e.search_text || ' ' || e.title || ' ' || COALESCE(e.title_en,''))"
                val tokenExpr = if (fuzzyTokens.size >= 2) {
                    fuzzyTokens.joinToString(" AND ") { "instr($hay,lower(?))>0" }
                } else {
                    "instr($hay,lower(?))>0"
                }
                add("(($tokenExpr) OR lower(e.title || ' ' || COALESCE(e.title_en,'')) LIKE ? ESCAPE '\\')")
            }
        }.joinToString(" AND ").ifEmpty { "1=1" }
        val args = buildList {
            request.categoryId?.let(::add)
            if (groupArg != null) add(groupArg)
            if (query.isNotEmpty()) {
                if (fuzzyTokens.size >= 2) fuzzyTokens.forEach(::add) else add(query)
                add(FuzzyMatcher.sqlSubsequenceLikePattern(query))
            }
        }.toTypedArray()

        var total = database.rawQuery("SELECT COUNT(*) FROM content_entries e WHERE $filters", args).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
        if (query.isNotEmpty() && total == 0) {
            val baseFilters = buildList {
                if (request.categoryId != null) add("e.category_id=?")
                if (group != null) {
                    when {
                        request.categoryId == "characters" -> add("EXISTS (SELECT 1 FROM character_details cd WHERE cd.entry_id=e.id AND cd.affiliation=?)")
                        request.categoryId == "classes" -> add("EXISTS (SELECT 1 FROM class_details cd WHERE cd.entry_id=e.id AND cd.tier=?)")
                        request.categoryId == "skills" && group.startsWith("stype:") ->
                            add("EXISTS (SELECT 1 FROM skill_details sd WHERE sd.entry_id=e.id AND sd.skill_type=?)")
                        (request.categoryId == "equipment" || request.categoryId == "weapons") && group.startsWith("eqsub:") ->
                            add("EXISTS (SELECT 1 FROM equipment_details ed WHERE ed.entry_id=e.id AND ed.subtype=?)")
                        (request.categoryId == "chapters" || request.categoryId == "battles") && group.startsWith("route:") ->
                            add("EXISTS (SELECT 1 FROM chapter_details cd WHERE cd.entry_id=e.id AND cd.route=?)")
                        else -> add("e.subtitle=?")
                    }
                }
            }.joinToString(" AND ").ifEmpty { "1=1" }
            val baseArgs = buildList {
                request.categoryId?.let(::add)
                if (groupArg != null) add(groupArg)
            }.toTypedArray()
            val fuzzyMatched = database.rawQuery(
                "SELECT e.id,e.category_id,e.title,e.title_ja,e.title_en,COALESCE(e.subtitle,''),e.verification_status," +
                    "(SELECT ca.app_file FROM entry_assets ea JOIN content_assets ca ON ca.asset_id=ea.asset_id " +
                    "WHERE ea.entry_id=e.id AND ca.app_file IS NOT NULL AND ea.role NOT IN ($EXCLUDED_SCREENSHOT_ROLES_SQL) " +
                    "ORDER BY $ASSET_ROLE_PRIORITY_SQL,ea.role LIMIT 1),COALESCE(e.search_text,'') " +
                    "FROM content_entries e WHERE $baseFilters",
                baseArgs,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val title = cursor.getString(2)
                        val titleEn = cursor.nullableText(4).orEmpty()
                        val sub = cursor.getString(5)
                        val st = cursor.getString(8)
                        val sc = maxOf(
                            FuzzyMatcher.score(query, title, sub, st),
                            if (titleEn.isNotBlank()) FuzzyMatcher.score(query, titleEn, sub, st) else 0,
                        )
                        if (sc > 0) add(sc to readSummary(cursor))
                    }
                }.sortedWith(compareByDescending<Pair<Int, GamePackEntrySummary>> { it.first }.thenBy { it.second.title })
                    .map { it.second }
            }
            total = fuzzyMatched.size
            val slice = fuzzyMatched.drop(request.offset).take(request.limit)
            return GamePackPage(slice, total, request.offset, request.limit)
        }
        val orderSql = if (query.isNotEmpty()) {
            "CASE WHEN lower(e.title)=lower(?) THEN 0 WHEN lower(e.title) LIKE lower(?)||'%' THEN 1 WHEN instr(lower(e.title),lower(?))>0 THEN 2 ELSE 3 END, e.title COLLATE NOCASE, e.id"
        } else {
            "e.title COLLATE NOCASE,e.id"
        }
        val orderArgs = if (query.isNotEmpty()) arrayOf(query, query, query) else emptyArray()
        val entries = database.rawQuery(
            "SELECT e.id,e.category_id,e.title,e.title_ja,e.title_en,COALESCE(e.subtitle,''),e.verification_status," +
                "(SELECT ca.app_file FROM entry_assets ea JOIN content_assets ca ON ca.asset_id=ea.asset_id " +
                "WHERE ea.entry_id=e.id AND ca.app_file IS NOT NULL AND ea.role NOT IN ($EXCLUDED_SCREENSHOT_ROLES_SQL) " +
                "ORDER BY $ASSET_ROLE_PRIORITY_SQL,ea.role LIMIT 1) " +
                "FROM content_entries e WHERE $filters ORDER BY $orderSql LIMIT ? OFFSET ?",
            args + orderArgs + arrayOf(request.limit.toString(), request.offset.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(readSummary(cursor))
            }
        }
        return GamePackPage(entries, total, request.offset, request.limit)
    }

    override fun suggest(query: String, categoryId: String?, limit: Int): List<FuzzySuggestion> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val catTitles = catalog.categories.associate { it.id to it.title }
        val capped = limit.coerceIn(1, 16)
        val likePattern = FuzzyMatcher.sqlSubsequenceLikePattern(trimmed)
        val catWhere = if (categoryId != null) "e.category_id=? AND " else ""
        val args = buildList {
            if (categoryId != null) add(categoryId)
            add(likePattern)
            add(trimmed)
        }.toTypedArray()

        val scored = mutableListOf<FuzzySuggestion>()
        database.rawQuery(
            "SELECT e.id,e.category_id,e.title,e.title_en,COALESCE(e.subtitle,'')," +
                "(SELECT ca.app_file FROM entry_assets ea JOIN content_assets ca ON ca.asset_id=ea.asset_id " +
                "WHERE ea.entry_id=e.id AND ca.app_file IS NOT NULL AND ea.role NOT IN ($EXCLUDED_SCREENSHOT_ROLES_SQL) " +
                "ORDER BY $ASSET_ROLE_PRIORITY_SQL,ea.role LIMIT 1),COALESCE(e.search_text,'') " +
                "FROM content_entries e WHERE $catWhere(lower(e.title || ' ' || COALESCE(e.title_en,'')) LIKE ? ESCAPE '\\' " +
                "OR instr(lower(e.search_text || ' ' || e.title || ' ' || COALESCE(e.title_en,'')),lower(?))>0) " +
                "LIMIT 160",
            args,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val cid = cursor.getString(1)
                val title = cursor.getString(2)
                val titleEn = cursor.nullableText(3).orEmpty()
                val sub = localizeSubtitle(cid, cursor.getString(4))
                val asset = cursor.nullableText(5)?.let { "games/$gameId/$it" }
                val st = cursor.getString(6)
                val sc = maxOf(
                    FuzzyMatcher.score(trimmed, title, sub, st),
                    if (titleEn.isNotBlank()) FuzzyMatcher.score(trimmed, titleEn, sub, st) else 0,
                )
                if (sc > 0) {
                    val catBoost = when (cid) {
                        "characters", "emblems" -> 30
                        "classes" -> 20
                        "skills", "weapons", "equipment" -> 12
                        else -> 0
                    }
                    scored += FuzzySuggestion(
                        id = id,
                        title = title,
                        subtitle = listOfNotNull(titleEn.takeIf { it.isNotBlank() }, sub.takeIf { it.isNotBlank() }).joinToString(" · "),
                        badge = catTitles[cid] ?: cid,
                        imageAsset = asset,
                        score = sc + catBoost,
                        gameId = gameId,
                        categoryId = cid,
                    )
                }
            }
        }

        if (scored.size < 3) {
            val existingIds = scored.mapTo(mutableSetOf()) { it.id }
            val fallbackArgs = if (categoryId != null) arrayOf(categoryId) else null
            val fallbackWhere = if (categoryId != null) "WHERE e.category_id=?" else ""
            database.rawQuery(
                "SELECT e.id,e.category_id,e.title,e.title_en,COALESCE(e.subtitle,'')," +
                    "(SELECT ca.app_file FROM entry_assets ea JOIN content_assets ca ON ca.asset_id=ea.asset_id " +
                    "WHERE ea.entry_id=e.id AND ca.app_file IS NOT NULL AND ea.role NOT IN ($EXCLUDED_SCREENSHOT_ROLES_SQL) " +
                    "ORDER BY $ASSET_ROLE_PRIORITY_SQL,ea.role LIMIT 1) " +
                    "FROM content_entries e $fallbackWhere",
                fallbackArgs,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    if (id in existingIds) continue
                    val cid = cursor.getString(1)
                    val title = cursor.getString(2)
                    val titleEn = cursor.nullableText(3).orEmpty()
                    val sub = localizeSubtitle(cid, cursor.getString(4))
                    val sc = maxOf(
                        FuzzyMatcher.score(trimmed, title, sub),
                        if (titleEn.isNotBlank()) FuzzyMatcher.score(trimmed, titleEn, sub) else 0,
                    )
                    if (sc >= 140) {
                        scored += FuzzySuggestion(
                            id = id,
                            title = title,
                            subtitle = listOfNotNull(titleEn.takeIf { it.isNotBlank() }, sub.takeIf { it.isNotBlank() }).joinToString(" · "),
                            badge = catTitles[cid] ?: cid,
                            imageAsset = cursor.nullableText(5)?.let { "games/$gameId/$it" },
                            score = sc,
                            gameId = gameId,
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

    override fun highlightEntries(categoryId: String, limit: Int): List<GamePackEntrySummary> =
        page(GamePackPageRequest(categoryId = categoryId, limit = limit.coerceIn(1, GamePackPageRequest.MAX_PAGE_SIZE))).entries

    override fun entry(id: String): GamePackEntryDetail? {
        val row = database.rawQuery(
            "SELECT e.id,e.category_id,e.title,e.title_ja,e.title_en,COALESCE(e.subtitle,''),e.verification_status," +
                "(SELECT ca.app_file FROM entry_assets ea JOIN content_assets ca ON ca.asset_id=ea.asset_id " +
                "WHERE ea.entry_id=e.id AND ca.app_file IS NOT NULL AND ea.role NOT IN ($EXCLUDED_SCREENSHOT_ROLES_SQL) " +
                "ORDER BY $ASSET_ROLE_PRIORITY_SQL,ea.role LIMIT 1), " +
                "COALESCE(e.description,'') " +
                "FROM content_entries e WHERE e.id=? LIMIT 1",
            arrayOf(id),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else readSummary(cursor) to cursor.getString(8)
        } ?: return null

        val summary = row.first
        val rawDesc = row.second
        val sections = mutableListOf<GamePackDetailSection>()
        var isCommunityOpinion = summary.categoryId == "community-skill-ratings"

        if (rawDesc.isNotBlank()) {
            val jsonObj = runCatching { JSONObject(rawDesc) }.getOrNull()
            if (jsonObj != null) {
                if (jsonObj.optString("claim_type") == "community_opinion") {
                    isCommunityOpinion = true
                }
                val fieldsObj = jsonObj.optJSONObject("fields")
                if (fieldsObj != null) {
                    val fields = buildList {
                        val keys = fieldsObj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            if (key in IGNORED_JSON_KEYS) continue
                            val valStr = formatReadableJsonValue(fieldsObj.opt(key))
                            if (valStr.isNotEmpty() && valStr != "--" && valStr != "-") {
                                add(GamePackDetailField(localizeJsonKey(key), valStr))
                            }
                        }
                    }
                    val sectionTitle = when (summary.categoryId) {
                        "characters" -> "人物武装与特技"
                        "emblems" -> "登场与入队信息"
                        "country-investment" -> "各级投资收益"
                        "growth-mechanics" -> "机制详解"
                        "weapon-proficiency" -> "纹章士传授一览"
                        else -> "详细属性与说明"
                    }
                    if (fields.isNotEmpty()) sections += GamePackDetailSection(sectionTitle, fields)
                }
                val notes = jsonObj.optString("notes", "").trim()
                if (notes.isNotEmpty()) {
                    sections += GamePackDetailSection("说明与附注", listOf(GamePackDetailField("备注", notes)))
                }
                listOf(
                    "stat_bonuses" to "转职能力补正",
                    "dismount_modifiers" to "下马能力变化",
                    "battalion_lv5_stats" to "满级属性加成（Lv.5）",
                    "battalion_lv1_stats" to "初始属性加成（Lv.1）",
                ).forEach { (jsonKey, secTitle) ->
                    jsonObj.optJSONObject(jsonKey)?.let { mapObj ->
                        jsonMapSection(secTitle, mapObj.toString())?.let(sections::add)
                    }
                }
            } else {
                sections += GamePackDetailSection("条目说明", listOf(GamePackDetailField("内容", rawDesc)))
            }
        }

        val supportConversations = mutableListOf<SupportRankConversation>()
        readTypedDetails(id, supportConversations).forEach { section ->
            if (section.fields.isNotEmpty()) sections += section
        }

        val rawRelations = database.rawQuery(
            "SELECT r.target_entry_id,t.title,t.category_id,r.relation_type,r.evidence,r.verification_status " +
                "FROM content_relations r JOIN content_entries t ON t.id=r.target_entry_id WHERE r.source_entry_id=? " +
                "UNION ALL " +
                "SELECT r.source_entry_id,s.title,s.category_id,r.relation_type,r.evidence,r.verification_status " +
                "FROM content_relations r JOIN content_entries s ON s.id=r.source_entry_id WHERE r.target_entry_id=? " +
                "LIMIT 40",
            arrayOf(id, id),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    GamePackRelation(
                        targetId = cursor.getString(0),
                        targetTitle = cursor.getString(1),
                        targetCategoryId = cursor.getString(2),
                        relationType = relationLabel(cursor.getString(3)),
                        evidence = cursor.getString(4),
                        verificationStatus = cursor.getString(5),
                    ),
                )
            }
        }
        val relationAssets = lookupPrimaryAssets(rawRelations.map(GamePackRelation::targetId))
        val relations = rawRelations.map { rel ->
            rel.copy(targetImageAsset = relationAssets[rel.targetId])
        }

        val citations = database.rawQuery(
            "SELECT s.title,s.url,es.locator,s.rights_status " +
                "FROM entry_sources es JOIN sources s ON s.source_id=es.source_id WHERE es.entry_id=?",
            arrayOf(id),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    GamePackCitation(
                        sourceTitle = cursor.getString(0),
                        url = cursor.nullableText(1),
                        locator = cursor.getString(2),
                        rightsStatus = cursor.getString(3),
                    ),
                )
            }
        }

        val images = database.rawQuery(
            "SELECT ca.app_file, ea.role FROM entry_assets ea JOIN content_assets ca ON ca.asset_id=ea.asset_id " +
                "WHERE ea.entry_id=? AND ca.app_file IS NOT NULL AND ea.role NOT IN ($EXCLUDED_SCREENSHOT_ROLES_SQL) " +
                "ORDER BY $ASSET_ROLE_PRIORITY_SQL, ea.role, ca.app_file LIMIT 16",
            arrayOf(id),
        ).use { cursor ->
            val seen = LinkedHashSet<String>()
            buildList {
                while (cursor.moveToNext()) {
                    val path = "games/$gameId/${cursor.getString(0)}"
                    if (seen.add(path)) {
                        add(GamePackImage(assetPath = path, roleLabel = imageRoleLabel(cursor.getString(1))))
                    }
                }
            }
        }

        val characterWiki = if (summary.categoryId == "characters") {
            readCharacterWikiData(id, summary.title, rawDesc)
        } else {
            null
        }

        return GamePackEntryDetail(
            summary = summary,
            sections = sections,
            relations = relations,
            citations = citations,
            isCommunityOpinion = isCommunityOpinion,
            images = images,
            characterWiki = characterWiki,
            supportConversations = supportConversations,
        )
    }

    private fun computeStatBenchmarks(): GameStatBenchmarks {
        val charBase = mutableMapOf<String, MutableList<Double>>()
        val charGrowth = mutableMapOf<String, MutableList<Double>>()
        val charTotals = mutableListOf<Double>()
        val charCaps = mutableMapOf<String, MutableList<Double>>()
        val classBase = mutableMapOf<String, MutableList<Double>>()
        val classGrowth = mutableMapOf<String, MutableList<Double>>()
        var rosterSize = 0
        var classSize = 0

        fun collectMap(raw: String?, dest: MutableMap<String, MutableList<Double>>, totals: MutableList<Double>? = null) {
            if (raw.isNullOrBlank()) return
            val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return
            val keys = obj.keys()
            while (keys.hasNext()) {
                val rawKey = keys.next()
                val key = normalizeStatKey(rawKey)
                val num = parseStatNumeric(obj.opt(rawKey)?.toString().orEmpty()) ?: continue
                if (rawKey == "成长合计") {
                    totals?.add(num)
                } else if (key !in NON_COMBAT_BENCHMARK_KEYS) {
                    dest.getOrPut(key) { mutableListOf() }.add(num)
                }
            }
        }

        runCatching {
            database.rawQuery(
                "SELECT cd.base_stats_json, cd.growths_json, cd.caps_json " +
                    "FROM character_details cd JOIN content_entries e ON e.id = cd.entry_id " +
                    "WHERE e.category_id = 'characters'",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    rosterSize++
                    collectMap(c.nullableText(0), charBase)
                    collectMap(c.nullableText(1), charGrowth, charTotals)
                    collectMap(c.nullableText(2), charCaps)
                }
            }
        }
        runCatching {
            database.rawQuery(
                "SELECT cd.base_stats_json, cd.growths_json " +
                    "FROM class_details cd JOIN content_entries e ON e.id = cd.entry_id " +
                    "WHERE e.category_id = 'classes'",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    classSize++
                    collectMap(c.nullableText(0), classBase)
                    collectMap(c.nullableText(1), classGrowth)
                }
            }
        }

        return StatRatingEngine.buildBenchmarks(
            gameId = gameId,
            characterBaseSamples = charBase,
            characterGrowthSamples = charGrowth,
            characterGrowthTotals = charTotals,
            characterCapSamples = charCaps,
            classBaseSamples = classBase,
            classGrowthSamples = classGrowth,
            rosterSize = rosterSize,
            classSize = classSize,
        )
    }

    private fun readCharacterWikiData(
        entryId: String,
        characterTitle: String,
        rawDesc: String,
    ): GamePackCharacterWikiData {
        var affiliation: String? = null
        var joinCondition: String? = null
        var routeAvailability: String? = null
        database.rawQuery(
            "SELECT affiliation, join_condition, route_availability FROM character_details WHERE entry_id=? LIMIT 1",
            arrayOf(entryId),
        ).use { c ->
            if (c.moveToFirst()) {
                affiliation = c.nullableText(0)
                joinCondition = c.nullableText(1)
                routeAvailability = c.nullableText(2)
            }
        }

        val rootObj = runCatching { JSONObject(rawDesc) }.getOrNull()
        val wikiObj = rootObj?.optJSONObject("wiki_profile")
        val fieldsObj = rootObj?.optJSONObject("fields")

        val startingClass = wikiObj?.optString("starting_class")?.takeIf { it.isNotBlank() && it != "null" }
            ?: fieldsObj?.optString("初始职业")?.takeIf { it.isNotBlank() && it != "--" }
        var crest = wikiObj?.optString("crest")?.takeIf { it.isNotBlank() && it != "null" && it != "无" }
            ?: fieldsObj?.optString("纹章")?.takeIf { it.isNotBlank() && it != "无" && it != "--" }
        val personalSkillName = wikiObj?.optString("personal_skill_name")?.takeIf { it.isNotBlank() && it != "null" && it != "无" }
            ?: fieldsObj?.optString("个人特技")?.takeIf { it.isNotBlank() && it != "无" && it != "--" }
        val personalSkillDesc = wikiObj?.optString("personal_skill_description")?.takeIf { it.isNotBlank() && it != "null" }
            ?: fieldsObj?.optString("个人特技说明")?.takeIf { it.isNotBlank() && it != "--" }
        val supportBonusNote = wikiObj?.optString("support_bonus_note")?.takeIf { it.isNotBlank() && it != "null" }

        val startingWeapons = buildList {
            val arr = wikiObj?.optJSONArray("starting_weapons")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optString(i)?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
        }

        val rawSkillTracks = buildList {
            val arr = wikiObj?.optJSONArray("skill_tracks")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val trackKey = obj.optString("track_key", "").trim()
                    val trackLabel = obj.optString("track_label", "").trim()
                    if (trackLabel.isEmpty()) continue
                    val initialRank = obj.optString("initial_rank", "—").trim().ifEmpty { "—" }
                    val proficiency = when (obj.optString("proficiency", "neutral").trim()) {
                        "strength" -> SkillProficiency.STRENGTH
                        "weakness" -> SkillProficiency.WEAKNESS
                        "budding" -> SkillProficiency.BUDDING
                        else -> SkillProficiency.NEUTRAL
                    }
                    val buddingSkill = obj.optString("budding_skill", "").takeIf { it.isNotBlank() && it != "null" }
                    val itemsArr = obj.optJSONArray("items")
                    val items = buildList {
                        if (itemsArr != null) {
                            for (j in 0 until itemsArr.length()) {
                                val itemObj = itemsArr.optJSONObject(j) ?: continue
                                val name = itemObj.optString("name", "").trim()
                                if (name.isEmpty()) continue
                                add(
                                    SkillMatrixItem(
                                        name = name,
                                        requirement = itemObj.optString("requirement", "").trim(),
                                        targetEntryId = itemObj.optString("target_id", "").takeIf { it.isNotBlank() && it != "null" },
                                        isUniversal = itemObj.optBoolean("is_universal", false),
                                        kind = itemObj.optString("kind", "").trim(),
                                        range = itemObj.optString("range", "").trim().takeIf { it.isNotEmpty() && it != "null" },
                                    ),
                                )
                            }
                        }
                    }
                    add(
                        SkillMatrixTrack(
                            trackKey = trackKey,
                            trackLabel = trackLabel,
                            initialRank = initialRank,
                            proficiency = proficiency,
                            buddingSkillName = buddingSkill,
                            items = items,
                        ),
                    )
                }
            }
        }

        // Batch-resolve real game icons for skills, weapons, and Engage Emblem mentors
        val skillTargetIds = rawSkillTracks.flatMap { tr -> tr.items.mapNotNull(SkillMatrixItem::targetEntryId) }
        val skillAssets = lookupPrimaryAssets(skillTargetIds)
        val emblemAssetsByToken: Map<String, String> = if (gameId == "engage" && rawSkillTracks.any { tr -> tr.items.any { it.kind == "纹章士资质" } }) {
            lookupEmblemAssetsByName()
        } else emptyMap()

        val skillTracks = rawSkillTracks.map { tr ->
            tr.copy(
                items = tr.items.map { item ->
                    val resolvedAsset = item.targetEntryId?.let(skillAssets::get)
                        ?: if (item.kind == "纹章士资质") {
                            val mentorName = item.name.removeSuffix(" 传授").removeSuffix("传授").trim()
                            emblemAssetsByToken[mentorName]
                        } else null
                    if (resolvedAsset != null) item.copy(imageAsset = resolvedAsset) else item
                },
            )
        }

        // Resolve personal skill entry & icon (42/42 Engage characters have real game skill icons!)
        val personalSkillItem = skillTracks.firstOrNull { it.trackKey == "personal" }
            ?.items?.firstOrNull { it.kind == "个人特技" }
        var personalSkillEntryId = personalSkillItem?.targetEntryId
        var personalSkillIconAsset = personalSkillItem?.imageAsset
        if (personalSkillIconAsset == null && !personalSkillName.isNullOrBlank()) {
            val matched = lookupEntryAndAssetByTitle(personalSkillName, listOf("skills"))
            if (matched != null) {
                if (personalSkillEntryId == null) personalSkillEntryId = matched.first
                personalSkillIconAsset = matched.second
            }
        }

        // Resolve starting weapon/item entries & real icons
        val startingWeaponItems = startingWeapons.map { rawWeapon ->
            val baseName = rawWeapon.substringBefore('（').substringBefore('(').trim()
            val matched = if (baseName.isNotEmpty()) {
                lookupEntryAndAssetByTitle(baseName, listOf("weapons", "items", "equipment"))
            } else null
            GamePackLinkedItem(
                name = rawWeapon,
                targetEntryId = matched?.first,
                imageAsset = matched?.second,
            )
        }

        val supportPartners = mutableListOf<GamePackSocialPartner>()
        val likedGifts = mutableListOf<GamePackPreferenceItem>()
        val dislikedGifts = mutableListOf<GamePackPreferenceItem>()
        val likedMeals = mutableListOf<GamePackPreferenceItem>()
        val dislikedMeals = mutableListOf<GamePackPreferenceItem>()

        // 1. From embedded wiki_profile (Engage supports & gifts)
        wikiObj?.optJSONArray("support_partners")?.let { arr ->
            val targetIds = mutableListOf<String>()
            val rawPairs = mutableListOf<Pair<String, String?>>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                val tid = obj.optString("target_id", "").takeIf { it.isNotBlank() && it != "null" }
                if (name.isNotEmpty()) {
                    rawPairs += name to tid
                    if (tid != null) targetIds += tid
                }
            }
            val portraits = lookupPrimaryAssets(targetIds)
            rawPairs.forEach { (name, tid) ->
                supportPartners += GamePackSocialPartner(
                    name = name,
                    targetEntryId = tid,
                    imageAsset = tid?.let(portraits::get),
                )
            }
        }
        wikiObj?.optJSONArray("liked_gifts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                if (name.isNotEmpty()) {
                    likedGifts += GamePackPreferenceItem(
                        name = name,
                        targetEntryId = obj.optString("target_id", "").takeIf { it.isNotBlank() && it != "null" },
                        liked = true,
                    )
                }
            }
        }
        wikiObj?.optJSONArray("disliked_gifts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                if (name.isNotEmpty()) {
                    dislikedGifts += GamePackPreferenceItem(
                        name = name,
                        targetEntryId = obj.optString("target_id", "").takeIf { it.isNotBlank() && it != "null" },
                        liked = false,
                    )
                }
            }
        }

        // 2. From SQLite content_relations (Three Houses supports, gifts, meals, crests)
        val selfShort = characterTitle.substringBefore('·').trim()
        val seenSupportNames = LinkedHashSet<String>(supportPartners.map { it.name })
        val seenLikedGifts = LinkedHashSet<String>(likedGifts.map { it.name })
        val seenDislikedGifts = LinkedHashSet<String>(dislikedGifts.map { it.name })
        val seenLikedMeals = LinkedHashSet<String>()
        val seenDislikedMeals = LinkedHashSet<String>()

        database.rawQuery(
            "SELECT r.relation_type, e.id, e.title, e.category_id " +
                "FROM content_relations r JOIN content_entries e ON e.id = r.target_entry_id " +
                "WHERE r.source_entry_id = ? " +
                "UNION ALL " +
                "SELECT r.relation_type, e.id, e.title, e.category_id " +
                "FROM content_relations r JOIN content_entries e ON e.id = r.source_entry_id " +
                "WHERE r.target_entry_id = ?",
            arrayOf(entryId, entryId),
        ).use { cursor ->
            val supportEntryIds = mutableListOf<Triple<String, String, String>>()
            while (cursor.moveToNext()) {
                val relType = cursor.getString(0)
                val otherId = cursor.getString(1)
                val otherTitle = cursor.getString(2).trim()
                when (relType) {
                    "likes_gift" -> if (seenLikedGifts.add(otherTitle)) {
                        likedGifts += GamePackPreferenceItem(otherTitle, otherId, liked = true)
                    }
                    "dislikes_gift" -> if (seenDislikedGifts.add(otherTitle)) {
                        dislikedGifts += GamePackPreferenceItem(otherTitle, otherId, liked = false)
                    }
                    "likes_meal" -> if (seenLikedMeals.add(otherTitle)) {
                        likedMeals += GamePackPreferenceItem(otherTitle, otherId, liked = true)
                    }
                    "dislikes_meal" -> if (seenDislikedMeals.add(otherTitle)) {
                        dislikedMeals += GamePackPreferenceItem(otherTitle, otherId, liked = false)
                    }
                    "crest_bearer" -> if (crest == null) {
                        crest = otherTitle
                    }
                    "support_pair_character", "support_member" -> {
                        // Extract the partner's name from "亚修 ＋ 帝弥托利"
                        val parts = otherTitle.split("＋", "+", "&").map { it.trim() }.filter { it.isNotEmpty() }
                        val partnerLabel = parts.firstOrNull { !it.startsWith(selfShort) && !selfShort.startsWith(it) }
                            ?: otherTitle
                        if (seenSupportNames.add(partnerLabel)) {
                            supportEntryIds += Triple(partnerLabel, otherId, otherTitle)
                        }
                    }
                }
            }
            if (supportEntryIds.isNotEmpty()) {
                // Resolve partner character ID & portrait for each support pair
                val charLookup = database.rawQuery(
                    "SELECT e.id, e.title, " +
                        "(SELECT ca.app_file FROM entry_assets ea JOIN content_assets ca ON ca.asset_id=ea.asset_id " +
                        "WHERE ea.entry_id=e.id AND ca.app_file IS NOT NULL ORDER BY $ASSET_ROLE_PRIORITY_SQL,ea.role LIMIT 1) " +
                        "FROM content_entries e WHERE e.category_id='characters'",
                    null,
                ).use { cc ->
                    buildList {
                        while (cc.moveToNext()) {
                            add(Triple(cc.getString(0), cc.getString(1), cc.nullableText(2)?.let { "games/$gameId/$it" }))
                        }
                    }
                }
                supportEntryIds.forEach { (partnerLabel, supportEntryId, _) ->
                    val baseName = partnerLabel.substringBefore('（').substringBefore('(').trim()
                    val matchedChar = charLookup.firstOrNull { (_, fullTitle, _) ->
                        fullTitle == partnerLabel ||
                            fullTitle.substringBefore('·') == baseName ||
                            (baseName.startsWith("贝雷") && fullTitle.startsWith("贝雷"))
                    }
                    supportPartners += GamePackSocialPartner(
                        name = partnerLabel,
                        targetEntryId = matchedChar?.first ?: supportEntryId,
                        imageAsset = matchedChar?.third,
                    )
                }
            }
        }

        return GamePackCharacterWikiData(
            affiliation = affiliation,
            startingClass = startingClass,
            crest = crest,
            personalSkillName = personalSkillName,
            personalSkillDescription = personalSkillDesc,
            personalSkillEntryId = personalSkillEntryId,
            personalSkillIconAsset = personalSkillIconAsset,
            startingWeapons = startingWeapons,
            startingWeaponItems = startingWeaponItems,
            joinCondition = joinCondition,
            routeAvailability = routeAvailability,
            supportBonusNote = supportBonusNote,
            skillTracks = skillTracks,
            supportPartners = supportPartners,
            likedGifts = likedGifts,
            dislikedGifts = dislikedGifts,
            likedMeals = likedMeals,
            dislikedMeals = dislikedMeals,
        )
    }

    private fun lookupEmblemAssetsByName(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        runCatching {
            database.rawQuery(
                "SELECT e.title, " +
                    "(SELECT ca.app_file FROM entry_assets ea JOIN content_assets ca ON ca.asset_id=ea.asset_id " +
                    "WHERE ea.entry_id=e.id AND ca.app_file IS NOT NULL AND ea.role NOT IN ($EXCLUDED_SCREENSHOT_ROLES_SQL) " +
                    "ORDER BY $ASSET_ROLE_PRIORITY_SQL,ea.role LIMIT 1) " +
                    "FROM content_entries e WHERE e.category_id='emblems'",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val title = cursor.getString(0).trim()
                    val asset = cursor.nullableText(1)?.let { "games/$gameId/$it" } ?: continue
                    result[title] = asset
                    title.split("/", "／", "·").forEach { part ->
                        val p = part.trim()
                        if (p.isNotEmpty()) result.putIfAbsent(p, asset)
                    }
                }
            }
        }
        return result
    }

    private fun lookupEntryAndAssetByTitle(title: String, categories: List<String>): Pair<String, String?>? {
        if (categories.isEmpty()) return null
        val placeholders = categories.joinToString(",") { "?" }
        val args = (categories + title).toTypedArray()
        return runCatching {
            database.rawQuery(
                "SELECT e.id, " +
                    "(SELECT ca.app_file FROM entry_assets ea JOIN content_assets ca ON ca.asset_id=ea.asset_id " +
                    "WHERE ea.entry_id=e.id AND ca.app_file IS NOT NULL AND ea.role NOT IN ($EXCLUDED_SCREENSHOT_ROLES_SQL) " +
                    "ORDER BY $ASSET_ROLE_PRIORITY_SQL,ea.role LIMIT 1) " +
                    "FROM content_entries e WHERE e.category_id IN ($placeholders) AND e.title=? LIMIT 1",
                args,
            ).use { cursor ->
                if (!cursor.moveToFirst()) null
                else cursor.getString(0) to cursor.nullableText(1)?.let { "games/$gameId/$it" }
            }
        }.getOrNull()
    }

    private fun lookupPrimaryAssets(entryIds: List<String>): Map<String, String> {
        if (entryIds.isEmpty()) return emptyMap()
        val distinct = entryIds.distinct()
        val placeholders = distinct.joinToString(",") { "?" }
        val result = mutableMapOf<String, String>()
        database.rawQuery(
            "SELECT e.id, " +
                "(SELECT ca.app_file FROM entry_assets ea JOIN content_assets ca ON ca.asset_id=ea.asset_id " +
                "WHERE ea.entry_id=e.id AND ca.app_file IS NOT NULL ORDER BY $ASSET_ROLE_PRIORITY_SQL,ea.role LIMIT 1) " +
                "FROM content_entries e WHERE e.id IN ($placeholders)",
            distinct.toTypedArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                cursor.nullableText(1)?.let { result[cursor.getString(0)] = "games/$gameId/$it" }
            }
        }
        return result
    }

    private fun readTypedDetails(
        entryId: String,
        supportConversationsOut: MutableList<SupportRankConversation> = mutableListOf(),
    ): List<GamePackDetailSection> {
        val sections = mutableListOf<GamePackDetailSection>()

        database.rawQuery(
            "SELECT gender,age,affiliation,join_condition,route_availability,base_stats_json,growths_json,caps_json " +
                "FROM character_details WHERE entry_id=? LIMIT 1",
            arrayOf(entryId),
        ).use { c ->
            if (c.moveToFirst()) {
                val baseFields = buildList {
                    c.nullableText(0)?.let { add(GamePackDetailField("性别", it)) }
                    c.nullableText(1)?.let { add(GamePackDetailField("年龄", it)) }
                    c.nullableText(2)?.let { add(GamePackDetailField("所属", it)) }
                    c.nullableText(3)?.let { add(GamePackDetailField("加入条件", it)) }
                    c.nullableText(4)?.let { add(GamePackDetailField("可用路线", it)) }
                }
                if (baseFields.isNotEmpty()) sections += GamePackDetailSection("角色档案", baseFields)
                jsonMapSection("初始属性", c.nullableText(5))?.let(sections::add)
                jsonMapSection("成长率", c.nullableText(6))?.let(sections::add)
                jsonMapSection("能力上限", c.nullableText(7))?.let(sections::add)
            }
        }

        database.rawQuery(
            "SELECT tier,movement,movement_type,weapon_ranks_json,base_stats_json,growths_json,caps_json,promotion_requirement,class_skill " +
                "FROM class_details WHERE entry_id=? LIMIT 1",
            arrayOf(entryId),
        ).use { c ->
            if (c.moveToFirst()) {
                val fields = buildList {
                    c.nullableText(0)?.let { add(GamePackDetailField("级别", it)) }
                    if (!c.isNull(1)) add(GamePackDetailField("移动力", c.getInt(1).toString()))
                    c.nullableText(2)?.let { add(GamePackDetailField("兵种类型", it)) }
                    c.nullableText(7)?.let { add(GamePackDetailField("转职条件", it)) }
                    c.nullableText(8)?.let { add(GamePackDetailField("职业特技", it)) }
                }
                if (fields.isNotEmpty()) sections += GamePackDetailSection("职业规格", fields)
                jsonMapSection("武器资质", c.nullableText(3))?.let(sections::add)
                jsonMapSection("基础能力", c.nullableText(4))?.let(sections::add)
                jsonMapSection("成长补正", c.nullableText(5))?.let(sections::add)
                jsonMapSection("能力上限", c.nullableText(6))?.let(sections::add)
            }
        }

        database.rawQuery(
            "SELECT bond_level_effects_json,sync_skills_json,engage_skills_json,inherited_skills_json,voice_actor,profile " +
                "FROM emblem_details WHERE entry_id=? LIMIT 1",
            arrayOf(entryId),
        ).use { c ->
            if (c.moveToFirst()) {
                val meta = buildList {
                    c.nullableText(4)?.let { add(GamePackDetailField("声优", it)) }
                    c.nullableText(5)?.let { add(GamePackDetailField("背景说明", it)) }
                }
                if (meta.isNotEmpty()) sections += GamePackDetailSection("纹章士概要", meta)
                jsonMapSection("同步特技", c.nullableText(1))?.let(sections::add)
                jsonMapSection("结合特技与战技", c.nullableText(2))?.let(sections::add)
                jsonMapSection("可继承特技", c.nullableText(3))?.let(sections::add)
                jsonMapSection("牵绊等级效果", c.nullableText(0))?.let(sections::add)
            }
        }

        database.rawQuery(
            "SELECT emblem_name,ring_rank,stat_bonuses_json,skill_name,skill_effect " +
                "FROM bond_ring_details WHERE entry_id=? LIMIT 1",
            arrayOf(entryId),
        ).use { c ->
            if (c.moveToFirst()) {
                val fields = buildList {
                    c.nullableText(0)?.takeIf { it != "未知" }?.let { add(GamePackDetailField("对应纹章士", it)) }
                    c.nullableText(1)?.takeIf { it != "未知" }?.let { add(GamePackDetailField("级别", it)) }
                    c.nullableText(3)?.let { add(GamePackDetailField("专属特技", it)) }
                    c.nullableText(4)?.let { add(GamePackDetailField("特技效果", it)) }
                }
                if (fields.isNotEmpty()) sections += GamePackDetailSection("牵绊戒指", fields)
                jsonMapSection("属性加成", c.nullableText(2))?.let(sections::add)
            }
        }

        database.rawQuery(
            "SELECT skill_type,weapon_type,requirement,cost,activation_condition,effect_json " +
                "FROM skill_details WHERE entry_id=? LIMIT 1",
            arrayOf(entryId),
        ).use { c ->
            if (c.moveToFirst()) {
                val fields = buildList {
                    c.nullableText(0)?.let { add(GamePackDetailField("技能类型", it)) }
                    c.nullableText(1)?.let { add(GamePackDetailField("关联武器", it)) }
                    c.nullableText(2)?.let { add(GamePackDetailField("习得要求", it)) }
                    c.nullableText(3)?.let { add(GamePackDetailField("SP / 消耗", it)) }
                    c.nullableText(4)?.let { add(GamePackDetailField("触发条件", it)) }
                }
                if (fields.isNotEmpty()) sections += GamePackDetailSection("特技详情", fields)
                jsonMapSection("效果细节", c.nullableText(5))?.let(sections::add)
            }
        }

        database.rawQuery(
            "SELECT equipment_type,subtype,rank,might,hit,critical,weight,durability,price,effect,acquisition,extra_stats_json " +
                "FROM equipment_details WHERE entry_id=? LIMIT 1",
            arrayOf(entryId),
        ).use { c ->
            if (c.moveToFirst()) {
                val extraObj = c.nullableText(11)?.let { runCatching { JSONObject(it) }.getOrNull() }
                val sourceFields = extraObj?.optJSONObject("source_fields")
                val rangeText = formatRangeText(
                    extraObj?.optString("range")?.takeIf { it.isNotBlank() && it != "null" }
                        ?: sourceFields?.optString("武器基础信息 / 射程")?.takeIf { it.isNotBlank() && it != "null" },
                )
                val usesSourceText = extraObj?.optString("uses_source_text")?.trim()
                    ?.takeIf { it.isNotEmpty() && it != "null" && it != "-" && it != "--" }
                val avoidText = cleanExtraStatValue(sourceFields?.optString("武器基础信息 / 回避"))
                val critAvoidText = cleanExtraStatValue(sourceFields?.optString("武器基础信息 / 必避"))
                val reqRankText = cleanExtraStatValue(sourceFields?.optString("武器基础信息 / 所需武器等级"))
                val sellPriceText = cleanExtraStatValue(extraObj?.opt("sell_price")?.toString())
                val repairMatText = cleanExtraStatValue(extraObj?.opt("repair_material")?.toString())
                val isMagic = c.nullableText(1)?.let { it.contains("魔法") || it.contains("Magic", ignoreCase = true) } == true
                val fields = buildList {
                    c.nullableText(0)?.let { add(GamePackDetailField("类别", it)) }
                    c.nullableText(1)?.let { add(GamePackDetailField("子类", it)) }
                    (c.nullableText(2) ?: reqRankText)?.let { add(GamePackDetailField("等级", it)) }
                    if (rangeText != null) add(GamePackDetailField("射程", rangeText))
                    if (!c.isNull(3)) add(GamePackDetailField("威力", trimNumber(c.getDouble(3))))
                    if (!c.isNull(4)) add(GamePackDetailField("命中", trimNumber(c.getDouble(4))))
                    if (!c.isNull(5)) add(GamePackDetailField("必杀", trimNumber(c.getDouble(5))))
                    if (avoidText != null && avoidText != "0") add(GamePackDetailField("回避", avoidText))
                    if (critAvoidText != null && critAvoidText != "0") add(GamePackDetailField("必避", critAvoidText))
                    if (!c.isNull(6)) add(GamePackDetailField("重量", trimNumber(c.getDouble(6))))
                    if (!c.isNull(7)) {
                        add(GamePackDetailField(if (isMagic) "使用次数" else "耐久", trimNumber(c.getDouble(7))))
                    } else if (usesSourceText != null) {
                        add(GamePackDetailField(if (isMagic) "使用次数" else "耐久", usesSourceText))
                    }
                    if (!c.isNull(8)) add(GamePackDetailField("价格", trimNumber(c.getDouble(8))))
                    if (sellPriceText != null && sellPriceText != "0") add(GamePackDetailField("售出价格", sellPriceText))
                    if (repairMatText != null) add(GamePackDetailField("锻造 / 修理材料", repairMatText))
                    c.nullableText(9)?.let { add(GamePackDetailField("效果", it)) }
                    c.nullableText(10)?.let { add(GamePackDetailField("获取方式", it)) }
                }
                if (fields.isNotEmpty()) {
                    sections += GamePackDetailSection(if (isMagic) "魔法战斗数据" else "装备属性", fields)
                }
                // Engage max-forge stats section if available
                if (sourceFields != null) {
                    val maxForge = buildList {
                        listOf(
                            "武器满强化信息 / 威力" to "威力",
                            "武器满强化信息 / 命中" to "命中",
                            "武器满强化信息 / 必杀" to "必杀",
                            "武器满强化信息 / 回避" to "回避",
                            "武器满强化信息 / 必避" to "必避",
                            "武器满强化信息 / 重量" to "重量",
                            "武器满强化信息 / 所需金钱" to "所需金钱",
                            "武器满强化信息 / 铁晶石" to "铁晶石",
                            "武器满强化信息 / 钢晶石" to "钢晶石",
                            "武器满强化信息 / 银晶石" to "银晶石",
                        ).forEach { (key, label) ->
                            cleanExtraStatValue(sourceFields.optString(key))?.let { v ->
                                add(GamePackDetailField(label, v))
                            }
                        }
                    }
                    if (maxForge.isNotEmpty()) {
                        sections += GamePackDetailSection("满强化属性与材料", maxForge)
                    }
                }
            }
        }

        database.rawQuery(
            "SELECT route,chapter_number,location,objective,defeat_condition,recommended_level " +
                "FROM chapter_details WHERE entry_id=? LIMIT 1",
            arrayOf(entryId),
        ).use { c ->
            if (c.moveToFirst()) {
                val fields = buildList {
                    c.nullableText(0)?.let { add(GamePackDetailField("路线", it)) }
                    c.nullableText(1)?.let { add(GamePackDetailField("章节序号", it)) }
                    c.nullableText(2)?.let { add(GamePackDetailField("地点", it)) }
                    c.nullableText(3)?.let { add(GamePackDetailField("胜利条件", it)) }
                    c.nullableText(4)?.let { add(GamePackDetailField("败北条件", it)) }
                    c.nullableText(5)?.let { add(GamePackDetailField("推荐等级", it)) }
                }
                if (fields.isNotEmpty()) sections += GamePackDetailSection("章节与战场", fields)
            }
        }

        database.rawQuery(
            "SELECT support_rank,availability_condition,support_points,dialogue_summary " +
                "FROM support_details WHERE entry_id=? LIMIT 1",
            arrayOf(entryId),
        ).use { c ->
            if (c.moveToFirst()) {
                val rawSummary = c.nullableText(3)
                var plainSummary: String? = null
                if (!rawSummary.isNullOrBlank() && rawSummary.trim().startsWith("{")) {
                    val convRoot = runCatching { JSONObject(rawSummary) }.getOrNull()
                    val convArr = convRoot?.optJSONArray("conversations")
                    if (convArr != null && convArr.length() > 0) {
                        for (i in 0 until convArr.length()) {
                            val cObj = convArr.optJSONObject(i) ?: continue
                            val rank = cObj.optString("rank", "").trim()
                            val linesArr = cObj.optJSONArray("lines") ?: continue
                            val lines = buildList {
                                for (j in 0 until linesArr.length()) {
                                    val lObj = linesArr.optJSONObject(j) ?: continue
                                    val spk = lObj.optString("s", "").trim()
                                    val txt = lObj.optString("t", "").trim()
                                    if (txt.isNotEmpty()) add(SupportDialogueLine(spk, txt))
                                }
                            }
                            if (rank.isNotEmpty() && lines.isNotEmpty()) {
                                supportConversationsOut += SupportRankConversation(rank, lines)
                            }
                        }
                    } else {
                        plainSummary = rawSummary
                    }
                } else {
                    plainSummary = rawSummary
                }
                val fields = buildList {
                    c.nullableText(0)?.let { add(GamePackDetailField("支援等级", it)) }
                    c.nullableText(1)?.let { add(GamePackDetailField("解锁条件", it)) }
                    c.nullableText(2)?.let { add(GamePackDetailField("支援点数", it)) }
                    plainSummary?.let { add(GamePackDetailField("摘要", it)) }
                }
                if (fields.isNotEmpty()) sections += GamePackDetailSection("支援信息", fields)
            }
        }

        database.rawQuery(
            "SELECT activity_type,availability_condition,options_json,effects_json " +
                "FROM activity_details WHERE entry_id=? LIMIT 1",
            arrayOf(entryId),
        ).use { c ->
            if (c.moveToFirst()) {
                val fields = buildList {
                    c.nullableText(0)?.let { add(GamePackDetailField("活动类型", it)) }
                    c.nullableText(1)?.let { add(GamePackDetailField("解锁条件", it)) }
                }
                if (fields.isNotEmpty()) sections += GamePackDetailSection("据点活动", fields)
                jsonMapSection("可选项与反应", c.nullableText(2))?.let(sections::add)
                jsonMapSection("奖励与效果", c.nullableText(3))?.let(sections::add)
            }
        }

        database.rawQuery(
            "SELECT mechanic_type,game_version,rule_description,formula_text,parameters_json " +
                "FROM mechanic_details WHERE entry_id=? LIMIT 1",
            arrayOf(entryId),
        ).use { c ->
            if (c.moveToFirst()) {
                val fields = buildList {
                    c.nullableText(0)?.let { add(GamePackDetailField("机制分类", it)) }
                    c.nullableText(1)?.let { add(GamePackDetailField("版本说明", it)) }
                    c.nullableText(2)?.let { formatReadableJsonText(it) }?.takeIf { it.isNotEmpty() }?.let {
                        add(GamePackDetailField("规则说明", it))
                    }
                    c.nullableText(3)?.let { formatReadableJsonText(it) }?.takeIf { it.isNotEmpty() }?.let {
                        add(GamePackDetailField("计算公式", it))
                    }
                }
                if (fields.isNotEmpty()) sections += GamePackDetailSection("规则与详情", fields)
                jsonMapSection("详细参数", c.nullableText(4))?.let(sections::add)
            }
        }

        database.rawQuery(
            "SELECT achievement_type,requirement,reward,chapter,notes FROM achievement_details WHERE entry_id=? LIMIT 1",
            arrayOf(entryId),
        ).use { c ->
            if (c.moveToFirst()) {
                val fields = buildList {
                    c.nullableText(0)?.let { add(GamePackDetailField("成就类型", it)) }
                    c.nullableText(1)?.let { add(GamePackDetailField("达成条件", it)) }
                    c.nullableText(2)?.let { add(GamePackDetailField("奖励", it)) }
                    c.nullableText(3)?.let { add(GamePackDetailField("章节", it)) }
                    c.nullableText(4)?.let { add(GamePackDetailField("备注", it)) }
                }
                if (fields.isNotEmpty()) sections += GamePackDetailSection("成就明细", fields)
            }
        }

        return sections
    }

    private fun jsonMapSection(title: String, raw: String?): GamePackDetailSection? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == "{}" || text == "[]") return null
        val fields = mutableListOf<GamePackDetailField>()
        runCatching { JSONObject(text) }.getOrNull()?.let { obj ->
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key in IGNORED_JSON_KEYS) continue
                val formatted = formatReadableJsonValue(obj.opt(key))
                val v = localizeJsonValue(key, formatted)
                if (v.isNotEmpty() && v != "null" && v != "--" && v != "-") {
                    fields += GamePackDetailField(localizeJsonKey(key), v)
                }
            }
        } ?: runCatching { JSONArray(text) }.getOrNull()?.let { arr ->
            val items = buildList {
                for (idx in 0 until arr.length()) {
                    val v = formatReadableJsonValue(arr.opt(idx))
                    if (v.isNotEmpty()) add(v)
                }
            }
            if (items.isNotEmpty()) {
                fields += GamePackDetailField(title, items.joinToString("；"))
            }
        }
        return if (fields.isEmpty()) null else GamePackDetailSection(title, fields)
    }

    private fun readSummary(cursor: Cursor): GamePackEntrySummary {
        val rawAsset = cursor.nullableText(7)
        val fullAsset = rawAsset?.let { "games/$gameId/$it" }
        val categoryId = cursor.getString(1)
        return GamePackEntrySummary(
            id = cursor.getString(0),
            categoryId = categoryId,
            title = cursor.getString(2),
            titleJa = cursor.nullableText(3),
            titleEn = cursor.nullableText(4),
            subtitle = localizeSubtitle(categoryId, cursor.getString(5)),
            verificationStatus = cursor.getString(6),
            imageAsset = fullAsset,
        )
    }

    private fun metadata(key: String): String? = database.rawQuery(
        "SELECT value FROM content_metadata WHERE key=? LIMIT 1",
        arrayOf(key),
    ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun loadThreeHousesPlannerData(): Pair<List<ThreeHousesCharacterData>, List<ThreeHousesClassData>> {
        require(gameId == "three-houses") { "仅《风花雪月》资料包支持期望成长规划数据" }
        val statKeys = ThreeHousesStat.entries.map { it.label }
        val characters = database.rawQuery(
            "SELECT e.id, e.title, COALESCE(e.title_en,''), COALESCE(cd.affiliation,''), " +
                "cd.base_stats_json, cd.growths_json, COALESCE(e.description,'') " +
                "FROM character_details cd JOIN content_entries e ON e.id = cd.entry_id " +
                "WHERE e.category_id = 'characters' AND cd.base_stats_json IS NOT NULL AND cd.growths_json IS NOT NULL " +
                "ORDER BY e.title",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val title = c.getString(1).trim()
                    val titleEn = c.getString(2).trim()
                    val aff = c.getString(3).trim()
                    val baseObj = runCatching { JSONObject(c.getString(4)) }.getOrNull() ?: continue
                    val grObj = runCatching { JSONObject(c.getString(5)) }.getOrNull() ?: continue
                    val descObj = runCatching { JSONObject(c.getString(6)) }.getOrNull()
                    val startClass = descObj?.optJSONObject("wiki_profile")?.optString("starting_class")
                        ?.takeIf { it.isNotBlank() && it != "null" }
                        ?: descObj?.optJSONObject("fields")?.optString("初始职业")
                            ?.takeIf { it.isNotBlank() && it != "--" }
                        ?: "平民"
                    val startLv = parsePackInt(baseObj.opt("初始等级"), default = 1).coerceIn(1, 98)
                    val baseVec = statKeys.map { k -> parsePackInt(baseObj.opt(k), default = 0) }
                    val grVec = statKeys.map { k -> parsePackInt(grObj.opt(k), default = 0) }
                    add(
                        ThreeHousesCharacterData(
                            entryId = id,
                            name = title,
                            englishName = titleEn,
                            affiliation = aff,
                            startLevel = startLv,
                            startClassName = startClass,
                            baseStats = baseVec,
                            personalGrowth = grVec,
                        ),
                    )
                }
            }
        }
        val classes = database.rawQuery(
            "SELECT e.id, e.title, COALESCE(e.title_en,''), COALESCE(cd.tier,''), COALESCE(cd.movement,4), " +
                "COALESCE(cd.movement_type,'步兵'), cd.base_stats_json, cd.growths_json, " +
                "COALESCE(cd.promotion_requirement,''), COALESCE(cd.class_skill,''), COALESCE(e.description,'') " +
                "FROM class_details cd JOIN content_entries e ON e.id = cd.entry_id " +
                "WHERE e.category_id = 'classes' AND cd.base_stats_json IS NOT NULL AND cd.growths_json IS NOT NULL " +
                "ORDER BY e.title",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val title = c.getString(1).trim()
                    val titleEn = c.getString(2).trim()
                    val tier = c.getString(3).trim()
                    val mv = c.getInt(4)
                    val mvType = c.getString(5).trim()
                    val baseObj = runCatching { JSONObject(c.getString(6)) }.getOrNull() ?: continue
                    val grObj = runCatching { JSONObject(c.getString(7)) }.getOrNull() ?: continue
                    val req = c.getString(8).trim()
                    val skill = c.getString(9).trim()
                    val descObj = runCatching { JSONObject(c.getString(10)) }.getOrNull()
                    val bonusObj = descObj?.optJSONObject("stat_bonuses")
                    val note = descObj?.optJSONObject("fields")?.optString("兵种特性与备注").orEmpty().trim()
                    val cls = ThreeHousesClassData(
                        entryId = id,
                        name = title,
                        englishName = titleEn,
                        tier = tier,
                        movement = mv,
                        movementType = mvType,
                        baseFloors = statKeys.map { k -> parsePackInt(baseObj.opt(k), default = 0) },
                        growthBonus = statKeys.map { k -> parsePackInt(grObj.opt(k), default = 0) },
                        statBonuses = statKeys.map { k -> parsePackInt(bonusObj?.opt(k), default = 0) },
                        promotionRequirement = req,
                        classSkill = skill,
                        note = note,
                    )
                    if (ThreeHousesRules.isPlayableClass(cls)) add(cls)
                }
            }
        }
        return characters to classes
    }

    fun loadEngagePlannerData(): Pair<List<EngageCharacterData>, List<EngageClassData>> {
        require(gameId == "engage") { "仅《Engage》资料包支持固定成长推算数据" }
        val statKeys = EngageStat.entries.map { it.label }
        val characters = database.rawQuery(
            "SELECT e.id, e.title, COALESCE(cd.affiliation,''), cd.base_stats_json, cd.growths_json, " +
                "cd.caps_json, COALESCE(e.description,'') " +
                "FROM character_details cd JOIN content_entries e ON e.id = cd.entry_id " +
                "WHERE e.category_id = 'characters' AND cd.base_stats_json IS NOT NULL AND cd.growths_json IS NOT NULL " +
                "ORDER BY e.title",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val title = c.getString(1).trim()
                    val aff = c.getString(2).trim()
                    val baseObj = runCatching { JSONObject(c.getString(3)) }.getOrNull() ?: continue
                    val grObj = runCatching { JSONObject(c.getString(4)) }.getOrNull() ?: continue
                    val capsObj = c.nullableText(5)?.let { runCatching { JSONObject(it) }.getOrNull() }
                    val descObj = runCatching { JSONObject(c.getString(6)) }.getOrNull()
                    val startClass = descObj?.optJSONObject("fields")?.optString("初始职业").orEmpty().trim()
                    val initLv = parsePackInt(baseObj.opt("初始等级"), default = 1).coerceIn(1, 40)
                    val internalLv = parsePackInt(baseObj.opt("内置等级"), default = 0).coerceAtLeast(0)
                    add(
                        EngageCharacterData(
                            entryId = id,
                            name = title,
                            affiliation = aff,
                            initialLevel = initLv,
                            internalLevel = internalLv,
                            initialClassName = startClass,
                            baseStats = statKeys.map { k -> parsePackInt(baseObj.opt(k), default = 0) },
                            personalGrowth = statKeys.map { k -> parsePackInt(grObj.opt(k), default = 0) },
                            capModifiers = statKeys.map { k -> parsePackInt(capsObj?.opt(k), default = 0) },
                        ),
                    )
                }
            }
        }
        val classes = database.rawQuery(
            "SELECT e.id, e.title, COALESCE(cd.tier,''), COALESCE(cd.movement,4), COALESCE(cd.movement_type,''), " +
                "cd.base_stats_json, cd.growths_json, cd.caps_json, " +
                "COALESCE(cd.promotion_requirement,''), COALESCE(cd.class_skill,''), COALESCE(e.description,'') " +
                "FROM class_details cd JOIN content_entries e ON e.id = cd.entry_id " +
                "WHERE e.category_id = 'classes' AND cd.tier != '转职机制说明' " +
                "AND cd.base_stats_json IS NOT NULL AND cd.growths_json IS NOT NULL AND cd.caps_json IS NOT NULL " +
                "ORDER BY cd.tier, e.title",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val title = c.getString(1).trim()
                    val tier = c.getString(2).trim()
                    val mv = c.getInt(3)
                    val style = c.getString(4).trim()
                    val baseObj = runCatching { JSONObject(c.getString(5)) }.getOrNull() ?: continue
                    val grObj = runCatching { JSONObject(c.getString(6)) }.getOrNull() ?: continue
                    val capsObj = runCatching { JSONObject(c.getString(7)) }.getOrNull() ?: continue
                    val req = c.getString(8).trim()
                    val skill = c.getString(9).trim()
                    val descObj = runCatching { JSONObject(c.getString(10)) }.getOrNull()
                    val note = descObj?.optJSONObject("fields")?.optString("限定与备注").orEmpty().trim()
                    add(
                        EngageClassData(
                            entryId = id,
                            name = title,
                            tier = tier,
                            movement = mv,
                            battleStyle = style,
                            baseStats = statKeys.map { k -> parsePackInt(baseObj.opt(k), default = 0) },
                            growth = statKeys.map { k -> parsePackInt(grObj.opt(k), default = 0) },
                            caps = statKeys.map { k -> parsePackInt(capsObj.opt(k), default = 30) },
                            weaponRequirement = req,
                            classSkill = skill,
                            restrictionNote = note,
                        ),
                    )
                }
            }
        }
        return characters to classes
    }

    private fun parsePackInt(raw: Any?, default: Int = 0): Int {
        if (raw == null || raw === JSONObject.NULL) return default
        if (raw is Number) return raw.toInt()
        val s = raw.toString().trim().removeSuffix("%").removePrefix("+").trim()
        if (s.isEmpty() || s == "--" || s == "-") return default
        return s.toIntOrNull() ?: s.toDoubleOrNull()?.toInt() ?: default
    }

    override fun close() = database.close()

    companion object {
        private const val APPLICATION_ID = 0x46454744
        private const val SCHEMA_VERSION = 1
        private const val EXCLUDED_SCREENSHOT_ROLES_SQL =
            "'workbook_image','community_skill_rating_chart','support_matrix_visual_candidate'," +
                "'bond_ring_rating_visual_candidate','character_rating_visual_candidate'"
        private const val ASSET_ROLE_PRIORITY_SQL =
            "CASE ea.role " +
                "WHEN 'character_portrait_primary' THEN 0 " +
                "WHEN 'character_portrait_candidate' THEN 0 " +
                "WHEN 'bond_ring_portrait_candidate' THEN 0 " +
                "WHEN 'emblem_illustration_or_icon_candidate' THEN 0 " +
                "WHEN 'weapon_icon_candidate' THEN 0 " +
                "WHEN 'item_icon_candidate' THEN 0 " +
                "WHEN 'skill_icon_candidate' THEN 0 " +
                "WHEN 'crest_icon_candidate' THEN 0 " +
                "WHEN 'battalion_icon_candidate' THEN 0 " +
                "WHEN 'weapon_proficiency_icon' THEN 0 " +
                "WHEN 'engraving_icon_candidate' THEN 0 " +
                "WHEN 'community_skill_rating_icon' THEN 0 " +
                "WHEN 'community_skill_rating_icon_candidate' THEN 0 " +
                "WHEN 'class_sprite_primary' THEN 0 " +
                "WHEN 'class_portrait_primary' THEN 0 " +
                "WHEN 'character_portrait_timeskip' THEN 1 " +
                "WHEN 'support_character_portrait' THEN 1 " +
                "WHEN 'dlc_character_portrait_candidate' THEN 1 " +
                "WHEN 'character_portrait_variant' THEN 2 " +
                "WHEN 'enchanter_effect_visual_candidate' THEN 2 " +
                "WHEN 'workbook_image' THEN 2 " +
                "ELSE 3 END"
        private val installLock = Any()

        fun open(context: Context, gameId: String): GameDataV1Repository = synchronized(installLock) {
            require(gameId == "engage" || gameId == "three-houses") { "不支持的多游戏资料包：$gameId" }
            val expectedHash = context.assets.open("games/$gameId/pack.sha256").bufferedReader().use {
                it.readText().trim()
            }
            check(Regex("[0-9a-f]{64}").matches(expectedHash)) { "$gameId 资料包校验值无效" }
            val dir = File(context.noBackupFilesDir, "game-packs/$gameId").apply { mkdirs() }
            val installed = File(dir, "pack-$expectedHash.db")
            if (!installed.isFile || sha256(installed) != expectedHash) {
                val staged = File.createTempFile("install-", ".db", dir)
                try {
                    context.assets.open("games/$gameId/pack.db").use { input ->
                        FileOutputStream(staged).use { output ->
                            input.copyTo(output)
                            output.fd.sync()
                        }
                    }
                    check(sha256(staged) == expectedHash) { "$gameId 资料包校验失败" }
                    openValidated(staged).close()
                    Files.move(
                        staged.toPath(),
                        installed.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } finally {
                    staged.delete()
                }
            }
            val database = openValidated(installed)
            try {
                GameDataV1Repository(gameId, database)
            } catch (error: Exception) {
                database.close()
                throw GamePackDatabaseException("无法读取 $gameId 离线资料库", error)
            }
        }

        private fun openValidated(file: File): SQLiteDatabase {
            val database = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                check(database.version == SCHEMA_VERSION) { "游戏资料包版本不匹配" }
                database.rawQuery("PRAGMA application_id", null).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getInt(0) == APPLICATION_ID) { "游戏资料包标识无效" }
                }
                database.rawQuery("PRAGMA quick_check", null).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getString(0) == "ok") { "游戏资料包完整性校验失败" }
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
                val buf = ByteArray(64 * 1024)
                var read = stream.read(buf)
                while (read >= 0) {
                    digest.update(buf, 0, read)
                    read = stream.read(buf)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

private fun Cursor.nullableText(column: Int): String? =
    if (isNull(column)) null else getString(column).trim().takeIf { it.isNotEmpty() }

private fun trimNumber(value: Double): String {
    val asInt = value.toInt()
    return if (kotlin.math.abs(value - asInt) < 1e-6) asInt.toString() else value.toString()
}

private fun cleanTrailingZero(value: String): String =
    if (value.endsWith(".0") && value.dropLast(2).removePrefix("-").all(Char::isDigit)) value.dropLast(2) else value

private fun cleanExtraStatValue(raw: String?): String? {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty() || s == "null" || s == "-" || s == "--") return null
    return cleanTrailingZero(s)
}

private fun formatRangeText(raw: String?): String? {
    val base = cleanExtraStatValue(raw) ?: return null
    val zh = base
        .replace(Regex("\\(\\s*magic\\s*/\\s*(\\d+)\\s*\\)", RegexOption.IGNORE_CASE), "魔力/$1")
        .replace(Regex("\\bmagic\\s*/\\s*(\\d+)", RegexOption.IGNORE_CASE), "魔力/$1")
        .replace(Regex("\\s*[–—~-]\\s*"), "～")
        .trim()
    return zh.takeIf { it.isNotEmpty() }
}

private fun formatReadableJsonText(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    if (trimmed.startsWith("{")) {
        runCatching { JSONObject(trimmed) }.getOrNull()?.let { return formatReadableJsonValue(it) }
    } else if (trimmed.startsWith("[")) {
        runCatching { JSONArray(trimmed) }.getOrNull()?.let { return formatReadableJsonValue(it) }
    }
    return cleanTrailingZero(trimmed)
}

private fun formatReadableJsonValue(value: Any?): String {
    if (value == null || value === JSONObject.NULL) return ""
    return when (value) {
        is JSONArray -> {
            val parts = buildList {
                for (i in 0 until value.length()) {
                    val item = formatReadableJsonValue(value.opt(i))
                    if (item.isNotEmpty()) add(item)
                }
            }
            parts.distinct().joinToString("、")
        }
        is JSONObject -> {
            val textOnly = value.optString("text", "").trim()
            if (textOnly.isNotEmpty() && value.length() <= 3 && (value.has("source_row") || value.has("source_table"))) {
                return cleanTrailingZero(textOnly)
            }
            val parts = buildList {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k in IGNORED_JSON_KEYS) continue
                    val fv = formatReadableJsonValue(value.opt(k))
                    if (fv.isNotEmpty() && fv != "--" && fv != "-") {
                        add("${localizeJsonKey(k)}：$fv")
                    }
                }
            }
            parts.joinToString("\n")
        }
        is String -> {
            val s = value.trim()
            if (s.isEmpty() || s == "null") ""
            else if (s.startsWith("{") || s.startsWith("[")) formatReadableJsonText(s)
            else cleanTrailingZero(s)
        }
        else -> cleanTrailingZero(value.toString().trim())
    }
}

private val NON_COMBAT_BENCHMARK_KEYS = setOf(
    "初始等级",
    "内置等级",
    "移动力",
    "初始SP",
    "等级",
    "SP",
)

private val IGNORED_JSON_KEYS = setOf(
    "official",
    "skill_name_may_be_simplified",
    "rating_kind",
    "rating_group_label",
    "game_presence_marker",
    "source_table",
    "source_row",
)

private val JSON_KEY_ZH = mapOf(
    "hp" to "HP",
    "str" to "力量",
    "strength" to "力量",
    "mag" to "魔力",
    "magic" to "魔力",
    "dex" to "技巧",
    "skill" to "技巧",
    "spd" to "速度",
    "speed" to "速度",
    "lck" to "幸运",
    "luck" to "幸运",
    "def" to "防守",
    "defense" to "防守",
    "res" to "魔防",
    "resistance" to "魔防",
    "cha" to "魅力",
    "charm" to "魅力",
    "bld" to "体格",
    "build" to "体格",
    "mov" to "移动力",
    "move" to "移动力",
    "source_text" to "武器资质",
    "claim_type" to "条目性质",
    "rating_group" to "推荐评级",
    "rating_scope" to "评级范围",
    "source_attribution" to "攻略署名",
    "source_note" to "补充说明",
    "source_version" to "版本",
    "sp_cost" to "继承 SP",
    "professor_level" to "指导等级",
    "activity_points" to "行动点数",
    "range" to "射程",
    "sell_price" to "售出价格",
    "repair_material" to "锻造 / 修理材料",
    "uses_source_text" to "使用次数",
)

private fun localizeJsonKey(key: String): String =
    JSON_KEY_ZH[key.lowercase()] ?: JSON_KEY_ZH[key] ?: key

private fun localizeJsonValue(key: String, value: String): String = when {
    key == "claim_type" && value == "community_opinion" -> "社区主观观点"
    key == "sp_cost" -> "${value} SP"
    else -> value
}

private fun relationLabel(type: String): String = when (type) {
    "battle_summary_for_chapter" -> "章节战场摘要"
    "chapter_of_dlc_campaign" -> "DLC 章节"
    "chapter_of_route" -> "路线章节"
    "class_growth_profile", "class_growth" -> "职业成长对照"
    "class_innate_ability" -> "职业内置特技"
    "class_mastery_ability" -> "职业精通特技"
    "crest_bearer" -> "纹章持有者"
    "dislikes_gift" -> "不喜欢的礼物"
    "likes_gift" -> "喜欢的礼物"
    "dislikes_meal" -> "不喜欢的料理"
    "likes_meal" -> "喜欢的料理"
    "serves_meal" -> "包含料理"
    "recruitment_condition_for", "character_recruitment" -> "角色招募条件"
    "room_reward_item" -> "房间奖励道具"
    "shop_offering_for" -> "商店售卖商品"
    "support_pair_character", "support_member" -> "支援对象"
    else -> type
}

private fun imageRoleLabel(role: String): String = when (role) {
    "character_portrait_primary", "character_portrait_candidate" -> "角色头像"
    "character_portrait_timeskip" -> "五年后立绘"
    "character_portrait_variant" -> "造型与异色立绘"
    "support_character_portrait" -> "支援对象头像"
    "class_sprite_primary" -> "兵种点阵"
    "class_portrait_primary" -> "魔物与首领立绘"
    "emblem_illustration_or_icon_candidate" -> "纹章士图像"
    "bond_ring_portrait_candidate" -> "牵绊戒指角色"
    "weapon_icon_candidate" -> "武器图标"
    "item_icon_candidate" -> "道具图标"
    "skill_icon_candidate", "community_skill_rating_icon", "community_skill_rating_icon_candidate" -> "特技图标"
    "crest_icon_candidate" -> "纹章图标"
    "battalion_icon_candidate" -> "骑士团徽章"
    "weapon_proficiency_icon" -> "武器资质图标"
    "engraving_icon_candidate" -> "纹章刻印图标"
    "community_skill_rating_chart" -> "特技评级表"
    "bond_ring_rating_visual_candidate" -> "戒指评级图"
    "character_rating_visual_candidate" -> "角色评级图"
    "enchanter_effect_visual_candidate" -> "魔法师效果图"
    "dlc_character_portrait_candidate" -> "DLC 配装参考"
    "dlc_map_or_guide_visual_candidate" -> "关卡与攻略配图"
    "support_matrix_visual_candidate" -> "支援关系表"
    else -> "关联图像"
}

private fun localizeSubtitle(categoryId: String, raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    if (trimmed.startsWith("Source roster class:")) {
        val rawClass = trimmed.removePrefix("Source roster class:").trim()
        return "初始职业：${localizeClassName(rawClass)}"
    }
    if (trimmed.startsWith("Bearers:")) {
        return "纹章持有者：${trimmed.removePrefix("Bearers:").trim()}"
    }
    EXACT_SUBTITLE_MAP[trimmed]?.let { return it }
    if (categoryId == "classes" && " · " in trimmed) {
        val parts = trimmed.split(" · ", limit = 2)
        val tier = when (parts[0].trim()) {
            "--" -> null
            "Unique" -> "专属"
            "Beginner" -> "初级"
            "Intermediate" -> "中级"
            "Advanced" -> "高级"
            "Master" -> "大师"
            "Special" -> "特殊"
            else -> parts[0].trim()
        }
        val move = parts[1].split(",").joinToString(" / ") { token ->
            when (token.trim().lowercase()) {
                "infantry" -> "步兵"
                "armored" -> "重甲"
                "cavalry" -> "骑兵"
                "flying" -> "飞行"
                "monster" -> "魔物"
                "dragon" -> "龙族"
                else -> token.trim()
            }
        }
        return listOfNotNull(tier, move).joinToString(" · ")
    }
    if (categoryId == "recruitment") {
        var text = trimmed
            .replace("after completing Cindered Shadows", "完成煤暗之章")
            .replace("Wave 3 Paid DLC", "第 3 波付费 DLC")
            .replace("Wave 4 Paid DLC", "第 4 波付费 DLC")
            .replace("onwards", "起")
            .replace("onward", "起")
            .replace("Prologue", "序章")
        text = Regex("Chapter (\\d+)").replace(text) { "第 ${it.groupValues[1]} 章" }
        return text
    }
    return trimmed
}

private fun localizeClassName(rawClass: String): String = CLASS_NAME_MAP[rawClass] ?: rawClass

private val CLASS_NAME_MAP = mapOf(
    "Commoner" to "平民",
    "Noble" to "贵族",
    "Myrmidon" to "剑士",
    "Soldier" to "士兵",
    "Fighter" to "战士",
    "Monk" to "修士",
    "Priest" to "修道士",
    "Mage" to "魔道士",
    "Archer" to "弓箭手",
    "Cavalier" to "轻骑士",
    "Mercenary" to "佣兵",
    "Thief" to "盗贼",
    "Armored Knight" to "重骑士",
    "Pegasus Knight" to "天马骑士",
    "Brigand" to "土匪",
    "Brawler" to "拳斗士",
    "Dark Mage" to "暗魔道士",
    "Warrior" to "勇士",
    "Swordmaster" to "刀剑将领",
    "Fortress Knight" to "重骑士",
    "Death Knight" to "死神骑士",
    "Archbishop" to "大司教",
    "Dancer" to "舞者",
    "Hero" to "勇者",
    "Assassin" to "刺客",
    "Sniper" to "狙击手",
    "Grappler" to "格斗家",
    "Paladin" to "圣骑士",
    "Wyvern Rider" to "飞龙骑士",
    "Warlock" to "术士",
    "Dark Bishop" to "暗主教",
    "Bishop" to "主教",
    "Trickster" to "骗术师",
    "War Monk" to "武僧",
    "War Cleric" to "战修女",
    "Dark Flier" to "暗天马",
    "Valkyrie" to "女武神",
)

private val EXACT_SUBTITLE_MAP = mapOf(
    "Class growth modifiers" to "职业成长补正",
    "Weapon" to "武器",
    "Item" to "道具",
    "Equipment" to "装备",
    "Class ability" to "职业特技",
    "Basic combat arts" to "基础战技",
    "Non-offensive combat arts" to "辅助战技",
    "Effective combat arts" to "特效战技",
    "Multi-hit combat arts" to "连击战技",
    "Magic combat arts" to "魔法战技",
    "White magic in the Fire Emblem series" to "白魔法",
    "Black magic in the Fire Emblem series" to "黑魔法",
    "Dark magic in the Fire Emblem series" to "暗魔法",
    "Gift preference" to "礼物偏好",
    "Dining Hall event" to "食堂活动",
    "Meat" to "肉类料理",
    "Fish" to "鱼类料理",
    "Vegetables" to "蔬菜料理",
    "Sweets" to "甜点",
    "Spicy" to "辛辣料理",
    "Bitter" to "苦味料理",
    "Main story" to "主线章节",
    "Part I" to "第一部（白云之章）",
    "Part II" to "第二部",
    "Paralogues" to "外传",
    "Crimson Flower" to "红花之章",
    "Azure Moon" to "苍月之章",
    "Silver Snow" to "银雪之章",
    "Verdant Wind" to "翠风之章",
    "Cindered Shadows DLC" to "煤暗之章（DLC）",
    "DLC campaign" to "DLC 剧情篇章",
    "Armory" to "武器店",
    "Item Shop" to "道具店",
    "Saint Statues" to "圣人像",
    "Amiibo Gazebo" to "Amiibo 凉亭",
    "Activity points" to "行动点数",
    "Byleth's Room" to "主角房间",
    "Share a Meal" to "共进晚餐",
    "Tea party scoring" to "茶会评分规则",
    "Traveling Merchants and Anna's Shop/Secret Shop" to "行商与安娜秘密商店",
    "Recon Training and Recon Missions" to "侦察训练与联机任务",
)
