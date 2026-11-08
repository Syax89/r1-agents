package dev.r1agents

import org.junit.Assert.assertEquals
import org.junit.Test

class HandoffLabelTest {
    @Test fun messageAgentNamesTheAgentBeingAsked() {
        assertEquals("chiede a sergino", toolLabel("message_agent", """{"agent":"sergino","message":"ciao"}"""))
    }

    @Test fun truncatedInputStillWorks() {
        assertEquals("chiede a voice", toolLabel("message_agent", """{"agent":"voice","message":"un messaggio molto lun"""))
    }

    @Test fun otherToolsAreUnchanged() {
        assertEquals("cerca sul web", toolLabel("web_search", ""))
    }
}
