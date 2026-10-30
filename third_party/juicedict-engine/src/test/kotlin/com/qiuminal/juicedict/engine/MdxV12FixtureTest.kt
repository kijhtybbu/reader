package com.qiuminal.juicedict.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MdxV12FixtureTest {
    @Test
    fun readsV12Fixture() {
        val file = File("src/test/resources/mdict/v12.mdx")
        assertTrue(file.isFile)
        val probe = DictionaryEngines.probeMdx(file)
        assertEquals(2L, probe?.entryCount)
        val engine = DictionaryEngines.openMdx("v12", file, emptyList(), "v12", "")
        try {
            assertEquals(2, engine.wordCount)
            val hit = engine.lookupSmart("hello", 5).first()
            assertEquals("hello", hit.word)
            assertTrue(engine.article(hit).toHtml().contains("hello body"))
        } finally {
            engine.close()
        }
    }
}
