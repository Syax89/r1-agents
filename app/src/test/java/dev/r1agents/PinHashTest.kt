package dev.r1agents

import org.junit.Assert.*
import org.junit.Test

class PinHashTest {
    @Test fun correctPinOnly() { val hash = PinHash.create("004279"); assertTrue(PinHash.verify("004279", hash)); assertFalse(PinHash.verify("004278", hash)) }
    @Test fun distinctSalts() { assertNotEquals(PinHash.create("1234"), PinHash.create("1234")) }
    @Test fun rejectsMalformedRecords() { assertFalse(PinHash.verify("1234", "v1:999999999:x:y")); assertFalse(PinHash.verify("1234", "invalid")) }
    @Test fun acceptsOnlyFourToEightDigits() { assertTrue(PinHash.valid("0000")); assertFalse(PinHash.valid("123")); assertFalse(PinHash.valid("123456789")); assertFalse(PinHash.valid("12ab")) }
}
