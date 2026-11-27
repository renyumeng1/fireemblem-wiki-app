package com.fireemblem.companion.maps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

object MapImageExporter {
    fun render(
        terrain: Bitmap,
        mapWidth: Double,
        mapHeight: Double,
        points: List<MapPoint>,
        graph: MapGraph,
        route: MapRoute?,
        visitedPointIds: Set<String>,
    ): Bitmap {
        require(mapWidth > 0 && mapHeight > 0)
        val output = Bitmap.createBitmap(terrain.width, terrain.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawBitmap(terrain, null, Rect(0, 0, output.width, output.height), Paint(Paint.FILTER_BITMAP_FLAG))
        val xScale = output.width / mapWidth.toFloat()
        val yScale = output.height / mapHeight.toFloat()
        drawRoads(canvas, graph, mapWidth, mapHeight, output.width, output.height)

        route?.nodes?.zipWithNext()?.forEach { (from, to) ->
            val start = graph.nodes[from]
            val end = graph.nodes[to]
            canvas.drawLine(start.x.toFloat() * xScale, start.y.toFloat() * yScale,
                end.x.toFloat() * xScale, end.y.toFloat() * yScale, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = ROUTE_HALO_COLOR
                    strokeWidth = (ROUTE_WIDTH + ROUTE_HALO_WIDTH) * minOf(xScale, yScale)
                    strokeCap = Paint.Cap.ROUND
                })
            canvas.drawLine(start.x.toFloat() * xScale, start.y.toFloat() * yScale,
                end.x.toFloat() * xScale, end.y.toFloat() * yScale, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = ROUTE_COLOR
                    strokeWidth = ROUTE_WIDTH * minOf(xScale, yScale)
                    strokeCap = Paint.Cap.ROUND
                })
        }
        points.forEach { point ->
            val x = point.position.x.toFloat() * xScale
            val y = point.position.y.toFloat() * yScale
            canvas.drawCircle(x, y, POINT_RADIUS + POINT_STROKE, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
            canvas.drawCircle(x, y, POINT_RADIUS, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (point.id in visitedPointIds) VISITED_COLOR else DEFAULT_POINT_COLOR
            })
        }
        return output
    }

    fun renderRoadLayer(
        graph: MapGraph,
        mapWidth: Double,
        mapHeight: Double,
        pixelWidth: Int,
        pixelHeight: Int,
    ): Bitmap {
        require(mapWidth > 0 && mapHeight > 0 && pixelWidth > 0 && pixelHeight > 0)
        return Bitmap.createBitmap(pixelWidth, pixelHeight, Bitmap.Config.ARGB_8888).also { bitmap ->
            drawRoads(Canvas(bitmap), graph, mapWidth, mapHeight, pixelWidth, pixelHeight)
        }
    }

    private fun drawRoads(canvas: Canvas, graph: MapGraph, mapWidth: Double, mapHeight: Double,
        pixelWidth: Int, pixelHeight: Int) {
        val xScale = pixelWidth / mapWidth.toFloat()
        val yScale = pixelHeight / mapHeight.toFloat()
        val scale = minOf(xScale, yScale)
        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ROAD_HALO_COLOR
            strokeWidth = (ROAD_WIDTH + ROAD_HALO_WIDTH) * scale
            strokeCap = Paint.Cap.ROUND
        }
        val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ROAD_COLOR
            strokeWidth = ROAD_WIDTH * scale
            strokeCap = Paint.Cap.ROUND
        }
        graph.roads.forEach { road ->
            val start = graph.nodes.getOrNull(road.from) ?: return@forEach
            val end = graph.nodes.getOrNull(road.to) ?: return@forEach
            val x1 = start.x.toFloat() * xScale
            val y1 = start.y.toFloat() * yScale
            val x2 = end.x.toFloat() * xScale
            val y2 = end.y.toFloat() * yScale
            canvas.drawLine(x1, y1, x2, y2, haloPaint)
            canvas.drawLine(x1, y1, x2, y2, roadPaint)
        }
    }

    private const val ROUTE_WIDTH = 5f
    private const val ROUTE_HALO_WIDTH = 6f
    private const val ROAD_WIDTH = 4f
    private const val ROAD_HALO_WIDTH = 6f
    private const val POINT_RADIUS = 7f
    private const val POINT_STROKE = 2f
    private val ROUTE_COLOR = android.graphics.Color.rgb(179, 78, 53)
    private val ROUTE_HALO_COLOR = android.graphics.Color.rgb(255, 255, 242)
    private val ROAD_COLOR = android.graphics.Color.argb(205, 49, 64, 85)
    private val ROAD_HALO_COLOR = android.graphics.Color.argb(225, 255, 254, 248)
    private val DEFAULT_POINT_COLOR = android.graphics.Color.rgb(144, 70, 50)
    private val VISITED_COLOR = android.graphics.Color.rgb(56, 120, 88)
}
