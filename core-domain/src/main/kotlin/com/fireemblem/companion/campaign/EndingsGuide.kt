package com.fireemblem.companion.campaign

import com.fireemblem.companion.content.WikiEntry

enum class EndingKind(val label: String) {
    SINGLE("单人结局"),
    PAIR("双人结局");

    companion object {
        fun fromLabel(label: String?): EndingKind? = entries.firstOrNull { it.label == label }
    }
}

data class EndingCharacter(
    val name: String,
    val epithet: String?,
)

data class EndingGuideSummary(
    val entryId: String,
    val title: String,
    val kind: EndingKind,
    val characterNames: List<String>,
    val label: String?,
)

data class EndingGuideRecord(
    val entryId: String,
    val title: String,
    val kind: EndingKind,
    val characters: List<EndingCharacter>,
    val label: String?,
    val condition: String?,
    val paragraphs: List<String>,
    val sourceRowNumber: Int,
) {
    val hasStory: Boolean get() = paragraphs.isNotEmpty()
}

object EndingGuideProjector {
    const val CATEGORY_ID = "endings"

    fun projectSummary(entry: WikiEntry): EndingGuideSummary? {
        if (entry.id.substringBefore(':') != CATEGORY_ID) return null
        val parts = entry.subtitle.split(" · ", limit = 2)
        val kind = EndingKind.fromLabel(parts.firstOrNull()) ?: return null
        val names = entry.title.split(" × ").map(String::trim).filter(String::isNotEmpty)
        if (names.size != kind.characterCount) return null
        return EndingGuideSummary(
            entryId = entry.id,
            title = entry.title,
            kind = kind,
            characterNames = names,
            label = parts.getOrNull(1)?.takeIf(String::isNotEmpty),
        )
    }

    fun project(entry: WikiEntry): EndingGuideRecord? {
        val summary = projectSummary(entry) ?: return null
        val fields = entry.fields.associate { it.label to it.value.trim() }
        val kind = EndingKind.fromLabel(fields["结局类型"]) ?: return null
        val names = fields["相关角色"].orEmpty().split('、').map(String::trim).filter(String::isNotEmpty)
        val epithets = fields["角色称号"].orEmpty().split('、').map(String::trim)
        val characters = names.mapIndexed { index, name ->
            EndingCharacter(name, epithets.getOrNull(index)?.takeIf(String::isNotEmpty))
        }
        if (kind != summary.kind || names != summary.characterNames || characters.size != kind.characterCount) return null
        val story = fields["后日谈正文"].orEmpty()
        val paragraphs = story.split(Regex("\\n\\s*\\n"))
            .map(String::trim)
            .filter(String::isNotEmpty)
        return EndingGuideRecord(
            entryId = entry.id,
            title = entry.title,
            kind = kind,
            characters = characters,
            label = fields["结局标签"]?.takeIf(String::isNotEmpty),
            condition = fields["触发条件"]?.takeIf(String::isNotEmpty),
            paragraphs = paragraphs,
            sourceRowNumber = entry.sourceRowNumber,
        )
    }

    private val EndingKind.characterCount: Int
        get() = if (this == EndingKind.SINGLE) 1 else 2
}
