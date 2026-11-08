package dev.r1agents

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachTest {
    @Test fun imagesByMimeOrExtension() { assertEquals(Kind.IMAGE, Attach.kindOf("a.jpg", "")); assertEquals(Kind.IMAGE, Attach.kindOf("x", "image/png")); assertEquals(Kind.IMAGE, Attach.kindOf("FOTO.JPEG", "")) }
    @Test fun pdf() { assertEquals(Kind.PDF, Attach.kindOf("doc.pdf", "")); assertEquals(Kind.PDF, Attach.kindOf("x", "application/pdf")) }
    @Test fun textLikeFiles() { for (n in listOf("note.txt", "README.md", "dati.csv", "a.json", "main.kt", "script.py", "query.sql")) assertEquals(n, Kind.TEXT, Attach.kindOf(n, "")); assertEquals(Kind.TEXT, Attach.kindOf("x", "text/plain")) }
    @Test fun unsupported() { assertEquals(Kind.OTHER, Attach.kindOf("a.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")); assertEquals(Kind.OTHER, Attach.kindOf("a.zip", "")) }
}
