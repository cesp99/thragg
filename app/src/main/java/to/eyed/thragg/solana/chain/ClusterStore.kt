package to.eyed.thragg.solana.chain

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import to.eyed.thragg.solana.build.BuildTasks
import java.io.File

/**
 * Which cluster a project is pointed at, and the two places that fact lives.
 *
 * For an Anchor or Seahorse project the answer is already written down:
 * `[provider] cluster` in Anchor.toml is what `anchor build`, `anchor keys
 * sync` and every script in the repo read, so the app reads it too and
 * *writes* it when the user switches — the Cluster sheet's caption says so.
 * Having a second opinion in our own prefs would let the two drift and the
 * user find out at deploy time. For a Native project there is no such file,
 * so a per-project entry in the `thragg.chain` preferences file is the truth
 * instead. Not [to.eyed.thragg.core.AppSettings]: that is settings.json,
 * which the user edits and the engine round-trips through a typed struct that
 * drops keys it does not know (the same reasoning as core/AgentChoice.kt).
 *
 * Switching cluster on an Anchor project also has to keep
 * `[programs.<cluster>]` populated. Anchor only ever writes the table for the
 * cluster it is configured for, and only when it exists, so a project that
 * lived on localnet has no `[programs.devnet]` at all — and a build there
 * would not sync the id, and a deploy would find three sources with one
 * missing. [set] fills the new table from the best source to hand: the id
 * already written under another cluster (the same program, the same id), else
 * the program keypair, else `declare_id!`.
 *
 * [version] is the Compose hook. The value itself is read from disk by [of],
 * which is cheap (one small file) but not free, so composition watches the
 * counter and re-reads only when a [set] has happened.
 */
object ClusterStore {

    /**
     * Shared with the wallet's persisted address (`SeedVaultWallet`): one
     * preferences file for the chain layer's small facts about this device.
     */
    const val prefsFile = "thragg.chain"

    private const val KEY_NO_PROJECT = "cluster"
    private const val KEY_PROJECT_PREFIX = "cluster:"

    /** Bumped by every [set]; observers re-read [of] when it changes. */
    var version by mutableIntStateOf(0)
        private set

    /**
     * The cluster [projectRoot] is on. Anchor.toml when the project has one
     * and it names a real network; the prefs entry otherwise; [Cluster.DEFAULT]
     * when neither says anything — which includes an Anchor.toml that says
     * `localnet`, since that is not a place this phone can deploy to.
     *
     * Blocking but cheap: a small file read, or a preferences load the first
     * time. Call it off the main thread where you can.
     */
    fun of(context: Context, projectRoot: String?): Cluster {
        if (projectRoot != null) {
            Cluster.fromAnchor(anchorTomlSays(projectRoot))?.let { return it }
        }
        val stored = prefs(context).getString(keyFor(projectRoot), null)
        return Cluster.fromId(stored) ?: Cluster.DEFAULT
    }

    /**
     * The raw `[provider] cluster` value of the project's Anchor.toml, or null
     * when there is no such file or no such row. The Settings row prints this
     * verbatim (`localnet · set in Anchor.toml`) when [Cluster.fromAnchor]
     * cannot place it.
     */
    fun anchorTomlSays(projectRoot: String): String? {
        val file = File(projectRoot, "Anchor.toml")
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return AnchorToml.providerCluster(text)
    }

    /**
     * Point [projectRoot] at [cluster]: the prefs entry always, Anchor.toml
     * too when there is one (cluster row plus the program table it needs),
     * then a [version] bump so every row re-reads. Blocking IO.
     */
    fun set(context: Context, projectRoot: String?, cluster: Cluster) {
        prefs(context).edit().putString(keyFor(projectRoot), cluster.id).apply()
        if (projectRoot != null) {
            val file = File(projectRoot, "Anchor.toml")
            if (file.isFile) rewriteAnchorToml(file, projectRoot, cluster)
        }
        version++
    }

    /**
     * The Anchor.toml half of [set]. Every rewrite goes through [AnchorToml],
     * which touches only the rows it is asked about, so the file the user has
     * in git changes by the cluster row and at most one row per program.
     */
    private fun rewriteAnchorToml(file: File, projectRoot: String, cluster: Cluster) {
        val original = runCatching { file.readText() }.getOrNull() ?: return
        var text = AnchorToml.withProviderCluster(original, cluster.anchorName)
        val layout = BuildTasks.detect(File(projectRoot))
        val tables = AnchorToml.programTables(text)
        for (program in layout.programs) {
            val module = program.moduleName
            if (AnchorToml.programId(text, cluster.anchorName, module) != null) continue
            val fromOtherTable = tables.entries
                .firstOrNull { (name, table) -> name != cluster.anchorName && table[module] != null }
                ?.value?.get(module)
            val id = fromOtherTable ?: run {
                val resolved = ProgramIds.resolve(projectRoot, program, cluster)
                resolved.keypairId ?: resolved.declaredId
            } ?: continue
            text = AnchorToml.withProgramId(text, cluster.anchorName, module, id)
        }
        if (text != original) runCatching { file.writeText(text) }
    }

    private fun keyFor(projectRoot: String?): String =
        if (projectRoot == null) KEY_NO_PROJECT else KEY_PROJECT_PREFIX + projectRoot

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(prefsFile, Context.MODE_PRIVATE)
}
