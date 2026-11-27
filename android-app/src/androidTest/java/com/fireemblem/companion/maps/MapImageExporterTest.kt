package com.fireemblem.companion.maps

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapImageExporterTest {
    @Test
    fun drawsTheCompleteRoadNetworkWithoutASelectedRoute() {
        val terrain = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val chapter = MapChapter("kai", "测试篇章", 0, 1.0, 1.0, 1.0)
        val graph = MapGraph(chapter,
            listOf(MapCoordinate(10.0, 10.0), MapCoordinate(90.0, 90.0),
                MapCoordinate(10.0, 70.0), MapCoordinate(90.0, 70.0)),
            listOf(MapRoad(0, 1, 113.0, 0, false), MapRoad(2, 3, 80.0, 0, false)))

        val result = MapImageExporter.render(terrain, 100.0, 100.0, emptyList(), graph, null, emptySet())

        assertNotEquals(Color.BLACK, result.getPixel(50, 50))
        assertNotEquals(Color.BLACK, result.getPixel(50, 70))
        assertEquals(Color.BLACK, result.getPixel(50, 20))
        result.recycle()
        terrain.recycle()
    }

    @Test
    fun rendersRouteAndVisitedLocationsOverTerrain() {
        val terrain = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val chapter = MapChapter("kai", "测试篇章", 0, 1.0, 1.0, 1.0)
        val graph = MapGraph(chapter,
            listOf(MapCoordinate(10.0, 10.0), MapCoordinate(90.0, 90.0),
                MapCoordinate(10.0, 70.0), MapCoordinate(90.0, 70.0)),
            listOf(MapRoad(0, 1, 113.0, 0, false), MapRoad(2, 3, 80.0, 0, false)))
        val point = MapPoint("visited", "kai", "已探索", "town", "城镇", "", "", MapCoordinate(10.0, 10.0), 0, "")

        val result = MapImageExporter.render(
            terrain, 100.0, 100.0, listOf(point), graph,
            MapRoute(listOf(2, 3), 80.0, 80.0, 2, 24.0), setOf("visited"),
        )

        assertEquals(100, result.width)
        assertEquals(100, result.height)
        assertTrue(result.getPixel(50, 50) != Color.BLACK)
        assertEquals(Color.rgb(179, 78, 53), result.getPixel(50, 70))
        assertEquals(Color.rgb(56, 120, 88), result.getPixel(10, 10))
        result.recycle()
        terrain.recycle()
    }
}
