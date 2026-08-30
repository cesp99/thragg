package to.eyed.seeker.code.ui.git

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import to.eyed.seeker.code.R
import to.eyed.seeker.code.core.Commit
import to.eyed.seeker.code.core.CommitDetails
import to.eyed.seeker.code.core.CommitPage
import to.eyed.seeker.code.core.CoreBridge
import to.eyed.seeker.code.core.GitBranch
import to.eyed.seeker.code.core.GitSession
import to.eyed.seeker.code.core.PatchResult
import to.eyed.seeker.code.core.ProjectSession
import to.eyed.seeker.code.core.ResumedEffect
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.ZedTheme
import to.eyed.seeker.code.ui.workspace.GitStatusColours
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One page of history; more are fetched as the list is scrolled. */
private const val PAGE = 100

/** However tangled the history, the diagram may not eat the whole row. */
private const val MAX_DRAWN_LANES = 8

/**
 * Row metrics — Zed's own graph constants. A single-line row is the UI line
 * box plus `ROW_VERTICAL_PADDING` = 4px (git_graph.rs:78, 1354-1361): 14 × φ
 * ≈ 22.7, so ~27. The stacked row a narrow screen gets is ours, sized the
 * same way for its two line boxes. Lanes are `LANE_WIDTH` = 16px with a
 * 3.5px-radius dot and 1.5px lines (git_graph.rs:68-72), and the diagram is
 * inset `LEFT_PADDING` = 12px (git_graph.rs:71).
 */
private val RowHeight = 27.dp
private val StackedRowHeight = 46.dp
private val LaneWidth = 16.dp
private val DotRadius = 3.5.dp
private val LineWidth = 1.5.dp
private val GraphLeftPadding = 12.dp

/** Where the columns appear, rather than the second line of a stacked row. */
private val ColumnsFrom = 640.dp

/**
 * The details sidebar: Zed's panel is a draggable split whose right half
 * refuses to shrink under `min_w(px(300.))` (git_graph.rs:2694+); ours is
 * that minimum, fixed. Below [SidebarSplitFrom] there is no room for a table
 * *and* a sidebar, so the sidebar takes the pane whole instead — the same
 * answer the docks give a compact screen.
 */
private val SidebarWidth = 300.dp
private val SidebarSplitFrom = 700.dp

/** The sidebar tree's indent step — Zed's `TREE_INDENT` (git_graph.rs:356). */
private val TreeIndent = 20.dp

/** The panel's `list_item_height()`, as the change rows already use. */
private val TreeRowHeight = 28.dp

/**
 * The row column's date: Zed formats
 * `"[day] [month repr:short] [year] [hour]:[minute]"` in the local timezone —
 * `05 Mar 2026 14:07` — and says `"Unknown"` when the timestamp cannot be
 * read (git_graph.rs:605-624), which is what a zero from our parser means.
 * English month names, as Zed's formatter hardcodes them.
 */
internal fun graphRowDate(epochSeconds: Long, zone: TimeZone = TimeZone.getDefault()): String {
    if (epochSeconds == 0L) return "Unknown"
    val format = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.US)
    format.timeZone = zone
    return format.format(Date(epochSeconds * 1000L))
}

/**
 * The sidebar's date under the author: `"[month repr:short] [day], [year]"`,
 * local time — `Mar 5, 2026`, absolute rather than relative
 * (git_graph.rs:2743-2754).
 */
internal fun sidebarDate(epochSeconds: Long, zone: TimeZone = TimeZone.getDefault()): String {
    if (epochSeconds == 0L) return "Unknown"
    val format = SimpleDateFormat("MMM d, yyyy", Locale.US)
    format.timeZone = zone
    return format.format(Date(epochSeconds * 1000L))
}

/**
 * Whether a `%D` decoration is the HEAD chip — the one that leads with a
 * check icon. Zed matches the current branch's name or `"HEAD -> " + branch`
 * (`is_head_ref`, git_graph.rs:1698-1701); a detached HEAD decorates as the
 * bare word, which is nobody's branch name, so its chip dresses like any
 * other — the plain 0.08/0.25 wash, no check (git_graph.rs:1717-1740).
 */
internal fun isHeadDecoration(decoration: String, branch: String?): Boolean =
    branch != null && (decoration == branch || decoration == "HEAD -> $branch")

/** The initials disc's text: `Carlo Esposito` → `CE`, and `?` for nobody. */
internal fun authorInitials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val initials = words.take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }
    return if (initials.isEmpty()) "?" else initials.joinToString("")
}

/**
 * The pager's book-keeping, out of the composition so a JVM test can drive
 * its interleavings. Snapshot state throughout, so the pane recomposes off it
 * exactly as it did off the local vars this replaces.
 *
 * The invariant it holds: **a page lands only on the list it was asked for.**
 * A page fetch is `git log` under proot — slow — and three callers share it:
 * the first load, the scroll-driven pager, and the version poll's reload.
 * Without a guard, a reload that emptied the list while a page for row 100
 * was in flight got that page appended *first*, then page zero after it —
 * history with its newest commits below older ones, lanes drawn from the
 * scrambled order, and the stale call's error path could set [exhausted] and
 * kill paging for the fresh list. So [reset] bumps a generation, and a load
 * that comes back under an old generation drops everything on the floor.
 */
internal class GraphPaging {
    var commits by mutableStateOf<List<Commit>>(emptyList())
        private set

    /** What the pane says while a read is due — true from birth, so the
     * empty pane opens on "Reading history…" rather than "nothing". */
    var loading by mutableStateOf(true)
        private set
    var exhausted by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Which list an in-flight page belongs to. */
    private var generation = 0

    /** A fetch is in flight — the guard, distinct from [loading], which is
     * also true before the first fetch has even been asked for. */
    private var inFlight = false

    /** Forget the list — history may have been rewritten, not just added to —
     * and strand every in-flight page on the old generation. */
    fun reset() {
        generation += 1
        inFlight = false
        commits = emptyList()
        loading = true
        exhausted = false
        error = null
    }

    /**
     * Read the next page through [fetch] (skip → page) and append it. One at
     * a time: a call while another is in flight returns immediately, and a
     * call that suspended across a [reset] discards its page.
     */
    suspend fun loadMore(fetch: suspend (skip: Int) -> CommitPage) {
        if (exhausted || inFlight) return
        val epoch = generation
        inFlight = true
        loading = true
        val page = fetch(commits.size)
        // A reset emptied the list while this page was on its way: it is a
        // page of a list that no longer exists, and everything below — the
        // append, the flags, the error — belongs to the new one.
        if (epoch != generation) return
        inFlight = false
        loading = false
        if (page.error != null) {
            error = page.error
            exhausted = true
            return
        }
        if (page.commits.isEmpty()) {
            exhausted = true
            return
        }
        // A page that is entirely commits already seen — history rewritten
        // under us — would leave the list unchanged and the paging waiting for
        // a change that never comes.
        val before = commits.size
        // Deduplicate: a commit made while this is open shifts the window, and
        // the same sha arriving twice would draw two rows and two lanes.
        val seen = commits.mapTo(mutableSetOf()) { it.sha }
        commits = commits + page.commits.filter { seen.add(it.sha) }
        if (commits.size == before) exhausted = true
    }
}

/**
 * The commit graph — Zed's `git_graph`, drawn for a screen you hold.
 *
 * The diagram down the left is the point of it: lanes that fork and rejoin are
 * what tell you the shape of the history, and no list of subjects can. Beside
 * it go the description, the date, the author and the short hash — Zed's own
 * columns — collapsing to two lines per row when the window is too narrow for
 * five columns, which on a phone it is. The walk is Zed's too: every branch,
 * remote and tag in `--date-order`, not just what HEAD can reach.
 *
 * Tapping a row opens the commit's details sidebar; tapping "View Commit" —
 * or double-tapping the row — opens the whole commit as a diff tab.
 */
@Composable
fun GitGraphPane(
    project: ProjectSession,
    onOpenCommit: (sha: String, subject: String, path: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    val context = LocalContext.current
    val session = remember(project) { GitSession(project) }
    val paging = remember(session) { GraphPaging() }
    // The one fetch every pager call shares — a slow `git log` under proot,
    // which is exactly why [GraphPaging] guards its interleavings.
    val fetchPage: suspend (Int) -> CommitPage = { skip ->
        withContext(Dispatchers.IO) { session.log(PAGE, skip, allRefs = true) }
    }
    /** The row whose sidebar is open, or null when none is. */
    var selected by remember(session) { mutableStateOf<Commit?>(null) }
    /** What the selection's sidebar has read so far. */
    var details by remember(session) { mutableStateOf<CommitDetails?>(null) }
    var stats by remember(session) { mutableStateOf<PatchResult?>(null) }
    var avatar by remember(session) { mutableStateOf<Bitmap?>(null) }
    /** Folded-shut directories in the changed-files tree, per selection. */
    var collapsedDirs by remember(session) { mutableStateOf<Set<String>>(emptySet()) }
    /** The branch HEAD is on, for telling the HEAD chip from the others. */
    var branchName by remember(session) { mutableStateOf<String?>(null) }
    /** The remote the permalink and avatars hang off — origin, as Zed falls back to. */
    var remoteUrl by remember(session) { mutableStateOf<String?>(null) }

    LaunchedEffect(session) {
        remoteUrl = withContext(Dispatchers.IO) {
            val remotes = session.remotes().remotes
            (remotes.firstOrNull { it.name == "origin" } ?: remotes.firstOrNull())?.url
        }
    }

    // Reading a commit's details and its diff totals are git calls of their
    // own, so they happen off the click rather than in the draw — message and
    // file list first, the ± totals when the patch arrives, exactly the two
    // processes Zed starts on selection (show + load_commit).
    LaunchedEffect(selected?.sha) {
        details = null
        stats = null
        collapsedDirs = emptySet()
        val sha = selected?.sha ?: return@LaunchedEffect
        details = withContext(Dispatchers.IO) { session.commitDetails(sha) }
        stats = withContext(Dispatchers.IO) { session.commitPatch(sha) }
    }

    // The avatar, separately: it needs only the author email the row already
    // carries, and it must not hold the details read hostage to the network.
    // Only a github.com origin is ever asked (the user's decision, and Zed's
    // `host_supports_avatars`); everyone else gets the initials disc.
    LaunchedEffect(selected?.sha, remoteUrl) {
        avatar = null
        val commit = selected ?: return@LaunchedEffect
        val remote = remoteUrl ?: return@LaunchedEffect
        if (githubRepoSlug(remote) != null && commit.authorEmail.isNotBlank()) {
            avatar = withContext(Dispatchers.IO) {
                CommitAvatars.load(context, commit.authorEmail)
            }
        }
    }

    val listState = rememberLazyListState()
    // Laid out off the main thread: it is O(commits) per page and the page
    // grows, so doing it in composition made every page cost more than the
    // last on the frame's own thread.
    var rows by remember(session) { mutableStateOf<List<GraphRow>>(emptyList()) }
    LaunchedEffect(paging.commits) {
        rows = withContext(Dispatchers.Default) { layoutGraph(paging.commits) }
    }

    LaunchedEffect(session) { paging.loadMore(fetchPage) }

    // A commit made while this tab is open belongs at the top of it. Watched
    // through the same counter everything else uses — but the counter also
    // bumps on every save, and each reload here is `git log` under proot, so
    // a move only reloads when what history depends on has moved: HEAD, or
    // the branch record — name, ahead/behind, upstream, so a fetch or push
    // that moves upstream refs without touching HEAD redraws the ref chips —
    // both read from the same cached run the counter versions. A pure `git
    // tag` moves neither and still does not reload, which is the one residual
    // this key accepts. A reload resets the list rather than appending, since
    // history can be rewritten as well as added to. When the engine cannot
    // name HEAD, every move reloads — the old trigger: eager, but never
    // stale.
    //
    // The baseline survives the lifecycle block's restarts on purpose: a
    // commit made while the app was in the background (an agent, a shell)
    // must reload on the way back, and re-capturing the key on resume would
    // hide it. Null still means "not captured yet", which is what makes the
    // very first pass a capture rather than a reload of what the effect
    // above just loaded.
    var seenGraph by remember(session) { mutableStateOf<Pair<String?, String?>?>(null) }
    ResumedEffect(session) {
        // The branch half is the record's JSON verbatim: serialization is
        // stable for equal values, so comparing the strings is comparing the
        // fields, with nothing to parse on a poll that mostly finds no change.
        fun graphKey(): Pair<String?, String?> =
            CoreBridge.gitHead(project.id) to CoreBridge.gitBranchInfo(project.id)
        withContext(Dispatchers.Default) {
            var seen = Long.MIN_VALUE
            while (true) {
                val now = session.version
                if (now != seen) {
                    seen = now
                    val graph = graphKey()
                    val prior = seenGraph
                    if (prior != graph || graph.first == null) {
                        // The chips need the branch's *name* out of the JSON
                        // the key already carries — parsed here, on the poll's
                        // own thread, only when something moved.
                        val name = graph.second?.let { json ->
                            runCatching { GitBranch.parse(JSONObject(json)).name }.getOrNull()
                        }
                        withContext(Dispatchers.Main) {
                            seenGraph = graph
                            branchName = name
                            if (prior != null) {
                                // Strands any in-flight page on the old
                                // generation before the fresh page-zero read.
                                paging.reset()
                                paging.loadMore(fetchPage)
                            }
                        }
                    }
                }
                delay(500)
            }
        }
    }

    // Paging: when the last few rows come into view, ask for the next page.
    //
    // `rows` is snapshot state and the derived block is keyed on it, because a
    // `derivedStateOf` created once over a plain local captures the *first*
    // value — which was the empty list, so the condition read `last >= -5`,
    // was always true, and the graph loaded the entire history at once.
    val nearTheEnd by remember(rows) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            rows.isNotEmpty() && last >= rows.size - 5
        }
    }
    LaunchedEffect(session) {
        snapshotFlow { nearTheEnd }.collect { near ->
            if (near && !paging.loading && !paging.exhausted) paging.loadMore(fetchPage)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(theme.color("editor.background"))
    ) {
        val columns = maxWidth >= ColumnsFrom
        val sideBySide = maxWidth >= SidebarSplitFrom
        val palette = lanePalette(theme)
        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                val error = paging.error
                when {
                    error != null && rows.isEmpty() -> Message(error, isError = true)
                    rows.isEmpty() && paging.loading -> Message("Reading history…")
                    rows.isEmpty() -> Message("Nothing has been committed yet")
                    else -> Column(modifier = Modifier.fillMaxSize()) {
                        if (columns) {
                            GraphHeader()
                            HorizontalDivider(color = theme.color("border.variant"))
                        }
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(rows, key = { it.commit.sha }) { row ->
                                GraphRowView(
                                    row = row,
                                    columns = columns,
                                    palette = palette,
                                    branchName = branchName,
                                    isSelected = selected?.sha == row.commit.sha,
                                    onClick = { selected = row.commit },
                                    onOpenCommit = {
                                        onOpenCommit(row.commit.sha, row.commit.subject, null)
                                    },
                                )
                            }
                            if (error != null) {
                                item(key = "error") {
                                    Message(error, isError = true, inline = true)
                                }
                            } else if (!paging.exhausted) {
                                item(key = "loading") {
                                    Message("Reading more…", inline = true)
                                }
                            }
                        }
                    }
                }
                // On a screen with no room for the split, the sidebar takes
                // the table's place instead of a slice of it.
                val overlaySelected = selected
                if (overlaySelected != null && !sideBySide) {
                    CommitSidebar(
                        commit = overlaySelected,
                        details = details,
                        stats = stats,
                        avatar = avatar,
                        accent = palette[laneOf(rows, overlaySelected.sha) % palette.size],
                        branchName = branchName,
                        remoteUrl = remoteUrl,
                        collapsedDirs = collapsedDirs,
                        onToggleDir = { key ->
                            collapsedDirs =
                                if (key in collapsedDirs) collapsedDirs - key
                                else collapsedDirs + key
                        },
                        onClose = { selected = null },
                        onOpenCommit = onOpenCommit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            val splitSelected = selected
            if (splitSelected != null && sideBySide) {
                // Zed's split handle: a 1px `border_variant` divider
                // (git_graph.rs:3630-3662); ours is not draggable.
                VerticalDivider(thickness = 1.dp, color = theme.color("border.variant"))
                CommitSidebar(
                    commit = splitSelected,
                    details = details,
                    stats = stats,
                    avatar = avatar,
                    accent = palette[laneOf(rows, splitSelected.sha) % palette.size],
                    branchName = branchName,
                    remoteUrl = remoteUrl,
                    collapsedDirs = collapsedDirs,
                    onToggleDir = { key ->
                        collapsedDirs =
                            if (key in collapsedDirs) collapsedDirs - key
                            else collapsedDirs + key
                    },
                    onClose = { selected = null },
                    onOpenCommit = onOpenCommit,
                    modifier = Modifier.width(SidebarWidth).fillMaxHeight(),
                )
            }
        }
    }
}

/** The lane a sha was laid out in, for painting its chips and sidebar. */
private fun laneOf(rows: List<GraphRow>, sha: String): Int =
    rows.firstOrNull { it.commit.sha == sha }?.lane ?: 0

/**
 * Lane colours: Zed cycles the theme's `AccentColors` per lane
 * (git_graph.rs:931-938). This app's themes don't carry that array, so the
 * cycle is the version-control family the graph is about — resolved once per
 * theme, never per row.
 */
@Composable
private fun lanePalette(theme: ZedTheme): List<Color> = remember(theme) {
    listOf(
        theme.color("text.accent", Color(0xFF61AFEF)),
        theme.color("version_control.added", Color(0xFF98C379)),
        theme.color("warning", Color(0xFFE5C07B)),
        theme.color("version_control.deleted", Color(0xFFE06C75)),
        theme.color("text.muted", Color(0xFFC678DD)),
    )
}

@Composable
private fun GraphHeader() {
    val theme = LocalZedTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.color("panel.background"))
            .padding(start = GraphLeftPadding, end = 8.dp, top = 4.dp, bottom = 4.dp),
    ) {
        // Zed's five headers, verbatim and in its order (git_graph.rs:3810-3850).
        HeaderCell("Graph", 90.dp)
        HeaderCell("Description", null)
        HeaderCell("Date", 130.dp)
        HeaderCell("Author", 150.dp)
        HeaderCell("Commit", 80.dp)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(
    text: String,
    width: androidx.compose.ui.unit.Dp?,
) {
    val theme = LocalZedTheme.current
    Text(
        // Column titles are Small muted labels, as every Zed table header.
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = theme.color("text.muted"),
        maxLines = 1,
        modifier = if (width != null) Modifier.width(width) else Modifier.weight(1f),
    )
}

@Composable
private fun GraphRowView(
    row: GraphRow,
    columns: Boolean,
    palette: List<Color>,
    branchName: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
    onOpenCommit: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val date = remember(row.commit.authorTime) { graphRowDate(row.commit.authorTime) }
    val accent = palette[row.lane % palette.size]
    // No divider under a row: Zed's graph rows meet edge to edge, told apart
    // by the lane drawing and the hover fill alone. Hover is
    // `element_hover.opacity(0.6)`, selection `element_selected`
    // (git_graph.rs:3236-3270).
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (columns) RowHeight else StackedRowHeight)
            .background(
                when {
                    isSelected -> theme.color("element.selected", theme.color("border.variant"))
                    hovered -> theme.color("element.hover", Color.Transparent)
                        .let { it.copy(alpha = it.alpha * 0.6f) }
                    else -> Color.Transparent
                }
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .combinedClickable(
                interactionSource = interaction,
                // Instant swap, no ripple — Zed's rows never animate.
                indication = null,
                onClickLabel = "Show this commit's details",
                // Zed's double-click opens the CommitView (git_graph.rs:3496-3530).
                onDoubleClick = onOpenCommit,
                onClick = onClick,
            )
            .padding(start = GraphLeftPadding, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Lanes(row, palette)
        Column(
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // One chip per `%D` decoration, in git's own order, raw text
                // and all — Zed does not strip `HEAD -> ` or `tag: ` for
                // display (git_graph.rs:1696-1775).
                for (name in row.commit.refs) {
                    RefChip(
                        name = name,
                        accent = accent,
                        isHead = isHeadDecoration(name, branchName),
                    )
                }
                Text(
                    text = row.commit.subject.ifBlank { "(no message)" },
                    style = MaterialTheme.typography.bodyMedium,
                    // Muted until the row is selected (git_graph.rs:1852-1926).
                    color = theme.color(if (isSelected) "text" else "text.muted"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!columns) {
                Text(
                    text = "${row.commit.author} · $date · ${row.commit.shortSha}",
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.color("text.muted"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (columns) {
            Cell(date, 130.dp)
            Cell(row.commit.author, 150.dp)
            Cell(row.commit.shortSha, 80.dp)
        }
    }
}

/**
 * One `%D` decoration in Zed's Chip clothes: `px_1`, 1px border, `rounded_sm`
 * (chip.rs:100-115), washed with the commit's **lane accent** — the HEAD chip
 * leads with a check icon on the stronger wash, bg 0.25/border 0.5; every
 * other chip, branch, remote and tag alike, gets 0.08/0.25
 * (git_graph.rs:1717-1740).
 */
@Composable
private fun RefChip(name: String, accent: Color, isHead: Boolean) {
    val theme = LocalZedTheme.current
    Row(
        modifier = Modifier
            .background(
                accent.copy(alpha = if (isHead) 0.25f else 0.08f),
                RoundedCornerShape(4.dp),
            )
            .border(
                1.dp,
                accent.copy(alpha = if (isHead) 0.5f else 0.25f),
                RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        // `gap_0p5` between check and label (chip.rs:100-115).
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (isHead) {
            androidx.compose.material3.Icon(
                painter = painterResource(R.drawable.ic_ui_check),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(12.dp),
            )
        }
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Each chip truncates on its own rather than pushing the subject
            // off the row (git_graph.rs:1746-1775).
            modifier = Modifier.widthIn(max = 140.dp),
        )
    }
}

@Composable
private fun Cell(text: String, width: androidx.compose.ui.unit.Dp) {
    val theme = LocalZedTheme.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = theme.color("text.muted"),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(width),
    )
}

/**
 * The diagram: a dot for this commit and a line for every lane that passes or
 * leaves it.
 *
 * Lanes are coloured by index rather than by branch, which is what every graph
 * does and the only thing that can be done without walking the whole history:
 * the colour says "this is a different line", not "this is that branch".
 */
@Composable
private fun Lanes(row: GraphRow, palette: List<Color>) {
    val laneCount = row.laneCount.coerceIn(1, MAX_DRAWN_LANES)
    Canvas(
        modifier = Modifier
            .width(LaneWidth * laneCount)
            .fillMaxHeight()
    ) {
        val laneWidth = LaneWidth.toPx()
        // A history tangled past the cap is drawn in the last column rather
        // than off the side of the canvas.
        fun x(lane: Int) = laneWidth * lane.coerceAtMost(laneCount - 1) + laneWidth / 2f
        val top = 0f
        val middle = size.height / 2f
        val bottom = size.height
        // `LINE_WIDTH` (git_graph.rs:72).
        val stroke = LineWidth.toPx()

        // Lines belonging to branches this commit is not on: straight through.
        for (lane in row.through) {
            drawLine(
                color = palette[lane % palette.size],
                start = Offset(x(lane), top),
                end = Offset(x(lane), bottom),
                strokeWidth = stroke,
            )
        }
        // Into this commit from above, and out to each parent below.
        drawLine(
            color = palette[row.lane % palette.size],
            start = Offset(x(row.lane), top),
            end = Offset(x(row.lane), middle),
            strokeWidth = stroke,
        )
        for (parent in row.parentLanes) {
            drawLine(
                color = palette[parent % palette.size],
                start = Offset(x(row.lane), middle),
                end = Offset(x(parent), bottom),
                strokeWidth = stroke,
            )
        }
        drawCircle(
            color = palette[row.lane % palette.size],
            radius = DotRadius.toPx(),
            center = Offset(x(row.lane), middle),
        )
    }
}

/**
 * The details sidebar — Zed's `render_commit_detail_panel`
 * (git_graph.rs:2694-3175), field for field: close button, the centred
 * identity block, the chips, the copyable meta rows, the message, the changed
 * files, and "View Commit" at the bottom.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommitSidebar(
    commit: Commit,
    details: CommitDetails?,
    stats: PatchResult?,
    avatar: Bitmap?,
    accent: Color,
    branchName: String?,
    remoteUrl: String?,
    collapsedDirs: Set<String>,
    onToggleDir: (String) -> Unit,
    onClose: () -> Unit,
    onOpenCommit: (sha: String, subject: String, path: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val colours = remember(theme) {
        GitStatusColours.from(theme, theme.color("text"), theme.color("text.muted"))
    }
    /** Which meta row shows its "copied" tick — cleared 2s later, as Zed's
     * `COPIED_STATE_DURATION` does (git_graph.rs:74). */
    var copied by remember(commit.sha) { mutableStateOf<String?>(null) }
    LaunchedEffect(copied) {
        if (copied != null) {
            delay(2_000)
            copied = null
        }
    }
    val commitUrl = remember(remoteUrl, commit.sha) {
        remoteUrl?.let { githubCommitUrl(it, commit.sha) }
    }

    Column(modifier = modifier.background(theme.color("editor.background"))) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // The centred identity block: avatar, name, absolute date
            // (git_graph.rs:2743-2754, 2861-2872).
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Avatar(avatar, commit.author, theme)
                Text(
                    text = commit.author.ifBlank { "Unknown" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.color("text"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    text = sidebarDate(commit.authorTime),
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.color("text.muted"),
                )
            }
            // `top_2 right_2` close, small (git_graph.rs:2845-2859).
            IconGhostButton(
                icon = R.drawable.ic_ui_close,
                label = "Close",
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp),
                onClick = onClose,
            )
        }
        if (commit.refs.isNotEmpty()) {
            // Wrapped and centred, same chips as the row (git_graph.rs:2874-2881).
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (name in commit.refs) {
                    RefChip(
                        name = name,
                        accent = accent,
                        isHead = isHeadDecoration(name, branchName),
                    )
                }
            }
        }
        // The meta column: email, full sha, permalink (git_graph.rs:2886-3020).
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (commit.authorEmail.isNotBlank()) {
                MetaRow(
                    icon = R.drawable.ic_ui_envelope,
                    // Swaps to the tick for 2s once copied — Zed's
                    // "Email Copied!" state (git_graph.rs:2886-2938).
                    label = commit.authorEmail,
                    copiedNow = copied == "email",
                    onClick = {
                        clipboard.setText(AnnotatedString(commit.authorEmail))
                        copied = "email"
                    },
                )
            }
            MetaRow(
                icon = R.drawable.ic_ui_hash,
                label = commit.sha,
                copiedNow = copied == "sha",
                onClick = {
                    clipboard.setText(AnnotatedString(commit.sha))
                    copied = "sha"
                },
            )
            if (commitUrl != null) {
                MetaRow(
                    icon = R.drawable.ic_ui_github,
                    // Zed says "View on {provider}" for whichever host parses
                    // (git_graph.rs:2988-3020); this app parses only GitHub.
                    label = "View on GitHub",
                    copiedNow = false,
                    onClick = {
                        // The browser's job, not ours.
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, commitUrl.toUri()))
                        }
                    },
                )
            }
        }
        HorizontalDivider(color = theme.color("border.variant"))
        // The whole message, capped at 12 line-heights with its own scroll
        // (git_graph.rs:3664-3713).
        val message = details?.message ?: commit.subject
        Text(
            text = message.trim().ifBlank { "(no message)" },
            style = MaterialTheme.typography.bodySmall,
            color = theme.color("text"),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        )
        HorizontalDivider(color = theme.color("border.variant"))
        ChangedFiles(
            details = details,
            stats = stats,
            colours = colours,
            collapsedDirs = collapsedDirs,
            onToggleDir = onToggleDir,
            onOpenFile = { path -> onOpenCommit(commit.sha, commit.subject, path) },
            modifier = Modifier.weight(1f),
        )
        HorizontalDivider(color = theme.color("border.variant"))
        // Full-width OutlinedGhost with the commit glyph (git_graph.rs:3159-3172).
        ViewCommitButton(onClick = { onOpenCommit(commit.sha, commit.subject, null) })
    }
}

/** 32px avatar, or the author's initials in Zed's fallback disc — `rounded_full`,
 * 1px `border_variant`, disabled fill (commit_tooltip.rs:118-138). */
@Composable
private fun Avatar(bitmap: Bitmap?, author: String, theme: ZedTheme) {
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Avatar of $author",
            modifier = Modifier.size(32.dp).clip(CircleShape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(theme.color("element.disabled", theme.color("element.background")))
                .border(1.dp, theme.color("border.variant"), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = authorInitials(author),
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text.muted"),
            )
        }
    }
}

/**
 * One meta row: a small muted text button with a leading small icon
 * (git_graph.rs:2886-2987). While [copiedNow], the icon is the tick in the
 * success colour — Zed's 2-second copied state.
 */
@Composable
private fun MetaRow(icon: Int, label: String, copiedNow: Boolean, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (hovered) theme.color("ghost_element.hover", Color.Transparent)
                else Color.Transparent
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = label,
                onClick = onClick,
            )
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        androidx.compose.material3.Icon(
            painter = painterResource(if (copiedNow) R.drawable.ic_ui_check else icon),
            contentDescription = null,
            tint = if (copiedNow) {
                theme.color("success", theme.color("created", Color(0xFF98C379)))
            } else {
                theme.color("text.muted")
            },
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = theme.color("text.muted"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A bare icon in ghost-button clothes — the sidebar's close X. */
@Composable
private fun IconGhostButton(
    icon: Int,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (hovered) theme.color("ghost_element.hover", Color.Transparent)
                else Color.Transparent
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = label,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = theme.color("text.muted"),
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * The changed-files section (git_graph.rs:3026-3157): the count-and-totals
 * header, then the directory tree — files sorted by path, single-child chains
 * compacted, every directory a fold.
 */
@Composable
private fun ChangedFiles(
    details: CommitDetails?,
    stats: PatchResult?,
    colours: GitStatusColours,
    collapsedDirs: Set<String>,
    onToggleDir: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    val files = details?.files ?: emptyList()
    val rows = remember(files, collapsedDirs) { commitFileTree(files, collapsedDirs) }
    val numbers = remember { NumberFormat.getIntegerInstance() }
    // Zero until the diff loads, as Zed's totals are (git_graph.rs:1278-1292).
    val added = stats?.files?.sumOf { it.added } ?: 0
    val removed = stats?.files?.sumOf { it.removed } ?: 0
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            // `p_2 pr_3 pb_1` (git_graph.rs:3026-3053).
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, top = 8.dp, end = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                // Singular iff exactly one (git_graph.rs:3043-3053).
                text = "${files.size} Changed " + if (files.size == 1) "File" else "Files",
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text.muted"),
            )
            Spacer(modifier = Modifier.weight(1f))
            // Zed's DiffStat, glyph for glyph: `+` and figure dash, a thin
            // space before each locale-grouped number (diff_stat.rs:36-58).
            Text(
                text = "+ ${numbers.format(added)}",
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("success", theme.color("created", Color(0xFF98C379))),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "‒ ${numbers.format(removed)}",
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("error", theme.color("deleted", Color(0xFFE06C75))),
            )
        }
        if (details == null) {
            Text(
                text = "Reading the commit…",
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text.muted"),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = true)) {
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is CommitTreeDir -> DirRow(
                        row = row,
                        expanded = row.key !in collapsedDirs,
                        onClick = { onToggleDir(row.key) },
                    )
                    is CommitTreeFile -> FileRow(
                        row = row,
                        colours = colours,
                        onClick = { onOpenFile(row.file.path) },
                    )
                }
            }
        }
    }
}

/** A foldable directory row — folder icon, muted label (git_graph.rs:356-401). */
@Composable
private fun DirRow(row: CommitTreeDir, expanded: Boolean, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TreeRowHeight)
            .background(
                if (hovered) theme.color("ghost_element.hover", Color.Transparent)
                else Color.Transparent
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = "Toggle folder",
                onClick = onClick,
            )
            .padding(start = 8.dp + TreeIndent * row.depth, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        androidx.compose.material3.Icon(
            painter = painterResource(
                if (expanded) R.drawable.ic_file_folder_open else R.drawable.ic_file_folder
            ),
            contentDescription = null,
            tint = theme.color("text.muted"),
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = row.label,
            style = MaterialTheme.typography.labelMedium,
            color = theme.color("text.muted"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A changed file: the status mark in its colour, then the name — the panel's
 * own letter-for-icon convention where Zed draws `git_status_icon`
 * (git_graph.rs:270-316). Click opens the commit's diff narrowed to this file,
 * Zed's "View Changes".
 */
@Composable
private fun FileRow(row: CommitTreeFile, colours: GitStatusColours, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TreeRowHeight)
            .background(
                if (hovered) theme.color("ghost_element.hover", Color.Transparent)
                else Color.Transparent
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = "View changes to ${row.file.path}",
                onClick = onClick,
            )
            .padding(start = 8.dp + TreeIndent * row.depth, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = row.file.status.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = colours.colorFor(statusOf(row.file.status)),
            modifier = Modifier.width(14.dp),
        )
        Text(
            text = row.name,
            style = MaterialTheme.typography.labelMedium,
            color = theme.color("text"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** git's letter, in the vocabulary the panel paints with. */
private fun statusOf(letter: Char): to.eyed.seeker.code.ui.workspace.GitFileStatus =
    when (letter) {
        'A' -> to.eyed.seeker.code.ui.workspace.GitFileStatus.Added
        'D' -> to.eyed.seeker.code.ui.workspace.GitFileStatus.Deleted
        'R', 'C' -> to.eyed.seeker.code.ui.workspace.GitFileStatus.Renamed
        else -> to.eyed.seeker.code.ui.workspace.GitFileStatus.Modified
    }

/**
 * "View Commit", full width at the sidebar's foot: `OutlinedGhost` with a
 * small muted `GitCommit` icon, wrapped in `p_1p5` (git_graph.rs:3159-3172).
 */
@Composable
private fun ViewCommitButton(onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(modifier = Modifier.fillMaxWidth().padding(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (hovered) theme.color("ghost_element.hover", Color.Transparent)
                    else Color.Transparent
                )
                .border(
                    1.dp,
                    theme.color("border").copy(alpha = 0.8f),
                    RoundedCornerShape(4.dp),
                )
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClickLabel = "View Commit",
                    onClick = onClick,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        ) {
            androidx.compose.material3.Icon(
                painter = painterResource(R.drawable.ic_ui_git_commit),
                contentDescription = null,
                tint = theme.color("text.muted"),
                modifier = Modifier.size(14.dp),
            )
            Text(
                // The button under the tree (git_graph.rs:3159-3172).
                text = "View Commit",
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text"),
            )
        }
    }
}

@Composable
private fun Message(text: String, isError: Boolean = false, inline: Boolean = false) {
    val theme = LocalZedTheme.current
    Box(
        modifier = if (inline) {
            Modifier.fillMaxWidth().padding(16.dp)
        } else {
            Modifier.fillMaxSize().padding(24.dp)
        },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) theme.color("error") else theme.color("text.muted"),
        )
    }
}
