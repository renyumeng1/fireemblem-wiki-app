package com.fireemblem.companion.personal

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.fireemblem.companion.importer.AtomicBuddyArchiveStore
import com.fireemblem.companion.importer.FefwGrowthSeedSourceArchive
import com.fireemblem.companion.engine.LevelJournal
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class PersonalStore(private val context: Context) {
    private class Helper(context: Context) : SQLiteOpenHelper(context,
        File(context.noBackupFilesDir.apply { mkdirs() }, "user.db").absolutePath, null, 1) {
        override fun onCreate(database: SQLiteDatabase) {
            database.execSQL("CREATE TABLE revisions(revision_id TEXT PRIMARY KEY,entity_id TEXT NOT NULL,kind TEXT NOT NULL," +
                "device_id TEXT NOT NULL,parents TEXT NOT NULL,deleted INTEGER NOT NULL,modified_at INTEGER NOT NULL,payload TEXT NOT NULL)")
            database.execSQL("CREATE INDEX revision_entities ON revisions(entity_id)")
            database.execSQL("CREATE TABLE device(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
            database.execSQL("INSERT INTO device VALUES('id',?)", arrayOf(UUID.randomUUID().toString()))
        }
        override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("不支持的个人数据库升级，请保留原文件")
        }
    }

    fun snapshot(): PersonalSnapshot = Helper(context).use { read(it.readableDatabase) }

    fun merge(incoming: PersonalSnapshot): PersonalSnapshot = transaction { database ->
        val merged = read(database).merge(incoming)
        merged.encode()
        insert(database, merged)
        merged
    }

    fun edit(entityId: String, kind: String, payload: JsonObject, deleted: Boolean = false,
        expectedParents: Set<String>,
    ): PersonalSnapshot = transaction { database ->
        val deviceId = database.rawQuery("SELECT value FROM device WHERE key='id'", null).use { cursor ->
            check(cursor.moveToFirst()); cursor.getString(0)
        }
        val edited = read(database).edit(entityId, kind, deviceId, payload, deleted, expectedParents)
        edited.encode()
        insert(database, edited)
        edited
    }

    fun importGrowthSeedJournal(
        entityId: String,
        journalPayload: JsonObject,
        sourceArchive: FefwGrowthSeedSourceArchive,
    ): PersonalSnapshot = transaction { database ->
        val archive = sourceArchive
        val journal = LevelJournal.decode(journalPayload)
        require(journal.importSourceSha256 == archive.sourceSha256 && journal.importSourceRecordIndex != null) {
            "记录册与原始成长文件不匹配"
        }
        val before = read(database)
        require(before.revisions.none { it.entityId == entityId }) { "记录册编号已存在，请刷新后重试" }
        val sourceRevision = archive.toRevision()
        require(entityId != sourceRevision.entityId) { "记录册编号无效" }
        val withSource = before.merge(PersonalSnapshot(listOf(sourceRevision)))
        val deviceId = database.rawQuery("SELECT value FROM device WHERE key='id'", null).use { cursor ->
            check(cursor.moveToFirst()); cursor.getString(0)
        }
        val updated = withSource.edit(entityId, LevelJournal.KIND, deviceId, journalPayload, expectedParents = emptySet())
        updated.encode()
        insert(database, updated)
        updated
    }

    fun growthSeedSourceArchive(sourceSha256: String): FefwGrowthSeedSourceArchive? =
        FefwGrowthSeedSourceArchive.find(snapshot(), sourceSha256)

    fun importLegacyArchives(archives: AtomicBuddyArchiveStore) = transaction { database ->
        var snapshot = read(database)
        val known = snapshot.revisions.map(PersonalRevision::entityId).toHashSet()
        val checksums = snapshot.revisions.filter { it.kind == "buddy" }.mapNotNull {
            it.payload["sourceSha256"]?.jsonPrimitive?.content
        }.toSet()
        val deviceId = database.rawQuery("SELECT value FROM device WHERE key='id'", null).use { cursor ->
            check(cursor.moveToFirst()); cursor.getString(0)
        }
        archives.list().filter { it.archiveId !in known && it.sourceSha256 !in checksums }.forEach { archive ->
            val payload = buildJsonObject {
                put("name", "Buddy · ${archive.importedAt}")
                put("sourceSha256", archive.sourceSha256)
                put("backup", Json.parseToJsonElement(archives.readPayload(archive.archiveId)))
            }
            snapshot = snapshot.edit(archive.archiveId, "buddy", deviceId, payload)
        }
        snapshot.encode()
        insert(database, snapshot)
    }

    private fun read(database: SQLiteDatabase): PersonalSnapshot = database.rawQuery(
        "SELECT revision_id,entity_id,kind,device_id,parents,deleted,modified_at,payload FROM revisions ORDER BY revision_id", null,
    ).use { cursor ->
        PersonalSnapshot(buildList {
            while (cursor.moveToNext()) add(PersonalRevision(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3),
                Json.parseToJsonElement(cursor.getString(4)).jsonArray.map { it.jsonPrimitive.content }, cursor.getInt(5) != 0,
                cursor.getLong(6), Json.parseToJsonElement(cursor.getString(7)).jsonObject))
        }).validated()
    }

    private fun insert(database: SQLiteDatabase, snapshot: PersonalSnapshot) {
        snapshot.revisions.forEach { revision ->
            val values = ContentValues().apply {
                put("revision_id", revision.revisionId); put("entity_id", revision.entityId); put("kind", revision.kind)
                put("device_id", revision.deviceId); put("parents", JsonArray(revision.parents.map(::JsonPrimitive)).toString())
                put("deleted", if (revision.deleted) 1 else 0); put("modified_at", revision.modifiedAt); put("payload", revision.payload.toString())
            }
            database.insertWithOnConflict("revisions", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    private fun <Result> transaction(action: (SQLiteDatabase) -> Result): Result = Helper(context).use { helper ->
        val database = helper.writableDatabase
        database.beginTransaction()
        try { action(database).also { database.setTransactionSuccessful() } }
        finally { database.endTransaction() }
    }
}
