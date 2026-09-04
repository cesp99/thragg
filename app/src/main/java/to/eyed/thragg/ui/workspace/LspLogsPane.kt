package to.eyed.thragg.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONException
import org.json.JSONObject
import to.eyed.thragg.core.CoreBridge
import to.eyed.thragg.core.ResumedEffect
import to.eyed.thragg.core.pollVersion
import to.eyed.thragg.ui.theme.LocalSeekerColors
import to.eyed.thragg.ui.theme.MonoSmall

/**
 * Zed's language server log view (crates/language_tools/src/lsp_log_view.rs,
 * `dev::OpenLanguageServerLogs`), as a read-only tab: the server's stderr,
 * its `window/logMessage`s and the RPC trace, in one list — Zed's "Server
 * Logs" and "RPC Messages" tabs folded together, which on a phone is one
 * list fewer to switch between. The engine keeps the last two thousand
 * lines per server; the tab follows the tail while it is at the tail.
 */

/** The log's `{version, lines}`, as `lspServerLogs` answers. */
internal data class ServerLog(val version: Long, val lines: List<String>) {
    companion object {
        val EMPTY = ServerLog(0L, emptyList())

        fun parse(json: String?): ServerLog {
            if (json.isNullOrEmpty()) return EMPTY
            return try {
                val root = JSONObject(json)
                val array = root.optJSONArray("lines")
                ServerLog(
                    version = root.optLong("version", 0L),
                    lines = (0 until (array?.length() ?: 0)).map { array?.optString(it).orEmpty() },
                )
            } catch (_: JSONException) {
                EMPTY
            }
        }
    }
}

/** How often the log's counter is read. It moves per line; the read is only on a move. */
private const val LOG_POLL_MILLIS = 400L

@Composable
fun LspLogsPane(
    projectId: Long,
    serverName: String,
    modifier: Modifier = Modifier,
) {
    var log by remember(projectId, serverName) { mutableStateOf(ServerLog.EMPTY) }
    val listState = rememberLazyListState()

    ResumedEffect(projectId, serverName) {
        pollVersion(
            intervalMs = LOG_POLL_MILLIS,
            version = { CoreBridge.lspServerLogsVersion(projectId, serverName) },
            read = { ServerLog.parse(CoreBridge.lspServerLogs(projectId, serverName)) },
            apply = { log = it },
        )
    }

    // Follow the tail, as a terminal does, unless the reader has scrolled
    // up to look at something.
    LaunchedEffect(log.lines.size) {
        val info = listState.layoutInfo
        val atTail = info.visibleItemsInfo.lastOrNull()?.index?.let { it >= info.totalItemsCount - 3 } ?: true
        if (atTail && log.lines.isNotEmpty()) listState.scrollToItem(log.lines.lastIndex)
    }

    // A pane the shell raises, not a buffer: `editor.background` and
    // `toolbar.background` become the M3 surface and the container rung above
    // it. Nothing here has to agree with tree-sitter — the lines are apt's and
    // the server's prose, and the one colour that carries meaning is the
    // stderr warning below.
    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = serverName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${log.lines.size} lines · stderr, log messages and RPC traffic",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (log.lines.isEmpty()) {
            Text(
                text = "Nothing logged yet. The server writes here as it starts and answers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        } else {
            // `warnInk`, solved: a `[stderr]` line is the one thing in this
            // list worth finding at a glance, and the raw `warning` key is
            // 1.64:1 on Ayu Light's panel.
            val warning = LocalSeekerColors.current.warnInk
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    items(log.lines) { line ->
                        Text(
                            text = line,
                            // The BUFFER face, not the system mono: a log
                            // line set in Droid Sans Mono over Material ink
                            // matches neither half (Type.kt, [MonoSmall]).
                            style = MonoSmall,
                            color = when {
                                line.startsWith("[stderr]") -> warning
                                line.startsWith("→") || line.startsWith("←") ->
                                    MaterialTheme.colorScheme.onSurfaceVariant

                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}
