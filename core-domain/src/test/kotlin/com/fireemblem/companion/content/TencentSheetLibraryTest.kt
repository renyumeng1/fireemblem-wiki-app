package com.fireemblem.companion.content

import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TencentSheetLibraryTest {
    @Test
    fun loadsSourceMetadataAndSearchesRowsWithOriginalCoordinates() {
        val packageFiles = fixturePackage()

        val library = TencentSheetLibrary.load(sourceOf(packageFiles))
        val result = library.search("hero")

        assertEquals("rev-12", library.metadata.revision)
        assertEquals(1, library.metadata.availableSheetCount)
        assertEquals(listOf("c001"), library.sheets.map(SheetDescriptor::id))
        assertEquals(3, library.rowsFor("c001").size)
        assertEquals(1, result.totalMatches)
        assertFalse(result.isTruncated)
        assertEquals(3, result.results.single().row.rowNumber)
        assertEquals("B3", result.results.single().matchingCells.single().address)
        assertEquals("Hero Name", result.results.single().matchingCells.single().value)
    }

    @Test
    fun preservesFormulaCachedValuesAndRichTextForBrowsing() {
        val library = TencentSheetLibrary.load(sourceOf(fixturePackage()))

        val cells = library.rowsFor("c001").last().cells

        assertEquals("4.5", cells.first { it.address == "C4" }.value)
        assertEquals("Long description", cells.first { it.address == "D4" }.value)
        assertEquals("[圖片]", cells.first { it.address == "E4" }.value)
        assertEquals("assets/icon.webp", cells.first { it.address == "E4" }.imageAsset)
    }

    @Test
    fun mapsRoleRowsIntoNamedWikiEntriesAndKeepsPortraitReferences() {
        val library = TencentSheetLibrary.load(sourceOf(wikiFixturePackage()))

        val category = library.wikiCategories.single()
        val entry = category.entries.single()

        assertEquals("角色", category.title)
        assertEquals("艾黛尔贾特", entry.title)
        assertEquals("贵族", entry.subtitle)
        assertEquals("assets/hero.webp", entry.imageAsset)
        assertEquals("", entry.fields.first().value)
        assertEquals("assets/faction.webp", entry.fields.first().imageAsset)
        assertEquals("加入条件", entry.fields.last().label)
        assertEquals("力量与技巧", entry.fields.last().value)
    }

    @Test
    fun reportsTruncatedSearchResultsAndCanRestrictSearchToOneSheet() {
        val library = TencentSheetLibrary.load(sourceOf(fixturePackage()))

        val truncated = library.search("r", limit = 1)
        val restricted = library.search("hero", sheetId = "missing")

        assertEquals(2, truncated.totalMatches)
        assertTrue(truncated.isTruncated)
        assertTrue(restricted.results.isEmpty())
    }

    @Test
    fun rejectsTableWhoseBytesDoNotMatchManifestChecksum() {
        val packageFiles = fixturePackage().toMutableMap()
        packageFiles["tables/c001.json"] = packageFiles.getValue("tables/c001.json") + byteArrayOf(0)

        val error = try {
            TencentSheetLibrary.load(sourceOf(packageFiles))
            null
        } catch (failure: SheetPackageException) {
            failure
        }

        assertEquals("工作表 角色资料 校验失败", error?.message)
    }

    @Test
    fun loadsTheFullLocalSnapshotWhenItIsProvisioned() {
        val packageDirectory = Path.of("..", "data", "derived", "tencent-sheet").toAbsolutePath().normalize()
        assumeTrue(Files.isRegularFile(packageDirectory.resolve("manifest.json")))

        val library = TencentSheetLibrary.load(
            SheetPackageSource { path -> Files.newInputStream(packageDirectory.resolve(path)) },
        )

        assertEquals(37, library.sheets.size)
        assertEquals(83_205, library.sheets.sumOf(SheetDescriptor::cellCount))
        assertEquals(1, library.metadata.unavailableSheets.size)
        assertTrue(library.search("兵种").totalMatches > 0)
        assertTrue(library.wikiMappingIssues.isEmpty())
        assertEquals(10, library.wikiCategories.size)
        assertTrue(library.wikiCategories.first { it.id == "characters" }.entries.isNotEmpty())
        assertTrue(library.wikiCategories.first { it.id == "characters" }.entries.first().imageAsset!!.endsWith(".webp"))
    }

    private fun fixturePackage(): Map<String, ByteArray> {
        val table = """
            {
              "schema_version": 1,
              "metadata": {"sheet_id": "c001", "name": "角色资料"},
              "cells": [
                {"row": 2, "column": 1, "address": "A2", "type": "string", "value": "角色"},
                {"row": 3, "column": 1, "address": "A3", "type": "string", "value": "艾黛尔贾特"},
                {"row": 3, "column": 2, "address": "B3", "type": "string", "value": "Hero Name"},
                {"row": 4, "column": 3, "address": "C4", "type": "formula", "formula": {"cached_value": 4.5, "model": "=1+3.5"}},
                {"row": 4, "column": 4, "address": "D4", "type": "rich_text", "value": "", "rich_text_runs": [{"text": "Long description", "style": {}}]},
                {"row": 4, "column": 5, "address": "E4", "type": "string", "value": "", "image_reference": {"url": "https://example.invalid/image.png", "local_file": "assets/icon.png"}}
              ]
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val manifest = """
            {
              "schema_version": 1,
              "generator": "fireemblem/tools/tencent-sheet",
              "source": {"uri": "https://docs.qq.com/sheet/example", "title": "FE Wiki", "revision": "rev-12", "retrieved_at": "2026-11-16"},
              "coverage": {"available_sheets": 1, "unavailable_sheets": 1},
              "sheets": [{"sheet_id": "c001", "name": "角色资料", "status": "available", "stored_cell_count": 6, "output_file": "tables/c001.json", "output_sha256": "${sha256(table)}"}],
              "unavailable_sheets": [{"sheet_id": "hidden1", "name": "隐藏表", "status": "unavailable", "hidden": true, "reason": "不可读取"}]
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)
        return mapOf("manifest.json" to manifest, "tables/c001.json" to table)
    }

    private fun wikiFixturePackage(): Map<String, ByteArray> {
        val table = """
            {"schema_version":1,"metadata":{"sheet_id":"sd3o0k","name":"01-全可加入角色信息"},"cells":[
              {"row":2,"column":4,"address":"D2","type":"string","value":"角色名称"},
              {"row":4,"column":2,"address":"B4","type":"string","value":"","image_reference":{"local_file":"assets/faction.png"}},
              {"row":4,"column":3,"address":"C4","type":"string","value":"","image_reference":{"local_file":"assets/hero.png"}},
              {"row":4,"column":4,"address":"D4","type":"string","value":"艾黛尔贾特"},
              {"row":4,"column":5,"address":"E4","type":"string","value":"贵族"},
              {"row":4,"column":18,"address":"R4","type":"string","value":"力量与技巧"}
            ]}
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val manifest = """
            {"schema_version":1,"generator":"fireemblem/tools/tencent-sheet",
             "source":{"uri":"https://docs.qq.com/sheet/example","title":"FE Wiki","revision":"rev-12"},
             "coverage":{"available_sheets":1,"unavailable_sheets":0},
             "sheets":[{"sheet_id":"sd3o0k","name":"01-全可加入角色信息","status":"available","stored_cell_count":6,"output_file":"tables/sd3o0k.json","output_sha256":"${sha256(table)}"}],
             "unavailable_sheets":[]}
        """.trimIndent().toByteArray(Charsets.UTF_8)
        return mapOf("manifest.json" to manifest, "tables/sd3o0k.json" to table)
    }

    private fun sourceOf(files: Map<String, ByteArray>) = SheetPackageSource { path ->
        val bytes = files[path] ?: throw FileNotFoundException(path)
        ByteArrayInputStream(bytes)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}
