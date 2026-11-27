package com.fireemblem.companion.maps

import java.util.PriorityQueue
import kotlin.math.ceil

data class MapCoordinate(val x: Double, val y: Double)
data class MapChapter(val id: String, val title: String, val startNode: Int, val cellPixels: Double, val cellsPerTurn: Double, val hoursPerTurn: Double)
data class MapPoint(val id: String, val chapter: String, val title: String, val type: String, val typeTitle: String,
    val description: String, val quests: String, val position: MapCoordinate, val node: Int?, val nativeName: String)
data class MapRoad(val from: Int, val to: Int, val distance: Double, val requiredFame: Int, val directed: Boolean,
    val discoveryAny: Set<String> = emptySet())
data class MapGraph(val chapter: MapChapter, val nodes: List<MapCoordinate>, val roads: List<MapRoad>)
data class MapRoute(val nodes: List<Int>, val pixels: Double, val cells: Double, val turns: Int, val hours: Double)

object MapRoutes {
    fun shortest(graph: MapGraph, start: Int, end: Int, fame: Int, discoveries: Set<String> = emptySet(),
        blockedNodes: Set<Int> = emptySet(), isCancelled: () -> Boolean = { false },
    ): MapRoute? {
        require(start in graph.nodes.indices && end in graph.nodes.indices && fame >= 0)
        require(graph.chapter.cellPixels > 0 && graph.chapter.cellsPerTurn > 0 && graph.chapter.hoursPerTurn > 0)
        if (start in blockedNodes || end in blockedNodes) return null
        val adjacency = Array(graph.nodes.size) { mutableListOf<Pair<Int, Double>>() }
        for (road in graph.roads) {
            require(road.from in graph.nodes.indices && road.to in graph.nodes.indices && road.distance.isFinite() && road.distance >= 0)
            if (graph.chapter.id != "salvation" && road.requiredFame > fame || road.from in blockedNodes || road.to in blockedNodes) continue
            if (road.discoveryAny.isNotEmpty() && road.discoveryAny.none { it in discoveries }) continue
            adjacency[road.from].add(road.to to road.distance)
            if (!road.directed) adjacency[road.to].add(road.from to road.distance)
        }
        val distances = DoubleArray(graph.nodes.size) { Double.POSITIVE_INFINITY }
        val previous = IntArray(graph.nodes.size) { -1 }
        val queue = PriorityQueue(compareBy<Pair<Int, Double>> { it.second }.thenBy { it.first })
        distances[start] = 0.0
        queue.add(start to 0.0)
        while (queue.isNotEmpty()) {
            if (isCancelled()) throw java.util.concurrent.CancellationException()
            val (node, distance) = queue.remove()
            if (distance != distances[node]) continue
            if (node == end) break
            adjacency[node].forEach { (neighbor, length) ->
                val candidate = distance + length
                if (candidate < distances[neighbor]) {
                    distances[neighbor] = candidate; previous[neighbor] = node; queue.add(neighbor to candidate)
                }
            }
        }
        if (!distances[end].isFinite()) return null
        val path = mutableListOf<Int>()
        var node = end
        while (node >= 0) { path.add(node); node = previous[node] }
        path.reverse()
        val cells = distances[end] / graph.chapter.cellPixels
        val turns = ceil(cells / graph.chapter.cellsPerTurn).toInt()
        return MapRoute(path, distances[end], cells, turns, turns * graph.chapter.hoursPerTurn)
    }
}
