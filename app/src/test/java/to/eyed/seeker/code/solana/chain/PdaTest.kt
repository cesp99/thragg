package to.eyed.seeker.code.solana.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PdaTest {

    private val loader = Pubkey.of("BPFLoaderUpgradeab1e11111111111111111111111")
    private val systemProgram = Pubkey.of("11111111111111111111111111111111")

    @Test
    fun `real public keys are on the curve`() {
        assertTrue(Ed25519.isOnCurve(Keypair.generate().publicKey.bytes))
        assertTrue(Ed25519.isOnCurve(Pubkey.of("4zvwRjXUKGfvwnParsHAS3HuSVzV5cA4McphgmoCtajS").bytes))
        // y = 0 decodes to x = sqrt(-1): the system program id is a valid point.
        // (Vanity ids like the loader are not keypairs and need not be; the loader is off-curve.)
        assertTrue(Ed25519.isOnCurve(systemProgram.bytes))
    }

    @Test
    fun `a program-derived address is off the curve and anything else is too`() {
        val (pda, _) = Pda.findProgramAddress(listOf("helloWorld".toByteArray()), systemProgram)
        assertFalse(Ed25519.isOnCurve(pda.bytes))
        assertFalse(Ed25519.isOnCurve(ByteArray(31)))
        assertFalse(Ed25519.isOnCurve(ByteArray(33)))
    }

    @Test
    fun `matches the example in the Solana docs`() {
        // solana.com/docs/core/pda: seed "helloWorld", program 111...111.
        val (pda, bump) = Pda.findProgramAddress(listOf("helloWorld".toByteArray()), systemProgram)
        assertEquals("46GZzzetjCURsdFPb7rcnspbEMnCBXe9kpjrsZAkKb6X", pda.base58)
        assertEquals(254, bump)
    }

    @Test
    fun `programdata address matches programs deployed on mainnet`() {
        // Metaplex Token Metadata, as cloned by `solana-test-validator --clone` in the wild.
        assertEquals(
            "PwDiXFxQsGra4sFFTT8r1QWRMd4vfumiWC1jfWNfdYT",
            Pda.programDataAddress(Pubkey.of("metaqbxxUerdq28cj1RbAWkYQm3ybzjb6a8bt518x1s")).base58,
        )
        // Token-2022, bump 255 on the first try.
        val token2022 = Pubkey.of("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb")
        assertEquals("DoU57AYuPFu2QU514RktNPG22QhApEjnKxnBcu4BHDTY", Pda.programDataAddress(token2022).base58)
        assertEquals(255, Pda.findProgramAddress(listOf(token2022.bytes), loader).second)
    }

    @Test
    fun `programdata address is find_program_address over the loader`() {
        val program = Keypair.generate().publicKey
        val (expected, _) = Pda.findProgramAddress(listOf(program.bytes), loader)
        assertEquals(expected, Pda.programDataAddress(program))
    }

    @Test
    fun `deterministic, and different seeds give different addresses`() {
        val program = Pubkey.of("metaqbxxUerdq28cj1RbAWkYQm3ybzjb6a8bt518x1s")
        val seeds = listOf("metadata".toByteArray(), program.bytes)
        val first = Pda.findProgramAddress(seeds, program)
        val second = Pda.findProgramAddress(seeds, program)
        assertEquals(first.first, second.first)
        assertEquals(first.second, second.second)
        assertTrue(first.second in 0..255)
        assertNotEquals(first.first, Pda.findProgramAddress(listOf("metadatb".toByteArray(), program.bytes), program).first)
        assertNotEquals(first.first, Pda.findProgramAddress(seeds, systemProgram).first)
        assertNotEquals(first.first, Pda.findProgramAddress(emptyList(), program).first)
    }

    @Test
    fun `refuses seeds the runtime would refuse`() {
        try {
            Pda.findProgramAddress(listOf(ByteArray(33)), systemProgram)
            fail("a seed is at most 32 bytes")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            Pda.findProgramAddress(List(17) { ByteArray(1) }, systemProgram)
            fail("at most 16 seeds")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
