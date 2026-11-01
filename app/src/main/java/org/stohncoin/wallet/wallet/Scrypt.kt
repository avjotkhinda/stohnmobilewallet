package org.stohncoin.wallet.wallet

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * scrypt (RFC 7914), used as the backup-container key derivation function from container v2.
 *
 * The point of scrypt over PBKDF2 is memory hardness: an attacker with a GPU can run PBKDF2
 * iterations cheaply, but each scrypt guess must also fill 128 * N * r bytes, which is why the
 * backup container uses N = 2^15, r = 8 (32 MiB per guess).
 *
 * JCE has no scrypt provider on Android, so the algorithm is implemented here and pinned by the
 * RFC 7914 test vectors in `ScryptTest`. Nothing else in the app depends on this class.
 */
object Scrypt {
    private const val BLOCK_BYTES = 64
    private const val HMAC = "HmacSHA256"

    /**
     * Derives [length] bytes from [password] and [salt]. [n] must be a power of two above 2.
     * Rejects parameters outside sane ranges so a hostile file cannot ask for gigabytes of memory.
     */
    fun derive(password: CharArray, salt: ByteArray, n: Int, r: Int, p: Int, length: Int): ByteArray {
        require(password.isNotEmpty()) { "scrypt password must not be empty" }
        require(n > 2 && Integer.bitCount(n) == 1) { "scrypt N must be a power of two above 2" }
        require(r in 1..32) { "scrypt r is out of range" }
        require(p in 1..16) { "scrypt p is out of range" }
        require(length in 1..1024) { "scrypt output length is out of range" }
        val passwordBytes = String(password).toByteArray(Charsets.UTF_8)
        val blockLength = 128 * r
        try {
            val blocks = pbkdf2(passwordBytes, salt, 1, p * blockLength)
            for (i in 0 until p) {
                val offset = i * blockLength
                val mixed = romix(blocks.copyOfRange(offset, offset + blockLength), n, r)
                System.arraycopy(mixed, 0, blocks, offset, blockLength)
                mixed.fill(0)
            }
            return pbkdf2(passwordBytes, blocks, 1, length).also { blocks.fill(0) }
        } finally {
            passwordBytes.fill(0)
        }
    }

    private fun romix(block: ByteArray, n: Int, r: Int): ByteArray {
        val blockLength = 128 * r
        val scratch = ByteArray(n * blockLength)
        var x = block.copyOf()
        try {
            for (i in 0 until n) {
                System.arraycopy(x, 0, scratch, i * blockLength, blockLength)
                x = blockMix(x, r)
            }
            for (i in 0 until n) {
                val j = (readLongLe(x, (2 * r - 1) * BLOCK_BYTES) and (n - 1).toLong()).toInt()
                val offset = j * blockLength
                for (k in 0 until blockLength) x[k] = (x[k].toInt() xor scratch[offset + k].toInt()).toByte()
                x = blockMix(x, r)
            }
            return x
        } finally {
            scratch.fill(0)
        }
    }

    private fun blockMix(input: ByteArray, r: Int): ByteArray {
        val output = ByteArray(128 * r)
        var x = input.copyOfRange((2 * r - 1) * BLOCK_BYTES, 2 * r * BLOCK_BYTES)
        val mixed = ByteArray(BLOCK_BYTES)
        for (i in 0 until 2 * r) {
            for (k in 0 until BLOCK_BYTES) {
                mixed[k] = (x[k].toInt() xor input[i * BLOCK_BYTES + k].toInt()).toByte()
            }
            salsa208(mixed)
            System.arraycopy(mixed, 0, x, 0, BLOCK_BYTES)
            val target = if (i % 2 == 0) (i / 2) * BLOCK_BYTES else (r + i / 2) * BLOCK_BYTES
            System.arraycopy(x, 0, output, target, BLOCK_BYTES)
        }
        return output
    }

    /** Salsa20/8 core over one 64-byte block, in place. */
    private fun salsa208(block: ByteArray) {
        val x = IntArray(16) { readIntLe(block, it * 4) }
        val original = x.copyOf()
        repeat(4) {
            x[4] = x[4] xor rotl(x[0] + x[12], 7)
            x[8] = x[8] xor rotl(x[4] + x[0], 9)
            x[12] = x[12] xor rotl(x[8] + x[4], 13)
            x[0] = x[0] xor rotl(x[12] + x[8], 18)
            x[9] = x[9] xor rotl(x[5] + x[1], 7)
            x[13] = x[13] xor rotl(x[9] + x[5], 9)
            x[1] = x[1] xor rotl(x[13] + x[9], 13)
            x[5] = x[5] xor rotl(x[1] + x[13], 18)
            x[14] = x[14] xor rotl(x[10] + x[6], 7)
            x[2] = x[2] xor rotl(x[14] + x[10], 9)
            x[6] = x[6] xor rotl(x[2] + x[14], 13)
            x[10] = x[10] xor rotl(x[6] + x[2], 18)
            x[3] = x[3] xor rotl(x[15] + x[11], 7)
            x[7] = x[7] xor rotl(x[3] + x[15], 9)
            x[11] = x[11] xor rotl(x[7] + x[3], 13)
            x[15] = x[15] xor rotl(x[11] + x[7], 18)
            x[1] = x[1] xor rotl(x[0] + x[3], 7)
            x[2] = x[2] xor rotl(x[1] + x[0], 9)
            x[3] = x[3] xor rotl(x[2] + x[1], 13)
            x[0] = x[0] xor rotl(x[3] + x[2], 18)
            x[6] = x[6] xor rotl(x[5] + x[4], 7)
            x[7] = x[7] xor rotl(x[6] + x[5], 9)
            x[4] = x[4] xor rotl(x[7] + x[6], 13)
            x[5] = x[5] xor rotl(x[4] + x[7], 18)
            x[11] = x[11] xor rotl(x[10] + x[9], 7)
            x[8] = x[8] xor rotl(x[11] + x[10], 9)
            x[9] = x[9] xor rotl(x[8] + x[11], 13)
            x[10] = x[10] xor rotl(x[9] + x[8], 18)
            x[12] = x[12] xor rotl(x[15] + x[14], 7)
            x[13] = x[13] xor rotl(x[12] + x[15], 9)
            x[14] = x[14] xor rotl(x[13] + x[12], 13)
            x[15] = x[15] xor rotl(x[14] + x[13], 18)
        }
        for (i in 0 until 16) writeIntLe(block, i * 4, x[i] + original[i])
    }

    private fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        require(password.isNotEmpty()) { "scrypt password must not be empty" }
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(password, HMAC))
        val output = ByteArray(length)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.update(salt)
            mac.update(byteArrayOf((counter ushr 24).toByte(), (counter ushr 16).toByte(), (counter ushr 8).toByte(), counter.toByte()))
            var u = mac.doFinal()
            val accumulator = u.copyOf()
            repeat(iterations - 1) {
                mac.update(u)
                u = mac.doFinal()
                for (i in accumulator.indices) accumulator[i] = (accumulator[i].toInt() xor u[i].toInt()).toByte()
            }
            val take = minOf(accumulator.size, length - offset)
            System.arraycopy(accumulator, 0, output, offset, take)
            offset += take
            counter++
        }
        return output
    }

    private fun rotl(value: Int, bits: Int): Int = (value shl bits) or (value ushr (32 - bits))

    private fun readIntLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun writeIntLe(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }

    private fun readLongLe(bytes: ByteArray, offset: Int): Long =
        (readIntLe(bytes, offset).toLong() and 0xFFFFFFFFL) or
            (readIntLe(bytes, offset + 4).toLong() shl 32)
}
