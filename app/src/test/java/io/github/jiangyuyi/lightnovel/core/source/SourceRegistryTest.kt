package io.github.jiangyuyi.lightnovel.core.source

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceRegistryTest {
    @Test
    fun `same remote id from different sources remains distinct`() {
        val kingdom = NovelKey(BuiltInSourceIds.LIGHT_NOVEL_KINGDOM, "42")
        val shelf = NovelKey(BuiltInSourceIds.LIGHT_NOVEL_SHELF, "42")

        assertNotEquals(kingdom, shelf)
    }

    @Test
    fun `duplicate source ids are rejected`() {
        val sourceA = FakeSource("duplicate")
        val sourceB = FakeSource("duplicate")

        assertThrows(IllegalArgumentException::class.java) {
            SourceRegistry(listOf(sourceA, sourceB))
        }
    }

    private class FakeSource(id: String) : NovelSource {
        override val descriptor = SourceDescriptor(id, id, emptySet())
    }
}

