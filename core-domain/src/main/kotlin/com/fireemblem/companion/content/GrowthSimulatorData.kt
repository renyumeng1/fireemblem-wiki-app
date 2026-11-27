package com.fireemblem.companion.content

data class GrowthSimCharacter(
    val nativeId: String,
    val code: String,
    val recordId: Int,
    val name: String,
    val faction: String,
    val gender: String,
    val startClass: String,
    val startLevel: Int,
    val base: List<Int>,
    val growth: List<Int>,
    val caps: List<Int>,
    val guaranteePre: Int,
    val guaranteePart3: Int,
)

data class GrowthSimClass(
    val id: String,
    val recordId: Int,
    val name: String,
    val tier: String,
    val base: List<Int>,
    val growthBonus: List<Int>,
    val baseGrowth: List<Int>,
    val modifier: List<Int>,
    val caps: List<Int>,
    val mountType: String,
    val mountMultiplier: Int,
    val allowedRecords: List<Int>,
    val forbiddenPeople: List<String>,
) {
    fun allowedFor(character: GrowthSimCharacter): Boolean {
        if (character.nativeId in forbiddenPeople) return false
        if (allowedRecords.isNotEmpty() && character.recordId !in allowedRecords) return false
        return true
    }
}

data class GrowthSimMount(
    val id: String,
    val type: String,
    val name: String,
    val growth: List<Int>,
    val attrBonus: List<Int>,
    val effect: String,
    val location: String,
)

data class GrowthSimulatorCatalog(
    val characters: List<GrowthSimCharacter>,
    val classes: List<GrowthSimClass>,
    val mounts: List<GrowthSimMount>,
    val sourceUri: String,
    val sourceRevision: String,
)
