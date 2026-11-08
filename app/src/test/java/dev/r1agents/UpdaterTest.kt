package dev.r1agents

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UpdaterTest {
    private val sha = "a6ba3c61841613f0976bf949030c8dd6c779faa80d2a474374e66050990ae1a1"
    private fun manifest(edit: JSONObject.() -> Unit = {}) = JSONObject()
        .put("schema", 1).put("packageName", "dev.r1agents").put("versionCode", 3).put("versionName", "0.3.0")
        .put("apkUrl", "https://example.org/r1agents.apk").put("sha256", sha).put("sizeBytes", 1009991).put("notes", "novità")
        .apply(edit).toString()
    private fun rejected(json: String): Int = try { Updater.parse(json, "dev.r1agents"); fail("doveva essere rifiutato"); 0 } catch (e: UpdateError) { e.resource }

    @Test fun acceptsTheManifestWrittenByPublishUpdate() {
        val m = Updater.parse(manifest(), "dev.r1agents")
        assertEquals(3L, m.versionCode); assertEquals("0.3.0", m.versionName); assertEquals(1009991L, m.sizeBytes); assertEquals("novità", m.notes)
    }

    @Test fun onlyHttpsWithoutCredentialsOrFragment() {
        assertTrue(Updater.httpsUrl("https://example.org/update.json"))
        assertFalse(Updater.httpsUrl("http://example.org/update.json"))
        assertFalse(Updater.httpsUrl("https://user:pass@example.org/update.json"))
        assertFalse(Updater.httpsUrl("https://example.org/update.json#x"))
        assertFalse(Updater.httpsUrl("ftp://example.org/a"))
        assertFalse(Updater.httpsUrl("non un indirizzo"))
        rejected(manifest { put("apkUrl", "http://example.org/r1agents.apk") })
    }

    @Test fun rejectsAnotherPackage() { assertEquals(R.string.ui_84e62dec0f, rejected(manifest { put("packageName", "dev.r1agents.qa") })) }

    @Test fun rejectsMalformedFields() {
        rejected("non è json")
        rejected(manifest { put("schema", 2) })
        rejected(manifest { put("sha256", "abc") })
        rejected(manifest { put("versionCode", 0) })
        rejected(manifest { put("sizeBytes", 0) })
        rejected(manifest { put("sizeBytes", Updater.MAX_APK_BYTES + 1) })
        rejected(manifest { put("notes", "x".repeat(12001)) })
    }

    @Test fun onlyAHigherVersionCodeIsAnUpdate() {
        val m = Updater.parse(manifest(), "dev.r1agents")
        assertTrue(Updater.isNewer(m, 2)); assertFalse(Updater.isNewer(m, 3)); assertFalse(Updater.isNewer(m, 9))
    }
}
