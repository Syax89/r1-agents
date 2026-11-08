package dev.r1agents

import org.junit.Assert.*
import org.junit.Test

class BuddyVisualStateTest {
    @Test fun handoffUsesOnlyTheReportedRecipient() {
        assertEquals("Luna", BuddyVisualState.fromActivity(toolLabel("message_agent", "{\"agent\":\"Luna\"}")).targetName)
        assertNull(BuddyVisualState.fromActivity(toolLabel("delegate", "")).targetName)
        assertNull(BuddyVisualState.of("thinking", "Luna").targetName)
    }
    @Test fun workStatesAreSharedAcrossBothScenes() {
        assertEquals("files", BuddyVisualState.fromActivity("invio foto.png…").activity)
        assertEquals("search", BuddyVisualState.fromActivity("cerca sul web").kind)
        assertEquals("waiting", BuddyVisualState.of("waiting").phase)
        assertEquals("thinking", BuddyVisualState.of("transcribing").phase)
    }
    @Test fun envelopeIgnoresInvalidLevelsAndResetsFully() {
        val e = VoiceEnvelope(); e.accept(10f); assertEquals(1f, e.target)
        e.advance(.04f); assertTrue(e.value > 0f && e.value < 1f)
        e.accept(Float.NaN); assertEquals(0f, e.target)
        e.reset(); assertEquals(0f, e.value); assertEquals(0f, e.target)
    }
    @Test fun releaseIsSofterThanAttackAndNeverOvershoots() {
        val e = VoiceEnvelope(); e.accept(1f)
        repeat(90) { assertTrue(e.advance(1f / 90) in 0f..1f) }
        val before = e.value; e.accept(0f); val after = e.advance(.04f)
        assertTrue(after < before && after > .5f)
        repeat(180) { e.advance(1f / 90) }; assertTrue(e.value < .001f)
    }
    @Test fun envelopeDoesNotDependOnMicrophoneSampleFrequency() {
        val a = VoiceEnvelope(); val b = VoiceEnvelope()
        a.accept(.8f); b.accept(.8f)
        repeat(90) { a.advance(1f / 90) }
        repeat(30) { b.accept(.8f); repeat(3) { b.advance(1f / 90) } }
        assertEquals(a.value, b.value, .0001f)
    }
}
