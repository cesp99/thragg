package to.eyed.thragg.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The completion menu's three pieces of arithmetic, none of which can be
 * checked by looking at a phone: what the query is and what matches it, where
 * the popup goes when the soft keyboard has taken the bottom of the screen,
 * and whether an answer that arrives late is still about the text in front of
 * the user.
 */
class CompletionsTest {

    private fun item(
        label: String,
        filter: String = label,
        sort: String = label,
        kind: CompletionKind? = null,
        insert: String = label,
        snippet: Boolean = false,
        edit: LspRange? = null,
        preselect: Boolean = false,
    ) = CompletionItem(
        index = 0,
        label = label,
        detail = null,
        kind = kind,
        insertText = insert,
        isSnippet = snippet,
        filterText = filter,
        sortText = sort,
        documentation = null,
        deprecated = false,
        preselect = preselect,
        edit = edit,
    )

    // ---- the query ----

    @Test
    fun theQueryIsTheIdentifierAlreadyTypedInFrontOfTheCaret() {
        assertEquals("push", completionQuery("    vec.push", 12))
        assertEquals("pu", completionQuery("    vec.push", 10))
        // Zed's `completion_query` gives nothing when the caret does not sit
        // inside a word (completions.rs:833-846) — which is where the menu
        // stands right after a trigger character, showing everything.
        assertEquals("", completionQuery("    vec.", 8))
        assertEquals("", completionQuery("", 0))
    }

    @Test
    fun theQueryStopsAtTheStartOfTheWordNotOfTheLine() {
        assertEquals("_x1", completionQuery("foo._x1", 7))
        assertEquals("", completionQuery("foo(", 4))
    }

    @Test
    fun wordsSplitAtCamelHumpsAndAtPunctuation() {
        assertEquals(listOf("get", "User", "Name"), splitWords("getUserName"))
        assertEquals(listOf("read_", "to_", "string"), splitWords("read_to_string"))
        assertEquals(listOf("HTTPServer"), splitWords("HTTPServer"))
    }

    // ---- the filter ----

    @Test
    fun aQueryThatIsNotASubsequenceDropsTheRow() {
        val rows = filterCompletions(listOf(item("push"), item("pop")), "psh")
        assertEquals(listOf("push"), rows.map { it.label })
    }

    @Test
    fun anExactMatchComesFirst() {
        val rows = filterCompletions(
            listOf(item("pushed"), item("push_within"), item("push")),
            "push",
        )
        assertEquals("push", rows.first().label)
    }

    @Test
    fun aQueryStartingAWordBeatsOneMerelyContainedInIt() {
        // `name` starts a word of `user_name` and none of `rename`, so Zed's
        // second tier takes `rename` below it whatever the scores say
        // (code_context_menus.rs:1560-1575).
        val rows = filterCompletions(listOf(item("rename"), item("user_name")), "name")
        assertEquals(listOf("user_name", "rename"), rows.map { it.label })
    }

    @Test
    fun theServersOwnOrderBreaksTies() {
        val rows = filterCompletions(
            listOf(item("push", sort = "0002push"), item("push", sort = "0001push")),
            "",
        )
        assertEquals(listOf("0001push", "0002push"), rows.map { it.sortText })
    }

    @Test
    fun anUpperCaseQueryIsMatchedCaseSensitively() {
        // Zed's smart case: a lower-case query matches either case, a query
        // with a capital in it does not (completions.rs:1359-1400).
        assertEquals(2, filterCompletions(listOf(item("Push"), item("push")), "pu").size)
        assertEquals(listOf("Push"), filterCompletions(listOf(item("Push"), item("push")), "Pu").map { it.label })
    }

    @Test
    fun anEmptyQueryKeepsEverything() {
        val rows = filterCompletions(listOf(item("b"), item("a")), "")
        assertEquals(2, rows.size)
    }

    @Test
    fun aShorterCandidateOutranksALongerOneOnTheSameHits() {
        val rows = filterCompletions(listOf(item("push_within_capacity"), item("push")), "push")
        assertEquals("push", rows.first().label)
    }

    // ---- snippets ----

    @Test
    fun aSnippetLosesItsPlaceholdersAndKeepsTheirDefaults() {
        assertEquals("push(value)" to 5, expandSnippet("push(\${1:value})"))
        assertEquals("foo()" to 4, expandSnippet("foo(\$0)"))
        assertEquals("plain" to 5, expandSnippet("plain"))
    }

    @Test
    fun anEscapedDollarIsALiteralOne() {
        assertEquals("cost \$5" to 7, expandSnippet("cost \\\$5"))
    }

    @Test
    fun aMalformedSnippetIsLeftAsItStands() {
        // Better a literal `${` on screen than a truncated insert: the server
        // said this was a snippet and it is not one.
        assertEquals("\${oops" to 6, expandSnippet("\${oops"))
    }

    // ---- placement ----

    private fun place(
        caretTop: Float,
        areaBottom: Float,
        caretX: Float = 40f,
        wantedHeight: Float = 200f,
    ) = placeMenuAtCaret(
        caretX = caretX,
        caretTop = caretTop,
        lineHeight = 20f,
        wantedWidth = 280f,
        wantedHeight = wantedHeight,
        minHeight = 60f,
        areaWidth = 1000f,
        areaTop = 0f,
        areaBottom = areaBottom,
    )

    @Test
    fun theMenuOpensBelowTheCaretsLineWhenThereIsRoom() {
        val placed = place(caretTop = 100f, areaBottom = 1000f)
        assertFalse(placed.above)
        // The bottom of the caret's row, so the menu never covers the line
        // being typed on.
        assertEquals(120f, placed.y, 0.01f)
        assertEquals(200f, placed.height, 0.01f)
    }

    @Test
    fun theSoftKeyboardIsWhatFlipsItAboveTheCaret() {
        // The deviation P5-3 is required to make: Zed measures the room below
        // against the bottom of the editor, and on a phone the bottom of the
        // editor is underneath the IME. Same caret, same pane — only the
        // bottom the menu is allowed to use changes.
        val keyboardDown = place(caretTop = 250f, areaBottom = 1000f)
        assertFalse(keyboardDown.above)

        val keyboardUp = place(caretTop = 250f, areaBottom = 300f)
        assertTrue(keyboardUp.above)
        // It rises from the *top* of the caret's row, so the row stays visible.
        assertEquals(250f - keyboardUp.height, keyboardUp.y, 0.01f)
        assertEquals(200f, keyboardUp.height, 0.01f)
    }

    @Test
    fun aMenuThatFitsNeitherWayTakesTheRoomierSide() {
        val placed = place(caretTop = 10f, areaBottom = 60f)
        assertFalse(placed.above)
        assertEquals(30f, placed.height, 0.01f)
    }

    @Test
    fun aShortMenuStaysBelowRatherThanFlipping() {
        // Zed only flips when the wanted height does not fit *and* there is
        // more room above (element.rs:4104-4110).
        val placed = place(caretTop = 250f, areaBottom = 300f, wantedHeight = 25f)
        assertFalse(placed.above)
        assertEquals(270f, placed.y, 0.01f)
    }

    @Test
    fun theRightEdgeSnapsInsideThePane() {
        assertEquals(720f, place(caretTop = 0f, areaBottom = 1000f, caretX = 900f).x, 0.01f)
        assertEquals(40f, place(caretTop = 0f, areaBottom = 1000f, caretX = 40f).x, 0.01f)
    }

    // ---- the answers ----

    private fun answerJson(
        state: String = "done",
        bufferId: Long = 12,
        row: Int = 4,
        col: Int = 8,
        bufferVersion: String = "40",
        payload: String = "null",
    ) = """
        {"id":7,"kind":"completion","state":"$state","version":2,"buffer_id":$bufferId,
         "row":$row,"col_utf16":$col,"buffer_version":$bufferVersion,"payload":$payload}
    """.trimIndent()

    @Test
    fun anAnswerCarriesWhereAndWhenItWasAsked() {
        val answer = LspAnswer.parse(answerJson())!!
        assertEquals(LspRequestState.Done, answer.state)
        assertEquals(12L, answer.bufferId)
        assertEquals(4, answer.row)
        assertEquals(8, answer.colUtf16)
        assertEquals(40L, answer.bufferVersion)
    }

    @Test
    fun aBufferVersionOfNullIsNotZero() {
        val answer = LspAnswer.parse(answerJson(bufferVersion = "null"))!!
        assertNull(answer.bufferVersion)
        // 0 is a real buffer version, so a null one can never be mistaken for
        // a match against a buffer that has not been edited yet.
        assertFalse(answer.describes(12L, 0L, 4, 8))
    }

    @Test
    fun aHoverAnswerIsDroppedOnceTheBufferHasMoved() {
        val answer = LspAnswer.parse(answerJson())!!
        assertTrue(answer.describes(12L, 40L, 4, 8))
        assertFalse(answer.describes(12L, 41L, 4, 8))
        assertFalse(answer.describes(13L, 40L, 4, 8))
        assertFalse(answer.describes(12L, 40L, 4, 9))
    }

    @Test
    fun onlyADoneAnswerIsAnAnswer() {
        for (state in listOf("timeout", "unavailable", "cancelled", "pending")) {
            val answer = LspAnswer.parse(answerJson(state = state))!!
            assertFalse(state, answer.describes(12L, 40L, 4, 8))
            assertFalse(state, answer.stillDescribes(12L, 4, 8, "        push"))
        }
    }

    @Test
    fun aCompletionListSurvivesTheWordGrowingUnderIt() {
        val answer = LspAnswer.parse(answerJson(col = 8))!!
        // Asked at column 8, three more letters typed since: the list is
        // still about this word, and Zed re-filters rather than re-asking.
        assertTrue(answer.stillDescribes(12L, 4, 11, "    vec.push"))
        // A space, and it is about the word before this one.
        assertFalse(answer.stillDescribes(12L, 4, 11, "    vec.p h"))
        // The caret went backwards past where the question was asked.
        assertFalse(answer.stillDescribes(12L, 4, 7, "    vec.push"))
        // Another row entirely.
        assertFalse(answer.stillDescribes(12L, 5, 11, "    vec.push"))
    }

    @Test
    fun aCompletionListIsNotDroppedForTheBufferMoving() {
        // Unlike hover: the buffer moving is the user typing the query, which
        // is the one thing that must not throw the list away.
        val answer = LspAnswer.parse(answerJson())!!
        assertFalse(answer.describes(12L, 41L, 4, 8))
        assertTrue(answer.stillDescribes(12L, 4, 10, "        pushed"))
    }

    @Test
    fun aForgottenIdIsCancelledAndNothingElse() {
        val answer = LspAnswer.forgotten(9L)
        assertEquals(LspRequestState.Cancelled, answer.state)
        assertNull(answer.kind)
        assertFalse(answer.describes(12L, 40L, 4, 8))
    }

    // ---- the payload ----

    @Test
    fun theThreeNeverNullFieldsFallBackToTheLabel() {
        val list = CompletionList.parse(
            org.json.JSONObject(
                """
                {"is_incomplete":true,"items":[
                  {"label":"push","detail":null,"kind":"method","insert_text":"push",
                   "is_snippet":false,"filter_text":"push","sort_text":"0001push",
                   "documentation":null,"deprecated":false,"preselect":true,
                   "edit":{"row":4,"col_utf16":8,"end_row":4,"end_col_utf16":10}}]}
                """.trimIndent()
            )
        )
        assertTrue(list.isIncomplete)
        val item = list.items.single()
        assertEquals("push", item.insertText)
        assertEquals(CompletionKind.Method, item.kind)
        assertNull(item.detail)
        assertTrue(item.preselect)
        assertEquals(LspRange(4, 8, 4, 10), item.edit)
    }

    @Test
    fun anItemWithNothingButALabelIsComplete() {
        val list = CompletionList.parse(
            org.json.JSONObject("""{"items":[{"label":"push"}]}""")
        )
        val item = list.items.single()
        assertEquals("push", item.insertText)
        assertEquals("push", item.filterText)
        assertEquals("push", item.sortText)
        assertNull(item.kind)
        assertNull(item.edit)
        assertFalse(list.isIncomplete)
    }

    @Test
    fun anEmptyDoneAnswerIsARealAnswer() {
        // "no completions here" — the payload is present and its list is
        // empty, which is not the same as no payload at all.
        assertTrue(CompletionList.parse(org.json.JSONObject("""{"items":[]}""")).items.isEmpty())
        assertTrue(CompletionList.parse(null).items.isEmpty())
    }
}
