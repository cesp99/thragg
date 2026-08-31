package to.eyed.seeker.code.ui.shell.projects

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.core.ProjectsRoot
import to.eyed.seeker.code.solana.templates.SolanaFramework
import to.eyed.seeker.code.solana.templates.SolanaNames
import to.eyed.seeker.code.solana.templates.SolanaProgram
import to.eyed.seeker.code.solana.templates.SolanaScaffold
import to.eyed.seeker.code.ui.components.BottomActions
import to.eyed.seeker.code.ui.components.BottomActionsGap
import to.eyed.seeker.code.ui.components.Choice
import to.eyed.seeker.code.ui.components.SectionHeader
import to.eyed.seeker.code.ui.components.SeekerCard
import to.eyed.seeker.code.ui.components.SegmentedSelect
import to.eyed.seeker.code.ui.components.fadeUnderBottomActions
import to.eyed.seeker.code.ui.shell.Destination
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.theme.MD
import to.eyed.seeker.code.ui.theme.MonoSmall
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
 * THE FRAMEWORK PICKER IS `SegmentedSelect`, and it is the first non-agent use
 * of it — which is the whole argument for that component living in
 * `ui/components/` rather than inside the agent's config sheet. Three flat
 * choices with the active one's sentence printed underneath is the same
 * problem here as it is there, and three cards each carrying a radio and a
 * blurb was 60dp of screen per option to say what one line says
 * (docs/VISUAL.md, "New program / Clone").
 *
 * The route's ← and title come from the shell's RouteHost, so this composable
 * is the body: fields that scroll, actions pinned at the bottom.
 */
@Composable
fun NewProgramScreen(state: ShellState, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    var name by remember { mutableStateOf("") }
    var framework by remember { mutableStateOf(SolanaFramework.Anchor) }
    var cluster by remember { mutableStateOf(SolanaProgram.DEFAULT_CLUSTER) }
    // The checkbox that is this design's thesis expressed as one checkbox
    // (docs/UI.md, "New program"), and it is on by default.
    var openThread by remember { mutableStateOf(true) }
    var creating by remember { mutableStateOf(false) }

    // Where the folder will land. `ProjectsRoot.directory` does an `mkdirs`,
    // so it is a filesystem write and cannot be called from a composition —
    // it is read once, off the main thread, and the row draws nothing until
    // it answers.
    var projectsDir by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        projectsDir = withContext(Dispatchers.IO) {
            runCatching { ProjectsRoot.directory(context).absolutePath }.getOrNull()
        }
    }

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

    val frameworks = SolanaFramework.entries.map { option ->
        Choice(
            value = option.name,
            name = stringResource(option.labelRes),
            // Language first, then the sentence: on one line under the row
            // the language is the fastest discriminator between the three,
            // and the blurb is what is read second.
            description = stringResource(option.languageRes) + " · " +
                stringResource(option.blurbRes),
        )
    }
    val clusters = SolanaProgram.CLUSTERS.map { Choice(value = it, name = it) }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // The form is CLIPPED where the action bar begins, and LOCATION
                // — the last field, and the one nobody scrolls looking for —
                // was the row that landed on the cut. The fade turns the cut
                // into "there is more", and the gap at the foot of this column
                // is what lets the last row scroll clear of it
                // ([BottomActions]). Before the scroll, so it masks the
                // VIEWPORT rather than the content.
                .fadeUnderBottomActions()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MD.space4),
        ) {
            SectionHeader("Name", modifier = Modifier.padding(top = MD.space4))
            SheetTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "escrow",
                // The rule and the refusal share one slot. `supportingText`
                // is announced as the field's error when `isError` is set and
                // as its help when it is not, which is what a separately drawn
                // line under a field could never be.
                error = error,
                autoFocus = true,
            )
            // The derivation, live and always visible — the line that makes
            // "My Project" becoming `my_project` a decision the user watched
            // rather than one they find in Cargo.toml later. Set in the buffer
            // face, because all three of them are identifiers that will appear
            // in a buffer.
            Text(
                text = "crate ${program.crateName} · mod ${program.moduleName} · " +
                    "type ${program.typeName}",
                style = MonoSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = MD.space2),
            )

            SectionHeader("Framework", modifier = Modifier.padding(top = MD.space6))
            SegmentedSelect(
                options = frameworks,
                selectedValue = framework.name,
                onSelect = { value ->
                    framework = SolanaFramework.entries.first { it.name == value }
                },
            )
            if (framework == SolanaFramework.Seahorse) {
                // Seahorse honesty, in as many words: a framework you can
                // create but cannot build is worse than one never offered
                // (docs/UI.md, "New program").
                Text(
                    text = "Seahorse compiles to Rust and builds through anchor build. " +
                        "It needs Python in the Linux guest — Build installs it the first time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = MD.space2),
                )
            }

            SectionHeader("Cluster", modifier = Modifier.padding(top = MD.space6))
            SegmentedSelect(
                options = clusters,
                selectedValue = cluster,
                onSelect = { cluster = it },
                // The four cluster names say everything a description would;
                // the sentence below is about where the choice is *written*,
                // which is a property of the form and not of the choice.
                showActiveDescription = false,
            )
            Text(
                // Native has no Anchor.toml to put it in, and saying so beats
                // a chip that silently does nothing.
                text = if (framework == SolanaFramework.Native) {
                    "Written into the deploy command. A native program has no Anchor.toml."
                } else {
                    "Written into Anchor.toml as [provider] cluster. Changeable later."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = MD.space2),
            )

            SectionHeader("Location", modifier = Modifier.padding(top = MD.space6))
            // A card and not a drill row: there is one project root on this
            // device and it is app-private storage by design
            // (core/ProjectsRoot.kt), so a chevron here would promise a picker
            // that cannot exist. It is here to be *read* — the one question
            // "where did my program go" needs an answer on the screen that
            // made it.
            SeekerCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = projectsDir?.let { "$it/${trimmed.ifEmpty { "escrow" }}" } ?: "",
                    style = MonoSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MD.rowMin)
                        .padding(horizontal = MD.space3, vertical = MD.rowPadY),
                )
            }

            // The row is the target and the checkbox is the mark, so the
            // toggle semantics live on the row rather than on the box —
            // otherwise the announced control is 18dp wide and the sentence
            // beside it is unreachable.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MD.space2),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MD.space6)
                    .toggleable(
                        value = openThread,
                        role = Role.Checkbox,
                        onValueChange = { openThread = it },
                    )
                    .heightIn(min = MD.rowMin),
            ) {
                Checkbox(checked = openThread, onCheckedChange = null)
                Text(
                    text = "Open a thread and describe it to the agent afterwards",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(BottomActionsGap))
        }

        // Actions at the bottom of the screen, above the IME — the rule every
        // surface in this app follows (docs/UI.md). Create is DISABLED rather
        // than hidden while the name is bad, so the reason for its state can
        // be read off the field above it (docs/VISUAL.md). The hairline, the
        // insets and the padding are [BottomActions]', so this bar sits on the
        // same seam as Setup's and every sheet's.
        BottomActions {
            Row(
                horizontalArrangement = Arrangement.spacedBy(MD.space3, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = { state.pop() }) {
                    Text("Cancel", style = MaterialTheme.typography.labelLarge)
                }
                Button(
                    onClick = {
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
                    enabled = canCreate,
                ) {
                    Text(
                        text = if (creating) "Creating…" else "Create",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
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
