package org.stohncoin.wallet.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeTempTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `the temp directory is its own folder inside the cache`() {
        val dir = RuntimeTemp.directory(temp.root)

        assertEquals(File(temp.root, "proot-tmp"), dir)
        assertEquals(temp.root.canonicalFile, dir.canonicalFile.parentFile)
    }

    @Test
    fun `prepare creates the temp directory`() {
        val dir = RuntimeTemp.prepare(temp.root)

        assertTrue(dir.isDirectory)
    }

    @Test
    fun `purge removes nested PRoot leftovers and reports them`() {
        val dir = RuntimeTemp.prepare(temp.root)
        File(dir, "proot-1234/loader").apply { parentFile?.mkdirs(); writeText("loader") }
        File(dir, ".l2s.tmp").writeText("stray")
        File(temp.root, "qr-share").apply { mkdirs(); File(this, "code.png").writeText("png") }
        File(temp.root, "stohn-restore-1.snapshot").writeText("snapshot")

        val removed = RuntimeTemp.purge(temp.root)

        assertEquals(2, removed)
        assertEquals(0, dir.listFiles()?.size ?: 0)
        assertTrue(File(temp.root, "qr-share/code.png").isFile)
        assertTrue(File(temp.root, "stohn-restore-1.snapshot").isFile)
    }

    @Test
    fun `purging an absent temp directory frees nothing and creates nothing`() {
        val removed = RuntimeTemp.purge(temp.root)

        assertEquals(0, removed)
        assertFalse(RuntimeTemp.directory(temp.root).exists())
    }

    @Test
    fun `purge is idempotent`() {
        val dir = RuntimeTemp.prepare(temp.root)
        File(dir, "loader").writeText("loader")

        assertEquals(1, RuntimeTemp.purge(temp.root))
        assertEquals(0, RuntimeTemp.purge(temp.root))
    }
}
