package to.eyed.thragg.solana.chain

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Who gets Transaction V1, what a V1 header has to carry, and the one-way
 * demotion when an endpoint cannot read it.
 */
class TxFormatPolicyTest {

    private val payer = Keypair.generate().publicKey
    private val other = Keypair.generate().publicKey

    @Before
    fun fresh() = TxFormatPolicy.reset()

    @After
    fun tidy() = TxFormatPolicy.reset()

    @Test
    fun `local keys get V1 until an endpoint refuses it, and then legacy for good`() {
        assertEquals(TxFormat.V1, TxFormatPolicy.local())
        assertFalse(TxFormatPolicy.isDemoted)
        val lines = ArrayList<String>()
        // A legacy refusal is not a demotion, nor is an unrelated V1 error.
        assertFalse(TxFormatPolicy.refusal(RpcException("failed to deserialize"), TxFormat.Legacy, lines::add))
        assertFalse(TxFormatPolicy.refusal(RpcException("custom program error: 0x1"), TxFormat.V1, lines::add))
        assertFalse(TxFormatPolicy.refusal(RpcException("slow down", httpStatus = 429), TxFormat.V1, lines::add))
        assertEquals(TxFormat.V1, TxFormatPolicy.local())
        assertTrue(lines.isEmpty())
        // The first real one flips the process and says so, once.
        assertTrue(TxFormatPolicy.refusal(RpcException("Transaction sanitize failure"), TxFormat.V1, lines::add))
        assertTrue(TxFormatPolicy.isDemoted)
        assertEquals(TxFormat.Legacy, TxFormatPolicy.local())
        assertEquals(1, lines.size)
        assertTrue(lines[0], "Transaction V1" in lines[0] && "legacy" in lines[0] && "sanitize failure" in lines[0])
        // A second refusal, of a V1 message compiled before the flip, still says "retry as legacy" but prints nothing more.
        assertTrue(TxFormatPolicy.refusal(RpcException("failed to deserialize"), TxFormat.V1, lines::add))
        assertEquals(1, lines.size)
        assertEquals(TxFormat.Legacy, TxFormatPolicy.local())
    }

    @Test
    fun `the wallet gets V1 only when its capabilities list version 1`() {
        // What MWA's GetCapabilitiesResult carries: strings and integers, in any order.
        assertEquals(TxFormat.V1, TxFormatPolicy.wallet(arrayOf<Any>("legacy", 0, 1)))
        assertEquals(TxFormat.V1, TxFormatPolicy.wallet(arrayOf<Any>(1)))
        assertEquals(TxFormat.Legacy, TxFormatPolicy.wallet(arrayOf<Any>("legacy", 0)))
        assertEquals(TxFormat.Legacy, TxFormatPolicy.wallet(arrayOf<Any>("legacy")))
        assertEquals(TxFormat.Legacy, TxFormatPolicy.wallet(arrayOf<Any>()))
        // A string "1" is not the integer 1; the spec's integers are integers.
        assertEquals(TxFormat.Legacy, TxFormatPolicy.wallet(arrayOf<Any>("1")))
        // Never asked, or the question failed: legacy.
        assertEquals(TxFormat.Legacy, TxFormatPolicy.wallet(null))
        // Demoted: legacy whatever the wallet said, since the endpoint would refuse it anyway.
        TxFormatPolicy.demote("test") {}
        assertEquals(TxFormat.Legacy, TxFormatPolicy.wallet(arrayOf<Any>("legacy", 0, 1)))
    }

    /**
     * A V1 header with the bits unset runs on zero compute units and loads
     * zero bytes of account data (agave `from_v1_config`, `unwrap_or(0)`),
     * so the config is never NONE for V1 and always NONE for legacy.
     */
    @Test
    fun `a V1 config carries the legacy defaults spelled out`() {
        val transfer = Loader.transfer(payer, other, 1L)
        val write = Loader.write(other, payer, 0, ByteArray(10))
        assertEquals(TxConfig.NONE, TxFormatPolicy.config(TxFormat.Legacy, listOf(transfer, write)))
        val config = TxFormatPolicy.config(TxFormat.V1, listOf(transfer, write))
        assertEquals(2 * TxFormatPolicy.BUILTIN_COMPUTE_UNITS, config.computeUnitLimit)
        assertEquals(TxConfig.MAX_LOADED_ACCOUNTS_DATA_SIZE, config.loadedAccountsDataSize)
        assertNull(config.priorityFeeLamports)
        assertNull(config.heapSize)
        assertEquals(0b1100L, config.mask)
        // A BPF program gets the runtime's per-instruction default; three of them, three of it.
        val claim = PowFaucet.claim(payer, other, PowFaucet.spec(3))
        assertEquals(3 * TxFormatPolicy.DEFAULT_COMPUTE_UNITS, TxFormatPolicy.defaultComputeUnits(List(3) { claim }))
        assertEquals(
            3 * TxFormatPolicy.DEFAULT_COMPUTE_UNITS + TxFormatPolicy.BUILTIN_COMPUTE_UNITS,
            TxFormatPolicy.config(TxFormat.V1, List(3) { claim } + transfer).computeUnitLimit,
        )
        // Never over the runtime's ceiling, whatever was asked or summed.
        assertEquals(TxConfig.MAX_COMPUTE_UNIT_LIMIT, TxFormatPolicy.defaultComputeUnits(List(64) { claim }))
        assertEquals(TxConfig.MAX_COMPUTE_UNIT_LIMIT, TxFormatPolicy.config(TxFormat.V1, listOf(transfer), computeUnitLimit = 5_000_000L).computeUnitLimit)
        // A caller's own measurement wins over the default.
        assertEquals(320_000L, TxFormatPolicy.config(TxFormat.V1, listOf(claim), computeUnitLimit = 320_000L).computeUnitLimit)
        // An empty message still asks for something sane.
        assertEquals(0L, TxFormatPolicy.config(TxFormat.V1, emptyList()).computeUnitLimit)
    }

    @Test
    fun `a V1 message compiled with the policy's config is what the deployer sends`() {
        val ix = Loader.write(other, payer, 0, ByteArray(100))
        val format = TxFormatPolicy.local()
        val message = Message.compile(payer, listOf(ix), Base58.encode(ByteArray(32)), format, TxFormatPolicy.config(format, listOf(ix)))
        assertEquals(TxFormat.V1, message.format)
        val back = Message.deserialize(message.serialize())
        assertEquals(message.config, back.config)
        assertEquals(TxFormatPolicy.BUILTIN_COMPUTE_UNITS, back.config.computeUnitLimit)
    }
}
