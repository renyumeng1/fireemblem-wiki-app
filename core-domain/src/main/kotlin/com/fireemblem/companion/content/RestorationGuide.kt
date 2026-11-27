package com.fireemblem.companion.content

data class RestorationMaterial(
    val id: String,
    val title: String,
    val itemEntryId: String?,
    val imageAsset: String?,
    val matchStatus: String,
)

data class RestorationRequirement(val materialId: String, val quantity: Int) {
    init { require(quantity in 1..1_000_000) }
}

data class RestorationLocation(
    val id: String,
    val title: String,
    val region: String,
    val sourceRow: Int,
    val evidence: String,
    val requirements: List<RestorationRequirement>,
)

data class RestorationGuideData(
    val materials: List<RestorationMaterial>,
    val locations: List<RestorationLocation>,
    val sourceSheetId: String,
    val sourceSheetName: String,
    val sourceRevision: String,
    val sourceUri: String,
    val accessNote: String,
)

data class RestorationMaterialBalance(val material: RestorationMaterial, val required: Long, val owned: Int) {
    val missing: Long get() = (required - owned).coerceAtLeast(0)
}

object RestorationPlanner {
    fun summarize(
        guide: RestorationGuideData,
        inventory: Map<String, Int>,
        completedLocations: Set<String>,
    ): List<RestorationMaterialBalance> {
        require(inventory.values.all { it in 0..1_000_000 }) { "库存数量应为0至1000000" }
        require(guide.materials.map { it.id }.distinct().size == guide.materials.size)
        require(guide.locations.map { it.id }.distinct().size == guide.locations.size)
        val materialIds = guide.materials.map { it.id }.toSet()
        require(inventory.keys.all { it in materialIds }) { "部分库存材料不在当前资料中，请保留记录并检查资料版本" }
        val locationIds = guide.locations.map { it.id }.toSet()
        require(completedLocations.all { it in locationIds }) { "部分已完成地点不在当前资料中，请保留记录并检查资料版本" }
        val totals = mutableMapOf<String, Long>()
        guide.locations.forEach { location ->
            require(location.requirements.map { it.materialId }.distinct().size == location.requirements.size)
            location.requirements.forEach { requirement ->
                require(requirement.materialId in materialIds) { "复兴材料引用缺失" }
                if (location.id !in completedLocations) {
                    totals[requirement.materialId] = (totals[requirement.materialId] ?: 0) + requirement.quantity
                }
            }
        }
        return guide.materials.map { RestorationMaterialBalance(it, totals[it.id] ?: 0, inventory[it.id] ?: 0) }
    }
}
