package to.eyed.seeker.code.ui.shell.setup

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.solana.toolchain.ComponentRow
import to.eyed.seeker.code.solana.toolchain.ComponentState
import to.eyed.seeker.code.solana.toolchain.SolanaToolchain
import to.eyed.seeker.code.solana.toolchain.ToolchainInstaller
import to.eyed.seeker.code.solana.toolchain.ToolchainManifest
import to.eyed.seeker.code.solana.toolchain.ToolchainPhase
import to.eyed.seeker.code.solana.toolchain.formatBytes
import to.eyed.seeker.code.terminal.Userland
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.touchTarget

/**
 * The one full-screen takeover: the honest cost of a phone that compiles
 * Solana programs, said plainly, once, with an honest Skip.
 *
 * It is a route like any other (Route.Setup) and it is reachable at any time
 * from Projects → Toolchain, where it doubles as the repair and free-the-disk
 * page. It is not, however, a gate: the toolchain download is deferred until
 * the first Build press rather than blocking first launch (docs/UI.md, "The
 * design chosen"), so nothing here runs until somebody presses a button.
 *
 * Three rules from docs/UI.md are enforced here rather than left to the
 * caller, because each one is a way a first-run screen can lie:
 *
 *  1. **Two numbers, not one.** Transfer and disk are different quantities and
 *     conflating them is the exact dishonesty this screen cannot afford. Both
 *     are summed from the manifest, so a toolchain bump moves them on its own.
 *  2. **Two kinds of row.** A sized download counts bytes and a rate; an
 *     on-device compile counts *seconds*. `cargo-build-sbf` and `anchor` have
 *     no arm64 binary anywhere upstream and are built here, and a MB bar on a
 *     four-minute compile would be an invention.
 *  3. **Skip is a text link, not a button.** It is the minority path and it is
 *     a real one: the editor, highlighting, the file tree, search, git and the
 *     agent all work with no toolchain at all.
 *
 * Leaving the screen does not stop the install — [ToolchainInstaller] lives
 * outside the composition and holds the terminal's foreground notification
 * while it runs — and coming back re-attaches to the same rows.
 */
@Composable
fun SetupScreen(state: ShellState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val theme = LocalZedTheme.current
    val supported = Userland.backend.isSupported

    LaunchedEffect(Unit) { ToolchainInstaller.refresh(context) }

    val manifest = remember(context) {
        runCatching { ToolchainManifest.load(context) }.getOrNull()
    }
    val rows = ToolchainInstaller.rows
    val phase = ToolchainInstaller.phase

    /**
     * One tick a second, and only while something is running — this is what
     * the elapsed timer on a compile row counts with. A clock in the state
     * holder would recompose every destination; a clock here stops when the
     * screen leaves, which is exactly when nobody is reading it.
     */
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(phase) {
        while (phase == ToolchainPhase.Running) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(theme.color("editor.background", MaterialTheme.colorScheme.background)),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Masthead(theme.color("text", MaterialTheme.colorScheme.onSurface))

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            Text(
                text = costLine(supported, manifest),
                style = MaterialTheme.typography.bodySmall,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            )

            Spacer(Modifier.height(12.dp))

            if (!supported) {
                UnsupportedCard()
            } else {
                for (row in rows) {
                    ComponentRowView(
                        row = row,
                        now = now,
                        onRetry = { ToolchainInstaller.retry(context, row.component.id) },
                    )
                }
            }

            ToolchainInstaller.lastError?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.color("error", MaterialTheme.colorScheme.error),
                )
            }

            Spacer(Modifier.height(16.dp))
        }

        // The actions sit at the bottom, in the thumb zone, which is the
        // reachability rule the whole shell is built on (docs/UI.md).
        Actions(
            state = state,
            context = context,
            supported = supported,
            manifest = manifest,
        )
    }
}

@Composable
private fun Masthead(textColor: androidx.compose.ui.graphics.Color) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "◎", style = MaterialTheme.typography.displaySmall, color = textColor)
        Text(
            text = "Seeker IDE",
            style = MaterialTheme.typography.titleLarge,
            color = textColor,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = "Build and ship Solana programs from your phone.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = textColor.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp),
        )
    }
}

/**
 * The headline, and the one place the two numbers appear together.
 *
 * Summed from the manifest rather than written down, so the sentence cannot
 * drift from the component list underneath it. They are genuinely different
 * numbers — the compiles produce disk out of CPU and download nothing — and
 * saying so is the point.
 */
private fun costLine(supported: Boolean, manifest: ToolchainManifest?): String {
    if (!supported) {
        return "This edition of Seeker IDE cannot run a Linux userland, so the Solana " +
            "toolchain cannot be installed here. Everything else works."
    }
    if (manifest == null) return "The toolchain manifest could not be read."
    return "One setup, once. ${formatBytes(manifest.totalDownloadBytes)} down, " +
        "${formatBytes(manifest.totalInstallBytes)} on disk, then the phone builds offline."
}

@Composable
private fun UnsupportedCard() {
    val theme = LocalZedTheme.current
    Text(
        text = "The Play edition targets a modern SDK, and Android will not execute a " +
            "program that arrived after installation — which every part of a compiler " +
            "toolchain is. The editor, the file tree, search and git are unaffected.",
        style = MaterialTheme.typography.bodySmall,
        color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
    )
}

/**
 * One component: a glyph, a name, a right-hand figure, and — only while it is
 * the row that is running — a bar under it.
 *
 * The right-hand figure is where the two row kinds diverge and it is decided
 * by the component, not by the state: a compile row says "builds on device"
 * before it starts and an elapsed timer while it runs, and never a byte.
 */
@Composable
private fun ComponentRowView(row: ComponentRow, now: Long, onRetry: () -> Unit) {
    val theme = LocalZedTheme.current
    val state = row.state
    val text = theme.color("text", MaterialTheme.colorScheme.onSurface)
    val muted = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = glyph(state),
                style = MaterialTheme.typography.labelLarge,
                color = when (state) {
                    is ComponentState.Installed -> theme.color("created", muted)
                    is ComponentState.Failed -> theme.color("error", MaterialTheme.colorScheme.error)
                    else -> muted
                },
                modifier = Modifier.width(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.component.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = text,
                )
                val detail = detail(row, now)
                if (detail.isNotEmpty()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                }
            }
            if (state is ComponentState.Failed || state is ComponentState.Cancelled) {
                Text(
                    text = "Retry",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .touchTarget()
                        .clickable(onClick = onRetry)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            } else {
                Text(
                    text = figure(row),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }
        }
        if (state is ComponentState.Downloading) {
            val fraction = state.fraction
            if (fraction == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            } else {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        } else if (state is ComponentState.Working) {
            // No fraction exists for an unpack, an apt run or a compile, and
            // inventing one is exactly what the two-row-kinds rule forbids.
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

private fun glyph(state: ComponentState): String = when (state) {
    is ComponentState.Installed -> "✓"
    is ComponentState.Downloading, is ComponentState.Working -> "▶"
    is ComponentState.Failed -> "✕"
    is ComponentState.Pending, is ComponentState.Cancelled -> "·"
}

/** The right-hand figure: a size, a byte count, or the words for a compile. */
private fun figure(row: ComponentRow): String {
    val component = row.component
    return when (val state = row.state) {
        is ComponentState.Downloading ->
            "${formatBytes(state.received)} / ${formatBytes(state.total)}"
        is ComponentState.Installed -> "installed"
        is ComponentState.Working ->
            if (component.isCompiled) elapsed(state.startedAt) else "working"
        else ->
            if (component.isCompiled) "builds on device"
            else if (component.approximate) "≈ ${formatBytes(component.downloadBytes)}"
            else formatBytes(component.downloadBytes)
    }
}

/** The second line: a percentage and a rate, or what the step last said. */
private fun detail(row: ComponentRow, now: Long): String = when (val state = row.state) {
    is ComponentState.Downloading -> buildString {
        state.fraction?.let { append("${(it * 100).toInt()} %") }
        state.bytesPerSecond?.let {
            if (isNotEmpty()) append(" · ")
            append("${formatBytes(it)}/s")
        }
    }
    is ComponentState.Working ->
        if (row.component.isCompiled) {
            "compiling on the device · ${elapsedFrom(state.startedAt, now)}"
        } else {
            state.step
        }
    is ComponentState.Failed -> state.message
    is ComponentState.Cancelled -> "stopped — the bytes already fetched are kept"
    else -> row.component.summary
}

/** Elapsed as `m:ss`, recomputed against the screen's one-second tick. */
private fun elapsedFrom(startedAt: Long, now: Long): String {
    val seconds = ((now - startedAt).coerceAtLeast(0L)) / 1000L
    return "%d:%02d".format(seconds / 60L, seconds % 60L)
}

private fun elapsed(startedAt: Long): String = elapsedFrom(startedAt, System.currentTimeMillis())

/**
 * One primary action, and the Skip link under it.
 *
 * On a metered connection the button names the cost instead of saying Start,
 * because "Start" on mobile data is a question the user was never asked
 * (docs/UI.md, "Setup" — metered connections).
 */
@Composable
private fun Actions(
    state: ShellState,
    context: Context,
    supported: Boolean,
    manifest: ToolchainManifest?,
) {
    val theme = LocalZedTheme.current
    val phase = ToolchainInstaller.phase
    val complete = ToolchainInstaller.isComplete
    val metered = remember(context) { isMetered(context) }
    val remaining = ToolchainInstaller.remainingDownloadBytes
        .takeIf { it > 0L }
        ?: manifest?.totalDownloadBytes
        ?: 0L
    // Summed from the rows that are actually in, not from the manifest total:
    // "frees 2.1 GB" has to mean the disk this device is holding right now.
    val installedBytes = ToolchainInstaller.rows
        .filter { it.state is ComponentState.Installed }
        .sumOf { it.component.installBytes }

    val label = when {
        !supported -> "Continue without a toolchain"
        phase == ToolchainPhase.Running -> "Pause"
        complete -> "Done"
        phase == ToolchainPhase.Failed -> "Retry"
        metered -> "Download over mobile data (${formatBytes(remaining)})"
        else -> "Start"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(theme.color("element.background", MaterialTheme.colorScheme.primary))
                .clickable {
                    when {
                        !supported || complete -> {
                            // From the rows, not from disk: this runs on the
                            // main thread and the rows already are the answer.
                            state.toolchainReady = ToolchainInstaller.isComplete
                            state.pop()
                        }
                        phase == ToolchainPhase.Running -> ToolchainInstaller.cancel()
                        else -> ToolchainInstaller.start(context) { ready ->
                            state.toolchainReady = ready
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = theme.color("text", MaterialTheme.colorScheme.onPrimary),
            )
        }
        Spacer(Modifier.height(4.dp))
        // A text link and not a button, deliberately: the minority path, and a
        // real one. Setup comes back from Projects → Toolchain at any time.
        Text(
            text = if (complete) "Close" else "Skip — I only want to edit code",
            style = MaterialTheme.typography.labelMedium,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier
                .touchTarget()
                .clickable {
                    state.toolchainReady = ToolchainInstaller.isComplete
                    state.pop()
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        // The other half of this screen's job: once the toolchain is in, this
        // is also the page you come to to get the disk back (docs/UI.md —
        // "the repair / uninstall / free-1.4-GB page"). Only offered when
        // there is something to free, and it leaves the Debian userland
        // standing, because the terminal and git are useful without a compiler.
        if (complete) {
            val scope = rememberCoroutineScope()
            Text(
                text = "Remove the toolchain — frees ${formatBytes(installedBytes)}",
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier
                    .touchTarget()
                    .clickable {
                        scope.launch {
                            withContext(Dispatchers.IO) { SolanaToolchain.remove(context) }
                            state.toolchainReady = false
                            ToolchainInstaller.refresh(context)
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * Whether the active network bills by the byte.
 *
 * Read once when the screen composes rather than watched: a change of network
 * mid-install is not a reason to relabel a button under the user's thumb, and
 * the install itself survives the change either way.
 */
private fun isMetered(context: Context): Boolean = runCatching {
    context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true
}.getOrDefault(false)
