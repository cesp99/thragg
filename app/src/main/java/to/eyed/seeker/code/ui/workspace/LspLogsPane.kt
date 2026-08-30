package to.eyed.seeker.code.ui.workspace

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.json.JSONException
import org.json.JSONObject
import to.eyed.seeker.code.core.CoreBridge
import to.eyed.seeker.code.core.ResumedEffect
import to.eyed.seeker.code.core.pollVersion
import to.eyed.seeker.code.ui.theme.LocalZedTheme

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
    val theme = LocalZedTheme.current
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

    Column(modifier = modifier.fillMaxSize().background(theme.color("editor.background"))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(theme.color("toolbar.background", theme.color("editor.background")))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = serverName,
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text"),
            )
            Text(
                text = "${log.lines.size} lines · stderr, log messages and RPC traffic",
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted"),
            )
        }
        if (log.lines.isEmpty()) {
            Text(
                text = "Nothing logged yet. The server writes here as it starts and answers.",
                style = MaterialTheme.typography.bodySmall,
                color = theme.color("text.muted"),
                modifier = Modifier.padding(12.dp),
            )
        } else {
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
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = when {
                                line.startsWith("[stderr]") -> theme.color("warning", theme.color("text"))
                                line.startsWith("→") || line.startsWith("←") -> theme.color("text.muted")
                                else -> theme.color("text")
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
