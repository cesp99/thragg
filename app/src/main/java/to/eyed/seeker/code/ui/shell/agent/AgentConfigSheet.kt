package to.eyed.seeker.code.ui.shell.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import to.eyed.seeker.code.core.AgentConfigOption
import to.eyed.seeker.code.core.AgentSessionState
import to.eyed.seeker.code.core.ULTRA_LOCK_REASON
import to.eyed.seeker.code.core.UltraState
import to.eyed.seeker.code.ui.agent.spettro.ConfigSheet
import to.eyed.seeker.code.ui.agent.spettro.ContextRing
import to.eyed.seeker.code.ui.agent.spettro.chipGlyph
import to.eyed.seeker.code.ui.agent.spettro.chipOrder
import to.eyed.seeker.code.ui.agent.spettro.ultraStateText
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.shell.SheetScaffold
import to.eyed.seeker.code.ui.theme.LocalZedTheme

/**
 * A config value as `session/set_config_option` wants it: a JSON **value**,
 * not a JSON object.
 *
 * `JSONObject.quote` rather than string interpolation because a model id may
 * hold a quote or a backslash (`lmstudio/qwen"3`), and a hand-built `"$value"`
 * would send malformed JSON that the agent answers with a parse error nobody
 * can act on.
 */
internal fun configValueJson(value: String): String = JSONObject.quote(value)

/** The same, for the boolean options. */
internal fun configValueJson(value: Boolean): String = if (value) "true" else "false"

/**
 * Everything the agent lets you change, behind the header's `Spettro · coding`
 * chip.
 *
 * The chip row above the composer carries the same options and is the fast
 * path; this is the *complete* one, and it exists because docs/UI.md is
 * explicit that the scrolling selector strip is moved off the 890 dp column
 * rather than deleted. It also carries the live context gauge, which is the
 * one reading that changes what you should do next and has no room of its own
 * in a sheet-less layout.
 *
 * Every row is drawn from what the agent advertised — never a hard-coded
 * plan/coding/ask, because the mode list comes from the project's own
 * `spettro.agents.toml` and varies per repo (docs/SPETTRO.md, "Toolbar chips").
 */
@Composable
fun AgentConfigSheet(
    shell: ShellState,
    state: AgentSessionState,
    onPick: (option: AgentConfigOption, valueJson: String) -> Unit,
    onOpenContext: () -> Unit,
    onLocked: () -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val toolbar = state.toolbar
    val ordered = chipOrder(toolbar.options)
    var picking by remember { mutableStateOf<AgentConfigOption?>(null) }

    SheetScaffold(
        state = shell,
        onDismiss = onDismiss,
        title = state.agent?.agentName ?: "Agent",
    ) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (ordered.isEmpty()) {
                Text(
                    // Not an error: `configOptions` arrives with the session
                    // and a starting agent has simply not sent it yet.
                    text = "This agent has not advertised any settings yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                    modifier = Modifier.padding(16.dp),
                )
            }
            for (option in ordered) {
                if (option.isBool) {
                    val ultra = option.id == "ultra"
                    val ultraState = toolbar.ultraState
                    ConfigRow(
                        glyph = chipGlyph(option),
                        name = option.name,
                        value = if (ultra) {
                            ultraStateText(ultraState)
                        } else {
                            if (option.currentBool == true) "On" else "Off"
                        },
                        onClick = {
                            // Turning Ultra *off* is never locked; turning it
                            // on under ask-first is refused by the agent, so
                            // it is never sent — the sentence is shown instead.
                            if (ultra && ultraState == UltraState.Locked) {
                                onLocked()
                            } else {
                                onPick(option, configValueJson(option.currentBool != true))
                            }
                        },
                    )
                    if (ultra && ultraState == UltraState.Locked) {
                        Text(
                            text = ULTRA_LOCK_REASON,
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.color(
                                "text.muted",
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        )
                    }
                } else {
                    ConfigRow(
                        glyph = chipGlyph(option),
                        name = option.name,
                        value = option.currentLabel,
                        onClick = { picking = option },
                    )
                }
            }
            GaugeRow(state, onOpenContext)
        }
    }

    // Nested rather than replacing: the selector is a *step* inside this
    // sheet, and dismissing it should put you back where the list was.
    picking?.let { option ->
        ConfigSheet(
            state = shell,
            option = option,
            ultraStored = toolbar.ultraOn,
            onPick = { value ->
                onPick(option, configValueJson(value))
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

/** One 56 dp row: what it is, what it is set to, and a chevron. */
@Composable
private fun ConfigRow(glyph: String, name: String, value: String, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable(onClickLabel = name, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.labelLarge,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = theme.color("text", MaterialTheme.colorScheme.onSurface),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            maxLines = 1,
            // From the END, so a model's variant survives the truncation and
            // `...sonnet-4-5` still says which one it is.
            overflow = TextOverflow.StartEllipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "›",
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
        )
    }
}

/** The context ring, with the number spelled out beside it. */
@Composable
private fun GaugeRow(state: AgentSessionState, onOpen: () -> Unit) {
    val theme = LocalZedTheme.current
    val usage = state.usage ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable(onClickLabel = "Context", onClick = onOpen)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ContextRing(usage = usage, label = true)
        Text(
            text = "Context",
            style = MaterialTheme.typography.bodyMedium,
            color = theme.color("text", MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "›",
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
        )
    }
}

/** One row of the overflow sheet. */
data class OverflowItem(
    val label: String,
    val detail: String? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * The header's overflow — everything the app bar deliberately does not carry.
 *
 * The bar keeps the title and the context ring and nothing else, because the
 * ring is the only reading that changes what to do next (docs/SPETTRO.md,
 * "Screen shell"). Sessions, the plan, the review pane and Spettro's own
 * settings are all one tap deeper, which is the right depth for things you
 * reach a few times an hour.
 */
@Composable
fun AgentOverflowSheet(shell: ShellState, items: List<OverflowItem>, onDismiss: () -> Unit) {
    val theme = LocalZedTheme.current
    SheetScaffold(state = shell, onDismiss = onDismiss, title = "Agent") {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            for (item in items) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = item.enabled, onClickLabel = item.label) {
                            item.onClick()
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (item.enabled) {
                            theme.color("text", MaterialTheme.colorScheme.onSurface)
                        } else {
                            theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                    )
                    item.detail?.let { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.color(
                                "text.muted",
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        }
    }
}
