package com.fireemblem.companion.personal

import com.fireemblem.companion.engine.BuddyCampaignLine
import com.fireemblem.companion.engine.GrowthStat
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class RouteMergeSnapshot(
    val line: BuddyCampaignLine,
    val level: Int,
    val profession: String = "",
    val stats: List<Int>,
    val note: String = "",
) {
    init {
        require(level in 1..99) { "角色等级应为 1 至 99" }
        require(profession.length <= 48) { "职业名称不能超过 48 字" }
        require(stats.size == GrowthStat.entries.size) { "必须包含九项基础属性" }
        require(stats.all { it in 0..999 }) { "属性值应为 0 至 999 的整数" }
        require(note.length <= 300) { "备注不能超过 300 字" }
    }

    val totalStats: Int
        get() = stats.sum()

    fun stat(stat: GrowthStat): Int = stats[stat.ordinal]
}

data class RouteMergeCharacter(
    val profileId: String,
    val characterId: String,
    val characterName: String,
    val snapshots: Map<BuddyCampaignLine, RouteMergeSnapshot> = emptyMap(),
    val selectedLines: Set<BuddyCampaignLine>? = null,
) {
    init {
        require(profileId.isNotBlank() && profileId.length <= 128) { "请选择个人存档" }
        require(characterId.isNotBlank() && characterId.length <= 128) { "角色编号无效" }
        require(characterName.trim().isNotEmpty() && characterName.trim().length <= 64) { "角色名称无效" }
        require(snapshots.all { (line, snap) -> snap.line == line }) { "路线快照键值不匹配" }
        require(selectedLines == null || selectedLines.all { it in snapshots }) { "自选融合路线必须是已录入的路线" }
    }

    val recordedLines: List<BuddyCampaignLine>
        get() = BuddyCampaignLine.entries.filter { it in snapshots }

    val hasCustomSelection: Boolean
        get() = selectedLines != null && selectedLines.isNotEmpty()

    val savedSelectedLines: List<BuddyCampaignLine>
        get() = if (selectedLines == null) recordedLines
        else BuddyCampaignLine.entries.filter { it in selectedLines && it in snapshots }

    fun withSnapshot(snapshot: RouteMergeSnapshot): RouteMergeCharacter =
        copy(snapshots = snapshots + (snapshot.line to snapshot))

    fun withoutLine(line: BuddyCampaignLine): RouteMergeCharacter {
        val nextSnapshots = snapshots - line
        val nextSelected = selectedLines
            ?.filterTo(LinkedHashSet()) { it in nextSnapshots }
            ?.takeIf { it.isNotEmpty() }
        return copy(snapshots = nextSnapshots, selectedLines = nextSelected)
    }

    fun withSelectedLines(lines: Set<BuddyCampaignLine>?): RouteMergeCharacter {
        if (lines == null) return copy(selectedLines = null)
        val valid = BuddyCampaignLine.entries.filterTo(LinkedHashSet()) { it in lines && it in snapshots }
        return copy(selectedLines = valid.takeIf { it.isNotEmpty() })
    }

    fun compare(activeLinesOverride: Set<BuddyCampaignLine>? = null): RouteMergeComparison {
        val allLines = recordedLines
        val fullMergedMaxStats = GrowthStat.entries.map { stat ->
            allLines.maxOfOrNull { snapshots.getValue(it).stat(stat) } ?: 0
        }
        val fullMergedMaxTotal = fullMergedMaxStats.sum()
        val fullHighestLevel = allLines.maxOfOrNull { snapshots.getValue(it).level }

        val activeLines = when {
            activeLinesOverride != null -> allLines.filter { it in activeLinesOverride }
            selectedLines != null -> allLines.filter { it in selectedLines }
            else -> allLines
        }

        val statLeaders = GrowthStat.entries.associateWith { stat ->
            val maxVal = activeLines.maxOfOrNull { snapshots.getValue(it).stat(stat) }
            if (maxVal == null || maxVal <= 0) emptySet()
            else activeLines.filterTo(LinkedHashSet()) { snapshots.getValue(it).stat(stat) == maxVal }
        }
        val mergedMaxStats = GrowthStat.entries.map { stat ->
            activeLines.maxOfOrNull { snapshots.getValue(it).stat(stat) } ?: 0
        }
        val mergedMaxTotal = mergedMaxStats.sum()
        val highestTotal = activeLines.maxOfOrNull { snapshots.getValue(it).totalStats }
        val totalLeaders = if (highestTotal == null) emptySet()
        else activeLines.filterTo(LinkedHashSet()) { snapshots.getValue(it).totalStats == highestTotal }
        val highestLevel = activeLines.maxOfOrNull { snapshots.getValue(it).level }
        val levelLeaders = if (highestLevel == null) emptySet()
        else activeLines.filterTo(LinkedHashSet()) { snapshots.getValue(it).level == highestLevel }

        val statDeltas = GrowthStat.entries.map { stat ->
            mergedMaxStats[stat.ordinal] - fullMergedMaxStats[stat.ordinal]
        }
        val totalDelta = mergedMaxTotal - fullMergedMaxTotal
        val droppedStats = GrowthStat.entries.filter { statDeltas[it.ordinal] < 0 }

        return RouteMergeComparison(
            recordedLines = allLines,
            selectedLines = activeLines,
            statLeaders = statLeaders,
            mergedMaxStats = mergedMaxStats,
            mergedMaxTotal = mergedMaxTotal,
            totalLeaders = totalLeaders,
            levelLeaders = levelLeaders,
            highestLevel = highestLevel,
            fullMergedMaxStats = fullMergedMaxStats,
            fullMergedMaxTotal = fullMergedMaxTotal,
            fullHighestLevel = fullHighestLevel,
            statDeltas = statDeltas,
            totalDelta = totalDelta,
            droppedStats = droppedStats,
            isSubsetMode = activeLines.size < allLines.size,
            hasSelectedSnapshot = activeLines.isNotEmpty(),
        )
    }

    fun compareForOverview(
        globalLines: Set<BuddyCampaignLine>,
        respectPerCharacterOverride: Boolean = true,
    ): RouteMergeComparison {
        val targetMask = if (respectPerCharacterOverride && selectedLines != null) selectedLines else globalLines
        return compare(activeLinesOverride = targetMask)
    }

    fun payload(): JsonObject = buildJsonObject {
        put("recordType", RECORD_TYPE)
        put("version", VERSION)
        put("profileId", profileId)
        put("characterId", characterId)
        put("characterName", characterName.trim())
        put(
            "snapshots",
            buildJsonObject {
                BuddyCampaignLine.entries.forEach { line ->
                    val snap = snapshots[line] ?: return@forEach
                    put(
                        line.id,
                        buildJsonObject {
                            put("level", snap.level)
                            put("profession", snap.profession.trim())
                            put("stats", JsonArray(snap.stats.map(::JsonPrimitive)))
                            put("note", snap.note.trim())
                        },
                    )
                }
            },
        )
        if (selectedLines != null) {
            val ordered = BuddyCampaignLine.entries.filter { it in selectedLines && it in snapshots }
            if (ordered.isNotEmpty()) {
                put("selectedLines", JsonArray(ordered.map { JsonPrimitive(it.id) }))
            }
        }
    }

    companion object {
        const val KIND = "route-merge-character"
        const val RECORD_TYPE = "route-merge-character"
        const val VERSION = 1

        fun entityId(profileId: String, characterId: String): String {
            require(profileId.isNotBlank() && characterId.isNotBlank()) { "存档或角色编号不能为空" }
            return UUID.nameUUIDFromBytes(
                "$RECORD_TYPE\u0000$profileId\u0000$characterId".toByteArray(Charsets.UTF_8),
            ).toString()
        }

        fun decode(payload: JsonObject): RouteMergeCharacter {
            require(strictText(payload, "recordType") == RECORD_TYPE) { "不是多线融合角色记录" }
            val version = payload["version"]?.jsonPrimitive ?: throw IllegalArgumentException("缺少版本信息")
            require(!version.isString && version.int == VERSION) { "不支持的多线融合记录版本" }
            val profileId = strictText(payload, "profileId")
            val characterId = strictText(payload, "characterId")
            val characterName = strictText(payload, "characterName")
            val snapshotsObj = payload["snapshots"]?.jsonObject ?: JsonObject(emptyMap())
            val map = linkedMapOf<BuddyCampaignLine, RouteMergeSnapshot>()
            BuddyCampaignLine.entries.forEach { line ->
                val raw = snapshotsObj[line.id]?.jsonObject ?: return@forEach
                val levelPrim = raw["level"]?.jsonPrimitive ?: throw IllegalArgumentException("缺少等级")
                require(!levelPrim.isString) { "等级格式无效" }
                val level = levelPrim.int
                val profession = optionalStrictText(raw, "profession") ?: ""
                val statsArr = raw["stats"]?.jsonArray ?: throw IllegalArgumentException("缺少属性数组")
                val stats = statsArr.map { elem ->
                    val p = elem.jsonPrimitive
                    require(!p.isString) { "属性数值格式无效" }
                    p.int
                }
                val note = optionalStrictText(raw, "note") ?: ""
                map[line] = RouteMergeSnapshot(
                    line = line,
                    level = level,
                    profession = profession,
                    stats = stats,
                    note = note,
                )
            }
            val parsedSelected: Set<BuddyCampaignLine>? = payload["selectedLines"]?.let { rawSelected ->
                val arr = rawSelected.jsonArray
                val lines = linkedSetOf<BuddyCampaignLine>()
                arr.forEach { elem ->
                    val p = elem.jsonPrimitive
                    require(p.isString) { "自选路线格式无效" }
                    val line = BuddyCampaignLine.entries.firstOrNull { it.id == p.content }
                        ?: throw IllegalArgumentException("未知的融合路线：${p.content}")
                    if (line in map) lines += line
                }
                lines.takeIf { it.isNotEmpty() }
            }
            return RouteMergeCharacter(
                profileId = profileId,
                characterId = characterId,
                characterName = characterName,
                snapshots = map,
                selectedLines = parsedSelected,
            )
        }

        fun readProfile(snapshot: PersonalSnapshot, profileId: String): RouteMergeCollection {
            val records = mutableListOf<RouteMergeStoredRecord>()
            val conflicts = mutableListOf<String>()
            val corrupted = mutableListOf<String>()
            snapshot.heads().toSortedMap().forEach { (entityId, group) ->
                if (group.none { it.kind == KIND }) return@forEach
                if (group.size > 1) {
                    val belongs = group.any { rev ->
                        val p = (rev.payload["profileId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        p == profileId || p == null
                    }
                    if (belongs) conflicts += entityId
                    return@forEach
                }
                val head = group.single()
                if (head.deleted) return@forEach
                runCatching { decode(head.payload) }.onSuccess { record ->
                    if (record.profileId == profileId) {
                        records += RouteMergeStoredRecord(entityId, head.revisionId, record)
                    }
                }.onFailure {
                    val p = (head.payload["profileId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (p == profileId || p == null) corrupted += entityId
                }
            }
            return RouteMergeCollection(
                records = records.sortedWith(
                    compareByDescending<RouteMergeStoredRecord> { it.character.recordedLines.size }
                        .thenBy { it.character.characterName },
                ),
                conflictEntityIds = conflicts,
                corruptedEntityIds = corrupted,
            )
        }

        private fun strictText(obj: JsonObject, key: String): String {
            val p = obj[key]?.jsonPrimitive ?: throw IllegalArgumentException("缺少字段：$key")
            require(p.isString) { "字段格式无效：$key" }
            return p.content
        }

        private fun optionalStrictText(obj: JsonObject, key: String): String? {
            val elem = obj[key] ?: return null
            val p = elem.jsonPrimitive
            require(p.isString) { "字段格式无效：$key" }
            return p.content
        }
    }
}

data class RouteMergeComparison(
    val recordedLines: List<BuddyCampaignLine>,
    val selectedLines: List<BuddyCampaignLine>,
    val statLeaders: Map<GrowthStat, Set<BuddyCampaignLine>>,
    val mergedMaxStats: List<Int>,
    val mergedMaxTotal: Int,
    val totalLeaders: Set<BuddyCampaignLine>,
    val levelLeaders: Set<BuddyCampaignLine>,
    val highestLevel: Int?,
    val fullMergedMaxStats: List<Int>,
    val fullMergedMaxTotal: Int,
    val fullHighestLevel: Int?,
    val statDeltas: List<Int>,
    val totalDelta: Int,
    val droppedStats: List<GrowthStat>,
    val isSubsetMode: Boolean,
    val hasSelectedSnapshot: Boolean,
)

sealed interface RouteMergeOverviewSort {
    val key: String
    val label: String

    data object SelectedTotalDesc : RouteMergeOverviewSort {
        override val key = "selected_total"
        override val label = "自选总和"
    }

    data object FullTotalDesc : RouteMergeOverviewSort {
        override val key = "full_total"
        override val label = "全线总和"
    }

    data object LossLargestFirst : RouteMergeOverviewSort {
        override val key = "loss_desc"
        override val label = "损失最大"
    }

    data object LossSmallestFirst : RouteMergeOverviewSort {
        override val key = "loss_asc"
        override val label = "损失最小"
    }

    data object LineCountDesc : RouteMergeOverviewSort {
        override val key = "lines_desc"
        override val label = "已录线数"
    }

    data class StatDesc(val stat: GrowthStat) : RouteMergeOverviewSort {
        override val key = "stat:${stat.name}"
        override val label = "${stat.label}最高"
    }

    companion object {
        fun fromKey(key: String?): RouteMergeOverviewSort = when {
            key == FullTotalDesc.key -> FullTotalDesc
            key == LossLargestFirst.key -> LossLargestFirst
            key == LossSmallestFirst.key -> LossSmallestFirst
            key == LineCountDesc.key -> LineCountDesc
            key != null && key.startsWith("stat:") -> {
                val statName = key.removePrefix("stat:")
                GrowthStat.entries.firstOrNull { it.name == statName }?.let(::StatDesc) ?: SelectedTotalDesc
            }
            else -> SelectedTotalDesc
        }
    }
}

data class RouteMergeOverviewRow(
    val record: RouteMergeStoredRecord,
    val comparison: RouteMergeComparison,
    val usingCustomOverride: Boolean,
)

object RouteMergeOverviewProjector {
    fun project(
        records: List<RouteMergeStoredRecord>,
        globalLines: Set<BuddyCampaignLine>,
        respectPerCharacterOverride: Boolean,
        sort: RouteMergeOverviewSort,
    ): List<RouteMergeOverviewRow> {
        val rows = records
            .filter { it.character.recordedLines.isNotEmpty() }
            .map { rec ->
                val custom = respectPerCharacterOverride && rec.character.hasCustomSelection
                RouteMergeOverviewRow(
                    record = rec,
                    comparison = rec.character.compareForOverview(globalLines, respectPerCharacterOverride),
                    usingCustomOverride = custom,
                )
            }
        val comparator: Comparator<RouteMergeOverviewRow> = when (sort) {
            RouteMergeOverviewSort.SelectedTotalDesc ->
                compareByDescending<RouteMergeOverviewRow> { it.comparison.hasSelectedSnapshot }
                    .thenByDescending { it.comparison.mergedMaxTotal }
                    .thenByDescending { it.comparison.fullMergedMaxTotal }
                    .thenBy { it.record.character.characterName }
            RouteMergeOverviewSort.FullTotalDesc ->
                compareByDescending<RouteMergeOverviewRow> { it.comparison.fullMergedMaxTotal }
                    .thenByDescending { it.comparison.mergedMaxTotal }
                    .thenBy { it.record.character.characterName }
            RouteMergeOverviewSort.LossLargestFirst ->
                compareBy<RouteMergeOverviewRow> { it.comparison.totalDelta } // most negative first
                    .thenByDescending { it.comparison.fullMergedMaxTotal }
                    .thenBy { it.record.character.characterName }
            RouteMergeOverviewSort.LossSmallestFirst ->
                compareByDescending<RouteMergeOverviewRow> { it.comparison.hasSelectedSnapshot }
                    .thenByDescending { it.comparison.totalDelta } // 0 first
                    .thenByDescending { it.comparison.mergedMaxTotal }
                    .thenBy { it.record.character.characterName }
            RouteMergeOverviewSort.LineCountDesc ->
                compareByDescending<RouteMergeOverviewRow> { it.comparison.recordedLines.size }
                    .thenByDescending { it.comparison.mergedMaxTotal }
                    .thenBy { it.record.character.characterName }
            is RouteMergeOverviewSort.StatDesc ->
                compareByDescending<RouteMergeOverviewRow> { it.comparison.hasSelectedSnapshot }
                    .thenByDescending { it.comparison.mergedMaxStats[sort.stat.ordinal] }
                    .thenByDescending { it.comparison.mergedMaxTotal }
                    .thenBy { it.record.character.characterName }
        }
        return rows.sortedWith(comparator)
    }
}

data class RouteMergeStoredRecord(
    val entityId: String,
    val revisionId: String,
    val character: RouteMergeCharacter,
)

data class RouteMergeCollection(
    val records: List<RouteMergeStoredRecord>,
    val conflictEntityIds: List<String>,
    val corruptedEntityIds: List<String>,
) {
    fun byCharacterId(characterId: String): RouteMergeStoredRecord? =
        records.firstOrNull { it.character.characterId == characterId }
}
