package to.eyed.thragg.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of diagnostics: reading the bridge's JSON, mapping its
 * severities, and the walk the draw pass makes over a visible window.
 *
 * All three are things a device test would only ever tell you about the one
 * file you happened to open. The parsing in particular is worth pinning: a
 * severity mapped wrong is an underline in the wrong colour on every file in
 * the language, and a window walk that starts in the wrong place is a
 * diagnostic that silently never draws.
 */
class DiagnosticsTest {

    // ---- severity ----

    @Test
    fun severitiesMapToZedsFourStatusColours() {
        assertEquals(DiagnosticSeverity.Error, DiagnosticSeverity.from("error"))
        assertEquals(DiagnosticSeverity.Warning, DiagnosticSeverity.from("warning"))
        assertEquals(DiagnosticSeverity.Info, DiagnosticSeverity.from("info"))
        assertEquals(DiagnosticSeverity.Hint, DiagnosticSeverity.from("hint"))
        assertEquals("error", DiagnosticSeverity.Error.token)
        assertEquals("warning", DiagnosticSeverity.Warning.token)
        assertEquals("info", DiagnosticSeverity.Info.token)
        assertEquals("hint", DiagnosticSeverity.Hint.token)
    }

    @Test
    fun anUnratedSeverityIsAWarning() {
        // The engine already reports a diagnostic the server left unrated as
        // `warning`; anything unexpected must land on the same value rather
        // than invent a fifth state.
        assertEquals(DiagnosticSeverity.Warning, DiagnosticSeverity.from(null))
        assertEquals(DiagnosticSeverity.Warning, DiagnosticSeverity.from(""))
        assertEquals(DiagnosticSeverity.Warning, DiagnosticSeverity.from("catastrophe"))
    }

    @Test
    fun severityOrdersMostSeriousFirst() {
        // The paint order depends on this: least severe first, so the most
        // severe lands on top (Zed sorts by Reverse(severity), element.rs:6166).
        assertTrue(DiagnosticSeverity.Error < DiagnosticSeverity.Warning)
        assertTrue(DiagnosticSeverity.Warning < DiagnosticSeverity.Info)
        assertTrue(DiagnosticSeverity.Info < DiagnosticSeverity.Hint)
    }

    // ---- bufferDiagnostics ----

    @Test
    fun parsesTheBridgesBufferPayload() {
        val parsed = BufferDiagnostics.parse(
            """
            {"version":1,"buffer_version":40,"stale":false,"rows":[
              {"row":3,"col_utf16":4,"end_row":3,"end_col_utf16":7,
               "severity":"error","message":"mismatched types",
               "source":"rustc","code":"E0308"}
            ]}
            """.trimIndent()
        )

        assertEquals(1L, parsed.version)
        assertEquals(40L, parsed.bufferVersion)
        assertEquals(1, parsed.rows.size)
        val row = parsed.rows[0]
        assertEquals(3, row.row)
        assertEquals(4, row.colUtf16)
        assertEquals(3, row.endRow)
        assertEquals(7, row.endColUtf16)
        assertEquals(DiagnosticSeverity.Error, row.severity)
        assertEquals("mismatched types", row.message)
        assertEquals("rustc", row.source)
        assertEquals("E0308", row.code)
        assertEquals("mismatched types (rustc E0308)", row.label)
    }

    @Test
    fun aNullBufferVersionSurvivesAsNullRatherThanZero() {
        // 0 is a real buffer version, which is why the bridge made this field
        // nullable — reading it as 0 would call a fresh buffer stale and a
        // stale one fresh.
        val parsed = BufferDiagnostics.parse(
            """{"version":2,"buffer_version":null,"stale":true,"rows":[]}"""
        )
        assertEquals(2L, parsed.version)
        assertNull(parsed.bufferVersion)
        assertTrue(parsed.isEmpty)
    }

    @Test
    fun versionZeroMeansNothingHasEverBeenPublished() {
        val parsed = BufferDiagnostics.parse("""{"version":0,"stale":false,"rows":[]}""")
        assertEquals(0L, parsed.version)
        assertTrue(parsed.isEmpty)
    }

    @Test
    fun optionalFieldsAndUnreadablePayloadsAreNotACrash() {
        val sparse = BufferDiagnostics.parse(
            """
            {"version":1,"buffer_version":0,"stale":false,"rows":[
              {"row":0,"col_utf16":0,"end_row":0,"end_col_utf16":0,
               "severity":"hint","message":"unused","source":null,"code":null}
            ]}
            """.trimIndent()
        )
        assertNull(sparse.rows[0].source)
        assertNull(sparse.rows[0].code)
        assertEquals("unused", sparse.rows[0].label)
        assertEquals(0L, sparse.bufferVersion)

        // A draw pass must not be the place a malformed payload is discovered.
        assertTrue(BufferDiagnostics.parse("not json at all").isEmpty)
        assertTrue(BufferDiagnostics.parse("").isEmpty)
        assertTrue(BufferDiagnostics.parse(null).isEmpty)
    }

    @Test
    fun aCodeThatArrivedAsANumberIsStillAString() {
        // LSP allows both forms and the bridge normalises to a string; this
        // pins that a number slipping through is still readable rather than
        // an exception in a poll.
        val parsed = BufferDiagnostics.parse(
            """
            {"version":1,"buffer_version":1,"stale":false,"rows":[
              {"row":0,"col_utf16":0,"end_row":0,"end_col_utf16":1,
               "severity":"warning","message":"m","source":"pylsp","code":605}
            ]}
            """.trimIndent()
        )
        assertEquals("605", parsed.rows[0].code)
        assertEquals("m (pylsp 605)", parsed.rows[0].label)
    }

    // ---- the visible-window walk ----

    private fun at(
        row: Int,
        col: Int = 0,
        endRow: Int = row,
        endCol: Int = col + 1,
        severity: DiagnosticSeverity = DiagnosticSeverity.Error,
    ) = Diagnostic(row, col, endRow, endCol, severity, "row $row")

    private fun listOfRows(vararg rows: Diagnostic) =
        BufferDiagnostics(version = 1L, bufferVersion = 1L, rows = rows.toList())

    @Test
    fun theWindowWalkPaintsExactlyTheRowsOnScreen() {
        val diagnostics = listOfRows(at(0), at(5), at(10), at(11), at(40))

        val painted = ArrayList<Int>()
        diagnostics.forEachIn(5, 11) { painted.add(it.row) }

        assertEquals(listOf(5, 10, 11), painted)
    }

    @Test
    fun theWalkStartsAtTheWindowRatherThanAtTheTopOfTheFile() {
        // The point of the binary search: a window near the end of a long
        // list must not touch the rows above it.
        val rows = (0 until 1000).map { at(it) }
        val diagnostics = BufferDiagnostics(1L, 1L, rows)

        assertEquals(0, diagnostics.maxRowSpan)
        assertEquals(900, diagnostics.firstIndexFor(900))

        val painted = ArrayList<Int>()
        diagnostics.forEachIn(900, 902) { painted.add(it.row) }
        assertEquals(listOf(900, 901, 902), painted)
    }

    @Test
    fun aDiagnosticStartingAboveTheWindowStillPaintsInIt() {
        // Sorted by *start*, so a tall diagnostic that begins off-screen is
        // in front of the window's first index. `maxRowSpan` is what lets the
        // search step back far enough — and no further.
        val diagnostics = listOfRows(
            at(row = 2, endRow = 40),
            at(row = 10),
            at(row = 50),
        )

        assertEquals(38, diagnostics.maxRowSpan)
        val painted = ArrayList<Int>()
        diagnostics.forEachIn(20, 30) { painted.add(it.row) }
        assertEquals(listOf(2), painted)
    }

    @Test
    fun theWalkStopsAtTheBottomOfTheWindow() {
        val diagnostics = listOfRows(at(1), at(2), at(3), at(4), at(5))
        var visited = 0
        diagnostics.forEachIn(1, 2) { visited++ }
        // Two painted, and the walk stopped rather than reading rows 3-5.
        assertEquals(2, visited)
    }

    @Test
    fun anEmptyListWalksNothing() {
        var visited = 0
        BufferDiagnostics.EMPTY.forEachIn(0, 100) { visited++ }
        assertEquals(0, visited)
    }

    // ---- at the cursor ----

    @Test
    fun theCursorTakesTheMostSevereThenTheTightestDiagnostic() {
        val wide = Diagnostic(4, 0, 4, 40, DiagnosticSeverity.Warning, "wide warning")
        val narrow = Diagnostic(4, 3, 4, 8, DiagnosticSeverity.Warning, "narrow warning")
        val error = Diagnostic(4, 2, 4, 30, DiagnosticSeverity.Error, "the error")
        val diagnostics = BufferDiagnostics(1L, 1L, listOf(wide, error, narrow))

        assertEquals(error, diagnostics.at(4, 5))
    }

    @Test
    fun aZeroWidthDiagnosticDescribesNothingAtTheCursor() {
        // Zed drops empty ranges before `min_by_key`, or the shortest range
        // always wins and always says nothing (items.rs:214).
        val empty = Diagnostic(4, 5, 4, 5, DiagnosticSeverity.Error, "nothing")
        val real = Diagnostic(4, 0, 4, 10, DiagnosticSeverity.Warning, "something")
        val diagnostics = BufferDiagnostics(1L, 1L, listOf(real, empty))

        assertEquals(real, diagnostics.at(4, 5))
    }

    @Test
    fun theCursorOutsideEveryRangeHasNoDiagnostic() {
        val diagnostics = listOfRows(at(row = 4, col = 0, endCol = 3))
        assertNull(diagnostics.at(4, 9))
        assertNull(diagnostics.at(7, 0))
    }

    // ---- next / previous ----

    @Test
    fun nextAndPreviousWrapAtBothEnds() {
        val first = at(1)
        val middle = at(5)
        val last = at(9)
        val diagnostics = BufferDiagnostics(1L, 1L, listOf(first, middle, last))

        assertEquals(middle, diagnostics.next(1, 0))
        assertEquals(last, diagnostics.next(5, 0))
        // Past the last one, round to the first — Zed chains `after` with
        // `before` (diagnostics.rs:220-244).
        assertEquals(first, diagnostics.next(9, 0))
        assertEquals(first, diagnostics.next(400, 0))

        assertEquals(middle, diagnostics.previous(9, 0))
        assertEquals(first, diagnostics.previous(5, 0))
        assertEquals(last, diagnostics.previous(1, 0))
        assertEquals(last, diagnostics.previous(0, 0))
    }

    @Test
    fun nextComparesColumnsAndNotJustRows() {
        val early = at(row = 3, col = 2, endCol = 4)
        val late = at(row = 3, col = 20, endCol = 24)
        val diagnostics = BufferDiagnostics(1L, 1L, listOf(early, late))

        assertEquals(late, diagnostics.next(3, 2))
        assertEquals(early, diagnostics.previous(3, 20))
    }

    @Test
    fun navigatingAnEmptyListGoesNowhere() {
        assertNull(BufferDiagnostics.EMPTY.next(0, 0))
        assertNull(BufferDiagnostics.EMPTY.previous(0, 0))
        assertNull(BufferDiagnostics.EMPTY.onRow(0))
    }

    @Test
    fun theRowMarkTakesTheWorstDiagnosticOnTheRow() {
        val diagnostics = listOfRows(
            at(row = 7, col = 0, severity = DiagnosticSeverity.Hint),
            at(row = 7, col = 4, severity = DiagnosticSeverity.Error),
            at(row = 7, col = 9, severity = DiagnosticSeverity.Warning),
        )
        assertEquals(DiagnosticSeverity.Error, diagnostics.onRow(7)?.severity)
        assertNull(diagnostics.onRow(8))
    }

    // ---- lspDiagnostics ----

    @Test
    fun parsesTheProjectSummary() {
        val summary = DiagnosticSummary.parse(
            """
            {"version":3,"errors":2,"warnings":1,"infos":0,"hints":0,
             "files":[{"path":"src/main.rs","errors":2,"warnings":1,
                       "infos":0,"hints":0}]}
            """.trimIndent()
        )

        assertEquals(3L, summary.version)
        assertEquals(2, summary.errors)
        assertEquals(1, summary.warnings)
        assertFalse(summary.isClean)
        assertEquals(1, summary.files.size)
        assertEquals("src/main.rs", summary.files[0].path)
        assertEquals("Project diagnostics: 2 errors, 1 warning", summary.label)
    }

    @Test
    fun cleanIsAboutErrorsAndWarningsOnly() {
        // Zed's `(0, 0)` match is on those two counts; a project full of
        // hints still shows the check (items.rs:36-40).
        val hintsOnly = DiagnosticSummary.parse(
            """{"version":1,"errors":0,"warnings":0,"infos":4,"hints":9,"files":[]}"""
        )
        assertTrue(hintsOnly.isClean)
        assertEquals("Project diagnostics: no problems", hintsOnly.label)
    }

    @Test
    fun theSummaryLabelIsSingularForOne() {
        val one = DiagnosticSummary(1L, 1, 0, 0, 0, emptyList())
        assertEquals("Project diagnostics: 1 error", one.label)
        val warned = DiagnosticSummary(1L, 0, 1, 0, 0, emptyList())
        assertEquals("Project diagnostics: 1 warning", warned.label)
    }

    @Test
    fun anUnreadableSummaryIsNoSummary() {
        assertEquals(DiagnosticSummary.EMPTY, DiagnosticSummary.parse("["))
        assertEquals(DiagnosticSummary.EMPTY, DiagnosticSummary.parse(null))
    }

    // ---- lspDiagnosticRows ----

    @Test
    fun parsesTheProjectRows() {
        val rows = ProjectDiagnosticRows.parse(
            """
            {"version":4,"files":[
              {"path":"src/lib.rs","rows":[
                {"row":1,"col_utf16":2,"end_row":1,"end_col_utf16":5,
                 "severity":"hint","message":"unused","source":"clippy","code":null}]},
              {"path":"src/main.rs","rows":[
                {"row":3,"col_utf16":4,"end_row":3,"end_col_utf16":7,
                 "severity":"error","message":"mismatched types",
                 "source":"rustc","code":"E0308"}]}
            ]}
            """.trimIndent()
        )

        assertEquals(4L, rows.version)
        assertEquals(2, rows.files.size)
        assertEquals("src/lib.rs", rows.files[0].path)
        assertEquals(DiagnosticSeverity.Hint, rows.files[0].rows[0].severity)
        // org.json's null trap: a JSON null must come back as Kotlin null,
        // not the string "null" — the same guard the buffer parse has.
        assertNull(rows.files[0].rows[0].code)
        val error = rows.files[1].rows[0]
        assertEquals(3, error.row)
        assertEquals(4, error.colUtf16)
        assertEquals("mismatched types", error.message)
        assertEquals("mismatched types (rustc E0308)", error.label)
    }

    @Test
    fun unreadableProjectRowsAreNoRows() {
        assertEquals(ProjectDiagnosticRows.EMPTY, ProjectDiagnosticRows.parse("["))
        assertEquals(ProjectDiagnosticRows.EMPTY, ProjectDiagnosticRows.parse(null))
        assertEquals(ProjectDiagnosticRows.EMPTY, ProjectDiagnosticRows.parse(""))
    }

    // ---- lspServers ----

    @Test
    fun parsesTheServerList() {
        val servers = parseLspServers(
            """[{"name":"clangd","state":"running","error":null,
                "languages":["c","cpp"],"progress":"indexing (45%)"}]"""
        )
        assertEquals(1, servers.size)
        assertEquals("clangd", servers[0].name)
        assertEquals(LspServerState.Running, servers[0].state)
        assertNull(servers[0].error)
        assertEquals(listOf("c", "cpp"), servers[0].languages)
        assertEquals("indexing (45%)", servers[0].progress)
        assertNull(servers[0].note)
    }

    @Test
    fun aServerThatCouldNotStartSaysSoInWords() {
        val servers = parseLspServers(
            """
            [{"name":"rust-analyzer","state":"unavailable",
              "error":"rust-analyzer: command not found","languages":["rust"]}]
            """.trimIndent()
        )
        assertEquals(LspServerState.Unavailable, servers[0].state)
        assertEquals("rust-analyzer is not installed", servers[0].note)
    }

    @Test
    fun aServerThatDiedForAnotherReasonSaysThatReason() {
        val servers = parseLspServers(
            """
            [{"name":"gopls","state":"unavailable",
              "error":"gopls: no go.mod found\nsecond line","languages":["go"]}]
            """.trimIndent()
        )
        // The first line only: the second is for a log, not a 30px bar.
        assertEquals("gopls could not start: gopls: no go.mod found", servers[0].note)
    }

    @Test
    fun aStartingServerIsNotAProblem() {
        val servers = parseLspServers(
            """[{"name":"pylsp","state":"starting","error":null,"languages":[]}]"""
        )
        assertEquals(LspServerState.Starting, servers[0].state)
        assertNull(servers[0].note)
        assertTrue(servers[0].languages.isEmpty())
    }

    @Test
    fun noServersIsANormalStateAndNotAFailure() {
        assertTrue(parseLspServers("[]").isEmpty())
        assertTrue(parseLspServers(null).isEmpty())
        assertTrue(parseLspServers("{").isEmpty())
    }
}
