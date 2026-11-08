package dev.r1agents

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class WheelRotationTest {
    @Before fun geometriaDiBase() { r1WheelLandscapeFlip = false }
    @After fun ripristino() { r1WheelLandscapeFlip = true }

    @Test fun samePhysicalStepKeepsDirectionThroughAllRotations() {
        // Tracce dello stesso gesto naturale dopo la trasformazione del display Android (inversione orizzontale spenta).
        assertEquals(24f, r1WheelDelta(0f, 24f, 0), 0f)
        assertEquals(24f, r1WheelDelta(24f, 0f, 1), 0f)
        assertEquals(24f, r1WheelDelta(0f, -24f, 2), 0f)
        assertEquals(24f, r1WheelDelta(-24f, 0f, 3), 0f)
    }
    @Test fun reverseAndDiagonalNoiseDoNotChangeDirection() {
        assertEquals(-24f, r1WheelDelta(2f, -24f, 0), 0f)
        assertEquals(-24f, r1WheelDelta(-24f, 2f, 1), 0f)
        assertEquals(-24f, r1WheelDelta(2f, 24f, 2), 0f)
        assertEquals(-24f, r1WheelDelta(24f, 2f, 3), 0f)
    }
    @Test fun inOrizzontaleIlVersoSiInvertePerDefault() {
        r1WheelLandscapeFlip = true
        // In verticale (0, 180) nulla cambia; in orizzontale (90, 270) il verso è opposto al calcolo teorico.
        assertEquals(24f, r1WheelDelta(0f, 24f, 0), 0f)
        assertEquals(24f, r1WheelDelta(0f, -24f, 2), 0f)
        assertEquals(-24f, r1WheelDelta(24f, 0f, 1), 0f)
        assertEquals(-24f, r1WheelDelta(-24f, 0f, 3), 0f)
    }
}
