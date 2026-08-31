package to.eyed.seeker.code.ui.agent.spettro

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import to.eyed.seeker.code.R
import to.eyed.seeker.code.core.ConnectOutcome
import to.eyed.seeker.code.core.LocalProbe
import to.eyed.seeker.code.core.ProviderEntry
import to.eyed.seeker.code.core.SpettroSetup
import to.eyed.seeker.code.ui.shell.SheetScaffold
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.theme.IconSize
import to.eyed.seeker.code.ui.theme.SeekerIcon
import to.eyed.seeker.code.ui.theme.SeekerIconButton
import to.eyed.seeker.code.ui.theme.SelectionMark
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.touchTarget

// ---------------------------------------------------------------------------
// Card 1 — sign in
// ---------------------------------------------------------------------------

/**
 * The device-flow sign-in: a link, a browser, and a sheet that waits.
 *
 * The waiting is the interesting part, and it is deliberately *not* a poll of
 * the backend. The agent owns that poller and pushes `_spettro/account/update`
 * as the state moves; this sheet watches [SpettroSetup.login], which the
 * driver in `SpettroSetup` fills from the pushed cache with a two-second read
 * of the agent's own local mirror behind it as a dropped-notification net
 * (docs/SPETTRO.md, "Card 1", steps 3 and 4).
 *
 * Cancel is a real cancel — `_spettro/account/login/cancel` — because a login
 * left open holds a code that would still work ten minutes later.
 */
@Composable
fun SignInSheet(
    state: ShellState,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val theme = LocalZedTheme.current
    val login = SpettroSetup.login

    // Started from the sheet rather than from the card, so a dismissed sheet
    // and a cancelled login are the same event.
    LaunchedEffect(Unit) { SpettroSetup.startLogin(onOpenUrl) }

    // A completed login is the end of the sheet; the screen behind it watches
    // the gate and takes itself away.
    LaunchedEffect(login?.status) {
        if (login?.status == "complete") onDismiss()
    }

    SheetScaffold(
        state = state,
        onDismiss = {
            SpettroSetup.cancelLogin()
            onDismiss()
        },
        title = "Sign in to Spettro",
        actions = {
            SheetButton(
                label = "Cancel",
                primary = false,
                onClick = {
                    SpettroSetup.cancelLogin()
                    onDismiss()
                },
            )
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            val message = when (login?.status) {
                null, "starting" -> "Asking Spettro for a sign-in link…"
                "pending" -> "Waiting for you to finish in the browser. Come back when " +
                    "you have — this page will notice on its own."
                "complete" -> "Signed in."
                "expired" -> "The link expired. Close this and try again."
                "cancelled" -> "The sign-in was cancelled."
                "error" -> login.error ?: "The sign-in failed."
                else -> "Waiting…"
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text", MaterialTheme.colorScheme.onSurface),
            )
            val url = login?.browserUrl
            if (url != null && login.isPending) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Open the link again",
                    style = MaterialTheme.typography.labelLarge,
                    color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
                    modifier = Modifier.touchTarget().clickable { onOpenUrl(url) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Card 2 — an API key
// ---------------------------------------------------------------------------

/**
 * Pick a provider, type its key, and wait while it is verified.
 *
 * The waiting is not optional and not shortenable: `_spettro/providers/connect`
 * checks the key against the provider's own API *before* it writes anything,
 * which takes up to thirty seconds and is the reason a bad key never reaches
 * disk. The field stays disabled and the button stays busy for the whole of
 * it; a client-side timeout here would leave a key half-written.
 *
 * The key itself never leaves this composable except as the parameter of that
 * one call, and the state holding it is cleared the instant the call returns —
 * success or failure. It is never logged, never saved, never put in a
 * `SharedPreferences`. Its only home is the CLI's encrypted `~/.spettro/keys.enc`.
 */
@Composable
fun ApiKeySheet(state: ShellState, onDismiss: () -> Unit) {
    val theme = LocalZedTheme.current
    val scope = rememberCoroutineScope()
    val grid = SpettroSetup.providers?.keyGrid.orEmpty()

    var chosen by remember(grid) { mutableStateOf(grid.firstOrNull()) }
    var key by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }

    val shown = if (expanded) grid else grid.take(FEATURED_ROWS)

    SheetScaffold(
        state = state,
        onDismiss = onDismiss,
        title = "Use my own API key",
        field = {
            SecretField(
                value = key,
                onValueChange = { key = it },
                placeholder = keyPlaceholder(chosen),
                enabled = !busy,
                reveal = reveal,
                onToggleReveal = { reveal = !reveal },
            )
        },
        actions = {
            Column {
                error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.color("error", MaterialTheme.colorScheme.error),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                SheetButton(
                    label = if (busy) "Verifying with ${chosen?.name ?: "the provider"}…" else "Connect",
                    primary = true,
                    enabled = !busy && key.isNotBlank() && chosen != null,
                    onClick = {
                        val provider = chosen ?: return@SheetButton
                        val secret = key
                        busy = true
                        error = null
                        scope.launch {
                            val outcome = SpettroSetup.connectProvider(provider.id, secret)
                            // Cleared whatever happened. A rejected key is
                            // still a key, and leaving it in a field the user
                            // may screenshot or leave on screen is the one
                            // thing this sheet must not do.
                            key = ""
                            busy = false
                            when (outcome) {
                                is ConnectOutcome.Connected -> onDismiss()
                                // The provider's own words. "key rejected
                                // (401)" was written to be read by the person
                                // who typed it; rewording it helps nobody.
                                is ConnectOutcome.Rejected -> error = outcome.message
                            }
                        }
                    },
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            if (grid.isEmpty()) {
                Text(
                    text = "Spettro has not said which providers it knows about yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
            // A flow of chips rather than a fixed grid: provider names are two
            // characters ("xAI") to eleven ("openrouter"), and a column layout
            // would leave half the row empty for most of them.
            ChipFlow(
                providers = shown,
                chosenId = chosen?.id,
                onChoose = { chosen = it },
            )
            if (!expanded && grid.size > FEATURED_ROWS) {
                val moreLabel = "${grid.size - FEATURED_ROWS} more"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .touchTarget()
                        .clickable(onClickLabel = moreLabel) { expanded = true }
                        .padding(top = 4.dp),
                ) {
                    SeekerIcon(
                        icon = R.drawable.ic_ui_chevron_down,
                        contentDescription = null,
                        tint = theme.color(
                            "text.muted",
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        size = IconSize.Marker,
                    )
                    Text(
                        text = moreLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = theme.color(
                            "text.muted",
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
            chosen?.let { provider ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = provider.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = theme.color("text", MaterialTheme.colorScheme.onSurface),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Your key is verified with ${provider.name}, then stored " +
                        "encrypted on this device. Seeker IDE never keeps a copy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                )
                provider.envKey?.let { env ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "It will also be read from $env if that is set in the " +
                            "Linux userland.",
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** How many provider chips show before "N more" — the wireframe's five. */
private const val FEATURED_ROWS = 5

private fun keyPlaceholder(provider: ProviderEntry?): String = when (provider?.id) {
    "anthropic" -> "sk-ant-…"
    "openai" -> "sk-…"
    else -> "Paste your ${provider?.name ?: "API"} key"
}

// ---------------------------------------------------------------------------
// Card 3 — a local model
// ---------------------------------------------------------------------------

/**
 * An Ollama or LM Studio endpoint, probed before it is saved.
 *
 * The probe is a separate step on purpose: `local/add` persists, and an
 * endpoint that answers with an empty model list would be saved and then fail
 * at the first prompt. So the sheet shows what it found first, and *Add* is
 * only offered once there is something to add.
 *
 * On the Seeker this is a real route rather than a courtesy — an on-device
 * llama.cpp is a model that costs nothing per token and works on a train.
 */
@Composable
fun LocalModelSheet(state: ShellState, onDismiss: () -> Unit) {
    val theme = LocalZedTheme.current
    val scope = rememberCoroutineScope()

    var endpoint by remember { mutableStateOf(DEFAULT_ENDPOINT) }
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    SheetScaffold(
        state = state,
        onDismiss = onDismiss,
        title = "Connect a local model",
        field = {
            Column {
                SecretField(
                    value = endpoint,
                    onValueChange = {
                        endpoint = it
                        // A changed endpoint invalidates what the last probe
                        // found; leaving the list up would let *Add* save an
                        // endpoint nobody probed.
                        found = null
                    },
                    placeholder = DEFAULT_ENDPOINT,
                    enabled = !busy,
                    reveal = true,
                    secret = false,
                    onToggleReveal = {},
                )
                Spacer(Modifier.height(8.dp))
                SecretField(
                    value = key,
                    onValueChange = { key = it },
                    placeholder = "API key (optional)",
                    enabled = !busy,
                    reveal = false,
                    onToggleReveal = {},
                )
            }
        },
        actions = {
            Column {
                error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.color("error", MaterialTheme.colorScheme.error),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                SheetButton(
                    label = when {
                        busy -> "Asking $endpoint…"
                        found != null -> "Add"
                        else -> "Check"
                    },
                    primary = true,
                    enabled = !busy && endpoint.isNotBlank(),
                    onClick = {
                        val secret = key.takeIf { it.isNotBlank() }
                        busy = true
                        error = null
                        scope.launch {
                            val result = if (found == null) {
                                SpettroSetup.probeLocal(endpoint, secret)
                            } else {
                                SpettroSetup.addLocal(endpoint, secret)
                            }
                            busy = false
                            when (result) {
                                is LocalProbe.Found ->
                                    if (found == null) {
                                        found = result.models
                                    } else {
                                        key = ""
                                        onDismiss()
                                    }
                                is LocalProbe.Failed -> {
                                    error = result.message
                                    found = null
                                }
                            }
                        }
                    },
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = "Ollama and LM Studio both speak the OpenAI API. Point Seeker IDE " +
                    "at the endpoint and it will list what is loaded there.",
                style = MaterialTheme.typography.bodySmall,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            )
            found?.let { models ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "${models.size} model${if (models.size == 1) "" else "s"} there",
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.color("text", MaterialTheme.colorScheme.onSurface),
                )
                Spacer(Modifier.height(4.dp))
                for (model in models.take(MAX_PREVIEW)) {
                    Text(
                        text = model,
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                if (models.size > MAX_PREVIEW) {
                    Text(
                        text = "and ${models.size - MAX_PREVIEW} more",
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Ollama's own default, which is what most people will have. */
private const val DEFAULT_ENDPOINT = "http://127.0.0.1:11434/v1"

/** A preview, not a catalogue: enough to recognise the machine. */
private const val MAX_PREVIEW = 8

// ---------------------------------------------------------------------------
// Step 5 — the permission decision, made once
// ---------------------------------------------------------------------------

/** One permission level, as the agent describes it plus the copy this sheet adds. */
data class PermissionChoice(
    val id: String,
    val name: String,
    /** The agent's own description, used when this build has no copy for the id. */
    val description: String?,
)

/**
 * The one-time "How much should Spettro ask?" sheet, shown right after the
 * first session opens.
 *
 * A fresh `~/.spettro/config.json` defaults to **ask-first**, which is the
 * right default only if the approval path is loud — a run parked behind a
 * sheet nobody saw looks exactly like an app that has hung. The
 * high-priority approval notification with haptics is K9's, and until it is in
 * this sheet pre-selects **Restricted** and says why rather than shipping a
 * default that can silently strand a turn (docs/SPETTRO.md, step 5).
 *
 * YOLO is never pre-selected, at any point, for any reason.
 */
@Composable
fun PermissionChoiceSheet(
    state: ShellState,
    options: List<PermissionChoice>,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val suggestedId = options.firstOrNull { it.id == SUGGESTED_PERMISSION }?.id
        ?: options.firstOrNull()?.id
    var selected by remember(options) { mutableStateOf(suggestedId) }

    SheetScaffold(
        state = state,
        onDismiss = onDismiss,
        title = "How much should Spettro ask?",
        actions = {
            SheetButton(
                label = "Continue",
                primary = true,
                enabled = selected != null,
                onClick = {
                    selected?.let(onChoose)
                    onDismiss()
                },
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            for (option in options) {
                val copy = PERMISSION_COPY[option.id]
                RadioRow(
                    selected = selected == option.id,
                    title = copy?.first ?: option.name,
                    body = copy?.second ?: option.description.orEmpty(),
                    badge = "SUGGESTED".takeIf { option.id == suggestedId },
                    onClick = { selected = option.id },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "You can change this any time from the Permission chip.",
                style = MaterialTheme.typography.bodySmall,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * Pre-selected until the approval notification lands — see
 * [PermissionChoiceSheet]. Matched by id against whatever the agent sent, so
 * an agent that renames its levels falls back to its own first option rather
 * than to a level this build guessed at.
 */
private const val SUGGESTED_PERMISSION = "restricted"

/**
 * The wireframe's sentences, keyed by the agent's own option ids.
 *
 * Written here rather than taken from the agent because these three
 * descriptions have to say what the *phone* consequences are — that Ultra and
 * workflows are gated on Restricted, that YOLO is what unattended `/goal`
 * needs. An id this table does not know falls back to the agent's own words.
 */
private val PERMISSION_COPY: Map<String, Pair<String, String>> = mapOf(
    "ask-first" to (
        "Ask first" to
            "Prompt before running tools, edits, or commands. Safest. Ultra and " +
            "workflows stay off."
        ),
    "restricted" to (
        "Restricted" to
            "Allow safe actions; prompt for sensitive ones. Unlocks Ultra and workflows."
        ),
    "yolo" to (
        "YOLO" to
            "Approve everything automatically. Needed for fully unattended /goal."
        ),
)

// ---------------------------------------------------------------------------
// Settings → Spettro
// ---------------------------------------------------------------------------

/**
 * The ongoing version of everything above: what is connected, and how to
 * disconnect it.
 *
 * One honest sentence sits at the bottom and is the reason this screen is not
 * merely a mirror of the setup one: model, permission, thinking and Ultra are
 * stored in **Spettro's own** `~/.spettro/config.json`, shared with any
 * Spettro running in the terminal. Mode is the only per-conversation setting.
 * Somebody who changes a model here and finds their TUI changed too deserves
 * to have been told once.
 */
@Composable
fun SpettroSettingsScreen(modifier: Modifier = Modifier) {
    val theme = LocalZedTheme.current
    val scope = rememberCoroutineScope()
    val text = theme.color("text", MaterialTheme.colorScheme.onSurface)
    val muted = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
    val providers = SpettroSetup.providers
    val account = SpettroSetup.account

    LaunchedEffect(Unit) {
        SpettroSetup.refreshAccount()
        SpettroSetup.refreshProviders()
        SpettroSetup.refreshModels()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(theme.color("editor.background", MaterialTheme.colorScheme.background))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        SectionTitle("Account")
        if (account?.signedIn == true) {
            Text(
                text = account.email ?: "Signed in",
                style = MaterialTheme.typography.bodyMedium,
                color = text,
            )
            val plan = listOfNotNull(account.plan, account.planStatus).joinToString(" · ")
            if (plan.isNotEmpty()) {
                Text(text = plan, style = MaterialTheme.typography.bodySmall, color = muted)
            }
            account.remainingCredits?.let { remaining ->
                Text(
                    // `stale` numbers are muted rather than errored: a credit
                    // figure from four minutes ago is still worth reading, and
                    // an error where a number belongs reads as an account
                    // problem it is not.
                    text = "%.2f credits left".format(remaining) +
                        if (account.stale) " (last known)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }
            Spacer(Modifier.height(8.dp))
            LinkRow("Sign out") { scope.launch { SpettroSetup.signOut() } }
        } else {
            Text(
                text = "Not signed in.",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle("Providers")
        val connected = providers?.providers?.filter { it.connected }.orEmpty()
        if (connected.isEmpty()) {
            Text(
                text = "No API keys connected.",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        }
        for (provider in connected) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(provider.name, style = MaterialTheme.typography.bodyMedium, color = text)
                    Text(
                        text = "${provider.modelCount} models",
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                    )
                }
                LinkRow("Disconnect") {
                    scope.launch { SpettroSetup.disconnectProvider(provider.id) }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle("Local endpoints")
        val local = providers?.local.orEmpty()
        if (local.isEmpty()) {
            Text(text = "None.", style = MaterialTheme.typography.bodySmall, color = muted)
        }
        for (endpoint in local) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(endpoint.name, style = MaterialTheme.typography.bodyMedium, color = text)
                    Text(
                        text = endpoint.endpoint,
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                    )
                }
                LinkRow("Remove") { scope.launch { SpettroSetup.removeLocal(endpoint.endpoint) } }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionTitle("Models")
        for (model in SpettroSetup.models.sortedByDescending { it.favorite }.take(MODEL_ROWS)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .touchTarget()
                    .clickable {
                        scope.launch {
                            SpettroSetup.favouriteModel(model.provider, model.name, !model.favorite)
                        }
                    },
            ) {
                Box(
                    modifier = Modifier.width(24.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    SeekerIcon(
                        icon = if (model.favorite) {
                            R.drawable.ic_ui_star_filled
                        } else {
                            R.drawable.ic_ui_star
                        },
                        // The row's click label says what the tap does; this
                        // says which of the two states it is in now.
                        contentDescription = if (model.favorite) "favourite" else null,
                        tint = if (model.favorite) theme.color("created", muted) else muted,
                        size = IconSize.Inline,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = text,
                    )
                    Text(
                        text = model.providerName,
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                    )
                }
                if (model.active) {
                    Text(
                        text = "ACTIVE",
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = "Model, permission, thinking and Ultra are stored in Spettro's own " +
                "config and are shared with any Spettro you run in the terminal. Mode " +
                "is per-conversation.",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "The per-request budget is left at 0 — unlimited — on purpose: it is " +
                "checked before each call, so a small \"mobile-friendly\" value makes " +
                "long turns fail outright rather than cost less.",
            style = MaterialTheme.typography.labelSmall,
            color = muted,
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** Enough of the model list to be useful without becoming a catalogue. */
private const val MODEL_ROWS = 40

// ---------------------------------------------------------------------------
// Shared bits
// ---------------------------------------------------------------------------

/**
 * A text field that may hold a secret.
 *
 * Not `SheetTextField`: an API key needs three things that field does not
 * have, and each is a way a key leaks — `KeyboardType.Password` so the IME
 * keeps it out of its own learned-words store, `autoCorrect = false` for the
 * same reason, and `semantics { password() }` so the platform keeps it out of
 * screenshots and autofill history.
 *
 * [secret] false reuses the same shape for the endpoint field, which is not a
 * secret and should not be masked.
 */
@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    reveal: Boolean,
    onToggleReveal: () -> Unit,
    secret: Boolean = true,
) {
    val theme = LocalZedTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                theme.color("editor.background", MaterialTheme.colorScheme.surface),
                RoundedCornerShape(8.dp),
            )
            .heightIn(min = 44.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                visualTransformation = if (secret && !reveal) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = theme.color("text", MaterialTheme.colorScheme.onSurface),
                ),
                cursorBrush = SolidColor(
                    theme.color("editor.foreground", MaterialTheme.colorScheme.onSurface),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (secret) Modifier.semantics { password() } else Modifier),
            )
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }
        if (secret) {
            // An eye, not two concentric circles: this is the same control
            // the askpass dialog draws, and it should look like it.
            SeekerIconButton(
                icon = if (reveal) R.drawable.ic_ui_eye_off else R.drawable.ic_ui_eye,
                description = if (reveal) "Hide" else "Reveal",
                onClick = onToggleReveal,
                enabled = enabled,
                tint = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                size = IconSize.Inline,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** The one button shape these sheets use, pinned under the field. */
@Composable
private fun SheetButton(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val theme = LocalZedTheme.current
    val background = if (primary) {
        theme.color("element.background", MaterialTheme.colorScheme.primary)
    } else {
        theme.color("ghost_element.background", MaterialTheme.colorScheme.surface)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) background else background.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = theme.color("text", MaterialTheme.colorScheme.onSurface)
                .copy(alpha = if (enabled) 1f else 0.5f),
        )
    }
}

/** The provider chips, wrapped by hand — Compose's FlowRow is experimental. */
@Composable
private fun ChipFlow(
    providers: List<ProviderEntry>,
    chosenId: String?,
    onChoose: (ProviderEntry) -> Unit,
) {
    val theme = LocalZedTheme.current
    // Three per row is what fits at 400 dp with the longest provider name the
    // agent ships; a fourth truncates every one of them.
    for (row in providers.chunked(3)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            for (provider in row) {
                val chosen = provider.id == chosenId
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (chosen) {
                                theme.color("element.selected", MaterialTheme.colorScheme.primaryContainer)
                            } else {
                                theme.color("ghost_element.background", MaterialTheme.colorScheme.surface)
                            },
                        )
                        .border(
                            width = 1.dp,
                            color = theme.color("border", MaterialTheme.colorScheme.outline),
                            shape = RoundedCornerShape(8.dp),
                        )
                        .clickable { onChoose(provider) },
                ) {
                    Text(
                        text = provider.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = theme.color("text", MaterialTheme.colorScheme.onSurface),
                        maxLines = 1,
                    )
                }
            }
            // Keeps a short last row the same width as a full one.
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** One radio row of the permission sheet. */
@Composable
private fun RadioRow(
    selected: Boolean,
    title: String,
    body: String,
    badge: String?,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Box(
            modifier = Modifier.width(32.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            SelectionMark(
                selected = selected,
                multi = false,
                tint = theme.color("text", MaterialTheme.colorScheme.onSurface),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = theme.color("text", MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.color("created", MaterialTheme.colorScheme.primary),
                    )
                }
            }
            if (body.isNotEmpty()) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = LocalZedTheme.current.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = LocalZedTheme.current.color("text.accent", MaterialTheme.colorScheme.primary),
        modifier = Modifier.touchTarget().clickable(onClick = onClick).padding(horizontal = 4.dp),
    )
}
