package com.nezhahq.agent.util

import org.junit.Assert.*
import org.junit.Test

class ConfigurationValuesTest {
    @Test fun mutableLegacyInputsCannotChangePublishedSnapshot() {
        val strings = mutableSetOf("first")
        val source = linkedMapOf<String, Any>("secret" to "original", "strings" to strings)
        val values = ConfigurationValues.from(source)
        source["secret"] = "changed"
        strings += "second"
        assertEquals("original", values.string("secret"))
        assertEquals(setOf("first"), values.entries["strings"])
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (values.entries as MutableMap<String, Any>)["secret"] = "changed"
        }
    }

    @Test fun updateKeepsUnrelatedSectionsAndLeavesPriorSnapshotUnchanged() {
        val original = ConfigurationValues.from(mapOf("secret" to "main", "simulator_secret" to "simulator"))
        val changed = original.changed(mapOf("enable_float_window" to true))
        assertEquals("main", changed.string("secret"))
        assertEquals("simulator", changed.string("simulator_secret"))
        assertFalse(original.boolean("enable_float_window"))
        assertTrue(changed.boolean("enable_float_window"))
    }

    @Test fun unsupportedLegacyValuesFailBeforePersistence() {
        assertThrows(IllegalArgumentException::class.java) { ConfigurationValues.from(mapOf("set" to setOf(1))) }
        assertThrows(java.io.IOException::class.java) { ConfigurationValues.from(mapOf("null" to null)) }
    }
}