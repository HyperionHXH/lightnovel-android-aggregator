package io.github.jiangyuyi.lightnovel.core.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserFontRepositoryTest {
    @Test
    fun catalogHasDistinctPinnedOpenSourceFonts() {
        val catalog = UserFontRepository.catalog
        assertEquals(6, catalog.size)
        assertEquals(catalog.size, catalog.map(UserFontDefinition::id).toSet().size)
        assertEquals(
            listOf("source-han-serif-cn", "source-han-sans-cn", "lxgw-wenkai", "genryu-min", "glow-sans-sc", "huiwen-mincho"),
            catalog.map(UserFontDefinition::id),
        )
        catalog.forEach { font ->
            assertTrue(font.urls.isNotEmpty())
            assertTrue(font.sha256.matches(Regex("[0-9A-Fa-f]{64}")))
            assertTrue(font.license.contains("OFL") || font.license.contains("CC0"))
        }
    }
}
