package to.eyed.seeker.code.solana.chain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The JSON-RPC parsers and the pacer's retry rule, against response text
 * shaped like the public endpoints' real answers. No network, no Android.
 */
class RpcParseTest {

    private val loader = "BPFLoaderUpgradeab1e11111111111111111111111"

    @Test
    fun `account info decodes base64 data and reads the owner`() {
        val json = """
            {"jsonrpc":"2.0","result":{"context":{"slot":100},"value":{
              "lamports":1141440,"owner":"$loader","data":["AgAAAA==","base64"],"executable":true,"rentEpoch":0,"space":4}},"id":1}
        """.trimIndent()
        val info = Rpc.parseAccountInfo(json)
        assertNotNull(info)
        info!!
        assertEquals(1_141_440L, info.lamports)
        assertEquals(loader, info.owner.base58)
        assertArrayEquals(byteArrayOf(2, 0, 0, 0), info.data)
        assertTrue(info.executable)
        assertEquals(4L, info.space)
    }

    @Test
    fun `account info is null when there is no account`() {
        val json = """{"jsonrpc":"2.0","result":{"context":{"slot":1},"value":null},"id":1}"""
        assertNull(Rpc.parseAccountInfo(json))
    }

    @Test
    fun `account info falls back to the data length when space is absent`() {
        val json = """
            {"jsonrpc":"2.0","result":{"context":{"slot":1},"value":{
              "lamports":5,"owner":"11111111111111111111111111111111","data":["","base64"],"executable":false}},"id":1}
        """.trimIndent()
        val info = Rpc.parseAccountInfo(json)!!
        assertEquals(0L, info.space)
        assertEquals(0, info.data.size)
        assertFalse(info.executable)
    }

    @Test
    fun `blockhash carries its last valid height`() {
        val json = """
            {"jsonrpc":"2.0","result":{"context":{"slot":2792},"value":{
              "blockhash":"EkSnNWid2cvwEVnVx9aBqawnmiCNiDgp3gUdkDPTKN1N","lastValidBlockHeight":3090}},"id":1}
        """.trimIndent()
        val hash = Rpc.parseBlockhash(json)
        assertEquals("EkSnNWid2cvwEVnVx9aBqawnmiCNiDgp3gUdkDPTKN1N", hash.blockhash)
        assertEquals(3090L, hash.lastValidBlockHeight)
    }

    @Test
    fun `statuses keep nulls in place and render errors as text`() {
        val json = """
            {"jsonrpc":"2.0","result":{"context":{"slot":82},"value":[
              {"slot":72,"confirmations":10,"err":null,"status":{"Ok":null},"confirmationStatus":"confirmed"},
              null,
              {"slot":73,"confirmations":null,"err":{"InstructionError":[0,{"Custom":1}]},"confirmationStatus":"finalized"},
              {"slot":74,"confirmations":0,"err":null,"confirmationStatus":"processed"}
            ]},"id":1}
        """.trimIndent()
        val statuses = Rpc.parseStatuses(json)
        assertEquals(4, statuses.size)

        val ok = statuses[0]!!
        assertEquals(72L, ok.slot)
        assertEquals(10L, ok.confirmations)
        assertNull(ok.err)
        assertEquals("confirmed", ok.confirmationStatus)
        assertTrue(ok.confirmed)

        assertNull(statuses[1])

        val failed = statuses[2]!!
        assertNull(failed.confirmations)
        assertTrue(failed.err!!.contains("InstructionError"))
        assertTrue(failed.err!!.contains("Custom"))
        assertFalse(failed.confirmed)

        val processed = statuses[3]!!
        assertNull(processed.err)
        assertFalse(processed.confirmed)
    }

    @Test
    fun `numbers come bare or wrapped in a context`() {
        assertEquals(1233L, Rpc.parseResultLong("""{"jsonrpc":"2.0","result":1233,"id":1}"""))
        assertEquals(
            2_000_000_000L,
            Rpc.parseResultLong("""{"jsonrpc":"2.0","result":{"context":{"slot":1},"value":2000000000},"id":1}"""),
        )
        assertEquals(
            890_880L,
            Rpc.parseResultLong("""{"jsonrpc":"2.0","result":890880,"id":1}"""),
        )
    }

    @Test
    fun `strings are the signature a send returns`() {
        val sig = "2id3YC2jK9G5Wo2phDx4gJVAew8DcY5NAojnVuao8rkxwPYPe8cSwE5GzhEgJA2y8fVjDEo6iR6ykBvDxrTQrtpb"
        assertEquals(sig, Rpc.parseResultString("""{"jsonrpc":"2.0","result":"$sig","id":1}"""))
    }

    @Test
    fun `error envelope becomes an exception with its code`() {
        val json = """{"jsonrpc":"2.0","error":{"code":-32005,"message":"Node is behind by 42 slots","data":{"numSlotsBehind":42}},"id":1}"""
        val error = Rpc.errorOf(json)
        assertNotNull(error)
        assertEquals(-32005, error!!.code)
        assertNull(error.httpStatus)
        assertTrue(error.isTransient)
        assertEquals("Node is behind by 42 slots", error.message)
    }

    @Test
    fun `error envelope quotes the first program logs`() {
        val json = """
            {"jsonrpc":"2.0","error":{"code":-32002,
              "message":"Transaction simulation failed: Error processing Instruction 0: custom program error: 0x1",
              "data":{"err":{"InstructionError":[0,{"Custom":1}]},"logs":[
                "Program BPFLoaderUpgradeab1e11111111111111111111111 invoke [1]",
                "Program log: Buffer account too small",
                "Program BPFLoaderUpgradeab1e11111111111111111111111 failed: custom program error: 0x1",
                "one more line"]}},"id":1}
        """.trimIndent()
        val error = Rpc.errorOf(json)!!
        assertEquals(-32002, error.code)
        assertFalse(error.isTransient)
        val message = error.message!!
        assertTrue(message.startsWith("Transaction simulation failed"))
        assertTrue(message.contains("Program log: Buffer account too small"))
        assertTrue(message.contains("invoke [1]"))
        assertFalse(message.contains("one more line"))
    }

    @Test
    fun `no error member means no error`() {
        assertNull(Rpc.errorOf("""{"jsonrpc":"2.0","result":5,"id":1}"""))
        assertNull(Rpc.errorOf("<html>Too Many Requests</html>"))
        assertNull(Rpc.errorOf(""))
    }

    @Test
    fun `parsers throw the envelope's error rather than a shape complaint`() {
        val json = """{"jsonrpc":"2.0","error":{"code":-32602,"message":"Invalid param: WrongSize"},"id":1}"""
        try {
            Rpc.parseAccountInfo(json)
            fail("expected RpcException")
        } catch (error: RpcException) {
            assertEquals(-32602, error.code)
            assertEquals("Invalid param: WrongSize", error.message)
        }
        try {
            Rpc.parseResultLong(json)
            fail("expected RpcException")
        } catch (error: RpcException) {
            assertEquals(-32602, error.code)
        }
    }

    @Test
    fun `text that is not JSON-RPC is a local complaint without a code`() {
        try {
            Rpc.parseResultString("<html>502 Bad Gateway</html>")
            fail("expected RpcException")
        } catch (error: RpcException) {
            assertNull(error.code)
            assertNull(error.httpStatus)
            assertTrue(error.message!!.contains("not JSON-RPC"))
        }
    }

    @Test
    fun `only 429, the gateway trio and node-behind are transient`() {
        assertTrue(RpcException("slow down", httpStatus = 429).isTransient)
        assertTrue(RpcException("behind", code = -32005).isTransient)
        assertTrue(RpcException("bad gateway", httpStatus = 502).isTransient)
        assertTrue(RpcException("unavailable", httpStatus = 503).isTransient)
        assertTrue(RpcException("gateway timeout", httpStatus = 504).isTransient)
        assertFalse(RpcException("bad", httpStatus = 400).isTransient)
        assertFalse(RpcException("server error", httpStatus = 500).isTransient)
        assertFalse(RpcException("sim failed", code = -32002).isTransient)
        assertFalse(RpcException("blockhash expired").isTransient)
    }

    @Test
    fun `pacer retries transient failures and then gives up`() = runBlocking {
        val pacer = RpcPacer(backoffMs = listOf(1L, 1L, 1L))
        val calls = AtomicInteger()
        try {
            pacer.run {
                calls.incrementAndGet()
                throw RpcException("rate limited", httpStatus = 429)
            }
            fail("expected RpcException")
        } catch (error: RpcException) {
            assertEquals(429, error.httpStatus)
        }
        assertEquals(4, calls.get())
    }

    @Test
    fun `pacer waits out a Retry-After instead of its own backoff`() = runBlocking {
        // A one-second backoff list would take a second per attempt; the
        // endpoint's own zero-second answer is what gets honoured instead.
        val pacer = RpcPacer(backoffMs = listOf(60_000L, 60_000L))
        val calls = AtomicInteger()
        val started = System.currentTimeMillis()
        val value = pacer.run {
            if (calls.incrementAndGet() < 3) {
                throw RpcException("rate limited", httpStatus = 429, retryAfterSeconds = 0)
            }
            "ok"
        }
        assertEquals("ok", value)
        assertEquals(3, calls.get())
        assertTrue(System.currentTimeMillis() - started < 10_000L)
    }

    @Test
    fun `pacer returns the value once a retry succeeds`() = runBlocking {
        val pacer = RpcPacer(backoffMs = listOf(1L, 1L, 1L))
        val calls = AtomicInteger()
        val value = pacer.run {
            if (calls.incrementAndGet() < 3) throw IOException("connection reset")
            "signature"
        }
        assertEquals("signature", value)
        assertEquals(3, calls.get())
    }

    @Test
    fun `pacer does not retry what is simply wrong`() = runBlocking {
        val pacer = RpcPacer(backoffMs = listOf(1L, 1L, 1L))
        val calls = AtomicInteger()
        try {
            pacer.run {
                calls.incrementAndGet()
                throw RpcException("simulation failed", code = -32002)
            }
            fail("expected RpcException")
        } catch (error: RpcException) {
            assertEquals(-32002, error.code)
        }
        assertEquals(1, calls.get())
    }
}
