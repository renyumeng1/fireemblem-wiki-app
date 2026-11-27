package com.fireemblem.companion.content

enum class CompanionGame(
    val id: String,
    val title: String,
    val shortTitle: String,
    val tagline: String,
) {
    FORTUNES_WEAVE(
        id = "fortunes-weave",
        title = "火焰纹章：万缕千丝",
        shortTitle = "万缕千丝",
        tagline = "四线攻略、世界地图与凹点养成",
    ),
    ENGAGE(
        id = "engage",
        title = "火焰纹章：Engage",
        shortTitle = "Engage",
        tagline = "纹章士、牵绊戒指、职业与关卡资料",
    ),
    THREE_HOUSES(
        id = "three-houses",
        title = "火焰纹章：风花雪月",
        shortTitle = "风花雪月",
        tagline = "三学级四线招募、职业成长与修道院",
    ),
    ;

    companion object {
        fun fromId(id: String?): CompanionGame = entries.firstOrNull { it.id == id } ?: FORTUNES_WEAVE
    }
}
