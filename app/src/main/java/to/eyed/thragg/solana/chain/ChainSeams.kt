package to.eyed.thragg.solana.chain

import android.content.Context
import to.eyed.thragg.solana.build.BuildRunner
import to.eyed.thragg.solana.build.Deployer
import to.eyed.thragg.solana.build.Deployers
import to.eyed.thragg.solana.build.ProgramTarget
import to.eyed.thragg.solana.build.ProjectLayout
import to.eyed.thragg.solana.toolchain.SolanaToolchain
import java.io.File

/**
 * Where the chain layer plugs into the build layer.
 *
 * BuildRunner was written before any of this existed and left two seams for
 * it: [Deployers.current], which is what turns the Deploy button from "not
 * set up yet" into a deploy, and [BuildRunner.idsDisagree], the full
 * three-way program-id comparison that decides whether an Anchor build runs
 * `anchor keys sync` first — with [BuildRunner.programIdsSync] beside it,
 * the same sync carried into the two files Anchor's own sync leaves behind:
 * the Anchor.toml table for the provider's cluster, and a Seahorse program's
 * Python, which is where its `declare_id` actually lives — and
 * [BuildRunner.testWallet], the keypair file `anchor test` signs with
 * ([TestWallet]). All are plain properties on the build side so that
 * solana/build never imports solana/chain — the build layer compiles and
 * tests without a wallet, an RPC client or an Ed25519 library on its
 * classpath, and this file is the one place the dependency points the other
 * way.
 *
 * Called once from `MainActivity.onCreate`, next to `AgentSeams.install()`,
 * for the same reason that one is: the button asks whether anyone is
 * registered *before* the user reaches a screen that would have registered
 * it. Idempotent; a second call re-registers the same objects.
 */
object ChainSeams {

    fun install() {
        Deployers.current = FlushingDeployer
        BuildRunner.idsDisagree = ProgramIds::disagree
        BuildRunner.programIdsSync = ProgramIds::syncProgramIds
        BuildRunner.testWallet = TestWallet::ensure
    }
}

/**
 * The wallet `anchor test` signs with, written into the guest before the run.
 *
 * The scaffold's Anchor.toml names `[provider] wallet =
 * "~/.config/solana/id.json"`, which in the guest is
 * `/root/.config/solana/id.json`, and Anchor's TypeScript client reads that
 * file for the payer of every test transaction. Nothing on this phone would
 * otherwise create it — there is no `solana-keygen` in the guest — so the
 * first test of a fresh install failed before it began.
 *
 * What goes there is the app's own **deploy key** ([DeployKey]): the
 * throwaway Ed25519 keypair that pays for and signs deploys, funded on devnet
 * by the faucet miner (docs/CHAIN.md, "Two keys, one prompt"). It is the
 * right key for two reasons and not merely the handy one: it is the key
 * that pays for deploys, so it is the one on this phone that holds SOL and
 * the tests' transactions pay (a fresh key holds nothing, and the build log
 * says where it gets funded); and it is *not* the Seed Vault wallet, whose
 * key never leaves the secure element and could not be written to a file if
 * we wanted to. The upgrade authority stays with the Seed Vault — the tests
 * call the program, they do not upgrade it, so they never need it. The
 * copy stays inside app-private storage — the rootfs is under `filesDir`,
 * and `Keypair.write` leaves it owner-readable only — so this is the same
 * secret in a second file the same process owns, not an export.
 *
 * Rewritten only when the content differs (a first run, or a deploy key
 * that was regenerated), so a Test press costs one small read, not a write
 * to a file Anchor may be holding open.
 */
private object TestWallet {

    /** Anchor.toml's `~/.config/solana/id.json`, as the guest's root sees it. */
    const val GUEST_PATH = "/root/.config/solana/id.json"

    /** True when the guest file holds the deploy key afterwards. Blocking. */
    fun ensure(context: Context): Boolean = runCatching {
        val app = context.applicationContext
        val key = DeployKey.get(app)
        val file: File = SolanaToolchain.hostPath(app, GUEST_PATH)
        val current = Keypair.read(file)
        if (current == null || current.publicKey != key.publicKey) {
            Keypair.write(file, key)
        }
        Keypair.read(file)?.publicKey == key.publicKey
    }.getOrDefault(false)
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
