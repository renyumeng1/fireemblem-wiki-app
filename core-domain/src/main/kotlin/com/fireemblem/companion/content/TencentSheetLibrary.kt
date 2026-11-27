package com.fireemblem.companion.content

import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun interface SheetPackageSource {
    fun open(path: String): InputStream
}

data class SheetPackageMetadata(
    val sourceUri: String?,
    val title: String?,
    val revision: String,
    val retrievedAt: String?,
    val availableSheetCount: Int,
    val unavailableSheets: List<UnavailableSheet>,
)

data class UnavailableSheet(
    val id: String,
    val name: String,
    val reason: String,
    val hidden: Boolean,
)

data class SheetDescriptor(
    val id: String,
    val name: String,
    val cellCount: Int,
)

data class SheetCell(
    val address: String,
    val column: Int,
    val type: String,
    val value: String,
    val imageAsset: String? = null,
)

data class SheetRow(
    val sheetId: String,
    val sheetName: String,
    val rowNumber: Int,
    val cells: List<SheetCell>,
) {
    internal val searchText: String = cells.asSequence()
        .map(SheetCell::value)
        .filter(String::isNotBlank)
        .joinToString(" ")
        .lowercase(Locale.ROOT)
}

data class SheetSearchResult(
    val row: SheetRow,
    val matchingCells: List<SheetCell>,
)

data class SheetSearchPage(
    val results: List<SheetSearchResult>,
    val totalMatches: Int,
) {
    val isTruncated: Boolean
        get() = totalMatches > results.size
}

data class WikiCategory(
    val id: String,
    val title: String,
    val description: String,
    val entries: List<WikiEntry>,
)

data class WikiEntry(
    val id: String,
    val title: String,
    val subtitle: String,
    val imageAsset: String?,
    val fields: List<WikiField>,
    val sourceSheetName: String,
    val sourceRowNumber: Int,
)

data class WikiField(
    val label: String,
    val value: String,
    val imageAsset: String? = null,
)

data class WikiMappingIssue(
    val categoryTitle: String,
    val sourceSheetName: String,
)

class SheetPackageException(message: String, cause: Throwable? = null) : Exception(message, cause)

class TencentSheetLibrary private constructor(
    val metadata: SheetPackageMetadata,
    val sheets: List<SheetDescriptor>,
    private val rowsBySheet: Map<String, List<SheetRow>>,
) {
    val wikiMappingIssues: List<WikiMappingIssue> by lazy {
        WIKI_ADAPTERS.mapNotNull { adapter ->
            val sheet = sheets.firstOrNull { it.id == adapter.sheetId } ?: return@mapNotNull null
            val header = rowsFor(adapter.sheetId).firstOrNull { it.rowNumber == adapter.headerRow }
                ?.cells?.firstOrNull { it.column == adapter.titleColumn }?.value.orEmpty().trim()
            if (header.startsWith(adapter.expectedTitleHeader)) null
            else WikiMappingIssue(adapter.title, sheet.name)
        }
    }

    val wikiCategories: List<WikiCategory> by lazy {
        val unsupported = wikiMappingIssues.map { it.categoryTitle }.toSet()
        WIKI_ADAPTERS.filterNot { it.title in unsupported }.mapNotNull { adapter ->
            val entries = rowsFor(adapter.sheetId).asSequence()
                .filter { it.rowNumber >= adapter.firstDataRow }
                .mapNotNull { row -> adapter.toEntry(row) }
                .toList()
            if (entries.isEmpty()) null else WikiCategory(
                adapter.id,
                adapter.title,
                adapter.description,
                entries,
            )
        }
    }

    fun wikiEntry(id: String): WikiEntry? = wikiCategories.asSequence()
        .flatMap { it.entries.asSequence() }
        .firstOrNull { it.id == id }

    fun rowsFor(sheetId: String): List<SheetRow> = rowsBySheet[sheetId].orEmpty()

    fun search(query: String, sheetId: String? = null, limit: Int = DEFAULT_SEARCH_LIMIT): SheetSearchPage {
        require(limit >= 0) { "limit must not be negative" }
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        if (normalizedQuery.isEmpty()) return SheetSearchPage(emptyList(), 0)

        val results = ArrayList<SheetSearchResult>(minOf(limit, 32))
        var totalMatches = 0
        val sheetIds = if (sheetId == null) sheets.map(SheetDescriptor::id) else listOf(sheetId)
        for (id in sheetIds) {
            for (row in rowsBySheet[id].orEmpty()) {
                if (!row.searchText.contains(normalizedQuery)) continue
                totalMatches++
                if (results.size < limit) {
                    results += SheetSearchResult(
                        row = row,
                        matchingCells = row.cells.filter { it.value.lowercase(Locale.ROOT).contains(normalizedQuery) },
                    )
                }
            }
        }
        return SheetSearchPage(results, totalMatches)
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val DEFAULT_SEARCH_LIMIT = 80
        private const val GENERATOR_ID = "fireemblem/tools/tencent-sheet"
        private val SHEET_ID_PATTERN = Regex("[A-Za-z0-9_-]+")
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        private val json = Json

        fun load(source: SheetPackageSource): TencentSheetLibrary {
            val manifestBytes = readBytes(source, "manifest.json")
            val manifest = parseObject(manifestBytes, "manifest.json")
            if (manifest.requiredInt("schema_version", "manifest") != SCHEMA_VERSION) {
                throw SheetPackageException("不支持的腾讯表格资料包版本")
            }
            if (manifest.requiredString("generator", "manifest") != GENERATOR_ID) {
                throw SheetPackageException("资料包来源标识不匹配")
            }

            val sourceObject = manifest.requiredObject("source", "manifest")
            val coverage = manifest.requiredObject("coverage", "manifest")
            val revision = sourceObject["revision"].scalarString()
                ?: throw SheetPackageException("资料包缺少来源修订号")
            val sheets = manifest.requiredArray("sheets", "manifest").mapIndexed { index, element ->
                val path = "manifest.sheets[$index]"
                val item = element.objectAt(path)
                val id = item.requiredString("sheet_id", path)
                if (!SHEET_ID_PATTERN.matches(id)) throw SheetPackageException("$path.sheet_id 格式不正确")
                if (item.requiredString("status", path) != "available") {
                    throw SheetPackageException("$path.status 不是可用状态")
                }
                val outputPath = item.requiredString("output_file", path)
                if (outputPath != "tables/$id.json") throw SheetPackageException("$path.output_file 路径不正确")
                val checksum = item.requiredString("output_sha256", path)
                if (!SHA256_PATTERN.matches(checksum)) throw SheetPackageException("$path.output_sha256 格式不正确")
                SheetManifestEntry(
                    descriptor = SheetDescriptor(
                        id = id,
                        name = item.requiredString("name", path),
                        cellCount = item.requiredInt("stored_cell_count", path),
                    ),
                    outputPath = outputPath,
                    checksum = checksum,
                )
            }
            if (sheets.map { it.descriptor.id }.distinct().size != sheets.size) {
                throw SheetPackageException("资料包包含重复工作表 ID")
            }

            val unavailable = manifest.requiredArray("unavailable_sheets", "manifest").mapIndexed { index, element ->
                val path = "manifest.unavailable_sheets[$index]"
                val item = element.objectAt(path)
                UnavailableSheet(
                    id = item.requiredString("sheet_id", path),
                    name = item.requiredString("name", path),
                    reason = item.requiredString("reason", path),
                    hidden = item["hidden"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                )
            }
            if (coverage.requiredInt("available_sheets", "manifest.coverage") != sheets.size ||
                coverage.requiredInt("unavailable_sheets", "manifest.coverage") != unavailable.size
            ) {
                throw SheetPackageException("资料包清单计数与内容不一致")
            }

            val rowsBySheet = linkedMapOf<String, List<SheetRow>>()
            for (entry in sheets) {
                val bytes = readBytes(source, entry.outputPath)
                if (sha256(bytes) != entry.checksum) {
                    throw SheetPackageException("工作表 ${entry.descriptor.name} 校验失败")
                }
                val table = parseObject(bytes, entry.outputPath)
                if (table.requiredInt("schema_version", entry.outputPath) != SCHEMA_VERSION) {
                    throw SheetPackageException("工作表 ${entry.descriptor.name} 版本不匹配")
                }
                val tableMetadata = table.requiredObject("metadata", entry.outputPath)
                if (tableMetadata.requiredString("sheet_id", entry.outputPath) != entry.descriptor.id ||
                    tableMetadata.requiredString("name", entry.outputPath) != entry.descriptor.name
                ) {
                    throw SheetPackageException("工作表 ${entry.descriptor.name} 身份与清单不一致")
                }
                val cellElements = table.requiredArray("cells", entry.outputPath)
                if (cellElements.size != entry.descriptor.cellCount) {
                    throw SheetPackageException("工作表 ${entry.descriptor.name} 单元格计数不一致")
                }
                val rows = linkedMapOf<Int, MutableList<SheetCell>>()
                cellElements.forEachIndexed { cellIndex, cellElement ->
                    val cellPath = "${entry.outputPath}.cells[$cellIndex]"
                    val cell = cellElement.objectAt(cellPath)
                    val rowNumber = cell.requiredInt("row", cellPath)
                    if (rowNumber < 1) throw SheetPackageException("$cellPath.row 必须从 1 开始")
                    val column = cell.requiredInt("column", cellPath)
                    if (column < 1) throw SheetPackageException("$cellPath.column 必须从 1 开始")
                    val address = cell.requiredString("address", cellPath)
                    val type = cell.requiredString("type", cellPath)
                    val value = if (type == "formula") {
                        cell["formula"]?.objectAt("$cellPath.formula")?.get("cached_value")
                    } else {
                        cell["value"]
                    }
                    val cellText = value.asDisplayText().ifBlank {
                        cell["rich_text_runs"].asRichText()
                    }
                    val imageText = cell["image_reference"]?.let { "[圖片]" }.orEmpty()
                    val displayText = cellText.ifBlank { imageText }
                    val imagePath = cell["image_reference"]?.jsonObject?.get("local_file")?.scalarString()
                    val imageAsset = imagePath?.let(::optimizedImagePath)
                    rows.getOrPut(rowNumber) { mutableListOf() } += SheetCell(address, column, type, displayText, imageAsset)
                }
                rowsBySheet[entry.descriptor.id] = rows.toSortedMap().map { (rowNumber, cells) ->
                    SheetRow(entry.descriptor.id, entry.descriptor.name, rowNumber, cells.sortedBy(SheetCell::column))
                }
            }

            return TencentSheetLibrary(
                metadata = SheetPackageMetadata(
                    sourceUri = sourceObject["uri"].scalarString(),
                    title = sourceObject["title"].scalarString(),
                    revision = revision,
                    retrievedAt = sourceObject["retrieved_at"].scalarString(),
                    availableSheetCount = sheets.size,
                    unavailableSheets = unavailable,
                ),
                sheets = sheets.map(SheetManifestEntry::descriptor),
                rowsBySheet = rowsBySheet,
            )
        }

        private data class SheetManifestEntry(
            val descriptor: SheetDescriptor,
            val outputPath: String,
            val checksum: String,
        )

        private fun optimizedImagePath(path: String): String? {
            if (!Regex("assets/[A-Za-z0-9._-]+\\.png").matches(path) || path.contains("..")) return null
            return path.removeSuffix(".png") + ".webp"
        }

        private val WIKI_ADAPTERS = listOf(
            WikiAdapter("characters", "角色", "加入条件、成长与初始能力", "sd3o0k", 2, "角色名称", 4, 4, 3,
                fields = listOf(
                    "所属势力" to 2, "初始兵种" to 5, "性别" to 6, "个人特技图标" to 7,
                    "个人特技" to 8, "特技效果" to 9, "等级特技图标" to 10, "等级特技" to 11,
                    "等级特技效果" to 12, "血印图标" to 14, "血印" to 15,
                    "最早加入章节" to 17, "加入条件" to 18,
                    "HP成长率" to 20, "力量成长率" to 21, "魔力成长率" to 22,
                    "速度成长率" to 23, "技巧成长率" to 24, "防守成长率" to 25,
                    "魔防成长率" to 26, "幸运成长率" to 27, "魅力成长率" to 28,
                    "初始等级" to 30, "初始HP" to 31, "初始力量" to 32, "初始魔力" to 33,
                    "初始速度" to 34, "初始技巧" to 35, "初始防守" to 36, "初始魔防" to 37,
                    "初始幸运" to 38, "初始魅力" to 39,
                ), subtitleColumns = listOf(2, 5)),
            WikiAdapter("professions", "职业", "兵种成长修正、能力补正与精通", "lnafol", 2, "兵种名称", 4, 3, 2,
                fields = listOf(
                    "职业级别" to 4, "考试要求" to 5, "技能经验加成" to 6, "移动力" to 7,
                    "HP成长修正" to 8, "力量成长修正" to 9, "魔力成长修正" to 10,
                    "速度成长修正" to 11, "技巧成长修正" to 12, "防守成长修正" to 13,
                    "魔防成长修正" to 14, "幸运成长修正" to 15, "魅力成长修正" to 16,
                    "基础HP" to 18, "基础力量" to 19, "基础魔力" to 20, "基础速度" to 21,
                    "基础技巧" to 22, "基础防守" to 23, "基础魔防" to 24, "基础幸运" to 25,
                    "基础魅力" to 26, "兵种特性" to 28, "固有特技" to 29,
                    "精通所需经验" to 32, "精通特技" to 33,
                ), subtitleColumns = listOf(4)),
            WikiAdapter("crests", "血印", "血印效果、持有者与对应纹章", "0x9327", 2, "血印名称", 4, 4, 3,
                fields = listOf("编号" to 2, "血印效果" to 5, "持有者" to 6, "对应纹章" to 7, "纹章描述" to 8)),
            WikiAdapter("skills", "特技", "特技效果与习得条件", "hbns9r", 2, "特技名称", 4, 3, 2,
                fields = listOf("特技效果" to 4, "习得条件" to 5, "备注" to 7)),
            WikiAdapter("combat-arts", "战技", "消耗、威力、命中与习得条件", "qgu8m4", 2, "战技名称", 4, 3, 2,
                fields = listOf("使用武器" to 4, "战技描述" to 5, "耐久消耗" to 6, "威力" to 7,
                    "命中" to 8, "必杀" to 9, "回避" to 10, "必避" to 11, "有效" to 12,
                    "特效" to 13, "射程" to 14, "习得条件" to 15, "备注" to 17)),
            WikiAdapter("magic", "魔法", "魔法效果、属性与习得条件", "14hhhl", 2, "魔法名称", 4, 3, 2,
                fields = listOf("魔术类型" to 4, "魔法描述" to 5, "使用次数" to 6, "威力/愈力" to 7,
                    "命中" to 8, "必杀" to 9, "回避" to 10, "重量" to 11, "有效" to 12,
                    "特效" to 13, "射程" to 14, "习得条件" to 15, "备注" to 17)),
            WikiAdapter("weapons", "武器与装备", "武器属性、获取方式与修理材料", "twr89r", 2, "武器名称", 4, 4, 3,
                fields = listOf("武器类型" to 2, "详细说明" to 5, "等级" to 6, "威力" to 7,
                    "命中" to 8, "必杀" to 9, "回避" to 10, "射程" to 11, "重量" to 12,
                    "有效" to 13, "特效" to 14, "耐久" to 15, "获取方法" to 16, "修理材料" to 17)),
            WikiAdapter("items", "道具与材料", "效果、价格、获取方法与地图位置", "vbvhs5", 2, "物品名称", 4, 3, 2,
                fields = listOf("耐久/口味" to 4, "购买价格" to 5, "出售价格" to 6,
                    "详细说明" to 7, "获得方法" to 8, "地图参考" to 10)),
            WikiAdapter("paralogues", "外传", "触发章节、路线期限与奖励", "6bb00l", 2, "外传章节", 5, 3, 4,
                fields = listOf("相关角色" to 5, "凯篇接取期限" to 6, "凯篇截止期限" to 7,
                    "迪篇接取期限" to 8, "迪篇截止期限" to 9, "赛篇接取期限" to 10,
                    "赛篇截止期限" to 11, "蕾篇接取期限" to 12, "蕾篇截止期限" to 13,
                    "触发时期" to 14, "触发条件与特殊奖励" to 15, "报酬奖励" to 16, "胜利条件" to 17)),
            WikiAdapter("side-quests", "支线任务", "四条路线的任务期限、地点与奖励", "7ypvuc", 2, "支线名称", 4, 3, null,
                fields = listOf("任务类型" to 4, "任务NPC" to 5, "凯篇" to 6, "迪篇" to 7,
                    "赛篇" to 8, "蕾篇" to 9, "报酬奖励" to 10, "触发时期与地点" to 11,
                    "达成条件/期限" to 12, "提示与备注" to 13)),
        )

        private data class WikiAdapter(
            val id: String,
            val title: String,
            val description: String,
            val sheetId: String,
            val headerRow: Int,
            val expectedTitleHeader: String,
            val firstDataRow: Int,
            val titleColumn: Int,
            val imageColumn: Int?,
            val fields: List<Pair<String, Int>>,
            val subtitleColumns: List<Int> = emptyList(),
        ) {
            fun toEntry(row: SheetRow): WikiEntry? {
                val title = row.cells.firstOrNull { it.column == titleColumn }?.value.orEmpty().trim()
                if (title.isBlank() || title == "--" || title == "×") return null
                val cells = row.cells.associateBy(SheetCell::column)
                val subtitle = subtitleColumns.mapNotNull { cells[it]?.wikiText()?.takeIf(String::isNotBlank) }
                    .distinct().joinToString(" · ")
                val fields = fields.mapNotNull { (label, column) ->
                    val cell = cells[column] ?: return@mapNotNull null
                    val value = cell.wikiText().takeUnless { it == "--" || it == "×" }.orEmpty()
                    if (value.isEmpty() && cell.imageAsset == null) null else WikiField(label, value, cell.imageAsset)
                }
                return WikiEntry(
                    id = "$id:${row.rowNumber}",
                    title = title,
                    subtitle = subtitle,
                    imageAsset = imageColumn?.let(cells::get)?.imageAsset,
                    fields = fields,
                    sourceSheetName = row.sheetName,
                    sourceRowNumber = row.rowNumber,
                )
            }

            private fun SheetCell.wikiText(): String = value.trim().takeUnless {
                it.isBlank() || it == "[圖片]" || it == "[图片]"
            }.orEmpty()
        }

        private fun readBytes(source: SheetPackageSource, path: String): ByteArray = try {
            source.open(path).use(InputStream::readBytes)
        } catch (error: Exception) {
            throw SheetPackageException("无法读取本地资料文件：$path", error)
        }

        private fun parseObject(bytes: ByteArray, path: String): JsonObject = try {
            json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        } catch (error: Exception) {
            throw SheetPackageException("本地资料文件格式不正确：$path", error)
        }

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

        private fun JsonObject.requiredString(field: String, path: String): String =
            get(field).scalarString() ?: throw SheetPackageException("$path.$field 缺失或类型不正确")

        private fun JsonObject.requiredInt(field: String, path: String): Int =
            get(field)?.jsonPrimitive?.intOrNull
                ?: throw SheetPackageException("$path.$field 缺失或类型不正确")

        private fun JsonObject.requiredObject(field: String, path: String): JsonObject =
            try {
                getValue(field).jsonObject
            } catch (error: Exception) {
                throw SheetPackageException("$path.$field 缺失或类型不正确", error)
            }

        private fun JsonObject.requiredArray(field: String, path: String): JsonArray =
            try {
                getValue(field).jsonArray
            } catch (error: Exception) {
                throw SheetPackageException("$path.$field 缺失或类型不正确", error)
            }

        private fun JsonElement.objectAt(path: String): JsonObject = try {
            jsonObject
        } catch (error: Exception) {
            throw SheetPackageException("$path 必须是对象", error)
        }

        private fun JsonElement?.scalarString(): String? = when (this) {
            null, JsonNull -> null
            is JsonPrimitive -> content
            else -> null
        }

        private fun JsonElement?.asDisplayText(): String = when (this) {
            null, JsonNull -> ""
            is JsonPrimitive -> if (isString) content else content
            is JsonArray -> joinToString(" ") { it.asDisplayText() }.trim()
            is JsonObject -> this["text"].asDisplayText().ifBlank {
                values.joinToString(" ") { it.asDisplayText() }.trim()
            }
        }

        private fun JsonElement?.asRichText(): String = when (this) {
            null, JsonNull -> ""
            is JsonArray -> joinToString("") { run ->
                run.jsonObject["text"].asDisplayText()
            }
            else -> asDisplayText()
        }
    }
}
