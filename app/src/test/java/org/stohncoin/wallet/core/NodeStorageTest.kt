package org.stohncoin.wallet.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NodeStorageTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun file(relative: String, bytes: Int): File {
        val target = File(temp.root, relative)
        target.parentFile?.mkdirs()
        target.writeBytes(ByteArray(bytes))
        return target
    }

    @Test
    fun `a missing data directory measures as empty`() {
        val usage = NodeStorage.measure(File(temp.root, "absent"))

        assertEquals(0L, usage.bytes)
        assertEquals(0, usage.files)
    }

    @Test
    fun `measure sums every file below the data directory`() {
        file("stohn.conf", 40)
        file("blocks/blk00000.dat", 1000)
        file("blocks/index/000001.ldb", 200)
        file("wallets/stohn-wallet/wallet.dat", 300)

        val usage = NodeStorage.measure(temp.root)

        assertEquals(1540L, usage.bytes)
        assertEquals(4, usage.files)
    }

    @Test
    fun `sizes are human readable in binary units`() {
        assertEquals("0 B", NodeStorage.format(0))
        assertEquals("1023 B", NodeStorage.format(1023))
        assertEquals("1.0 KiB", NodeStorage.format(1024))
        assertEquals("1.5 KiB", NodeStorage.format(1536))
        assertEquals("2.0 MiB", NodeStorage.format(2L * 1024 * 1024))
        assertEquals("5.4 GiB", NodeStorage.format(5500L * 1024 * 1024))
    }

    @Test
    fun `resetting the chain keeps the wallet and the config`() {
        val wallet = file("wallets/stohn-wallet/wallet.dat", 512)
        val conf = file("stohn.conf", 64)
        val cookie = file(".cookie", 32)
        file("blocks/blk00000.dat", 4096)
        file("blocks/index/000001.ldb", 1024)
        file("chainstate/000002.ldb", 2048)
        file("indexes/txindex/000003.ldb", 256)

        val freed = NodeStorage.resetChainData(temp.root)

        assertEquals(7424L, freed.bytes)
        assertEquals(4, freed.files)
        assertTrue(wallet.isFile)
        assertTrue(conf.isFile)
        assertTrue(cookie.isFile)
        assertFalse(File(temp.root, "blocks").exists())
        assertFalse(File(temp.root, "chainstate").exists())
        assertFalse(File(temp.root, "indexes").exists())
    }

    @Test
    fun `a second reset on a clean data directory frees nothing`() {
        file("wallets/stohn-wallet/wallet.dat", 512)

        val freed = NodeStorage.resetChainData(temp.root)

        assertEquals(0L, freed.bytes)
        assertEquals(0, freed.files)
        assertTrue(File(temp.root, "wallets/stohn-wallet/wallet.dat").isFile)
    }

    @Test
    fun `resetting a data directory that does not exist is refused`() {
        val missing = File(temp.root, "absent")

        val failure = runCatching { NodeStorage.resetChainData(missing) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }
}
