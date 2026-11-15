package app.ferry.core

import app.ferry.core.model.AppInfo
import app.ferry.core.model.CheckBatchReply
import app.ferry.core.model.CheckBatchRequest
import app.ferry.core.model.CheckFile
import app.ferry.core.model.ErrorReply
import app.ferry.core.model.OutboxList
import app.ferry.core.model.PcInfo
import app.ferry.core.model.UploadReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replies captured from ferry.py 2.0.0 (Ph2a, 2026-11-15). */
class JsonModelsTest {
    @Test
    fun info() {
        val s = """{"app": "ferry", "protocol": 2, "version": "2.0.0", "name": "vm", "device_type": "pc", "os": "linux", "capabilities": ["receive", "outbox", "check_batch", "app_update", "discovery"], "inbox_label": "/tmp/fx/i", "outbox_label": "/tmp/fx/o", "free_bytes": 31731707904, "app_version_code": null}"""
        val i = FerryJson.decodeFromString(PcInfo.serializer(), s)
        assertEquals("ferry", i.app)
        assertEquals(2, i.protocol)
        assertEquals("2.0.0", i.version)
        assertEquals("vm", i.name)
        assertEquals("pc", i.deviceType)
        assertEquals(31731707904L, i.freeBytes)
        assertNull(i.appVersionCode)
        assertTrue("check_batch" in i.capabilities)
    }

    @Test
    fun v1InfoHasNoProtocolAndReadsAsOne() {
        val s = """{"app": "ferry", "version": "1.3.0", "name": "PC", "os": "windows", "free_bytes": 5}"""
        assertEquals(1, FerryJson.decodeFromString(PcInfo.serializer(), s).protocol)
    }

    @Test
    fun checkBatch() {
        val s = """{"ok": true, "results": [{"duplicate": null}, {"duplicate": "a.jpg"}], "needed_bytes": 8, "free_bytes": 31664599040}"""
        val r = FerryJson.decodeFromString(CheckBatchReply.serializer(), s)
        assertTrue(r.ok)
        assertNull(r.results[0].duplicate)
        assertEquals("a.jpg", r.results[1].duplicate)
        assertEquals(8L, r.neededBytes)
        assertEquals(31664599040L, r.freeBytes)
    }

    @Test
    fun uploadReplies() {
        for ((s, result, savedAs) in listOf(
            Triple("""{"result": "saved", "saved_as": "a.jpg", "bytes": 5}""", "saved", "a.jpg"),
            Triple("""{"result": "renamed", "saved_as": "a (1).jpg", "bytes": 5}""", "renamed", "a (1).jpg"),
            Triple("""{"result": "skipped", "saved_as": "a.jpg", "bytes": 5}""", "skipped", "a.jpg"),
        )) {
            val r = FerryJson.decodeFromString(UploadReply.serializer(), s)
            assertEquals(result, r.result)
            assertEquals(savedAs, r.savedAs)
            assertEquals(5L, r.bytes)
        }
    }

    @Test
    fun errors() {
        val e = FerryJson.decodeFromString(ErrorReply.serializer(), """{"result": "error", "message": "bad upload request"}""")
        assertEquals("bad upload request", e.message)
        assertNull(e.reason)
        val full = FerryJson.decodeFromString(
            ErrorReply.serializer(),
            """{"result": "error", "reason": "disk_full", "message": "PC disk is full"}""",
        )
        assertEquals("disk_full", full.reason)
    }

    @Test
    fun outbox() {
        val s = """{"files": [{"id": 1, "name": "report.pdf", "size": 6, "mtime": 1794748115773, "kind": "other", "url": "/api/v1/outbox/1/report.pdf"}], "total_size": 6, "folders_ignored": 0}"""
        val o = FerryJson.decodeFromString(OutboxList.serializer(), s)
        assertEquals(1, o.files.size)
        val f = o.files[0]
        assertEquals(1, f.id)
        assertEquals("report.pdf", f.name)
        assertEquals(6L, f.size)
        assertEquals(1794748115773L, f.mtime)
        assertEquals("/api/v1/outbox/1/report.pdf", f.url)
        assertEquals(6L, o.totalSize)
    }

    @Test
    fun appInfo() {
        val s = """{"version_code": 20022, "version_name": "2.0.22", "size": 123, "sha256": "ab", "url": "/app/Ferry.apk"}"""
        val a = FerryJson.decodeFromString(AppInfo.serializer(), s)
        assertEquals(20022L, a.versionCode)
        assertEquals("2.0.22", a.versionName)
        assertEquals("/app/Ferry.apk", a.url)
    }

    @Test
    fun unknownKeysIgnored() {
        val s = """{"result": "saved", "saved_as": "a.jpg", "bytes": 5, "something_new": {"x": [1, 2]}}"""
        assertEquals("saved", FerryJson.decodeFromString(UploadReply.serializer(), s).result)
    }

    @Test
    fun mtimeOmittedWhenNull() { // [B17]
        val json = FerryJson.encodeToString(
            CheckBatchRequest.serializer(),
            CheckBatchRequest(listOf(CheckFile("a.jpg", 5, 1700000000000), CheckFile("b.jpg", 3))),
        )
        assertEquals("""{"files":[{"name":"a.jpg","size":5,"mtime":1700000000000},{"name":"b.jpg","size":3}]}""", json)
        assertFalse(json.contains("null"))
    }
}
