package to.eyed.thragg.ui.agent.spettro

import android.content.Intent
import androidx.annotation.DrawableRes
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import to.eyed.thragg.R
import to.eyed.thragg.core.SetupGate
import to.eyed.thragg.core.SpettroSetup
import to.eyed.thragg.ui.components.NoticeCard
import to.eyed.thragg.ui.components.Severity
import to.eyed.thragg.ui.shell.ShellState
import to.eyed.thragg.ui.theme.IconSize
import to.eyed.thragg.ui.theme.LocalSeekerColors
import to.eyed.thragg.ui.theme.RowChevron
import to.eyed.thragg.ui.theme.SeekerIcon
import to.eyed.thragg.ui.theme.touchTarget

/**
 * "Set up Spettro" — the screen between an installed agent and an agent that
 * can answer.
 *
 * It exists because of one sequence: the agent starts, handshakes, opens a
 * session and *then* fails at the first prompt with a provider error. Nothing
 * before that moment is wrong, and nothing about it tells a phone user what to
 * do. So the check happens first — `_spettro/providers/list` immediately after
 * the handshake — and this screen is what a `NEEDED` answer opens
 * (docs/SPETTRO.md, "First run").
 *
 * Three routes, in the order a phone should prefer them:
 *
 *  1. **Sign in.** No key to type, no keyboard, no clipboard. On a 400 dp
 *     screen that is not a nicety; pasting a 108-character secret into a
 *     password field is the single worst thing this app could ask for.
 *  2. **An API key**, for the people who already have one.
 *  3. **A local model**, which on this device means an on-device llama.cpp or
 *     an Ollama on the LAN, and deserves the same weight as the other two.
 *
 * And an honest **Skip for now**, which does not pretend the agent works: it
 * leaves [SpettroSetupBanner] above the composer rather than letting the first
 * prompt produce a raw provider error.
 *
 * [quotedError] is the agent's own words when this screen was re-opened *by* a
 * failed prompt, shown at the top rather than paraphrased.
 */
@Composable
fun SpettroSetupScreen(
    state: ShellState,
    onSkip: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    quotedError: String? = null,
) {
    val context = LocalContext.current
    val text = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    var sheet by remember { mutableStateOf<SetupSheet?>(null) }

    // The gate can be satisfied from underneath us — a login completing, a key
    // accepted in a sheet — and when it is, this screen's job is over.
    LaunchedEffect(SpettroSetup.gate) {
        if (SpettroSetup.gate == SetupGate.SATISFIED) onDone()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            Text(
                text = "Set up Spettro",
                style = MaterialTheme.typography.titleLarge,
                color = text,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Spettro is the agent built into Thragg. Give it a model and " +
                    "you're done.",
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            if (quotedError != null) {
                Spacer(Modifier.height(16.dp))
                QuotedError(quotedError)
            }

            // The gate can close on a *signed-in* user: an account whose
            // plan exposes no models (SpettroSetup.setupGate). Card 1 below
            // still says "Sign in", which is the wrong first word for
            // somebody who already did; this card says what actually
            // happened, above it, before they read that.
            val account = SpettroSetup.account
            val modelsNote = SpettroSetup.subscriptionModelsNote
            if (account?.signedIn == true && modelsNote != null) {
                Spacer(Modifier.height(16.dp))
                NoticeCard(
                    severity = Severity.Warn,
                    title = "Signed in as ${account.email ?: "Spettro"}" +
                        (account.plan?.let { " · $it" } ?: ""),
                    body = "$modelsNote Add an API key or a local model below to " +
                        "keep going.",
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(24.dp))

            SetupCard(
                icon = R.drawable.ic_ui_sparkles,
                title = "Sign in to Spettro",
                subtitle = "No key to type. Opens your browser.",
                badge = "RECOMMENDED",
                onClick = { sheet = SetupSheet.SignIn },
            )
            Spacer(Modifier.height(12.dp))
            SetupCard(
                icon = R.drawable.ic_ui_key,
                title = "Use my own API key",
                subtitle = providerLine(),
                chevron = true,
                onClick = { sheet = SetupSheet.ApiKey },
            )
            Spacer(Modifier.height(12.dp))
            SetupCard(
                icon = R.drawable.ic_ui_house,
                title = "Connect a local model",
                subtitle = "Ollama or LM Studio, on device or on your network.",
                chevron = true,
                onClick = { sheet = SetupSheet.LocalModel },
            )

            SpettroSetup.lastError?.let { message ->
                Spacer(Modifier.height(16.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalSeekerColors.current.dangerInk,
                )
            }

            Spacer(Modifier.height(24.dp))
        }

        // Skip is a text link and not a button: it is the minority path, and it
        // is a real one — the editor, the file tree, search, git and Build all
        // work with no model connected at all.
        Text(
            text = "Skip for now",
            style = MaterialTheme.typography.labelLarge,
            color = muted,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .touchTarget()
                .clickable {
                    SpettroSetup.skip()
                    onSkip()
                }
                .padding(vertical = 12.dp),
        )
        Spacer(Modifier.height(8.dp))
    }

    when (sheet) {
        SetupSheet.SignIn -> SignInSheet(
            state = state,
            onDismiss = { sheet = null },
            onOpenUrl = { url ->
                // A plain ACTION_VIEW rather than a Chrome Custom Tab: this
                // module does not depend on androidx.browser, and adding a
                // dependency is another chunk's build file. The sign-in comes
                // back through the agent's pushed notification rather than
                // through a redirect into the app, so the tab being separate
                // costs nothing but a task switch back.
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, url.toUri())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        )
        SetupSheet.ApiKey -> ApiKeySheet(
            state = state,
            onDismiss = { sheet = null },
        )
        SetupSheet.LocalModel -> LocalModelSheet(
            state = state,
            onDismiss = { sheet = null },
        )
        null -> Unit
    }

    // The grid needs the provider list; asked for once when the screen opens
    // rather than by every card that wants a name out of it.
    LaunchedEffect(Unit) {
        if (SpettroSetup.providers == null) SpettroSetup.refreshProviders()
    }
}

/** Which of the three routes has a sheet open. */
private enum class SetupSheet { SignIn, ApiKey, LocalModel }

/**
 * The second line of the API-key card: the providers the agent actually
 * offers, not a list written down here that a CLI update would falsify.
 */
@Composable
private fun providerLine(): String {
    val names = SpettroSetup.providers?.keyGrid?.take(4)?.map { it.name }.orEmpty()
    return if (names.isEmpty()) {
        "Anthropic, OpenAI, Mistral, xAI and more."
    } else {
        names.joinToString(" · ") + " …"
    }
}

/**
 * One of the three routes, as a card.
 *
 * A whole card is the touch target rather than a button inside it: at 400 dp
 * a row with a small affordance on the right is a row most people will miss
 * the first time.
 */
@Composable
private fun SetupCard(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    badge: String? = null,
    chevron: Boolean = false,
) {
    val text = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Box(
            modifier = Modifier.width(28.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            SeekerIcon(
                icon = icon,
                contentDescription = null,
                tint = text,
                size = IconSize.Inline,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = text,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalSeekerColors.current.addedInk,
                    )
                }
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        }
        if (chevron) {
            RowChevron(tint = muted)
        }
    }
}

/**
 * The agent's own error, quoted rather than rewritten.
 *
 * A [NoticeCard] at [Severity.Error], which is the app's one shape for "this
 * went wrong and here is what it said": the hue at 10% behind a solved
 * `dangerInk` glyph and title, rather than the hand-rolled box with a raw
 * `error` border this drew before. The MESSAGE is untouched — Spettro's own
 * words are the only part of a failure that helps.
 */
@Composable
private fun QuotedError(message: String) {
    NoticeCard(
        severity = Severity.Error,
        title = "Spettro said:",
        body = message,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The banner a skipped setup leaves above the composer.
 *
 * The whole reason **Skip for now** is allowed to exist: it must not produce a
 * raw provider error at the first prompt. One line, permanently visible, with
 * the way back on it.
 */
@Composable
fun SpettroSetupBanner(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = "No model connected — Spettro can't answer yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .touchTarget()
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onOpen)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = "Set up",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = LocalSeekerColors.current.accentInk,
            )
        }
    }
}
