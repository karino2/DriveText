package io.github.karino2.listtextview

import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class TextSplitterTest {
    val target = TextSplitter()
    @Test
    fun split_normalCase() {
        val content = """
            Hello
            this is test.
            
            next cell.
        """.trimIndent()
        target.text = content
        val actual = target.textList

        assertEquals(2, actual.size)
        assertEquals("next cell.", actual[1])
    }

    @Test
    fun mergedContent_normalCase() {
        val content = """
            Hello
            this is test.
            
            next cell.
        """.trimIndent()
        target.text = content
        val actual = target.mergedContent
        assertEquals(content, actual)
    }
}
