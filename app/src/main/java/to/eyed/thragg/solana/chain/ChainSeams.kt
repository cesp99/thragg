package to.eyed.thragg.solana.chain

import android.content.Context
import to.eyed.thragg.solana.build.BuildRunner
import to.eyed.thragg.solana.build.Deployer
import to.eyed.thragg.solana.build.Deployers
import to.eyed.thragg.solana.build.ProgramTarget
import to.eyed.thragg.solana.build.ProjectLayout

/**
 * Where the chain layer plugs into the build layer.
 *
 * BuildRunner was written before any of this existed and left two seams for
 * it: [Deployers.current], which is what turns the Deploy button from "not
 * set up yet" into a deploy, and [BuildRunner.idsDisagree], the full
 * three-way program-id comparison that decides whether an Anchor build runs
 * `anchor keys sync` first. Both are plain properties on the build side so
 * that solana/build never imports solana/chain — the build layer compiles
 * and tests without a wallet, an RPC client or an Ed25519 library on its
 * classpath, and this file is the one place the dependency points the other
 * way.
 *
 * Called once from `MainActivity.onCreate`, next to `AgentSeams.install()`,
 * for the same reason that one is: the button asks whether anyone is
 * registered *before* the user reaches a screen that would have registered
 * it. Idempotent; a second call re-registers the same two objects.
 */
object ChainSeams {

    fun install() {
        Deployers.current = FlushingDeployer
        BuildRunner.idsDisagree = ProgramIds::disagree
    }
}

/**
 * [SeedVaultDeployer] with every progress line made visible at once, and the
 * CPU kept awake for the length of it ([BackgroundWork]).
 *
 * BuildRunner coalesces log writes into 100 ms batches and, for a *build*,
 * runs a ticker that drains the last batch. A deploy has no ticker: it prints
 * a line, then spends a minute writing chunks, and the line sat in the queue
 * until the next one pushed it out — measured on the Seeker, where "Buffer
 * created" was on screen for three minutes before "Writing 199 chunks"
 * appeared, though both had been printed. A deploy prints a few dozen lines
 * in total, so a flush per line costs nothing and says what is happening
 * while it is happening.
 */
private object FlushingDeployer : Deployer {
    override val label: String get() = SeedVaultDeployer.label

    override suspend fun deploy(
        context: Context,
        project: ProjectLayout,
        program: ProgramTarget,
        onLine: (String) -> Unit,
    ): Result<String> = BackgroundWork.hold(context, "chain.deploy") {
        SeedVaultDeployer.deploy(context, project, program) { line ->
            onLine(line)
            BuildRunner.log.flush()
        }
    }
}
