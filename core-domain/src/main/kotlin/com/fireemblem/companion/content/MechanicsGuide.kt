package com.fireemblem.companion.content

data class MechanicsGroup(
    val id: String,
    val title: String,
    val category: String,
    val description: String,
    val href: String?,
    val count: Int,
)

data class MechanicsEntry(
    val id: String,
    val title: String,
    val groupId: String,
    val kind: String,
    val paragraphs: List<String>,
    val sources: List<String>,
)

data class GrowthMechanicsBlock(
    val kind: String,
    val text: String,
)

data class GrowthMechanicsSection(
    val id: String,
    val title: String,
    val blocks: List<GrowthMechanicsBlock>,
)

data class MechanicsGuideData(
    val groups: List<MechanicsGroup>,
    val entries: List<MechanicsEntry>,
    val growthSections: List<GrowthMechanicsSection>,
    val archiveSourceUri: String,
    val growthSourceUri: String,
    val sourceRevision: String,
)
