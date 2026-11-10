package dev.r1agents

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachTest {
    @Test fun imagesByMimeOrExtension() { assertEquals(Kind.IMAGE, Attach.kindOf("a.jpg", "")); assertEquals(Kind.IMAGE, Attach.kindOf("x", "image/png")); assertEquals(Kind.IMAGE, Attach.kindOf("FOTO.JPEG", "")) }
    @Test fun pdf() { assertEquals(Kind.PDF, Attach.kindOf("doc.pdf", "")); assertEquals(Kind.PDF, Attach.kindOf("x", "application/pdf")) }
    @Test fun textLikeFiles() { for (n in listOf("note.txt", "README.md", "dati.csv", "a.json", "main.kt", "script.py", "query.sql")) assertEquals(n, Kind.TEXT, Attach.kindOf(n, "")); assertEquals(Kind.TEXT, Attach.kindOf("x", "text/plain")) }
    @Test fun unsupported() { assertEquals(Kind.OTHER, Attach.kindOf("a.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")); assertEquals(Kind.OTHER, Attach.kindOf("a.zip", "")) }
    @Test fun soloContenutiDiAltreApp() {
        val me = "dev.r1agents"
        org.junit.Assert.assertTrue(Attach.allowedUri("content", "com.android.providers.media.documents", me))
        org.junit.Assert.assertFalse(Attach.allowedUri("file", null, me))
        org.junit.Assert.assertFalse(Attach.allowedUri("content", "dev.r1agents.photos", me))
        org.junit.Assert.assertFalse(Attach.allowedUri("content", "dev.r1agents.qa.photos", "dev.r1agents.qa"))
        org.junit.Assert.assertFalse(Attach.allowedUri("content", "", me))
        org.junit.Assert.assertFalse(Attach.allowedUri(null, "x", me))
    }
    @Test fun tokenInterno() {
        org.junit.Assert.assertTrue(InternalIntent.matches("abc", "abc"))
        org.junit.Assert.assertFalse(InternalIntent.matches(null, "abc"))
        org.junit.Assert.assertFalse(InternalIntent.matches("abd", "abc"))
    }
    @Test fun soloCartellaAllegati() {
        org.junit.Assert.assertTrue(Attach.inAttachDir(java.io.File("/data/user/0/dev.r1agents/files/attach/att-1-foto.jpg")))
        org.junit.Assert.assertFalse(Attach.inAttachDir(java.io.File("/data/user/0/dev.r1agents/shared_prefs/settings.xml")))
        org.junit.Assert.assertFalse(Attach.inAttachDir(java.io.File("/data/user/0/dev.r1agents/files/attach/../../shared_prefs/settings.xml")))
    }
    @Test fun backupSenzaPercorsi() {
        val c = """[{"type":"text","text":"ciao"},{"type":"attachment","name":"x","path":"/data/data/dev.r1agents/shared_prefs/settings.xml"}]"""
        val out = Backup.stripAttachmentPaths(c)
        org.junit.Assert.assertFalse(out.contains("shared_prefs"))
        org.junit.Assert.assertTrue(out.contains("\"name\":\"x\""))
        org.junit.Assert.assertEquals("[]", Backup.stripAttachmentPaths("[]"))
    }
}
