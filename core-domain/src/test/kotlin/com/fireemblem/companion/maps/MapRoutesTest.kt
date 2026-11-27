package com.fireemblem.companion.maps

import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MapRoutesTest {
    private fun graph(chapterId: String = "kai", roads: List<MapRoad> = emptyList()) = MapGraph(
        chapter = MapChapter(chapterId, "测试篇章", 0, cellPixels = 10.0, cellsPerTurn = 2.0, hoursPerTurn = 12.0),
        nodes = List(4) { MapCoordinate(it.toDouble(), 0.0) },
        roads = roads,
    )

    @Test
    fun selectsShortestEligiblePathAndCalculatesTravelEstimate() {
        val map = graph(roads = listOf(
            MapRoad(0, 1, 15.0, requiredFame = 0, directed = false),
            MapRoad(1, 3, 15.0, requiredFame = 0, directed = false),
            MapRoad(0, 2, 12.0, requiredFame = 5, directed = false),
            MapRoad(2, 3, 12.0, requiredFame = 5, directed = false),
        ))

        val restricted = requireNotNull(MapRoutes.shortest(map, 0, 3, fame = 0))
        val unlocked = requireNotNull(MapRoutes.shortest(map, 0, 3, fame = 5))

        assertEquals(listOf(0, 1, 3), restricted.nodes)
        assertEquals(30.0, restricted.pixels, 0.0)
        assertEquals(3.0, restricted.cells, 0.0)
        assertEquals(2, restricted.turns)
        assertEquals(24.0, restricted.hours, 0.0)
        assertEquals(listOf(0, 2, 3), unlocked.nodes)
        assertEquals(24.0, unlocked.pixels, 0.0)
    }

    @Test
    fun respectsDirectionAndDiscoveryAlternatives() {
        val map = graph(roads = listOf(
            MapRoad(0, 1, 1.0, requiredFame = 0, directed = true),
            MapRoad(1, 3, 1.0, requiredFame = 0, directed = false, discoveryAny = setOf("旧桥", "风车村")),
        ))

        assertNull(MapRoutes.shortest(map, 3, 0, fame = 99))
        assertNull(MapRoutes.shortest(map, 0, 3, fame = 99))
        assertEquals(listOf(0, 1, 3), requireNotNull(
            MapRoutes.shortest(map, 0, 3, fame = 99, discoveries = setOf("风车村")),
        ).nodes)
    }

    @Test
    fun salvationRoadsDoNotApplyStoryFameRestrictions() {
        val map = graph("salvation", listOf(MapRoad(0, 1, 1.0, requiredFame = 99, directed = false)))

        assertEquals(listOf(0, 1), requireNotNull(MapRoutes.shortest(map, 0, 1, fame = 0)).nodes)
    }

    @Test
    fun blockedNodesAndUnreachableEndpointsHaveNoRoute() {
        val map = graph(roads = listOf(MapRoad(0, 1, 0.0, requiredFame = 0, directed = false)))

        assertNull(MapRoutes.shortest(map, 0, 1, fame = 0, blockedNodes = setOf(1)))
        assertNull(MapRoutes.shortest(map, 0, 3, fame = 0))
        assertEquals(listOf(0), requireNotNull(MapRoutes.shortest(map, 0, 0, fame = 0)).nodes)
    }

    @Test
    fun rejectsInvalidInputsAndStopsWhenCancelled() {
        val map = graph(roads = listOf(MapRoad(0, 1, 1.0, requiredFame = 0, directed = false)))

        assertThrows(IllegalArgumentException::class.java) { MapRoutes.shortest(map, 0, 9, fame = 0) }
        assertThrows(IllegalArgumentException::class.java) { MapRoutes.shortest(map, 0, 1, fame = -1) }
        assertThrows(IllegalArgumentException::class.java) {
            MapRoutes.shortest(graph(roads = listOf(MapRoad(0, 1, Double.NaN, requiredFame = 0, directed = false))), 0, 1, 0)
        }
        assertThrows(CancellationException::class.java) {
            MapRoutes.shortest(map, 0, 1, fame = 0, isCancelled = { true })
        }
        assertTrue(requireNotNull(MapRoutes.shortest(map, 0, 1, fame = 0)).pixels > 0)
    }
}
