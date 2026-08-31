package to.eyed.seeker.code.ui.shell.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.core.AgentDefinition
import to.eyed.seeker.code.core.AgentSessions
import to.eyed.seeker.code.solana.agents.AgentCatalog
import to.eyed.seeker.code.solana.agents.SpettroInstall
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.shell.SheetScaffold
import to.eyed.seeker.code.ui.components.SelectRow
import to.eyed.seeker.code.ui.theme.LocalAppSettings
import to.eyed.seeker.code.ui.theme.LocalSeekerColors
import to.eyed.seeker.code.ui.theme.MD

/**
 * Which agent this project talks to — which on this device is very nearly a
 * settled question.
 *
 * Seeker IDE ships **one** agent. Claude Code and Codex are gone from the
 * catalogue along with the whole npm install path (docs/SPETTRO.md, W-14:
 * "One bundled agent, no picker"), so this is not the three-way chooser
 * docs/UI.md described before the Spettro work landed. What is left is the two
 * things a user can still need:
 *
 *  1. **Register the bundled agent**, when the binary is on disk but no
 *     `agent_servers` entry points at it — a fresh install, or an entry
 *     deleted by hand. [SpettroInstall.ensureRegistered] is idempotent and
 *     cheap when there is nothing to do, and it is the only writer of that
 *     entry.
 *  2. **Switch to a hand-written entry.** Any other ACP agent is an
 *     `agent_servers` entry in settings.json, and this lists what is there
 *     rather than pretending the file cannot be edited.
 *
 * Choosing starts a **new thread** with that agent rather than purging the
 * open ones: what a running conversation is bound to is the process it
 * started with.
 */
@Composable
fun AgentPickerSheet(
    shell: ShellState,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    val context = LocalContext.current
    val settings = LocalAppSettings.current
    val scope = rememberCoroutineScope()
    val current = AgentSessions.agent
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    SheetScaffold(state = shell, onDismiss = onDismiss, title = "Agent") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MD.space4, vertical = MD.space2),
        ) {
            val bundled = settings.agents.firstOrNull { it.name == SpettroInstall.NAME }
            if (bundled == null) {
                AgentRow(
                    name = AgentCatalog.SPETTRO.name,
                    detail = if (working) {
                        "Registering…"
                    } else {
                        AgentCatalog.SPETTRO.summary
                    },
                    selected = false,
                    onClick = {
                        if (working) return@AgentRow
                        working = true
                        problem = null
                        scope.launch {
                            // Blocking: it stats the rootfs, spawns proot to
                            // probe the ACP flag, and writes settings through
                            // the engine.
                            val result = withContext(Dispatchers.IO) {
                                SpettroInstall.ensureRegistered(context)
                            }
                            working = false
                            when (result) {
                                is SpettroInstall.Result.Registered -> {
                                    AgentSessions.choose(result.agent)
                                    onDismiss()
                                }
                                is SpettroInstall.Result.Unconfirmed -> {
                                    // A success with a caveat: the entry is
                                    // written and usable, and the note says
                                    // only that --help did not mention the
                                    // flag. Refusing over a wording change
                                    // would be worse than an editable entry.
                                    problem = result.note
                                    AgentSessions.choose(result.agent)
                                }
                                is SpettroInstall.Result.Failed -> problem = result.message
                            }
                        }
                    },
                )
            }
            for (agent in settings.agents) {
                AgentRow(
                    name = agent.name,
                    detail = agent.argv.joinToString(" "),
                    selected = agent.name == current?.name,
                    onClick = {
                        chooseAgent(shell, agent)
                        onDismiss()
                    },
                )
            }
            problem?.let { message ->
                Text(
                    // The install's own words. "Spettro is not installed yet"
                    // names the download; a rewrite of it names nothing.
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalSeekerColors.current.dangerInk,
                    modifier = Modifier.padding(vertical = MD.space2),
                )
                LinkRow("Set up the toolchain", onOpenSetup)
            }
            Text(
                text = "Any other ACP agent is an agent_servers entry in settings.json.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = MD.space2),
            )
            LinkRow("Edit settings.json", onOpenSettings)
            Spacer(Modifier.height(MD.space6))
        }
    }
}

/**
 * Start a thread with [agent] for the open project.
 *
 * A new thread rather than a re-attach, and the threads the previous agent has
 * are left alone: an agent is a *process*, and a conversation cannot be moved
 * from one to another.
 */
private fun chooseAgent(shell: ShellState, agent: AgentDefinition) {
    val project = shell.project
    if (project == null) {
        AgentSessions.choose(agent)
        return
    }
    AgentSessions.startWith(agent, project.id, project.rootName, project.rootPath)
}

/**
 * One agent: the mark, its name, and what it actually runs.
 *
 * [SelectRow] rather than a hand-rolled row — the whole row is the target, the
 * `selectable` semantics announce "selected, radio button", and the press gets
 * the state layer back. The detail line is the argv, because two entries
 * called "spettro" pointing at different binaries is a real settings.json.
 */
@Composable
private fun AgentRow(
    name: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    SelectRow(
        label = name,
        description = detail,
        selected = selected,
        onSelect = onClick,
    )
}

/** A way out of this sheet and into a screen that can fix the problem. */
@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = LocalSeekerColors.current.accentInk,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = label, onClick = onClick)
            .heightIn(min = MD.rowMin)
            .padding(vertical = MD.space3),
    )
}
