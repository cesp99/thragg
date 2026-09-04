package to.eyed.thragg.ui.shell.build

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.thragg.solana.build.ProjectLayout
import to.eyed.thragg.solana.chain.Cluster
import to.eyed.thragg.solana.chain.ClusterStore
import to.eyed.thragg.solana.chain.DeployKey
import to.eyed.thragg.solana.chain.DeployedProgram
import to.eyed.thragg.solana.chain.DeployedPrograms
import to.eyed.thragg.solana.chain.OnChainProgram
import to.eyed.thragg.solana.chain.ProgramIds
import to.eyed.thragg.solana.chain.ProgramStatus
import to.eyed.thragg.solana.chain.Rpc
import to.eyed.thragg.solana.chain.SeedVaultWallet
import to.eyed.thragg.ui.components.CopyChip
import to.eyed.thragg.ui.components.SeekerCard
import to.eyed.thragg.ui.components.SeekerChip
import to.eyed.thragg.ui.shell.ShellState
import to.eyed.thragg.ui.shell.settings.ProgramSheet
import to.eyed.thragg.ui.shell.settings.copyAddress
import to.eyed.thragg.ui.shell.settings.openExplorer
import to.eyed.thragg.ui.theme.MD

/**
 * The card a deploy leaves behind, on the page where the deploy happened.
 *
 * The program id, the explorer link and the way to the Program sheet all
 * lived three taps away under Settings, and the first person to deploy from
 * the phone said so: Build is where you are when it lands, so Build is where
 * the id has to be. This reads the record the deployer wrote
 * ([DeployedPrograms]) for the open project's program on the cluster it is
 * set to, and draws nothing when there is none — a project deployed from
 * another phone has its row in Settings, where the chain is asked directly.
 *
 * The Program button opens the same sheet Settings does, with its own
 * on-chain read, so closing the program from here is the same confirm and
 * the same path; when that lands the record goes and so does this card.
 */
@Composable
internal fun DeployedCard(state: ShellState, root: String, layout: ProjectLayout?) {
    val context = LocalContext.current
    val program = layout?.primary ?: return
    val recordsVersion = DeployedPrograms.version
    val clusterVersion = ClusterStore.version

    // Cluster, program id and the record, read together off the main thread.
    val found by produceState<Found?>(null, root, program, recordsVersion, clusterVersion) {
        value = withContext(Dispatchers.IO) {
            val cluster = ClusterStore.of(context, root)
            val id = ProgramIds.resolve(root, program, cluster).id ?: return@withContext null
            val record = DeployedPrograms.all(context)
                .firstOrNull { it.programId == id && it.cluster == cluster }
                ?: return@withContext null
            val deployKey = if (DeployKey.exists(context)) DeployKey.get(context).publicKey.base58 else null
            Found(cluster, record, deployKey)
        }
    }
    val (cluster, record, deployKey) = found ?: return

    var sheetOpen by remember { mutableStateOf(false) }
    var status by remember(record.programId, cluster) { mutableStateOf<OnChainProgram?>(null) }
    var checking by remember { mutableStateOf(false) }
    var unreachable by remember { mutableStateOf(false) }
    LaunchedEffect(sheetOpen, record.programId, cluster) {
        if (!sheetOpen) return@LaunchedEffect
        checking = true
        unreachable = false
        status = withContext(Dispatchers.IO) {
            runCatching { ProgramStatus.inspect(Rpc(cluster), record.programId) }
                .onFailure { unreachable = true }
                .getOrNull()
        }
        checking = false
    }

    SeekerCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MD.space4, vertical = MD.space3),
    ) {
        Column(
            modifier = Modifier.padding(MD.space3),
            verticalArrangement = Arrangement.spacedBy(MD.space2),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Deployed on ${cluster.display}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = deployedAgo(record.deployedAt, System.currentTimeMillis()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MD.space2),
            ) {
                Text(
                    text = record.programId,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                CopyChip(text = record.programId, label = "Copy id")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(MD.space2)) {
                SeekerChip(
                    label = "Explorer",
                    onClick = { openExplorer(context, cluster.explorerAddress(record.programId)) },
                )
                SeekerChip(
                    label = "Copy link",
                    onClick = { copyAddress(context, cluster.explorerAddress(record.programId), "Explorer link") },
                )
                SeekerChip(
                    label = "Program",
                    onClick = { sheetOpen = true },
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    if (sheetOpen) {
        ProgramSheet(
            state = state,
            cluster = cluster,
            name = record.name.ifEmpty { program.moduleName },
            programId = record.programId,
            status = status,
            checking = checking,
            unreachable = unreachable,
            wallet = SeedVaultWallet.address,
            deployKey = deployKey,
            onDismiss = { sheetOpen = false },
        )
    }
}

/** The card's inputs, read together off the main thread. */
private data class Found(val cluster: Cluster, val record: DeployedProgram, val deployKey: String?)

/** `just now`, `3 min ago`, `2 h ago`, `yesterday`: the card's timestamp. Pure. */
internal fun deployedAgo(at: Long, now: Long): String {
    val seconds = ((now - at) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> "${seconds / 3600} h ago"
        seconds < 172_800 -> "yesterday"
        else -> "${seconds / 86_400} days ago"
    }
}
