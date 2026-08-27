package io.github.jiangyuyi.lightnovel.feature.local

import io.github.jiangyuyi.lightnovel.core.local.LocalBookFormat
import io.github.jiangyuyi.lightnovel.core.local.LocalBookRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalLibrarySearchTest {
    private val books = listOf(
        LocalBookRecord("1", "content://one", "星海旅人", author = "Lin Ye", format = LocalBookFormat.EPUB),
        LocalBookRecord("2", "content://two", "纸上月光", author = "白川", format = LocalBookFormat.TXT),
    )

    @Test
    fun blankQueryReturnsAllBooksInOriginalOrder() {
        assertEquals(books, filterLocalBooks(books, "  "))
    }

    @Test
    fun queryMatchesTitleOrAuthorIgnoringCase() {
        assertEquals(listOf("星海旅人"), filterLocalBooks(books, "星海").map(LocalBookRecord::title))
        assertEquals(listOf("星海旅人"), filterLocalBooks(books, "LIN").map(LocalBookRecord::title))
    }

    @Test
    fun toggleAllSelectionSelectsEveryRecordThenClearsIt() {
        val ids = books.map(LocalBookRecord::id).toSet()

        assertEquals(ids, toggleAllSelection(emptySet(), ids))
        assertEquals(emptySet<String>(), toggleAllSelection(ids, ids))
    }
}
