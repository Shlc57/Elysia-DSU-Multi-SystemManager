package dev.multisystem.client

import org.junit.Assert.*
import org.junit.Test

class UiInputRulesTest {
    @Test fun slotAcceptsOnlyAscii() {
        assertEquals("A0_b", UiInputRules.filterSlot("A0_b"))
        assertEquals("abc", UiInputRules.filterSlot("a中文b- c"))
        assertEquals(48, UiInputRules.filterSlot("a".repeat(60)).length)
    }
    @Test fun userdataRejectsInvalidOrUnsafeSizes() {
        listOf("", "NaN", "Infinity", "-1", "0", "0.99", "64.1", "中文").forEach {
            assertNull(it, UiInputRules.userdataBytes(it))
        }
        assertEquals(1073741824L, UiInputRules.userdataBytes("1"))
        assertEquals(68719476736L, UiInputRules.userdataBytes("64"))
        assertEquals(0L, UiInputRules.userdataBytes("1.234")!! % 512L)
    }
}
