package com.fireemblem.companion.ui

import androidx.compose.ui.graphics.Color
import com.fireemblem.companion.engine.GrowthStat

internal data class GrowthStatTone(val container: Color, val content: Color, val accent: Color)

internal fun growthStatTone(stat: GrowthStat, darkTheme: Boolean): GrowthStatTone = when (stat) {
    GrowthStat.HP -> if (darkTheme) {
        GrowthStatTone(Color(0xFF552D2A), Color(0xFFFFDAD6), Color(0xFFE99784))
    } else {
        GrowthStatTone(Color(0xFFF8E3E0), Color(0xFF712B27), Color(0xFFA94F35))
    }
    GrowthStat.STRENGTH -> if (darkTheme) {
        GrowthStatTone(Color(0xFF4D351F), Color(0xFFFFD8A8), Color(0xFFE0A45E))
    } else {
        GrowthStatTone(Color(0xFFF3E5D5), Color(0xFF704018), Color(0xFFA56B31))
    }
    GrowthStat.MAGIC -> if (darkTheme) {
        GrowthStatTone(Color(0xFF3C3154), Color(0xFFECD9FF), Color(0xFFB69AE8))
    } else {
        GrowthStatTone(Color(0xFFEBE4F8), Color(0xFF533B7A), Color(0xFF7751A2))
    }
    GrowthStat.DEXTERITY -> if (darkTheme) {
        GrowthStatTone(Color(0xFF233D49), Color(0xFFC5EDFA), Color(0xFF80C2D7))
    } else {
        GrowthStatTone(Color(0xFFDCEBEF), Color(0xFF24586B), Color(0xFF4686A1))
    }
    GrowthStat.SPEED -> if (darkTheme) {
        GrowthStatTone(Color(0xFF244136), Color(0xFFC8E9D2), Color(0xFF83C49B))
    } else {
        GrowthStatTone(Color(0xFFDDECE4), Color(0xFF28543F), Color(0xFF3E7957))
    }
    GrowthStat.LUCK -> if (darkTheme) {
        GrowthStatTone(Color(0xFF4D431F), Color(0xFFFBE6A2), Color(0xFFD6BD56))
    } else {
        GrowthStatTone(Color(0xFFF5EECF), Color(0xFF6A5400), Color(0xFFA38520))
    }
    GrowthStat.DEFENSE -> if (darkTheme) {
        GrowthStatTone(Color(0xFF3C4230), Color(0xFFDCE5CA), Color(0xFFAAB887))
    } else {
        GrowthStatTone(Color(0xFFE7E6DD), Color(0xFF4C5141), Color(0xFF68724E))
    }
    GrowthStat.RESISTANCE -> if (darkTheme) {
        GrowthStatTone(Color(0xFF313B52), Color(0xFFD5E2FF), Color(0xFF9CB4E5))
    } else {
        GrowthStatTone(Color(0xFFE1E7F0), Color(0xFF3D4D6A), Color(0xFF546C96))
    }
    GrowthStat.CHARM -> if (darkTheme) {
        GrowthStatTone(Color(0xFF4F2D42), Color(0xFFFFD8E9), Color(0xFFE2A0C0))
    } else {
        GrowthStatTone(Color(0xFFF2E2EA), Color(0xFF6C3654), Color(0xFFA5567F))
    }
}
