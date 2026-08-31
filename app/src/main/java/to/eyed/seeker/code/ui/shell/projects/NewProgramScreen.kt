package to.eyed.seeker.code.ui.shell.projects

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.core.ProjectsRoot
import to.eyed.seeker.code.solana.templates.SolanaFramework
import to.eyed.seeker.code.solana.templates.SolanaNames
import to.eyed.seeker.code.solana.templates.SolanaProgram
import to.eyed.seeker.code.solana.templates.SolanaScaffold
import to.eyed.seeker.code.ui.shell.Destination
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.SelectionMark
import to.eyed.seeker.code.ui.workspace.Notifications

/**
 * New program — the 'create' step of the loop, and three taps plus a name.
 *
 * The dialog Solana Playground opens is the model, down to the framework
 * list: Anchor, Native, Seahorse and nothing else (docs/SOLANA.md,
 * "Projects"). What is added on this side is the live name preview, because
 * the three names a Solana project needs are all derived from the one typed
 * ("My Project" → crate `my-project`, module `my_project`, IDL type
 * `MyProject`) and a scaffold whose derivation is a surprise is a scaffold
 * whose `Anchor.toml` looks wrong.
 *
 * **The toolchain is not required to get here or to leave.** Scaffolding is
 * eight `writeText` calls; Setup appears the first time Build is pressed
 * (docs/UI.md, "New program"). Nobody waits on a gigabyte before seeing their
 * code.
 *
 * The route's ← and title come from the shell's RouteHost, so this composable
 * is the body: fields that scroll, actions pinned at the bottom.
 */
@Composable
fun NewProgramScreen(state: ShellState, modifier: Modifier = Modifier) {
    val theme = LocalZedTheme.current
    val context = LocalContext.current

    var name by remember { mutableStateOf("") }
    var framework by remember { mutableStateOf(SolanaFramework.Anchor) }
    var cluster by remember { mutableStateOf(SolanaProgram.DEFAULT_CLUSTER) }
    // The checkbox that is this design's thesis expressed as one checkbox
    // (docs/UI.md, "New program"), and it is on by default.
    var openThread by remember { mutableStateOf(true) }
    var creating by remember { mutableStateOf(false) }

    val trimmed = name.trim()
    // Two validators, in the order the two failures happen: the directory
    // first (ProjectsRoot owns the name the user actually typed), then the
    // crate (SolanaNames owns what cargo will be asked to call it). Both are
    // silent until something has been typed — a form that opens red is a form
    // that has told you off for nothing.
    val error = remember(trimmed) {
        if (trimmed.isEmpty()) null
        else ProjectsRoot.nameError(context, trimmed) ?: SolanaNames.error(trimmed)
    }
    val program = remember(trimmed) { SolanaProgram.of(trimmed.ifEmpty { "program" }) }
    val canCreate = trimmed.isNotEmpty() && error == null && !creating

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding),
        ) {
            FieldLabel("Name")
            SheetTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "escrow",
                error = error,
                autoFocus = true,
            )
            // The derivation, live and always visible — the line that makes
            // "My Project" becoming `my_project` a decision the user watched
            // rather than one they find in Cargo.toml later.
            Text(
                text = "crate ${program.crateName} · mod ${program.moduleName} · " +
                    "type ${program.typeName}",
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.padding(top = 6.dp),
            )

            FieldLabel("Framework")
            for (option in SolanaFramework.entries) {
                FrameworkCard(
                    framework = option,
                    isSelected = option == framework,
                    onClick = { framework = option },
                )
            }
            if (framework == SolanaFramework.Seahorse) {
                // Seahorse honesty, in as many words: a framework you can
                // create but cannot build is worse than one never offered
                // (docs/UI.md, "New program").
                Text(
                    text = "Seahorse compiles to Rust and builds through anchor build. " +
                        "It needs Python in the Linux guest — Build installs it the first time.",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            FieldLabel("Cluster")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (option in SolanaProgram.CLUSTERS) {
                    Chip(
                        label = option,
                        isSelected = option == cluster,
                        onClick = { cluster = option },
                    )
                }
            }
            Text(
                // Native has no Anchor.toml to put it in, and saying so beats
                // a chip that silently does nothing.
                text = if (framework == SolanaFramework.Native) {
                    "Written into the deploy command. A native program has no Anchor.toml."
                } else {
                    "Written into Anchor.toml as [provider] cluster. Changeable later."
                },
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.padding(top = 6.dp),
            )

            CheckRow(
                checked = openThread,
                label = "Open a thread and describe it to the agent afterwards",
                onToggle = { openThread = !openThread },
            )
        }

        // Actions at the bottom of the screen, above the IME — the rule every
        // surface in this app follows (docs/UI.md).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = ScreenPadding, vertical = 8.dp),
        ) {
            SheetButtons(
                cancelLabel = "Cancel",
                onCancel = { state.pop() },
                confirmLabel = if (creating) "Creating…" else "Create",
                confirmEnabled = canCreate,
                onConfirm = {
                    creating = true
                    // [ProjectWork], not a composition scope: creating ends
                    // by opening the project, which pops this very route.
                    ProjectWork.launch {
                        val created = createProgram(
                            context = context,
                            state = state,
                            displayName = trimmed,
                            framework = framework,
                            cluster = cluster,
                            openThread = openThread,
                        )
                        creating = false
                        if (created) state.pop()
                    }
                },
            )
        }
    }
}

/**
 * Create the directory, write the template into it, open it, and land the
 * user where the design says they should land.
 *
 * Every step is blocking and every one is on IO: `mkdirs`, the template's
 * eight `writeText`s, and `ProjectSession`'s JNI hop into the engine.
 *
 * The ordering is the whole of the function and none of it is arbitrary.
 * The directory is created **before** the files, obviously; the project is
 * opened **after** the files, so the engine's first scan sees a finished
 * scaffold rather than an empty folder it would have to be told about again;
 * and the landing — a thread, or the program's source in Code — happens
 * **last**, because both need the open project.
 *
 * Returns whether the route should be popped. False leaves the form up with
 * its error on screen, which is the only place the typed name still exists.
 */
private suspend fun createProgram(
    context: Context,
    state: ShellState,
    displayName: String,
    framework: SolanaFramework,
    cluster: String,
    openThread: Boolean,
): Boolean {
    val root = withContext(Dispatchers.IO) { ProjectsRoot.create(context, displayName) }
    if (root == null) {
        Notifications.error("Could not create $displayName", key = "new-program")
        return false
    }
    val program = SolanaProgram.of(displayName)
    val written = withContext(Dispatchers.IO) {
        SolanaScaffold.write(root, framework, program, cluster)
    }
    if (written is SolanaScaffold.Result.Failed) {
        // The directory stays, deliberately — see the note on
        // [SolanaScaffold.write]. What is on disk is what the user asked for
        // minus one file, and deleting the lot would be the worse answer.
        Notifications.error(written.reason, key = "new-program")
        return false
    }
    val opened = openProjectInShell(context, state, root.absolutePath) ?: return false
    val entry = (written as SolanaScaffold.Result.Written).entryPath

    // The scaffold's entry file is opened either way. It costs one queued
    // path, it is what the user came to see, and it is what makes Code a
    // finished screen rather than an empty one the moment they tap the bar.
    state.openPath?.invoke(entry)

    if (openThread) {
        // The design's thesis: a new program lands in a conversation about
        // it, not in a file tree. The prompt is *seeded, not sent* — it goes
        // into the composer for the user to finish, because "describe it to
        // the agent" is the user's sentence and this is only its first half.
        AgentThreadSeed.seed(
            AgentThreadSeed.Request(
                projectId = opened.id,
                prompt = "This is a new ${framework.name} program called " +
                    "${program.crateName}. ",
                openPath = entry,
            )
        )
    }
    // …but the *switch* only happens when there is a conversation to switch
    // to. Until P3's AgentScreen registers itself the Agent destination is a
    // placeholder, and sending a user who has just pressed Create to a screen
    // that says "(P3)" would end the one flow this app exists for on a blank
    // rectangle. The seed keeps waiting — [AgentThreadSeed] is keyed by
    // project id and drained by whoever arrives — so nothing is lost, and the
    // day AgentScreen lands this becomes the behaviour the checkbox promises
    // with no change here.
    if (openThread && AgentThreadSeed.hasReader) {
        state.show(Destination.Agent)
    } else {
        state.show(Destination.Code)
        if (openThread) {
            Notifications.info(
                "No coding agent is set up yet — ${program.crateName} is open in Code.",
                key = "new-program",
            )
        }
    }
    return true
}

/**
 * The seam between "a program was just scaffolded" and the Agent destination
 * — P3's, and null here on purpose (docs/UI.md, P3 owns ui/agent/).
 *
 * A one-slot mailbox rather than a call, because the two halves do not exist
 * at the same time: the thread is started by the Agent screen when it first
 * composes for a project, which is *after* this route has popped. P3's
 * AgentScreen reads [take] once it has a thread and puts [Request.prompt]
 * into the composer draft (`AgentThread.draft`, core/AgentSessions.kt:69).
 *
 * [Request.projectId] is checked by the consumer, not by this object: a seed
 * left behind by a create that was followed by a project switch is a seed for
 * a conversation that will never happen, and it must not be pasted into the
 * next project's thread.
 */
object AgentThreadSeed {

    /** What a freshly scaffolded program wants said about it. */
    data class Request(
        /** The [to.eyed.seeker.code.core.ProjectSession.id] this is about. */
        val projectId: Long,
        /** The composer's opening text, ending in a space for the user to continue. */
        val prompt: String,
        /** The program's source, so the thread can offer it as context. */
        val openPath: String,
    )

    @Volatile
    private var pending: Request? = null

    /**
     * Whether anything is listening — set to true by P3's AgentScreen the
     * first time it composes, and read by [createProgram] to decide whether
     * "open a thread and describe it to the agent" is a promise it can keep.
     *
     * Deliberately not a `hasAgent` check against the ACP catalog: what
     * matters here is not whether an agent is *installed* but whether the
     * destination can draw a thread at all, and while the Agent destination is
     * a placeholder the honest answer is no. One assignment removes this.
     */
    @Volatile
    var hasReader: Boolean = false

    fun seed(request: Request) {
        pending = request
    }

    /** The pending seed for [projectId], consumed. Null when there is none. */
    fun take(projectId: Long): Request? {
        val request = pending ?: return null
        if (request.projectId != projectId) return null
        pending = null
        return request
    }

    /** Drop whatever is waiting — a project switch, or a closed project. */
    fun clear() {
        pending = null
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = LocalZedTheme.current.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

/**
 * One framework, as a card: a radio, the name, the language and one line on
 * why you would pick it.
 *
 * A card rather than a row of chips because the blurb is the point — the
 * three are not three flavours of the same thing, and someone arriving from a
 * tutorial needs to know which one the tutorial was written for.
 */
@Composable
private fun FrameworkCard(
    framework: SolanaFramework,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .background(
                if (isSelected) {
                    theme.color("element.selected", MaterialTheme.colorScheme.surfaceVariant)
                } else {
                    theme.color("element.background", MaterialTheme.colorScheme.surface)
                },
                RoundedCornerShape(10.dp),
            )
            .border(
                width = 1.dp,
                color = if (isSelected) {
                    theme.color("border.focused", MaterialTheme.colorScheme.primary)
                } else {
                    theme.color("border", MaterialTheme.colorScheme.outline)
                },
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .heightIn(min = CardHeight)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        SelectionMark(
            selected = isSelected,
            multi = false,
            tint = theme.color("text.accent", MaterialTheme.colorScheme.primary),
            modifier = Modifier.padding(end = 10.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(framework.labelRes),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = theme.color("text", MaterialTheme.colorScheme.onSurface),
                )
                Text(
                    text = stringResource(framework.languageRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                text = stringResource(framework.blurbRes),
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun Chip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = if (isSelected) {
            theme.color("text", MaterialTheme.colorScheme.onSurface)
        } else {
            theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
        },
        modifier = Modifier
            .background(
                if (isSelected) {
                    theme.color("element.selected", MaterialTheme.colorScheme.surfaceVariant)
                } else {
                    theme.color("element.background", MaterialTheme.colorScheme.surface)
                },
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
private fun CheckRow(checked: Boolean, label: String, onToggle: () -> Unit) {
    val theme = LocalZedTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp)
            .clickable(onClick = onToggle)
            .heightIn(min = CardHeight),
    ) {
        SelectionMark(
            selected = checked,
            multi = true,
            tint = theme.color("text.accent", MaterialTheme.colorScheme.primary),
            modifier = Modifier.padding(end = 10.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = theme.color("text", MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.weight(1f),
        )
    }
}

private val ScreenPadding = 16.dp
private val CardHeight = 44.dp
