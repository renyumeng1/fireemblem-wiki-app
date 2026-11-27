package com.fireemblem.companion.agent

import com.fireemblem.companion.engine.EngageFixedGrowthReport
import com.fireemblem.companion.engine.EngageStageSpec
import com.fireemblem.companion.engine.ParetoGrowthPlanReport
import com.fireemblem.companion.engine.SeedWorkbenchRecord
import com.fireemblem.companion.engine.ThreeHousesCertDipSpec
import com.fireemblem.companion.engine.ThreeHousesGender
import com.fireemblem.companion.engine.ThreeHousesPlanReport
import com.fireemblem.companion.engine.ThreeHousesStageSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

data class AgentWikiCitation(
    val gameId: String,
    val gameTitle: String,
    val categoryId: String,
    val entryId: String,
    val title: String,
    val subtitle: String = "",
)

data class AgentPlanProposal(
    val report: ParetoGrowthPlanReport,
    val recordTemplate: SeedWorkbenchRecord,
    val defaultTierIndex: Int = report.paretoTiers.lastIndex.coerceAtLeast(0),
)

data class AgentThreeHousesProposal(
    val report: ThreeHousesPlanReport,
    val customStages: List<ThreeHousesStageSpec> = emptyList(),
    val extraCertDips: List<ThreeHousesCertDipSpec> = emptyList(),
    val genderOverride: ThreeHousesGender? = null,
    val includeDlcClasses: Boolean = true,
)

data class AgentEngageProposal(
    val report: EngageFixedGrowthReport,
    val requestedStages: List<EngageStageSpec> = emptyList(),
    val requestedTotalLevels: Int = report.totalLevelUps,
    val starsphere: Boolean = report.usedStarsphere,
)

data class AgentToolTrace(
    val toolName: String,
    val summary: String,
    val isError: Boolean = false,
)

data class AgentMemoryEntry(
    val id: String,
    val category: String, // preference | build | roster | note
    val content: String,
    val updatedAtMillis: Long,
)

data class AgentToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

data class AgentToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject,
)

data class AgentToolExecutionOutcome(
    val outputJson: JsonObject,
    val summary: String,
    val citations: List<AgentWikiCitation> = emptyList(),
    val planProposal: AgentPlanProposal? = null,
    val threeHousesProposal: AgentThreeHousesProposal? = null,
    val engageProposal: AgentEngageProposal? = null,
    val isError: Boolean = false,
)

sealed interface AgentTurnMessage {
    data class User(val text: String) : AgentTurnMessage
    data class Assistant(
        val text: String,
        val toolCalls: List<AgentToolCall> = emptyList(),
    ) : AgentTurnMessage
    data class ToolResult(
        val toolCallId: String,
        val toolName: String,
        val contentJson: String,
        val isError: Boolean = false,
    ) : AgentTurnMessage
}

data class AgentLlmResponse(
    val text: String,
    val toolCalls: List<AgentToolCall> = emptyList(),
    val stopReason: String = "end_turn",
)

interface AgentLlmClient {
    suspend fun complete(
        systemPrompt: String,
        messages: List<AgentTurnMessage>,
        tools: List<AgentToolDefinition>,
        onTextDelta: (String) -> Unit = {},
    ): AgentLlmResponse
}

interface AgentToolExecutor {
    suspend fun execute(call: AgentToolCall): AgentToolExecutionOutcome
}

data class AgentTurnResult(
    val assistantText: String,
    val toolTraces: List<AgentToolTrace>,
    val citations: List<AgentWikiCitation>,
    val planProposals: List<AgentPlanProposal>,
    val threeHousesProposals: List<AgentThreeHousesProposal> = emptyList(),
    val engageProposals: List<AgentEngageProposal> = emptyList(),
    val updatedHistory: List<AgentTurnMessage>,
    val stoppedByBudget: Boolean = false,
)

object CompanionAgentTools {
    const val TOOL_SEARCH_WIKI = "search_wiki"
    const val TOOL_GET_WIKI_ENTRY = "get_wiki_entry"
    const val TOOL_LIST_SAVED_CHARACTERS = "list_saved_characters"
    const val TOOL_SOLVE_PARETO_GROWTH_PLAN = "solve_pareto_growth_plan"
    const val TOOL_COMPARE_CLASSES_AT_LEVEL = "compare_classes_at_level"
    const val TOOL_GET_ROUTE_MERGE_SUMMARY = "get_route_merge_summary"
    const val TOOL_PLAN_THREE_HOUSES_GROWTH = "plan_three_houses_growth"
    const val TOOL_PLAN_ENGAGE_FIXED_GROWTH = "plan_engage_fixed_growth"
    const val TOOL_READ_MEMORIES = "read_memories"
    const val TOOL_SAVE_MEMORY = "save_memory"
    const val TOOL_FORGET_MEMORY = "forget_memory"

    fun buildSystemPrompt(
        activeGameId: String,
        activeGameTitle: String,
        memories: List<AgentMemoryEntry> = emptyList(),
        compactedSummary: String? = null,
    ): String = buildString {
        appendLine("你是「火焰纹章随行资料库」内置的「战策参谋」，精通《火焰纹章：万缕千丝》（fortunes-weave）、《风花雪月》（three-houses）与《Engage》（engage）三部作品。")
        appendLine("当前用户所在游戏视图：$activeGameTitle（game_id=\"$activeGameId\"）。")
        appendLine()
        appendLine("严格工作规范：")
        appendLine("1. **以本地离线 Wiki 数据库为唯一事实源**：凡涉及角色成长率、职业加成、招募条件、技能效果、支援关系、礼物/料理偏好、章节流程，必须先调用 `$TOOL_SEARCH_WIKI` 与 `$TOOL_GET_WIKI_ENTRY` 检索确认，绝不凭记忆编造数值。")
        appendLine("2. **三作专属确定性计算引擎（绝不手算数值）**：")
        appendLine("   - **《万缕千丝》乱数与转职分档规划**：调用 `$TOOL_LIST_SAVED_CHARACTERS`、`$TOOL_SOLVE_PARETO_GROWTH_PLAN`、`$TOOL_COMPARE_CLASSES_AT_LEVEL`。必须**严格校验转职可行性**：不要在角色前期转职系统尚未开放（如仅解锁平民/贵族且等级 <10）时胡乱建议当下立刻转职！工具默认在仅解锁基础职业时限制未解锁转职从 Lv.10 起预演（并自动过滤非当前路线的第1部限定职业），玩家若告知具体开放等级请传入 `earliest_promotion_level`；给出未解锁转职节点时必须标明「需考试解锁」及对应条件。")
        appendLine("   - **《风花雪月》期望成长与转职路线规划**：《风花雪月》升级乱数按单场战斗/地图锁定，不支持跨章节反推终生角色种子；涉及角色期望面板、转职保底提点（如 Lv.10 重甲骑士防12 / Lv.20 要塞防17）、学生 2 点保底重抽期望、考试路线规划时，必须调用 `$TOOL_PLAN_THREE_HOUSES_GROWTH`。")
        appendLine("   - **《Engage》固定成长确定性逐级推算**：《Engage》固定成长（超难一周目 / 积分制）为 100% 确定性满 100 进 1 累积制（初始积分 = 个人成长率 % 100，大师证/转职证无缝继承积分，贾恩个人特技双倍职业成长，星玉加护全成长 +15%）；涉及 Engage 角色逐级固定成长面板与转职方案时，必须调用 `$TOOL_PLAN_ENGAGE_FIXED_GROWTH`。")
        appendLine("3. **《万缕千丝》考试跳过规则**：`$TOOL_SOLVE_PARETO_GROWTH_PLAN` 原生支持 `min_total_gain`（跳过总成长低于阈值的加点）、`min_weighted_gain`、`required_stats`、`optimize_skips` 与 `max_skips`。当用户要求跳过劣质加点时直接传参求解即可。")
        appendLine("4. **跨会话记忆**：当用户明确表达长期养成偏好、路线目标或要求记住某事时，主动调用 `$TOOL_SAVE_MEMORY` 存入参谋记忆本；需要回忆历史偏好时参考下方记忆或调用 `$TOOL_READ_MEMORIES`。")
        appendLine("5. **输出格式与大白话**：使用简体中文与清晰 Markdown（标题、加粗、列表、表格均可正常渲染）；不要使用「帕累托」等晦涩术语，一律称为**「转职分档方案」**；给出方案时清晰列出转职节点、是否需考试解锁、跳过次数或逐级亮点，并提示用户可展开下方卡片的逐级预览表（《万缕千丝》可点击「一键导入成长工作台」）。")
        if (memories.isNotEmpty()) {
            appendLine()
            appendLine("## 玩家参谋记忆本（长期偏好与目标）")
            memories.take(16).forEach { m ->
                appendLine("- [${m.category}] ${m.content} (id=${m.id})")
            }
        }
        if (!compactedSummary.isNullOrBlank()) {
            appendLine()
            appendLine("## 本会话早前上下文摘要")
            appendLine(compactedSummary.trim())
        }
    }.trimEnd()

    val ALL_TOOLS: List<AgentToolDefinition> = listOf(
        AgentToolDefinition(
            name = TOOL_SEARCH_WIKI,
            description = "在离线三作 Wiki 数据库（万缕千丝 fortunes-weave / 风花雪月 three-houses / Engage engage）中检索角色、职业、技能、装备、招募、攻略与支援资料。",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "中文或英文搜索关键词，例如 '圣天马骑士'、'凯伊 招募'、'琉尔'。")
                    }
                    putJsonObject("game") {
                        put("type", "string")
                        put("description", "游戏筛选：'fortunes-weave'、'three-houses'、'engage' 或 'all'（默认 'all'）。")
                    }
                    putJsonObject("category") {
                        put("type", "string")
                        put("description", "可选分类 ID 过滤，例如 'characters'、'professions'、'classes'、'skills'。")
                    }
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", "返回条数上限（1..12，默认 8）。")
                    }
                }
                put("required", buildJsonArray { add(JsonPrimitive("query")) })
            },
        ),
        AgentToolDefinition(
            name = TOOL_GET_WIKI_ENTRY,
            description = "读取指定游戏某条 Wiki 词条的完整结构化字段、属性成长、关联关系与四线加入信息。",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("game") {
                        put("type", "string")
                        put("description", "游戏 ID：'fortunes-weave'、'three-houses' 或 'engage'。")
                    }
                    putJsonObject("entry_id") {
                        put("type", "string")
                        put("description", "由 search_wiki 返回的精确 entry_id。")
                    }
                }
                put("required", buildJsonArray {
                    add(JsonPrimitive("game"))
                    add(JsonPrimitive("entry_id"))
                })
            },
        ),
        AgentToolDefinition(
            name = TOOL_LIST_SAVED_CHARACTERS,
            description = "读取当前个人存档中已保存的《万缕千丝》种子工作台方案、升级记录种子检查点与多线融合角色概况（只读）。",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("character_query") {
                        put("type", "string")
                        put("description", "可选角色名或角色 ID 过滤。")
                    }
                }
            },
        ),
        AgentToolDefinition(
            name = TOOL_SOLVE_PARETO_GROWTH_PLAN,
            description = "基于《万缕千丝》精确乱数表与 2D 动态规划，同时优化转职次数与考试跳过（支持跳过总成长低于阈值/加权分低于阈值/缺核心属性的加点，也支持按权重自动选最优跳过步；严格校验路线限定职业与最早可转职等级），输出多档转职分档方案并附带可展开逐级预览与导入工作台的卡片。",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("character") {
                        put("type", "string")
                        put("description", "角色名称或 Buddy 角色 ID（例如 '皮特鲁'、'凯伊' 或 'c044'）。")
                    }
                    putJsonObject("seed") {
                        put("type", "integer")
                        put("description", "当前乱数种子（0..4294967295）；省略时自动从本地存档检查点读取。")
                    }
                    putJsonObject("line") {
                        put("type", "string")
                        put("description", "路线：'kai'、'dit'、'seo'、'leda'（默认读取存档或 'kai'）。")
                    }
                    putJsonObject("current_level") {
                        put("type", "integer")
                        put("description", "当前等级（1..98）。")
                    }
                    putJsonObject("target_level") {
                        put("type", "integer")
                        put("description", "目标等级（高于 current_level，最大 99）。")
                    }
                    putJsonObject("initial_profession") {
                        put("type", "string")
                        put("description", "当前职业名称（如 '平民'、'士兵'、'勇士'）。")
                    }
                    putJsonObject("unlocked_professions") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put("description", "已解锁可转职业列表；为空时自动从存档读取，并结合最早可转职等级预演合法职业。")
                    }
                    putJsonObject("include_locked_preview") {
                        put("type", "boolean")
                        put("description", "是否预演尚未解锁但当前路线可达的合法职业，默认在仅 1 个已解锁职业时开启；若只想严格使用已解锁职业请设为 false。")
                    }
                    putJsonObject("earliest_promotion_level") {
                        put("type", "integer")
                        put("description", "未解锁职业的最早允许转职等级（1..99）。省略时若存档仅解锁平民/贵族且等级 <10，默认从 Lv.10 起预演，避免前期转职未开放时建议 Lv.5 立刻转职。")
                    }
                    putJsonObject("enforce_route_availability") {
                        put("type", "boolean")
                        put("description", "是否过滤非当前路线第 1 部限定职业（如凯伊篇过滤迪篇锻造师/赛篇铁甲骑兵等），默认 true。")
                    }
                    putJsonObject("weights") {
                        put("type", "object")
                        put("description", "9 项属性权重（0..5，键支持 HP/力量/魔力/技巧/速度/幸运/防守/魔防/魅力 或英文）。")
                    }
                    putJsonObject("max_switch_budget") {
                        put("type", "integer")
                        put("description", "最大转职次数预算（0..10，默认 5）。")
                    }
                    putJsonObject("max_skips") {
                        put("type", "integer")
                        put("description", "最多允许考试跳过的次数（0..45；开启跳过过滤或 optimize_skips 时建议 10..30）。")
                    }
                    putJsonObject("optimize_skips") {
                        put("type", "boolean")
                        put("description", "是否允许引擎按权重自动挑选最优考试跳过位置以获得更高加权成长，默认 false。")
                    }
                    putJsonObject("min_total_gain") {
                        put("type", "integer")
                        put("description", "硬过滤：要求每次实际升级的总成长点数 >= 该值（例如填 3 即跳过成长值 < 3 的加点）。")
                    }
                    putJsonObject("min_weighted_gain") {
                        put("type", "integer")
                        put("description", "硬过滤：要求每次实际升级的加权分 >= 该值。")
                    }
                    putJsonObject("required_stats") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put("description", "硬过滤：要求每次实际升级必须跳点的核心属性名列表（如 ['力量']）。")
                    }
                    putJsonObject("max_exam_probability") {
                        put("type", "integer")
                        put("description", "可选约束：仅允许在考试最低通过率 <= 该百分比（0..100）的乱数步上考试跳过。")
                    }
                    putJsonObject("part3") {
                        put("type", "boolean")
                        put("description", "是否已进入第三部，默认 false。")
                    }
                }
                put("required", buildJsonArray { add(JsonPrimitive("character")) })
            },
        ),
        AgentToolDefinition(
            name = TOOL_COMPARE_CLASSES_AT_LEVEL,
            description = "在指定等级与乱数种子下，对比该角色所有合法职业在这一级升级时的精确属性跳点（适合排查例如「23 级不加力量换什么职业能加」）。",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("character") {
                        put("type", "string")
                        put("description", "角色名或 Buddy 角色 ID。")
                    }
                    putJsonObject("seed") {
                        put("type", "integer")
                        put("description", "该级对应的乱数种子；省略时若本地有检查点将自动推算。")
                    }
                    putJsonObject("level") {
                        put("type", "integer")
                        put("description", "要排查的升级前等级（1..98）。")
                    }
                    putJsonObject("current_profession") {
                        put("type", "string")
                        put("description", "当前所在职业名。")
                    }
                    putJsonObject("line") {
                        put("type", "string")
                        put("description", "路线：'kai'、'dit'、'seo'、'leda'。")
                    }
                    putJsonObject("part3") {
                        put("type", "boolean")
                        put("description", "是否第三部。")
                    }
                    putJsonObject("weights") {
                        put("type", "object")
                        put("description", "可选属性权重用于排序对比结果。")
                    }
                }
                put("required", buildJsonArray {
                    add(JsonPrimitive("character"))
                    add(JsonPrimitive("level"))
                })
            },
        ),
        AgentToolDefinition(
            name = TOOL_GET_ROUTE_MERGE_SUMMARY,
            description = "读取当前个人存档中《万缕千丝》多线融合面板记录，对比全线融合与已保存自选路线的属性差值。",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("character") {
                        put("type", "string")
                        put("description", "可选角色名过滤；省略时返回所有已录角色的融合汇总。")
                    }
                }
            },
        ),
        AgentToolDefinition(
            name = TOOL_PLAN_THREE_HOUSES_GROWTH,
            description = "《风花雪月》期望成长与考试转职规划器：基于离线数据库 40 名角色与全部可用兵种，精确计算 2 点保底重抽期望、各阶段转职保底属性补点（如重甲骑士防 12 / 飞龙骑士力 18）与最终职业能力补正，严格遵守性别/专属/等级门槛，并附带可展开逐级预览卡片。",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("character") {
                        put("type", "string")
                        put("description", "《风花雪月》角色中文名或英文名（如 '英谷莉特'、'艾黛尔贾特'、'贝雷丝'、'菲力克斯'）。")
                    }
                    putJsonObject("start_level") {
                        put("type", "integer")
                        put("description", "起始等级（省略时使用该角色初始加入等级）。")
                    }
                    putJsonObject("target_level") {
                        put("type", "integer")
                        put("description", "目标等级（默认 40，最大 99）。")
                    }
                    putJsonObject("gender") {
                        put("type", "string")
                        put("description", "可选性别覆盖（仅贝雷特/贝雷丝需要区分 'male'/'female'）。")
                    }
                    putJsonObject("weights") {
                        put("type", "object")
                        put("description", "9 项属性偏好权重（HP/力量/魔力/技巧/速度/幸运/防守/魔防/魅力），用于自动推荐最优转职路线。")
                    }
                    putJsonObject("stages") {
                        put("type", "array")
                        put("description", "可选自定义转职阶段列表；省略时引擎按 Lv.5/10/20/30 自动规划最优合法路线。")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("class_name") { put("type", "string") }
                                putJsonObject("from_level") { put("type", "integer") }
                                putJsonObject("to_level") { put("type", "integer") }
                            }
                        }
                    }
                    putJsonObject("cert_dips") {
                        put("type", "array")
                        put("description", "可选仅考证蹭保底的职业节点（例如 [{'at_level': 10, 'class_name': '重甲骑士'}] 蹭防守 12 保底）。")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("at_level") { put("type", "integer") }
                                putJsonObject("class_name") { put("type", "string") }
                            }
                        }
                    }
                    putJsonObject("include_dlc") {
                        put("type", "boolean")
                        put("description", "是否包含煤暗之章 DLC 特殊职业（暗天马/女武神/武僧/骗术师），默认 true。")
                    }
                }
                put("required", buildJsonArray { add(JsonPrimitive("character")) })
            },
        ),
        AgentToolDefinition(
            name = TOOL_PLAN_ENGAGE_FIXED_GROWTH,
            description = "《Engage》固定成长（积分制）确定性逐级推算器：基于离线数据库 42 名角色与全部兵种，精确按满 100 进 1 积分公式推算每一级整数面板与剩余积分，支持大师证/转职证换职基础补正与积分无缝继承、贾恩双倍职业成长、琪姬星玉加护 +15%，并附带可展开逐级推算卡片。",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("character") {
                        put("type", "string")
                        put("description", "《Engage》角色名称（如 '琉尔'、'克萝艾'、'安娜'、'卡盖茨'、'贾恩'、'帕涅托涅'）。")
                    }
                    putJsonObject("total_level_ups") {
                        put("type", "integer")
                        put("description", "自动规划时要升级的总次数（1..60，默认 25）；若已提供 stages 则以 stages 为准。")
                    }
                    putJsonObject("starsphere") {
                        put("type", "boolean")
                        put("description", "是否装备琪姬 DLC 特技「星玉加护」（全 9 项成长率 +15%），默认 false。")
                    }
                    putJsonObject("weights") {
                        put("type", "object")
                        put("description", "9 项属性偏好权重（HP/力量/魔力/技巧/速度/幸运/防守/魔防/体格），用于自动推荐最佳高级职业。")
                    }
                    putJsonObject("stages") {
                        put("type", "array")
                        put("description", "可选自定义多阶段转职序列，每段指定 class_name、levels（升级次数）与可选 starsphere。")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("class_name") { put("type", "string") }
                                putJsonObject("levels") { put("type", "integer") }
                                putJsonObject("starsphere") { put("type", "boolean") }
                            }
                        }
                    }
                }
                put("required", buildJsonArray { add(JsonPrimitive("character")) })
            },
        ),
        AgentToolDefinition(
            name = TOOL_READ_MEMORIES,
            description = "读取参谋记忆本中保存的玩家长期偏好、培养计划与常用规则。",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {}
            },
        ),
        AgentToolDefinition(
            name = TOOL_SAVE_MEMORY,
            description = "向参谋记忆本写入一条跨会话持久记忆（例如玩家常用权重偏好、主推路线、角色培养方向或固定跳点习惯）。",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("category") {
                        put("type", "string")
                        put("description", "分类：'preference'（偏好）、'build'（角色养成）、'roster'（路线队伍）、'note'（备忘）。")
                    }
                    putJsonObject("content") {
                        put("type", "string")
                        put("description", "精炼的一句话记忆内容（不超过 120 字）。")
                    }
                }
                put("required", buildJsonArray { add(JsonPrimitive("content")) })
            },
        ),
        AgentToolDefinition(
            name = TOOL_FORGET_MEMORY,
            description = "根据记忆 ID 删除一条已过时或不再需要的参谋记忆。",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("memory_id") {
                        put("type", "string")
                        put("description", "要删除的记忆 ID。")
                    }
                }
                put("required", buildJsonArray { add(JsonPrimitive("memory_id")) })
            },
        ),
    )
}

class CompanionAgentOrchestrator(
    private val client: AgentLlmClient,
    private val executor: AgentToolExecutor,
    private val maxToolRounds: Int = DEFAULT_MAX_TOOL_ROUNDS,
    private val maxToolCalls: Int = DEFAULT_MAX_TOOL_CALLS,
) {
    suspend fun runTurn(
        systemPrompt: String,
        priorHistory: List<AgentTurnMessage>,
        userPrompt: String,
        tools: List<AgentToolDefinition> = CompanionAgentTools.ALL_TOOLS,
        onTextDelta: (String) -> Unit = {},
        onToolStarted: (String) -> Unit = {},
        onToolFinished: (AgentToolTrace) -> Unit = {},
    ): AgentTurnResult {
        val workingHistory = priorHistory.toMutableList()
        workingHistory += AgentTurnMessage.User(userPrompt)

        val traces = mutableListOf<AgentToolTrace>()
        val citationsByKey = linkedMapOf<String, AgentWikiCitation>()
        val proposals = mutableListOf<AgentPlanProposal>()
        val threeHousesProposals = mutableListOf<AgentThreeHousesProposal>()
        val engageProposals = mutableListOf<AgentEngageProposal>()
        var totalToolCalls = 0
        var finalAssistantText = ""
        var stoppedByBudget = false

        for (round in 0 until maxToolRounds) {
            val roundTextBuffer = StringBuilder()
            val response = client.complete(
                systemPrompt = systemPrompt,
                messages = workingHistory.toList(),
                tools = tools,
                onTextDelta = { chunk ->
                    roundTextBuffer.append(chunk)
                    onTextDelta(chunk)
                },
            )
            val resolvedText = response.text.ifBlank { roundTextBuffer.toString() }
            if (response.stopReason == "refusal") {
                finalAssistantText = resolvedText.ifBlank { "模型拒绝了本次请求，请换一种表述重试。" }
                workingHistory += AgentTurnMessage.Assistant(finalAssistantText)
                break
            }
            if (response.toolCalls.isEmpty()) {
                finalAssistantText = resolvedText
                workingHistory += AgentTurnMessage.Assistant(finalAssistantText)
                break
            }

            workingHistory += AgentTurnMessage.Assistant(resolvedText, response.toolCalls)

            val allowedCalls = response.toolCalls.take((maxToolCalls - totalToolCalls).coerceAtLeast(0))
            if (allowedCalls.isEmpty()) {
                stoppedByBudget = true
                finalAssistantText = resolvedText.ifBlank { "工具调用次数达到本轮上限，已汇总当前检索到的信息。" }
                break
            }

            for (call in allowedCalls) {
                totalToolCalls++
                onToolStarted(toolDisplayLabel(call.name))
                val outcome = runCatching {
                    executor.execute(call)
                }.getOrElse { err ->
                    AgentToolExecutionOutcome(
                        outputJson = buildJsonObject {
                            put("error", err.message ?: "工具执行失败")
                        },
                        summary = "${toolDisplayLabel(call.name)}失败：${err.message ?: "未知错误"}",
                        isError = true,
                    )
                }
                val trace = AgentToolTrace(call.name, outcome.summary, outcome.isError)
                traces += trace
                onToolFinished(trace)
                outcome.citations.forEach { c ->
                    citationsByKey["${c.gameId}|${c.entryId}"] = c
                }
                outcome.planProposal?.let { proposals += it }
                outcome.threeHousesProposal?.let { threeHousesProposals += it }
                outcome.engageProposal?.let { engageProposals += it }
                workingHistory += AgentTurnMessage.ToolResult(
                    toolCallId = call.id,
                    toolName = call.name,
                    contentJson = outcome.outputJson.toString(),
                    isError = outcome.isError,
                )
            }

            if (round == maxToolRounds - 1) {
                stoppedByBudget = true
                val synthBuf = StringBuilder()
                val synth = client.complete(
                    systemPrompt = systemPrompt,
                    messages = workingHistory.toList(),
                    tools = emptyList(),
                    onTextDelta = { chunk ->
                        synthBuf.append(chunk)
                        onTextDelta(chunk)
                    },
                )
                finalAssistantText = synth.text.ifBlank { synthBuf.toString() }
                workingHistory += AgentTurnMessage.Assistant(finalAssistantText)
            }
        }

        return AgentTurnResult(
            assistantText = finalAssistantText,
            toolTraces = traces,
            citations = citationsByKey.values.toList(),
            planProposals = proposals,
            threeHousesProposals = threeHousesProposals,
            engageProposals = engageProposals,
            updatedHistory = workingHistory,
            stoppedByBudget = stoppedByBudget,
        )
    }

    companion object {
        const val DEFAULT_MAX_TOOL_ROUNDS = 6
        const val DEFAULT_MAX_TOOL_CALLS = 12

        fun toolDisplayLabel(name: String): String = when (name) {
            CompanionAgentTools.TOOL_SEARCH_WIKI -> "检索三作离线图鉴"
            CompanionAgentTools.TOOL_GET_WIKI_ENTRY -> "查阅词条详情"
            CompanionAgentTools.TOOL_LIST_SAVED_CHARACTERS -> "读取本地养成存档"
            CompanionAgentTools.TOOL_SOLVE_PARETO_GROWTH_PLAN -> "计算转职分档与跳过方案"
            CompanionAgentTools.TOOL_COMPARE_CLASSES_AT_LEVEL -> "对比同级各职业跳点"
            CompanionAgentTools.TOOL_GET_ROUTE_MERGE_SUMMARY -> "读取多线融合总表"
            CompanionAgentTools.TOOL_PLAN_THREE_HOUSES_GROWTH -> "规划《风花雪月》期望成长与转职"
            CompanionAgentTools.TOOL_PLAN_ENGAGE_FIXED_GROWTH -> "推算《Engage》固定成长逐级面板"
            CompanionAgentTools.TOOL_READ_MEMORIES -> "读取参谋记忆本"
            CompanionAgentTools.TOOL_SAVE_MEMORY -> "记录参谋记忆"
            CompanionAgentTools.TOOL_FORGET_MEMORY -> "清理参谋记忆"
            else -> name
        }
    }
}
