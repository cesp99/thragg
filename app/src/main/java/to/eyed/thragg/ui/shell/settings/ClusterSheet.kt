package to.eyed.thragg.ui.shell.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import to.eyed.thragg.solana.chain.Cluster
import to.eyed.thragg.solana.chain.ClusterStore
import to.eyed.thragg.ui.components.SelectRow
import to.eyed.thragg.ui.shell.SheetScaffold
import to.eyed.thragg.ui.shell.ShellState
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.workspace.Notifications

/**
 * The Cluster sheet: three rows, one of them marked, and a tap that writes.
 *
 * Or none marked: [current] is null when the project's Anchor.toml names a
 * cluster the picker does not offer — `localnet` — because ticking devnet
 * there would say the file agrees with a choice nobody has made, and the
 * user would leave the sheet without making it.
 *
 * Exactly three, and the reason is in Cluster.kt: there is no validator on
 * this phone, so `localnet` is not a place a deploy can go, and a custom RPC
 * is a text field whose only honest use is the one this app does not have.
 * Each row carries the one sentence that decides between them — what the SOL
 * costs and what a mistake costs — because the choice is made here, once per
 * project, by someone who may be picking a Solana cluster for the first time.
 *
 * SELECTING IS THE WRITE. There is no confirm and no Done: the row is the
 * radio and the radio is the setting, which is what the Theme list and the
 * agent's model chips already do. The write itself is [ClusterStore.set],
 * which for an Anchor project rewrites `[provider] cluster` and fills in the
 * `[programs.<cluster>]` table (ClusterStore.kt says why), so it goes to IO
 * and the sheet closes when it has landed rather than when the finger lifts.
 * The caption under the rows says that the file is being written, for the
 * project that has one — a rewrite of a file that is in git deserves a line.
 */
@Composable
internal fun ClusterSheet(
    state: ShellState,
    /** The row to tick; null ticks none, until a choice has been written. */
    current: Cluster?,
    projectRoot: String?,
    /** Whether the project has an Anchor.toml this choice will be written into. */
    isAnchorProject: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // One write at a time: a second tap while the first is on IO would race
    // two rewrites of the same Anchor.toml.
    var switching by remember { mutableStateOf(false) }

    SheetScaffold(state = state, onDismiss = onDismiss, title = "Cluster") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            for (cluster in Cluster.entries) {
                SelectRow(
                    label = cluster.display,
                    description = clusterDescription(cluster),
                    selected = cluster == current,
                    onSelect = {
                        if (switching) return@SelectRow
                        switching = true
                        scope.launch {
                            // Re-selecting the current cluster still writes:
                            // a Native project's prefs entry may not exist
                            // yet, and the row was showing the default.
                            val written = withContext(Dispatchers.IO) {
                                runCatching { ClusterStore.set(context, projectRoot, cluster) }
                            }
                            written.onFailure {
                                Notifications.error(
                                    "Could not switch to ${cluster.display}: ${it.message}",
                                    key = CLUSTER_KEY,
                                )
                            }
                            onDismiss()
                        }
                    },
                )
            }
            if (isAnchorProject) {
                Text(
                    text = "Written into Anchor.toml as [provider] cluster",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = MD.space4, vertical = MD.space3),
                )
            }
        }
    }
}

/**
 * The one sentence under each cluster's name: what the SOL is and what a
 * mistake costs. Pure so the three readings are pinned by ChainRowsTest and
 * the mainnet warning cannot quietly soften.
 */
internal fun clusterDescription(cluster: Cluster): String = when (cluster) {
    Cluster.Devnet -> "Free SOL from the faucet. Where a new program should start."
    Cluster.Testnet -> "Also free. Closer to mainnet's validator set."
    Cluster.MainnetBeta -> "Real SOL. Deploys cost money and cannot be undone."
}

/** Cluster notifications replace each other rather than stacking. */
private const val CLUSTER_KEY = "chain.cluster"
