package to.eyed.seeker.code.ui.git

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.R
import to.eyed.seeker.code.core.GitBranchEntry
import to.eyed.seeker.code.core.GitBranchList
import to.eyed.seeker.code.core.GitSession
import to.eyed.seeker.code.core.ProjectSession
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.revealItem
import to.eyed.seeker.code.ui.workspace.ContextMenu
import to.eyed.seeker.code.ui.workspace.ContextMenuItem
import to.eyed.seeker.code.ui.workspace.PickerEmptyState
import to.eyed.seeker.code.ui.workspace.PickerListItem
import to.eyed.seeker.code.ui.workspace.PickerListPadding
import to.eyed.seeker.code.ui.workspace.PickerModal

/**
 * Which branches the picker was last asked to show. Zed keeps the filter in a
 * global that survives closing the picker (`GlobalBranchFilter`,
 * branch_picker.rs:622-625, 993-995), so ours outlives the composition the
 * same way [CommitDrafts] does.
 */
internal object BranchFilterState {
    @Volatile
    var filter: BranchFilter = BranchFilter.All
}

/** A branch command git refused: the prompt's title, and git's own words. */
private data class BranchOpFailure(val title: String, val detail: String)

/**
 * The branch switcher — Zed's `BranchList` (branch_picker.rs), worn as this
 * app's one picker chrome ([PickerModal], the modal variant at 34 rem).
 *
 * Everything visible follows Zed: the placeholder, the two-line rows with the
 * fuzzy highlights and the `author • time • subject` meta line, the local and
 * remote section headers, the create-branch entry the query grows, the filter
 * behind Ctrl+K, delete on Ctrl+Shift+Backspace with the not-fully-merged
 * force prompt, and Enter checking out — a remote branch by growing a local
 * tracking branch first, which is the engine's half of the deal.
 */
@Composable
fun BranchPicker(project: ProjectSession, onDismiss: () -> Unit) {
    val theme = LocalZedTheme.current
    val scope = rememberCoroutineScope()
    val session = remember(project) { GitSession(project) }

    var query by remember { mutableStateOf(TextFieldValue("")) }
    /** Null until the first listing lands: "loading", not "no branches". */
    var listing by remember { mutableStateOf<GitBranchList?>(null) }
    /** What "Create New From:" names — Zed resolves it once per open. */
    var defaultBranch by remember { mutableStateOf<String?>(null) }
    /** Remotes whose rows wear the GitHub glyph rather than the server one. */
    var githubRemotes by remember { mutableStateOf<Set<String>>(emptySet()) }
    var filter by remember { mutableStateOf(BranchFilterState.filter) }
    var filterMenuOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableIntStateOf(0) }
    /**
     * One git command at a time — the *same* one command the panel counts,
     * through [GitOps]: a checkout must not run through the middle of a pull
     * the panel started, and the picker opens over the panel whatever the
     * panel is doing.
     */
    val ops = remember(project) { GitOps.of(project.id) }
    /** Alt swaps delete for force delete, on the trash icon and its colour. */
    var altHeld by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<BranchOpFailure?>(null) }
    /**
     * A delete git refused with "not fully merged", waiting on the user —
     * Zed's Warning prompt with Force Delete beside Cancel
     * (branch_picker.rs:1140-1160).
     */
    var forceDeleteAsk by remember { mutableStateOf<GitBranchEntry?>(null) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    // One clock per open: the rows' "3 days ago" have no business ticking
    // while the picker is up.
    val now = remember { System.currentTimeMillis() / 1000 }

    fun reload() {
        scope.launch {
            listing = withContext(Dispatchers.IO) { session.branches() }
        }
    }

    LaunchedEffect(session) {
        focus.requestFocus()
        // Three reads, one trip: the listing itself, the default branch for
        // "Create New From:", and which remotes get the GitHub glyph
        // (Zed's remote_provider_icons, branch_picker.rs:1679-1686).
        val (branches, fallback, remotes) = withContext(Dispatchers.IO) {
            Triple(session.branches(), session.defaultBranch(), session.remotes())
        }
        listing = branches
        defaultBranch = fallback
        githubRemotes = remotes.remotes.filter { it.isGithub }.map { it.name }.toSet()
    }

    val rows = remember(listing, query.text, filter) {
        branchPickerRows(listing?.branches ?: emptyList(), query.text, filter)
    }
    val currentBranch = remember(listing) {
        listing?.branches?.firstOrNull { it.isHead }?.name
    }

    LaunchedEffect(query.text, filter) { selected = 0 }
    LaunchedEffect(selected, rows) {
        if (selected in rows.indices) listState.revealItem(selected)
    }

    fun move(delta: Int) {
        if (rows.isEmpty()) return
        val size = rows.size
        selected = ((selected + delta) % size + size) % size
    }

    fun setFilter(next: BranchFilter) {
        filter = next
        BranchFilterState.filter = next
    }

    /**
     * A branch command off the main thread, its refusal worded by [title] —
     * through the shared single-flight, so it queues behind nothing and
     * nothing runs over it. Refused silently while anything is in flight:
     * every button here is disabled on the same flag, so a refusal only
     * meets a keyboard's Enter.
     */
    fun run(title: String, action: () -> String?, onRefused: ((String) -> Unit)? = null) {
        GitOps.run(project.id, { action() }) { refusal ->
            when {
                refusal == null -> onDismiss()
                onRefused != null -> onRefused(refusal)
                else -> failure = BranchOpFailure(title, refusal)
            }
        }
    }

    fun confirmRow(row: BranchPickerRow, secondary: Boolean) {
        when (row) {
            is BranchPickerRow.Branch -> {
                // Confirming the branch you are on just closes the picker
                // (branch_picker.rs:1550-1561).
                if (row.entry.isHead) {
                    onDismiss()
                    return
                }
                // Zed's error prompt title (branch_picker.rs:1575-1580); the
                // detail is git's own sentence — a dirty worktree above all —
                // and nothing is stashed or forced behind it.
                run("Failed to change branch", { session.checkoutBranch(row.entry.name) })
            }
            is BranchPickerRow.Create -> {
                // Plain confirm branches off HEAD; SecondaryConfirm — Ctrl+
                // Enter, the row's hover button, the footer — off the default
                // branch (branch_picker.rs:1600-1607).
                val base = if (secondary) defaultBranch else null
                // Zed's title, detail = the error itself (branch_picker.rs:1069-1071).
                run("Failed to create branch", { session.createBranch(row.name, base) })
            }
        }
    }

    fun confirm(secondary: Boolean) {
        rows.getOrNull(selected)?.let { confirmRow(it, secondary) }
    }

    fun delete(entry: GitBranchEntry, force: Boolean) {
        // HEAD is never deletable, from any of the routes here
        // (branch_picker.rs:1118-1120).
        if (entry.isHead) return
        GitOps.run(project.id, {
            session.deleteBranch(entry.name, entry.isRemote, force)
        }) { refusal ->
            when {
                // Zed removes the row and keeps the picker up; re-listing is
                // the same thing said fresher.
                refusal == null -> reload()
                // Git only says this through localized stderr, so the check is
                // best-effort, exactly as Zed's (branch_picker.rs:797-838).
                !force && "not fully merged" in refusal.lowercase() ->
                    forceDeleteAsk = entry
                else -> failure = BranchOpFailure("Failed to delete branch", refusal)
            }
        }
    }

    fun deleteSelected(force: Boolean) {
        (rows.getOrNull(selected) as? BranchPickerRow.Branch)?.let { delete(it.entry, force) }
    }

    PickerModal(
        onDismiss = onDismiss,
        modifier = Modifier.onPreviewKeyEvent { event ->
            // Alt re-arms the trash icons both ways, so both key phases are
            // watched — Zed's `handle_modifiers_changed`
            // (branch_picker.rs:408-418).
            if (event.key == Key.AltLeft || event.key == Key.AltRight) {
                altHeld = event.type == KeyEventType.KeyDown
                return@onPreviewKeyEvent false
            }
            altHeld = event.isAltPressed
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when {
                event.key == Key.DirectionDown -> { move(1); true }
                event.key == Key.DirectionUp -> { move(-1); true }
                event.isCtrlPressed && event.key == Key.N -> { move(1); true }
                event.isCtrlPressed && event.key == Key.P -> { move(-1); true }
                event.key == Key.Tab -> {
                    move(if (event.isShiftPressed) -1 else 1)
                    true
                }
                // `ctrl-shift-backspace` deletes, alt on top force-deletes
                // (default-linux.json:1540-1547).
                event.isCtrlPressed && event.isShiftPressed &&
                    event.key == Key.Backspace -> {
                    deleteSelected(force = event.isAltPressed)
                    true
                }
                // `ctrl-shift-i`: CycleBranchFilter.
                event.isCtrlPressed && event.isShiftPressed && event.key == Key.I -> {
                    setFilter(filter.next())
                    true
                }
                // `ctrl-k`: ToggleFilterMenu.
                event.isCtrlPressed && event.key == Key.K -> {
                    filterMenuOpen = !filterMenuOpen
                    true
                }
                event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                    // Ctrl+Enter is menu::SecondaryConfirm — create from the
                    // default branch (default-linux.json:14-20).
                    confirm(secondary = event.isCtrlPressed)
                    true
                }
                event.key == Key.Escape -> {
                    if (filterMenuOpen) filterMenuOpen = false else onDismiss()
                    true
                }
                else -> false
            }
        },
    ) {
        BranchQueryRow(
            query = query,
            onQueryChange = { query = it },
            focusRequester = focus,
            filter = filter,
            filterMenuOpen = filterMenuOpen,
            onFilterClick = { filterMenuOpen = !filterMenuOpen },
            onFilterMenuDismiss = { filterMenuOpen = false },
            onFilter = ::setFilter,
        )

        // A listing that half-worked keeps its rows and wears the complaint —
        // Zed's warning Banner (branch_picker.rs:1271-1280).
        listing?.error?.let { error ->
            Text(
                text = "Some branches could not be loaded: $error",
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("warning", theme.color("error")),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
            HorizontalDivider(color = theme.color("border.variant"))
        }

        // The one-title header a narrowed filter gets (branch_picker.rs:1354-1375).
        branchListHeader(rows, filter)?.let { label ->
            BranchSectionHeader(label)
        }

        if (rows.isEmpty()) {
            PickerEmptyState(if (listing == null) "Loading branches" else "No matches")
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PickerListPadding,
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            ) {
                itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        branchSectionHeader(rows, index, filter)?.let { (label, divider) ->
                            // `pt_1p5`, and a `border_variant` rule with `mt_1`
                            // when anything sits above (branch_picker.rs:1959-1967).
                            Box(modifier = Modifier.height(6.dp))
                            if (divider) {
                                Box(modifier = Modifier.height(4.dp))
                                HorizontalDivider(color = theme.color("border.variant"))
                            }
                            BranchSectionHeader(label)
                        }
                        BranchRow(
                            row = row,
                            isSelected = index == selected,
                            altHeld = altHeld,
                            currentBranch = currentBranch,
                            defaultBranch = defaultBranch,
                            githubRemotes = githubRemotes,
                            now = now,
                            onClick = { confirmRow(row, secondary = false) },
                            onDelete = { force -> (row as? BranchPickerRow.Branch)?.let { delete(it.entry, force) } },
                            onCreateFrom = { confirmRow(row, secondary = true) },
                        )
                    }
                }
            }
        }

        // The modal footer: right-justified verbs over a `border.variant` rule
        // (branch_picker.rs:1986-2116). Enter and Escape do the same things,
        // but a phone has neither.
        val footerRow = rows.getOrNull(selected)
        if (footerRow != null) {
            HorizontalDivider(color = theme.color("border.variant"))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(6.dp),
            ) {
                Box(modifier = Modifier.weight(1f))
                when (footerRow) {
                    is BranchPickerRow.Branch -> {
                        if (!footerRow.entry.isHead) {
                            FooterButton(
                                label = "Delete",
                                hint = "Ctrl Shift Backspace",
                                enabled = !ops.busy,
                                // The plain delete: Zed's footer button
                                // dispatches DeleteBranch, and Alt only arms
                                // the row's trash icon (branch_picker.rs:2027-2042).
                                onClick = { delete(footerRow.entry, force = false) },
                            )
                        }
                        FooterButton(
                            label = "Switch",
                            hint = "Enter",
                            enabled = !ops.busy,
                            isPrimary = true,
                            onClick = { confirmRow(footerRow, secondary = false) },
                        )
                    }
                    is BranchPickerRow.Create -> {
                        defaultBranch?.let { base ->
                            FooterButton(
                                label = "Create New From: $base",
                                hint = "Ctrl Enter",
                                enabled = !ops.busy,
                                onClick = { confirmRow(footerRow, secondary = true) },
                            )
                        }
                        FooterButton(
                            label = "Create",
                            hint = "Enter",
                            enabled = !ops.busy,
                            isPrimary = true,
                            onClick = { confirmRow(footerRow, secondary = false) },
                        )
                    }
                }
            }
        }
    }

    // What git refused, in its own words — Zed's modal error prompt
    // (`detach_and_prompt_err`, notifications.rs:1655-1691).
    failure?.let { refused ->
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text(refused.title) },
            text = { Text(refused.detail) },
            confirmButton = {
                TextButton(onClick = { failure = null }) { Text("OK") }
            },
        )
    }

    // Zed's not-fully-merged prompt, words and both buttons
    // (branch_picker.rs:823-825, 1140-1160).
    forceDeleteAsk?.let { entry ->
        AlertDialog(
            onDismissRequest = { forceDeleteAsk = null },
            text = { Text("Branch \"${entry.name}\" is not fully merged. Force delete it?") },
            confirmButton = {
                TextButton(onClick = {
                    forceDeleteAsk = null
                    delete(entry, force = true)
                }) { Text("Force Delete") }
            },
            dismissButton = {
                TextButton(onClick = { forceDeleteAsk = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * The query row with the filter button riding at its end — Zed's editor row is
 * `h_9 px_2p5` with the query on the left and the filter `IconButton` right-
 * justified (branch_picker.rs:1290-1334); everything else is [PickerModal]'s
 * usual query field.
 */
@Composable
private fun BranchQueryRow(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    filter: BranchFilter,
    filterMenuOpen: Boolean,
    onFilterClick: () -> Unit,
    onFilterMenuDismiss: () -> Unit,
    onFilter: (BranchFilter) -> Unit,
) {
    val theme = LocalZedTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(horizontal = 10.dp),
    ) {
        Box(
            modifier = Modifier.weight(1f).pointerHoverIcon(PointerIcon.Text),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = theme.color("text")),
                cursorBrush = SolidColor(theme.cursor),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
            if (query.text.isEmpty()) {
                Text(
                    // Zed's placeholder (branch_picker.rs:1242-1250).
                    text = "Switch or type to create a branch…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.color("text.placeholder"),
                    maxLines = 1,
                )
            }
        }
        Box {
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            val pressed by interaction.collectIsPressedAsState()
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        when {
                            pressed || filterMenuOpen -> theme.color("ghost_element.active")
                            hovered -> theme.color("ghost_element.hover")
                            else -> Color.Transparent
                        }
                    )
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        // Zed's tooltip for ToggleFilterMenu (branch_picker.rs:1030-1043).
                        onClickLabel = "Filter Branches",
                        onClick = onFilterClick,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_ui_filter),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(theme.color("text.muted")),
                    modifier = Modifier.size(14.dp),
                )
                // `Indicator::dot().color(Color::Info)` while the filter is
                // narrowing anything (branch_picker.rs:1300-1313).
                if (filter != BranchFilter.All) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(theme.color("info")),
                    )
                }
            }
            // The three-way filter menu, its checks and its labels
            // (branch_picker.rs:626-657).
            ContextMenu(
                expanded = filterMenuOpen,
                onDismiss = onFilterMenuDismiss,
                items = BranchFilter.entries.map { choice ->
                    ContextMenuItem(
                        label = choice.label,
                        checked = choice == filter,
                        onClick = { onFilter(choice) },
                    )
                },
            )
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(theme.color("border.variant")),
    )
}

/**
 * Zed's inset `ListSubHeader` — a muted small label on the list's own padding
 * (branch_picker.rs:1968).
 */
@Composable
private fun BranchSectionHeader(label: String) {
    val theme = LocalZedTheme.current
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = theme.color("text.muted"),
        maxLines = 1,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/**
 * One row (`render_match`, branch_picker.rs:1618-1975): leading icon, the
 * two-line name-over-meta column, and on hover a trailing verb — trash for a
 * branch, create-from-default for the create entry.
 */
@Composable
private fun BranchRow(
    row: BranchPickerRow,
    isSelected: Boolean,
    altHeld: Boolean,
    currentBranch: String?,
    defaultBranch: String?,
    githubRemotes: Set<String>,
    now: Long,
    onClick: () -> Unit,
    onDelete: (force: Boolean) -> Unit,
    onCreateFrom: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val entry = (row as? BranchPickerRow.Branch)?.entry

    // Check on the branch you are on, a provider glyph (GitHub, else the
    // plain server) on a remote, the branch glyph on a local, plus on the
    // create entry (branch_picker.rs:1670-1687).
    val icon = when {
        row is BranchPickerRow.Create -> R.drawable.ic_ui_plus
        entry!!.isHead -> R.drawable.ic_ui_check
        entry.isRemote ->
            if (entry.remote in githubRemotes) R.drawable.ic_ui_github else R.drawable.ic_ui_server
        else -> R.drawable.ic_ui_git_branch
    }

    PickerListItem(
        isSelected = isSelected,
        onClick = onClick,
        interactionSource = interaction,
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            // Accent when checked, muted otherwise (branch_picker.rs:1789-1795);
            // `IconSize::Small` = 14px.
            colorFilter = ColorFilter.tint(
                if (entry?.isHead == true) theme.color("text.accent") else theme.color("text.muted")
            ),
            modifier = Modifier.size(14.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            when (row) {
                is BranchPickerRow.Branch -> {
                    Text(
                        text = highlighted(row.entry.name, row.positions, theme.color("text.accent")),
                        style = MaterialTheme.typography.bodyMedium,
                        color = theme.color("text"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    BranchMetaLine(entry = row.entry, now = now)
                }
                is BranchPickerRow.Create -> {
                    Text(
                        // Zed's label, ellipsis included (branch_picker.rs:1694-1697).
                        text = "Create Branch: \"${row.name}\"…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = theme.color("text"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        // Its second line (branch_picker.rs:1809-1819).
                        text = currentBranch?.let { "Based off $it" }
                            ?: "Based off the current branch",
                        style = MaterialTheme.typography.labelMedium,
                        color = theme.color("text.muted"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        // Zed shows the end slot on hover; a finger never hovers, so the
        // selected row keeps its verb reachable too.
        if (hovered || isSelected) {
            when {
                row is BranchPickerRow.Branch && !row.entry.isHead -> RowIconButton(
                    icon = R.drawable.ic_ui_trash,
                    // Zed's tooltip pair (branch_picker.rs:875-891).
                    label = if (altHeld) "Force Delete Branch" else "Delete Branch",
                    // The trash goes `Color::Error` while Alt is down
                    // (branch_picker.rs:1735).
                    tint = if (altHeld) theme.color("error") else theme.color("text.muted"),
                    onClick = { onDelete(altHeld) },
                )
                row is BranchPickerRow.Create && defaultBranch != null -> RowIconButton(
                    icon = R.drawable.ic_ui_git_branch_plus,
                    // Zed's tooltip (branch_picker.rs:1758-1776).
                    label = "Create New From: $defaultBranch",
                    tint = theme.color("text.muted"),
                    onClick = onCreateFrom,
                )
            }
        }
    }
}

/**
 * The meta line: author, relative commit time and subject, dotted apart at
 * half alpha — or "No commits found" on a branch with none
 * (branch_picker.rs:1823-1874). The author is unconditional because Zed's
 * gate, `git.branch_picker.show_author_name`, defaults to on
 * (default.json:1712).
 */
@Composable
private fun BranchMetaLine(entry: GitBranchEntry, now: Long) {
    val theme = LocalZedTheme.current
    val muted = theme.color("text.muted")

    @Composable
    fun Dot() {
        Text(
            text = "•",
            style = MaterialTheme.typography.labelMedium,
            color = muted.copy(alpha = 0.5f),
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        // `gap_1p5` (branch_picker.rs:1840).
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!entry.hasCommit) {
            Text(
                text = "No commits found",
                style = MaterialTheme.typography.labelMedium,
                color = muted,
                maxLines = 1,
            )
            return@Row
        }
        if (entry.author.isNotEmpty()) {
            Text(
                text = entry.author,
                style = MaterialTheme.typography.labelMedium,
                color = muted,
                maxLines = 1,
            )
            Dot()
        }
        Text(
            text = relativeTime(entry.committerDate, now),
            style = MaterialTheme.typography.labelMedium,
            color = muted,
            maxLines = 1,
        )
        if (entry.subject.isNotEmpty()) {
            Dot()
            Text(
                text = entry.subject,
                style = MaterialTheme.typography.labelMedium,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/**
 * A row's end-slot verb: Zed's small `IconButton`, 22px ghost square, 14px
 * glyph (button_like.rs:245-330).
 */
@Composable
private fun RowIconButton(
    icon: Int,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    pressed -> theme.color("ghost_element.active")
                    hovered -> theme.color("ghost_element.hover")
                    else -> Color.Transparent
                }
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
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(tint),
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * A footer verb: Zed's `Button` with its 12px key-binding hint beside the
 * label (branch_picker.rs:2019-2054) — the ghost dress every footer button
 * here wears.
 */
@Composable
private fun FooterButton(
    label: String,
    hint: String,
    enabled: Boolean,
    isPrimary: Boolean = false,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .height(22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    pressed && enabled -> theme.color("ghost_element.active")
                    hovered && enabled -> theme.color("ghost_element.hover")
                    else -> Color.Transparent
                }
            )
            .then(
                if (enabled) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = onClick,
                        )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                !enabled -> theme.color("text.disabled")
                isPrimary -> theme.color("text.accent")
                else -> theme.color("text")
            },
            maxLines = 1,
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text.muted"),
            maxLines = 1,
        )
    }
}

/**
 * The name with matched characters recoloured to `text.accent` — a colour
 * change only, as Zed's `HighlightedLabel` draws it
 * (crates/ui/src/components/label/highlighted_label.rs:208-218).
 */
private fun highlighted(name: String, positions: List<Int>, color: Color): AnnotatedString {
    if (positions.isEmpty()) return AnnotatedString(name)
    val marked = positions.toHashSet()
    return buildAnnotatedString {
        name.forEachIndexed { index, character ->
            if (index in marked) {
                withStyle(SpanStyle(color = color)) { append(character) }
            } else {
                append(character)
            }
        }
    }
}
