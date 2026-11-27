package com.fireemblem.companion.maps

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class MapRepository private constructor(private val database: SQLiteDatabase) : AutoCloseable {
    val width: Double = metadata("width").toDouble()
    val height: Double = metadata("height").toDouble()
    val terrainSha256: String = metadata("terrain_sha256")
    val chapters: List<MapChapter> = database.rawQuery("SELECT id,title,start_node,cell_px,cells_per_turn,hours_per_turn FROM chapters ORDER BY rowid", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(MapChapter(cursor.getString(0), cursor.getString(1), cursor.getInt(2), cursor.getDouble(3), cursor.getDouble(4), cursor.getDouble(5))) }
    }

    fun types(): List<Pair<String, String>> = database.rawQuery("SELECT id,title FROM point_types ORDER BY rowid", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
    }

    fun points(chapter: String, query: String = "", type: String? = null): List<MapPoint> {
        val filter = if (type == null) "" else " AND point.type=?"
        val parameters = arrayOf(chapter, query.trim(), query.trim()) + if (type == null) emptyArray() else arrayOf(type)
        return database.rawQuery("SELECT point.id,point.chapter,point.title,point.type,types.title,point.description,point.quests,point.x,point.y,point.node,point.native_name " +
            "FROM points point JOIN point_types types ON types.id=point.type WHERE point.chapter=? AND " +
            "(instr(point.title||' '||point.description||' '||point.quests,?)>0 OR EXISTS(SELECT 1 FROM point_materials pm JOIN materials material ON material.id=pm.material_id " +
            "WHERE pm.point_id=point.id AND instr(material.title,?)>0))$filter ORDER BY point.title,point.id", parameters).use { cursor ->
            buildList { while (cursor.moveToNext()) add(MapPoint(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3),
                cursor.getString(4), cursor.getString(5), cursor.getString(6), MapCoordinate(cursor.getDouble(7), cursor.getDouble(8)),
                if (cursor.isNull(9)) null else cursor.getInt(9), cursor.getString(10))) }
        }
    }

    fun graph(chapterId: String): MapGraph {
        val chapter = chapters.single { it.id == chapterId }
        val nodes = database.rawQuery("SELECT x,y FROM nodes WHERE chapter=? ORDER BY id", arrayOf(chapterId)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(MapCoordinate(cursor.getDouble(0), cursor.getDouble(1))) }
        }
        val discoveries = database.rawQuery("SELECT edge_id,place FROM edge_discoveries WHERE chapter=?", arrayOf(chapterId)).use { cursor ->
            buildMap<Int, MutableSet<String>> { while (cursor.moveToNext()) getOrPut(cursor.getInt(0)) { mutableSetOf() }.add(cursor.getString(1)) }
        }
        val roads = database.rawQuery("SELECT id,start_node,end_node,distance,need,directed FROM edges WHERE chapter=? ORDER BY id", arrayOf(chapterId)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(MapRoad(cursor.getInt(1), cursor.getInt(2), cursor.getDouble(3), cursor.getInt(4), cursor.getInt(5) != 0,
                discoveries[cursor.getInt(0)].orEmpty())) }
        }
        return MapGraph(chapter, nodes, roads)
    }

    private fun metadata(key: String): String = database.rawQuery("SELECT value FROM metadata WHERE key=?", arrayOf(key)).use { cursor ->
        check(cursor.moveToFirst()); cursor.getString(0)
    }

    override fun close() = database.close()

    companion object {
        @Synchronized
        fun open(context: Context): MapRepository {
            val hash = context.assets.open("world-map/maps.sha256").bufferedReader().use { it.readText().trim() }
            check(Regex("[0-9a-f]{64}").matches(hash))
            val root = File(context.noBackupFilesDir, "map-databases").apply { mkdirs() }
            val target = File(root, "maps-$hash.db")
            fun digest(file: File): String {
                val checksum = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input -> val buffer = ByteArray(65536); var count = input.read(buffer)
                    while (count >= 0) { checksum.update(buffer, 0, count); count = input.read(buffer) } }
                return checksum.digest().joinToString("") { "%02x".format(it) }
            }
            if (!target.isFile || digest(target) != hash) {
                val staged = File.createTempFile("stage-", ".db", root)
                try {
                    context.assets.open("world-map/maps.db").use { input -> FileOutputStream(staged).use { input.copyTo(it); it.fd.sync() } }
                    check(digest(staged) == hash) { "地图资料校验失败" }
                    validate(staged).close()
                    Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } finally { staged.delete() }
            }
            val database = validate(target)
            return try { MapRepository(database) } catch (error: Exception) { database.close(); throw error }
        }

        private fun validate(file: File): SQLiteDatabase {
            val database = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                check(database.version == 1)
                database.rawQuery("PRAGMA application_id", null).use { check(it.moveToFirst() && it.getInt(0) == 0x46454D50) }
                database.rawQuery("PRAGMA quick_check", null).use { check(it.moveToFirst() && it.getString(0) == "ok") }
                database.rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) }
                return database
            } catch (error: Exception) { database.close(); throw error }
        }
    }
}
