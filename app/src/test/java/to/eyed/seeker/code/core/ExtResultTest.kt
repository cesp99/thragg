package to.eyed.seeker.code.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `_spettro/…` envelope, decoded.
 *
 * JNI has no exception channel here, so every one of the nineteen extension
 * methods answers with the same envelope and this is the only place it is
 * read. Four outcomes rather than "ok or not", because the UI has four
 * different sentences and collapsing any two of them costs a user an accurate
 * one — most of all `-32601`, which means "your CLI is old", not "that
 * failed".
 */
class ExtResultTest {

    @Test
    fun anOkEnvelopeCarriesItsResultObject() {
        val result = ExtResult.parse("""{"ok":true,"result":{"connected":true,"modelCount":42}}""")
        assertTrue(result is ExtResult.Ok)
        assertEquals(42, (result as ExtResult.Ok).result.getInt("modelCount"))
    }

    /** The provider's own words survive intact; they are what is shown. */
    @Test
    fun anRpcErrorKeepsItsMessageVerbatim() {
        val result = ExtResult.parse(
            """{"ok":false,"code":-32603,"message":"key rejected (401)","data":{"error":"bad key"}}"""
        )
        assertTrue(result is ExtResult.Rpc)
        result as ExtResult.Rpc
        assertEquals(-32603, result.code)
        assertEquals("key rejected (401)", result.message)
        assertEquals("bad key", result.data?.getString("error"))
    }

    @Test
    fun methodNotFoundIsAVersionRatherThanAFailure() {
        val result = ExtResult.parse("""{"ok":false,"code":-32601,"message":"method not found"}""")
        assertEquals(ExtResult.Unsupported, result)
    }

    @Test
    fun codeZeroMeansTheCallNeverLeftThePhone() {
        val result = ExtResult.parse("""{"ok":false,"code":0,"message":"the agent is not running"}""")
        assertTrue(result is ExtResult.Offline)
        assertEquals("the agent is not running", (result as ExtResult.Offline).message)
    }

    /**
     * An answer that is not JSON is a fault on *this* side of the wire.
     * Reporting it as a rejected key would be a lie about somebody's key.
     */
    @Test
    fun anUnreadableEnvelopeIsOfflineAndNotAnError() {
        assertTrue(ExtResult.parse("") is ExtResult.Offline)
        assertTrue(ExtResult.parse("not json at all") is ExtResult.Offline)
    }

    /** A result that is not an object still gives decoders an object to read. */
    @Test
    fun aScalarResultIsWrappedRatherThanDropped() {
        val result = ExtResult.parse("""{"ok":true,"result":7}""")
        assertTrue(result is ExtResult.Ok)
        assertEquals(7, (result as ExtResult.Ok).result.getInt("result"))
    }

    /** An envelope with no `message` still has something to say. */
    @Test
    fun anErrorWithNoMessageStillReads() {
        val result = ExtResult.parse("""{"ok":false,"code":-32000}""")
        assertTrue(result is ExtResult.Rpc)
        assertTrue((result as ExtResult.Rpc).message.isNotBlank())
    }
}
