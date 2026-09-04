package to.eyed.thragg.ui.agent.spettro

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.eyed.thragg.R
import to.eyed.thragg.core.AgentPastSession
import to.eyed.thragg.ui.components.ThraggSearchField
import to.eyed.thragg.ui.shell.projects.relativeTime
import to.eyed.thragg.ui.theme.IconSize
import to.eyed.thragg.ui.theme.LocalThraggColors
import to.eyed.thragg.ui.theme.RowChevron
import to.eyed.thragg.ui.theme.ThraggIcon
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// ---------------------------------------------------------------------------
// Which call opens a row
// ---------------------------------------------------------------------------

/** `session/list {cwd}` versus `session/list {}` — the segmented control. */
enum class SessionScope { PROJECT, ALL }

/**
 * The two ways back into a conversation, which are not interchangeable.
 *
 * `session/load` **replays**: Spettro streams every `user_message_chunk`,
 * `agent_thought_chunk` and `agent_message_chunk` back down the wire *before*
 * the response returns. `session/resume` does not — it re-attaches to the
 * conversation and says nothing about its past.
 *
 * So the choice is not a preference, it is a function of what the phone
 * already has on screen. Replaying into a view that already holds the
 * transcript prints the whole conversation twice; resuming into an empty view
 * leaves a session that answers correctly above a blank scrollback.
 */
enum class SessionOpen { LOAD, RESUME }

/**
 * Pick the call, so the user never has to.
 *
 * This is the whole of "make the difference invisible but correct": a row is
 * tapped, and the right method goes out. [alreadyOpen] means this app is
 * already holding a thread for the session — the transcript is in hand, so
 * replaying it would duplicate it.
 *
 * [canReplay] is `AgentCapabilities.loadSession`. Spettro advertises it; an
 * agent that does not gets a resume and the notice, which is honest, rather
 * than a row that does nothing.
 */
internal fun sessionOpenMode(alreadyOpen: Boolean, canReplay: Boolean): SessionOpen = when {
    alreadyOpen -> SessionOpen.RESUME
    canReplay -> SessionOpen.LOAD
    else -> SessionOpen.RESUME
}

/**
 * The sentence at the top of a conversation that arrived by replay.
 *
 * Spettro's on-disk store keeps the flat transcript and nothing else — no
 * tool calls, no plan, no usage. Without this line a reopened session looks
 * like one where the app lost the tool cards, and the first thing a user does
 * with an app that loses data is stop trusting it with any.
 */
const val REPLAYED_SESSION_NOTICE =
    "Earlier tool activity isn't stored — this is the conversation only."

// ---------------------------------------------------------------------------
// Rows, times and grouping — pure
// ---------------------------------------------------------------------------

/** The project a row belongs to: the last segment of its `cwd`. */
internal fun sessionProject(session: AgentPastSession): String =
    session.cwd.trimEnd('/').substringAfterLast('/')

/**
 * Search over the title and the project name, per docs/SPETTRO.md.
 *
 * The project name is in scope because half these rows have no title of their
 * own — an untitled session falls back to its folder — and a search that
 * matched only titles would hide exactly those rows when you typed the folder
 * you were looking for.
 */
internal fun sessionMatches(session: AgentPastSession, query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    return session.label.contains(needle, ignoreCase = true) ||
        sessionProject(session).contains(needle, ignoreCase = true)
}

/**
 * `updatedAt` as epoch millis, or null when it cannot be read.
 *
 * Lenient by design. The field is "an ISO-8601 timestamp, as the agent wrote
 * it" and the agent is a Go program whose store predates the ACP surface; a
 * row that sorts wrong is a bug, but a row that *disappears* because its
 * timestamp had no offset would be worse. Every shape below has been seen in
 * the wild from one Go time formatter or another, and a bare epoch is
 * accepted because `session/list` is not the only writer of that store.
 */
internal fun sessionTimeMillis(updatedAt: String?, zone: ZoneId = ZoneId.systemDefault()): Long? {
    val raw = updatedAt?.trim().orEmpty()
    if (raw.isEmpty()) return null
    // A bare number: seconds up to the year 5138, millis after it. Nothing
    // else can distinguish the two, and the crossover is not a real date.
    if (raw.all { it.isDigit() }) {
        val value = raw.toLongOrNull() ?: return null
        return if (raw.length <= 11) value * 1000L else value
    }
    runCatching { return Instant.parse(raw).toEpochMilli() }
    runCatching { return OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
    runCatching { return ZonedDateTime.parse(raw).toInstant().toEpochMilli() }
    // No offset at all. Reading it as local time is the only reading that puts
    // a session saved five minutes ago under TODAY.
    runCatching { return LocalDateTime.parse(raw).atZone(zone).toInstant().toEpochMilli() }
    runCatching { return LocalDate.parse(raw).atStartOfDay(zone).toInstant().toEpochMilli() }
    return null
}

/** One day's worth of rows, under one header. */
internal data class SessionDay(val label: String, val sessions: List<AgentPastSession>)

/**
 * Newest first, grouped by the day they were last touched.
 *
 * Sorting happens here rather than being taken on trust: `session/list`'s
 * order is the agent's, and a picker whose first row is not the conversation
 * you were just in is a picker nobody scrolls twice.
 *
 * Rows with an unreadable timestamp keep the agent's order and go last, under
 * their own header. Dropping them would hide real conversations, and guessing
 * a date for them would put them at the top on the strength of the guess.
 */
internal fun sessionDays(
    sessions: List<AgentPastSession>,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): List<SessionDay> {
    val dated = mutableListOf<Pair<Long, AgentPastSession>>()
    val undated = mutableListOf<AgentPastSession>()
    for (session in sessions) {
        val at = sessionTimeMillis(session.updatedAt, zone)
        if (at == null) undated += session else dated += at to session
    }
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val groups = dated
        .sortedByDescending { it.first }
        .groupBy { Instant.ofEpochMilli(it.first).atZone(zone).toLocalDate() }
        .map { (day, rows) -> SessionDay(dayLabel(day, today, locale), rows.map { it.second }) }
    return if (undated.isEmpty()) groups else groups + SessionDay("UNDATED", undated)
}

/** `TODAY`, `YESTERDAY`, `SAT 30 AUG`, `30 AUG 2025`. */
internal fun dayLabel(day: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String {
    val month = day.month.getDisplayName(TextStyle.SHORT, locale).uppercase(locale)
    return when {
        day == today -> "TODAY"
        day == today.minusDays(1) -> "YESTERDAY"
        // Inside the last week the weekday is what people actually remember;
        // beyond it the weekday is noise and the date is the fact.
        day.isAfter(today.minusDays(7)) && day.isBefore(today) ->
            "${day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).uppercase(locale)} " +
                "${day.dayOfMonth} $month"
        day.year != today.year -> "${day.dayOfMonth} $month ${day.year}"
        else -> "${day.dayOfMonth} $month"
    }
}

/** `14:02` — the trailing figure on a row, within its day. */
internal fun sessionClock(
    millis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String = Instant.ofEpochMilli(millis)
    .atZone(zone)
    .format(DateTimeFormatter.ofPattern("HH:mm", locale))

// ---------------------------------------------------------------------------
// The picker
// ---------------------------------------------------------------------------

/**
 * Spettro's own conversations, as it stores them.
 *
 * Backed by `session/list` rather than by an app-local list, which is the
 * decision this whole surface rests on: a conversation started in the Spettro
 * TUI on this device appears here, and one started here appears there. Two
 * stores would drift, and the phone's would be the one that was wrong.
 *
 * Consequences of using the agent's store, all deliberate:
 *
 *  - **No delete.** Spettro does not advertise `SessionCapabilities.Delete`,
 *    so `session/delete` answers `false`. A button that never works is worse
 *    than an absent one.
 *  - **No pin, no archive, no folders.** The store has no flags for them, so
 *    they would have to live on the phone, and then two things would disagree
 *    about the same list.
 *  - **The `All` tab depends on the engine.** `refresh_session_list` always
 *    scopes with `.cwd(root)` (acp.rs:546-559) — for a good reason, since
 *    resuming another project's conversation against this project's files
 *    hands the agent paths from a tree its context never saw. Until that scope
 *    is made optional the control is not drawn at all, rather than drawn as a
 *    tab that silently returns the same rows.
 *
 * The text field is *not* here: docs/UI.md pins a sheet's field to the bottom,
 * above the IME, so the host passes [SessionSearchField] to `SheetScaffold`'s
 * `field` slot and hands the value back down as [query].
 */
@Composable
fun SessionPicker(
    sessions: List<AgentPastSession>,
    scope: SessionScope,
    query: String,
    onOpen: (AgentPastSession) -> Unit,
    onResume: (AgentPastSession) -> Unit,
    onNew: () -> Unit,
    /**
     * The connected agent's own name. `session/list` is the agent's own
     * history, so "Asking …" has to name the agent that is being asked.
     */
    agentName: String,
    modifier: Modifier = Modifier,
    onScopeChange: (SessionScope) -> Unit = {},
    /** Session ids this app already holds a thread for — the ● rows. */
    openSessionIds: Set<String> = emptySet(),
    /** `AgentCapabilities.loadSession`. False ⇒ every tap is a resume. */
    canReplay: Boolean = true,
    /** Whether the engine can list beyond the current project yet. */
    allScopeSupported: Boolean = false,
    loading: Boolean = false,
    error: String? = null,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // Recomputed on every list identity rather than cached: `session/list`
    // answers land as a whole new list, and the day a row falls into changes
    // with the clock as much as with the data.
    val visible = remember(sessions, query) { sessions.filter { sessionMatches(it, query) } }
    val days = remember(visible) { sessionDays(visible) }
    // One row's actions at a time. Held by id, so a refreshed list — which is
    // a new list of new objects — keeps the sheet the user opened.
    var expandedId by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        if (allScopeSupported) {
            ScopeControl(scope = scope, onScopeChange = onScopeChange)
            Spacer(Modifier.height(4.dp))
        }
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = LocalThraggColors.current.dangerInk,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (days.isEmpty()) {
                item {
                    Text(
                        text = when {
                            loading -> stringResource(R.string.agent_sessions_loading, agentName)
                            query.isNotBlank() -> "No conversation matches “$query”."
                            else -> "No conversations yet. The first prompt starts one."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
            }
            for (day in days) {
                item(key = "day/${day.label}") {
                    Text(
                        text = day.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = muted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(
                    count = day.sessions.size,
                    key = { index -> day.sessions[index].sessionId },
                ) { index ->
                    val session = day.sessions[index]
                    SessionRow(
                        session = session,
                        isOpen = session.sessionId in openSessionIds,
                        expanded = expandedId == session.sessionId,
                        onTap = {
                            expandedId = null
                            when (sessionOpenMode(session.sessionId in openSessionIds, canReplay)) {
                                SessionOpen.LOAD -> onOpen(session)
                                SessionOpen.RESUME -> onResume(session)
                            }
                        },
                        onLongPress = {
                            expandedId =
                                if (expandedId == session.sessionId) null else session.sessionId
                        },
                        onResume = {
                            expandedId = null
                            onResume(session)
                        },
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clickable(onClickLabel = "New session", onClick = onNew)
                .padding(horizontal = 16.dp),
        ) {
            ThraggIcon(
                icon = R.drawable.ic_ui_plus,
                contentDescription = null,
                tint = LocalThraggColors.current.accentMark,
                size = IconSize.Inline,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                text = "New session",
                style = MaterialTheme.typography.labelLarge,
                color = LocalThraggColors.current.accentInk,
            )
        }
    }
}

/**
 * One conversation, 64 dp.
 *
 * Tap does the right thing without saying which thing it did (see
 * [sessionOpenMode]). Long-press is where the distinction becomes visible, for
 * the one case the rule cannot cover: a session the phone believes it already
 * holds, which the user wants re-attached without a replay.
 *
 * The actions expand *inline* rather than in a popup menu. A dropdown inside a
 * modal bottom sheet needs its own window and lands under the sheet's scrim on
 * some devices, and two 48 dp rows under the one that was pressed are easier
 * to hit one-handed than a menu anchored to a finger.
 */
@Composable
private fun SessionRow(
    session: AgentPastSession,
    isOpen: Boolean,
    expanded: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onResume: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val at = remember(session.updatedAt) { sessionTimeMillis(session.updatedAt) }
    val project = sessionProject(session)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = RowHeight)
                .background(
                    if (expanded) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        Color.Transparent
                    },
                )
                .combinedClickable(
                    onClickLabel = session.label,
                    onLongClickLabel = "Session options",
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    },
                    onClick = onTap,
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            // A filled dot for a conversation this app is already holding
            // open, a hollow one for a conversation that only exists in the
            // agent's store.
            ThraggIcon(
                icon = if (isOpen) R.drawable.ic_ui_dot else R.drawable.ic_ui_circle,
                contentDescription = if (isOpen) "open" else null,
                tint = if (isOpen) {
                    LocalThraggColors.current.accentMark
                } else {
                    muted
                },
                size = IconSize.Marker,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The second line carries both facts the wireframe asks for.
                // The clock on the right places the row inside its day; this
                // says which day in words, because "yesterday 18:20" is what
                // gets remembered and a bare time is not.
                val when_ = at?.let { relativeTime(it) }
                val second = listOfNotNull(
                    // Suppressed when the title already *is* the folder name:
                    // an untitled session falls back to its basename, and the
                    // row would otherwise print it twice.
                    project.takeIf { it.isNotEmpty() && it != session.label },
                    when_,
                ).joinToString(" · ")
                if (second.isNotEmpty()) {
                    Text(
                        text = second,
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (at != null) {
                Text(
                    text = sessionClock(at),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFeatureSettings = "tnum",
                    ),
                    color = muted,
                    maxLines = 1,
                )
            }
            RowChevron(tint = muted)
        }
        if (expanded) {
            RowAction(
                label = "Resume without replay",
                subtitle = "Re-attach without re-sending the transcript.",
                onClick = onResume,
            )
            RowAction(
                label = "Copy session id",
                subtitle = session.sessionId,
                onClick = {
                    clipboard.setText(AnnotatedString(session.sessionId))
                },
            )
        }
    }
}

/** docs/SPETTRO.md's 64 dp row — a minimum, so a two-line title still fits. */
private val RowHeight = 64.dp

/** One long-press action, indented under the row it belongs to. */
@Composable
private fun RowAction(label: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(start = 42.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = LocalThraggColors.current.accentInk,
            maxLines = 1,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** `[ This project │ All ]`, drawn only when the engine can answer both. */
@Composable
private fun ScopeControl(scope: SessionScope, onScopeChange: (SessionScope) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        for (option in SessionScope.entries) {
            val selected = option == scope
            val label = if (option == SessionScope.PROJECT) "This project" else "All"
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            Color.Transparent
                        },
                    )
                    .clickable(onClickLabel = label) { onScopeChange(option) },
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The picker's search field, for the host to pin at the bottom of the sheet.
 *
 * [ThraggSearchField] is the app's one search pill — 20dp, `surfaceContainerHigh`,
 * a hairline that warms to the accent while it holds the caret, and a clear
 * button that appears only once there is something to clear. This file used to
 * hand-roll an 8dp version of it with no focus state at all; every search field
 * in the app is now the same control, which is the point of having one.
 */
@Composable
fun SessionSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ThraggSearchField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = "Search conversations",
        modifier = modifier,
    )
}

/**
 * The line at the top of a replayed conversation.
 *
 * Mounted by the transcript, not by the picker, because it belongs to the
 * conversation and has to survive the picker being dismissed.
 */
@Composable
fun ReplayedSessionNotice(modifier: Modifier = Modifier) {
    Text(
        text = REPLAYED_SESSION_NOTICE,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * What a session shows while `session/load` is streaming its replay.
 *
 * A skeleton rather than a spinner because a replay arrives in order and fills
 * from the top, so the shape it is filling into is the truthful thing to draw.
 * The view must be cleared before the replay starts — the chunks are the same
 * ones a live turn sends, and appending them to an existing transcript would
 * double it.
 */
@Composable
fun SessionReplaySkeleton(modifier: Modifier = Modifier) {
    val bar = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Replaying the conversation…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (fraction in listOf(0.9f, 0.65f, 0.8f, 0.4f)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(bar),
            )
        }
    }
}
