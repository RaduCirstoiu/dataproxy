package com.dataproxy.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogBufferTest {
    @Test
    fun retainsOnlyNewestEntriesInOrder() {
        var now = 100L
        val buffer = LogBuffer(maxEntries = 3, clock = { now++ })

        repeat(5) { index ->
            buffer.append(AppLogLevel.INFO, "Test", "message-$index")
        }

        val entries = buffer.entries.value
        assertEquals(listOf("message-2", "message-3", "message-4"), entries.map { it.message })
        assertEquals(listOf(3L, 4L, 5L), entries.map { it.id })
        assertEquals(listOf(102L, 103L, 104L), entries.map { it.timestampMillis })
    }

    @Test
    fun clearRemovesAllEntries() {
        val buffer = LogBuffer(maxEntries = 2)
        buffer.append(AppLogLevel.WARN, "Test", "warning")

        buffer.clear()

        assertTrue(buffer.entries.value.isEmpty())
    }
}
