package to.eyed.seeker.code.ui.agent.spettro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.seeker.code.core.AgentTurnUsage
import to.eyed.seeker.code.core.AgentUsage

/**
 * The gauge's arithmetic, which is where its lies would live.
 *
 * `usage_update` arrives after every LLM request inside a turn, so these
 * functions run several times a second on a working session; every one of
 * them has an end of its range that reads wrong if it is written the obvious
 * way, and none of those ends is reachable in a screenshot.
 */
class ContextGaugeTest {

    // -- the window ---------------------------------------------------------

    @Test
    fun aZeroWindowIsIgnoredRatherThanDividedBy() {
        val usage = AgentUsage(used = 4_000, size = 0)
        // AgentUsage.fraction already refuses to divide; what matters here is
        // that the sheet prints no line at all rather than "4.0k of 0 tokens".
        assertEquals(0f, usage.fraction, 0f)
        assertNull(contextTokensLine(usage))
        assertNull(contextTokensLine(null))
    }

    @Test
    fun aNegativeWindowIsAlsoNoWindow() {
        assertNull(contextTokensLine(AgentUsage(used = 10, size = -1)))
    }

    @Test
    fun theTokensLineIsBothNumbersToOneDecimal() {
        assertEquals(
            "168.2k of 200.0k tokens",
            contextTokensLine(AgentUsage(used = 168_200, size = 200_000)),
        )
    }

    // -- the percent --------------------------------------------------------

    @Test
    fun aNonZeroFractionUnderOnePercentNeverPrintsZero() {
        assertEquals("<1%", contextPercentLabel(0.004f))
        assertEquals("<1%", contextPercentLabel(0.0001f))
        // Genuinely nothing is still nothing.
        assertEquals("0%", contextPercentLabel(0f))
    }

    @Test
    fun anAlmostFullWindowNeverPrintsAHundred() {
        assertEquals("99%", contextPercentLabel(0.996f))
        assertEquals("100%", contextPercentLabel(1f))
        // And past the end it is still 100, not 104.
        assertEquals("100%", contextPercentLabel(1.04f))
    }

    @Test
    fun thePercentRounds() {
        assertEquals("41%", contextPercentLabel(0.4149f))
        assertEquals("42%", contextPercentLabel(0.4151f))
        assertEquals("84%", contextPercentLabel(0.841f))
    }

    // -- token formatting ---------------------------------------------------

    @Test
    fun tokensFormatByMagnitude() {
        assertEquals("0", formatTokens(0))
        assertEquals("999", formatTokens(999))
        assertEquals("1.0k", formatTokens(1_000))
        assertEquals("3.4k", formatTokens(3_412))
        assertEquals("412.8k", formatTokens(412_800))
        assertEquals("1.2M", formatTokens(1_240_000))
    }

    @Test
    fun aCountThatRoundsPastAThousandKPromotesToMegatokens() {
        // 999_950 formats as "1000.0k" if you only ever divide by 1e3.
        assertEquals("1.0M", formatTokens(999_950))
    }

    @Test
    fun aNegativeCountReadsAsZeroRatherThanAsMinus() {
        assertEquals("0", formatTokens(-5))
    }

    // -- severity -----------------------------------------------------------

    @Test
    fun severityMatchesTheDocumentedThresholds() {
        assertEquals(ContextSeverity.CALM, contextSeverity(0f))
        assertEquals(ContextSeverity.CALM, contextSeverity(0.7499f))
        assertEquals(ContextSeverity.WARM, contextSeverity(0.75f))
        assertEquals(ContextSeverity.WARM, contextSeverity(0.8999f))
        assertEquals(ContextSeverity.FULL, contextSeverity(0.90f))
        assertEquals(ContextSeverity.FULL, contextSeverity(1f))
    }

    @Test
    fun theActionRowAppearsExactlyWhereTheAmberDoes() {
        assertFalse(showsCompactActions(0.74f))
        assertTrue(showsCompactActions(0.75f))
        assertNull(contextAdviceLine(0.74f))
        assertEquals("Nearly full — compact soon", contextAdviceLine(0.84f))
        assertTrue(contextAdviceLine(0.95f)!!.startsWith("Full — compact now"))
    }

    @Test
    fun theComposerWarningArrivesBeforeTheRedRing() {
        // 0.85, not AgentUsage.isNearlyFull's 0.90: the strip is the one that
        // has to leave room to act on it.
        assertFalse(showsComposerWarning(0.849f))
        assertTrue(showsComposerWarning(0.85f))
        assertFalse(AgentUsage(used = 85, size = 100).isNearlyFull)
    }

    // -- the two derived lines ----------------------------------------------

    @Test
    fun totalProcessedIsShownOnlyWhenItExceedsOccupancy() {
        // Before a compaction the two track each other and the second line
        // would be a restatement of the first.
        assertNull(totalProcessedLine(AgentUsage(used = 168_200, size = 200_000, tokensUsed = 168_200)))
        assertNull(totalProcessedLine(AgentUsage(used = 168_200, size = 200_000, tokensUsed = 4_000)))
        assertEquals(
            "Total processed: 412.8k tokens",
            totalProcessedLine(AgentUsage(used = 168_200, size = 200_000, tokensUsed = 412_800)),
        )
    }

    @Test
    fun anAgentThatSendsNoSpendGetsNoSpendLine() {
        assertNull(totalProcessedLine(AgentUsage(used = 10, size = 100)))
        assertNull(totalProcessedLine(null))
    }

    @Test
    fun cacheHitsCountBothHalvesOfTheCache() {
        val turn = AgentTurnUsage(
            inputTokens = 1_000,
            outputTokens = 500,
            totalTokens = 11_500,
            cachedReadTokens = 9_000,
            cachedWriteTokens = 0,
        )
        // 9000 / (1000 + 9000 + 0) = 90 %.
        assertEquals("Cache hits: 90%", cacheHitsLine(turn))
    }

    @Test
    fun aTurnThatOnlyFilledTheCacheDoesNotReadAsAllHits() {
        val turn = AgentTurnUsage(
            inputTokens = 0,
            outputTokens = 200,
            totalTokens = 9_200,
            cachedReadTokens = 0,
            cachedWriteTokens = 9_000,
        )
        assertEquals("Cache hits: 0%", cacheHitsLine(turn))
    }

    @Test
    fun aTurnWithNoPromptAtAllHasNoRate() {
        val turn = AgentTurnUsage(0, 0, 0, 0, 0)
        assertNull(turn.cacheHitRate)
        assertNull(cacheHitsLine(turn))
        assertNull(cacheHitsLine(null))
    }
}
