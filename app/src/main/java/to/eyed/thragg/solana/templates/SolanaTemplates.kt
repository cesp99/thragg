package to.eyed.thragg.solana.templates

import androidx.annotation.StringRes
import to.eyed.thragg.R
import java.io.File

/**
 * One file a template writes: a project-relative, '/'-separated path and its
 * whole contents.
 *
 * The same path vocabulary [to.eyed.thragg.ui.workspace.ProjectFiles]
 * uses, so a template's paths go through the same resolver every other write
 * into a project does — see [SolanaScaffold].
 */
data class TemplateFile(val path: String, val contents: String)

/**
 * The names one scaffold is built around.
 *
 * Carried as a value rather than recomputed per file because the four files
 * that mention the program — `Anchor.toml`, the crate manifest, `lib.rs` and
 * the test — have to agree, and the cheapest way to guarantee that is for them
 * all to read the same struct. See [SolanaNames] for how these are derived.
 */
data class SolanaProgram(
    /** What the user typed, and what the project directory is called. */
    val displayName: String,
    /** Cargo's package name: `my-project`. */
    val crateName: String,
    /** The `[lib]` target, `#[program]` module and Python module: `my_project`. */
    val moduleName: String,
    /** The IDL type and `anchor.workspace` key: `MyProject`. */
    val typeName: String,
    /** The address in `declare_id!`, until a keypair replaces it. */
    val programId: String,
) {
    companion object {
        /**
         * Anchor's own placeholder, the one `anchor init` writes and
         * `anchor keys sync` overwrites with the real program keypair's
         * address on the first build.
         *
         * A made-up base58 string would be worse than useless: `declare_id!`
         * decodes its argument at compile time, so an invalid one is a build
         * error, and a *valid* one nobody holds the key to is a program that
         * builds and can never be deployed. This is the address the whole
         * ecosystem already recognises as "not yet assigned".
         */
        const val PLACEHOLDER_ID = "Fg6PaFpoGXkYsidMpWTK6W2BeZ7FEfcYkg476zPFsLnS"

        /**
         * The three clusters, in the order the New program screen offers
         * them, spelled the way `anchor init` writes `[provider] cluster`.
         *
         * Deliberately no Localnet, the same rule as `chain/Cluster.kt`:
         * there is no validator on this phone (Agave has no arm64 build,
         * docs/SOLANA.md), so a project born saying `localnet` could never
         * build, deploy or test here, and the chain layer would refuse it
         * on first open anyway. The `[programs.localnet]` table stays in
         * the template regardless — that is Anchor's program-id map, not a
         * cluster choice, and `anchor keys sync` looks it up there.
         *
         * Anchor lower-cases the value before matching it, so the capitals
         * are cosmetic and a hand-edited `"devnet"` means the same thing —
         * but a file this app writes should read like a file Anchor wrote,
         * because the next person to open it will be following an Anchor
         * tutorial.
         */
        val CLUSTERS: List<String> = listOf("Devnet", "Testnet", "Mainnet")

        /**
         * What a new project gets unless the screen says otherwise.
         *
         * Devnet, not Anchor's own Localnet: `anchor init` assumes a
         * validator you can start next to your editor, and on a phone there
         * is no second terminal to leave `solana-test-validator` running in
         * (docs/UI.md, "New program" — devnet is the default in the dialog
         * for the same reason).
         */
        const val DEFAULT_CLUSTER = "Devnet"

        /** The names for [displayName], all derived the same way. */
        fun of(displayName: String, programId: String = PLACEHOLDER_ID): SolanaProgram {
            val trimmed = displayName.trim()
            return SolanaProgram(
                displayName = trimmed,
                crateName = SolanaNames.crateName(trimmed),
                moduleName = SolanaNames.moduleName(trimmed),
                typeName = SolanaNames.typeName(trimmed),
                programId = programId,
            )
        }
    }
}

/**
 * The three ways to write a Solana program, and the file set each one needs.
 *
 * Three rather than a dozen because that is the choice Solana Playground
 * offers and the one people arrive expecting; anything else is a repository to
 * clone, not a template to pick from a dialog. docs/SOLANA.md, "Projects".
 *
 * The programs are Solana Playground's own starters — `hello_anchor`, the
 * native greeting counter, Seahorse's `fizzbuzz` — copied from
 * `client/src/frameworks/<framework>/files/` in its repository, because the
 * people who pick one of these arrive from playground.solana.com and should
 * open the same file they know. Only the program id (the id sync's placeholder)
 * and, for Anchor, the module name (the project's, as `anchor init` gives it)
 * are substituted; `SolanaTemplatesTest` checks the rest byte for byte against
 * a copy of Playground's files. Playground's tests lean on its `pg.*` globals,
 * which exist only on the website, so the tests here are the same tests said
 * with `anchor.workspace` and the provider — the mapping Playground's own
 * export applies (`export.ts`).
 *
 * Around those files sits a project that *builds*: real manifests at the
 * versions that build on this phone (`anchor-lang` 0.31, `solana-program` 2.2 —
 * not Playground's, which are its build server's), pinned loosely so cargo and
 * npm pick up patch releases without the template being wrong the week after
 * it was written.
 */
enum class SolanaFramework(
    @param:StringRes val labelRes: Int,
    /** The language the program is written in — the card's subtitle. */
    @param:StringRes val languageRes: Int,
    /** One sentence on why you would pick it. */
    @param:StringRes val blurbRes: Int,
) {
    /** `anchor init`, minus the parts a phone has no use for. */
    Anchor(
        labelRes = R.string.solana_framework_anchor,
        languageRes = R.string.solana_framework_anchor_language,
        blurbRes = R.string.solana_framework_anchor_blurb,
    ),

    /** A bare `solana-program` crate: one entrypoint and nothing else. */
    Native(
        labelRes = R.string.solana_framework_native,
        languageRes = R.string.solana_framework_native_language,
        blurbRes = R.string.solana_framework_native_blurb,
    ),

    /** Python that the Seahorse compiler turns into an Anchor program. */
    Seahorse(
        labelRes = R.string.solana_framework_seahorse,
        languageRes = R.string.solana_framework_seahorse_language,
        blurbRes = R.string.solana_framework_seahorse_blurb,
    );

    /**
     * Every file this template writes, in creation order.
     *
     * [cluster] is Anchor's `[provider] cluster` — one of the three
     * spellings this app offers ([SolanaProgram.CLUSTERS]). It is a
     * parameter rather than a field of [SolanaProgram] because it is not a
     * *name*: it is the one thing in the scaffold that changes after the
     * project exists, and P6's cluster chip rewrites the same line in
     * `Anchor.toml` (docs/UI.md, P6).
     * The default is Anchor's, so `files(program)` alone still writes what
     * `anchor init` writes.
     */
    fun files(
        program: SolanaProgram,
        cluster: String = SolanaProgram.DEFAULT_CLUSTER,
    ): List<TemplateFile> = when (this) {
        Anchor -> anchorFiles(program, cluster)
        Native -> nativeFiles(program)
        Seahorse -> seahorseFiles(program, cluster)
    }

    /**
     * The file to open once the project is created: the source, every time.
     * A new project that opens on `Cargo.toml` is a new project you have to
     * navigate out of before you can start.
     */
    fun entryPath(program: SolanaProgram): String = when (this) {
        Anchor -> "programs/${program.crateName}/src/lib.rs"
        Native -> "src/lib.rs"
        Seahorse -> "programs_py/${programNames(program).moduleName}.py"
    }

    /**
     * The names the *program* inside a project for [project] gets — the
     * project's own for Anchor and Native, Playground's `fizzbuzz` for
     * Seahorse, where the file's stem is the program name (see
     * `seahorseProgram`). The New program screen previews these.
     */
    fun programNames(project: SolanaProgram): SolanaProgram = when (this) {
        Anchor, Native -> project
        Seahorse -> seahorseProgram(project)
    }
}

// --- Anchor ------------------------------------------------------------------

/**
 * Solana Playground's Anchor starter, in the layout `anchor init` writes.
 *
 * The program is Playground's `src/lib.rs` byte for byte (`hello_anchor`: one
 * `initialize` that stores a `u64`), because the people creating a project here
 * arrive from playground.solana.com and expect to open the same file — the
 * comments they read there are the comments they read here. Two things are
 * substituted and nothing else: the `#[program]` module takes the project's
 * name, as `anchor init <name>` would give it (Playground itself renames it on
 * export), and `declare_id!` holds [SolanaProgram.PLACEHOLDER_ID] so the id sync
 * in `chain/ProgramIds.kt` can find and replace it on the first build.
 *
 * Playground's tree is flat (`src/`, `client/`, `tests/`); the rest of this
 * file set — the Cargo workspace, Anchor.toml, package.json, tsconfig — is
 * what Playground's own "Export" writes around those three files
 * (`client/src/frameworks/anchor/export.ts`), at the versions that build on
 * this phone. Left out: `migrations/deploy.ts` (nothing to migrate on a fresh
 * program) and `.prettierignore`.
 */
private fun anchorFiles(program: SolanaProgram, cluster: String): List<TemplateFile> = listOf(
    TemplateFile("Anchor.toml", anchorToml(program, cluster)),
    TemplateFile("Cargo.toml", ANCHOR_WORKSPACE_CARGO_TOML),
    TemplateFile(
        "programs/${program.crateName}/Cargo.toml",
        """
        [package]
        name = "${program.crateName}"
        version = "0.1.0"
        description = "Created with Thragg"
        edition = "2021"

        # cdylib is the deployable `.so`; the plain lib is what a test, another
        # program's CPI, or the IDL build links against.
        [lib]
        crate-type = ["cdylib", "lib"]
        name = "${program.moduleName}"

        [features]
        default = []
        cpi = ["no-entrypoint"]
        no-entrypoint = []
        no-idl = []
        no-log-ix-name = []
        idl-build = ["anchor-lang/idl-build"]

        [dependencies]
        anchor-lang = "0.31.1"
        """.trimIndent() + "\n",
    ),
    // Playground: client/src/frameworks/anchor/files/src/lib.rs
    TemplateFile(
        "programs/${program.crateName}/src/lib.rs",
        """
        use anchor_lang::prelude::*;

        // This is your program's public key and it will update
        // automatically when you build the project.
        declare_id!("${program.programId}");

        #[program]
        mod ${program.moduleName} {
            use super::*;
            pub fn initialize(ctx: Context<Initialize>, data: u64) -> Result<()> {
                ctx.accounts.new_account.data = data;
                msg!("Changed data to: {}!", data); // Message will show up in the tx logs
                Ok(())
            }
        }

        #[derive(Accounts)]
        pub struct Initialize<'info> {
            // We must specify the space in order to initialize an account.
            // First 8 bytes are default account discriminator,
            // next 8 bytes come from NewAccount.data being type u64.
            // (u64 = 64 bits unsigned integer = 8 bytes)
            #[account(init, payer = signer, space = 8 + 8)]
            pub new_account: Account<'info, NewAccount>,
            #[account(mut)]
            pub signer: Signer<'info>,
            pub system_program: Program<'info, System>,
        }

        #[account]
        pub struct NewAccount {
            data: u64
        }
        """.trimIndent() + "\n",
    ),
    // Playground: client/src/frameworks/anchor/files/tests/anchor.test.ts
    TemplateFile(
        "tests/anchor.test.ts",
        """
        // Mirrors Solana Playground's default Anchor test (tests/anchor.test.ts).
        // Playground has web3, anchor, BN, assert and pg as globals; outside the
        // website they are imports, pg.program is anchor.workspace, and
        // pg.wallet / pg.connection are the provider Anchor.toml configures.
        import * as anchor from "@coral-xyz/anchor";
        import { BN, web3 } from "@coral-xyz/anchor";
        import { assert } from "chai";
        import type { ${program.typeName} } from "../target/types/${program.moduleName}";

        describe("Test", () => {
          // Configure the client to use the cluster in Anchor.toml
          const provider = anchor.AnchorProvider.env();
          anchor.setProvider(provider);

          const program = anchor.workspace.${program.typeName} as anchor.Program<${program.typeName}>;

          it("initialize", async () => {
            // Generate keypair for the new account
            const newAccountKp = new web3.Keypair();

            // Send transaction
            const data = new BN(42);
            // accountsPartial, not accounts: Anchor 0.30+'s typed accounts()
            // refuses accounts the client resolves itself (system_program),
            // and Playground's test names every account.
            const txHash = await program.methods
              .initialize(data)
              .accountsPartial({
                newAccount: newAccountKp.publicKey,
                signer: provider.publicKey,
                systemProgram: web3.SystemProgram.programId,
              })
              .signers([newAccountKp])
              .rpc();
            console.log(`Use 'solana confirm -v ${'$'}{txHash}' to see the logs`);

            // Confirm transaction
            await provider.connection.confirmTransaction(txHash);

            // Fetch the created account
            const newAccount = await program.account.newAccount.fetch(
              newAccountKp.publicKey
            );

            console.log("On-chain data is:", newAccount.data.toString());

            // Check whether the data on-chain is equal to local 'data'
            assert(data.eq(newAccount.data));
          });
        });
        """.trimIndent() + "\n",
    ),
    TemplateFile("client/client.ts", anchorClient()),
    TemplateFile("package.json", anchorPackageJson(program)),
    TemplateFile("tsconfig.json", ANCHOR_TSCONFIG),
    TemplateFile(".gitignore", ANCHOR_GITIGNORE),
)

/**
 * `Anchor.toml`, shared by the Anchor and Seahorse templates because Seahorse
 * *is* an Anchor project — `seahorse build` generates the Rust and then hands
 * off to `anchor build`, and it reads this file to do it.
 *
 * `[programs.localnet]` is keyed by the **lib name**, not the package name:
 * that is the key `anchor keys sync` looks up and rewrites with the real
 * program id, and getting it wrong is a sync that silently does nothing.
 * The section stays `localnet` whatever [cluster] says — it is the map of
 * program name to address, and Anchor writes the deployed addresses of the
 * other clusters into their own sections as they happen.
 *
 * `[scripts] client` is what Playground's export writes so `anchor run client`
 * runs `client/client.ts`, the third file of its default tree.
 */
private fun anchorToml(program: SolanaProgram, cluster: String): String =
    """
    [toolchain]

    [features]
    resolution = true
    skip-lint = false

    [programs.localnet]
    ${program.moduleName} = "${program.programId}"

    [registry]
    url = "https://api.apr.dev"

    [provider]
    cluster = "$cluster"
    wallet = "~/.config/solana/id.json"

    [scripts]
    test = "yarn run ts-mocha -p ./tsconfig.json -t 1000000 tests/**/*.ts"
    client = "yarn run ts-node client/*.ts"
    """.trimIndent() + "\n"

/**
 * Playground's `client/client.ts` — the same four lines for all three
 * frameworks — with its globals spelled out. Wrapped in an async function
 * because the tsconfig Playground exports (`module: commonjs`) has no
 * top-level `await`.
 */
private fun anchorClient(): String =
    """
    // Mirrors Solana Playground's default client (client/client.ts); run it with
    // `anchor run client`. pg.wallet and pg.connection are Playground globals —
    // here they are the provider Anchor.toml configures.
    import * as anchor from "@coral-xyz/anchor";

    const provider = anchor.AnchorProvider.env();
    anchor.setProvider(provider);

    // Client
    (async () => {
      console.log("My address:", provider.publicKey.toString());
      const balance = await provider.connection.getBalance(provider.publicKey);
      console.log(`My balance: ${'$'}{balance / anchor.web3.LAMPORTS_PER_SOL} SOL`);
    })();
    """.trimIndent() + "\n"

/**
 * The workspace manifest Playground's export writes (and `anchor init` writes),
 * with `resolver = "2"`, which edition-2021 members want anyway.
 */
private val ANCHOR_WORKSPACE_CARGO_TOML =
    """
    [workspace]
    members = ["programs/*"]
    resolver = "2"

    # Anchor's release profile. `overflow-checks` is not a nicety on chain:
    # a silent wrap in a balance is how programs lose money, and the cost
    # of the check is nothing next to the transaction it is inside.
    [profile.release]
    overflow-checks = true
    lto = "fat"
    codegen-units = 1

    [profile.release.build-override]
    opt-level = 3
    incremental = false
    codegen-units = 1
    """.trimIndent() + "\n"

private fun anchorPackageJson(program: SolanaProgram): String =
    """
    {
      "name": "${program.crateName}",
      "version": "0.1.0",
      "license": "ISC",
      "scripts": {
        "lint:fix": "prettier */*.js \"*/**/*{.js,.ts}\" -w",
        "lint": "prettier */*.js \"*/**/*{.js,.ts}\" --check"
      },
      "dependencies": {
        "@coral-xyz/anchor": "^0.31.1"
      },
      "devDependencies": {
        "@types/bn.js": "^5.1.6",
        "@types/chai": "^4.3.20",
        "@types/mocha": "^10.0.10",
        "chai": "^4.5.0",
        "mocha": "^10.8.2",
        "prettier": "^3.4.2",
        "ts-mocha": "^10.1.0",
        "typescript": "^5.7.3"
      }
    }
    """.trimIndent() + "\n"

private val ANCHOR_TSCONFIG =
    """
    {
      "compilerOptions": {
        "types": ["mocha", "chai"],
        "typeRoots": ["./node_modules/@types"],
        "lib": ["es2015"],
        "module": "commonjs",
        "target": "es6",
        "esModuleInterop": true
      }
    }
    """.trimIndent() + "\n"

/**
 * The ignores Playground's export and `anchor init` both write. `test-ledger`
 * matters more here than on a laptop: it is the several-hundred-megabyte
 * directory `anchor test` leaves behind.
 */
private val ANCHOR_GITIGNORE =
    """
    .anchor
    .DS_Store
    target
    **/*.rs.bk
    node_modules
    test-ledger
    .yarn
    """.trimIndent() + "\n"

// --- Native ------------------------------------------------------------------

/**
 * Solana Playground's Native starter: the hello-world greeting counter.
 *
 * `src/lib.rs` is Playground's byte for byte — an account owned by the
 * program whose Borsh-encoded `u32` is incremented every time it is greeted.
 * There is no `declare_id!` in it, and none is added: Playground's Native
 * program has none, and `chain/ProgramIds.kt` reads a Native program's id
 * from its keypair (id sync is an Anchor-only step there).
 *
 * The manifest is the one Playground's export writes (`native/export.ts`) at
 * the versions that build here: `solana-program` 2.2 and `borsh` 1 with
 * `derive`, which the two `#[derive]`s need and borsh 1 no longer turns on
 * by default. Playground's `tests/native.test.ts` and `client/client.ts` are
 * TypeScript against `@solana/web3.js`; this scaffold's Test is `cargo test`
 * (docs/SOLANA.md, "How tests run"), so they are not written — a Node test
 * with nothing to run it would be a file that cannot pass.
 *
 * This is also the fastest build there is (1 min 11 s on the device,
 * docs/SOLANA.md), so it is the quickest way to find out whether a toolchain
 * install worked.
 */
private fun nativeFiles(program: SolanaProgram): List<TemplateFile> = listOf(
    TemplateFile(
        "Cargo.toml",
        """
        [package]
        name = "${program.crateName}"
        version = "0.1.0"
        description = "Native Solana Program"
        edition = "2021"

        [lib]
        crate-type = ["cdylib", "lib"]
        name = "${program.moduleName}"

        # `no-entrypoint` is the convention every native program follows: a
        # crate that calls this one over CPI depends on it with the feature on,
        # so the two programs do not both define `entrypoint`.
        [features]
        default = []
        no-entrypoint = []

        [dependencies]
        borsh = { version = "1.5", features = ["derive"] }
        solana-program = "2.2"

        [profile.release]
        overflow-checks = true
        lto = "fat"
        codegen-units = 1
        """.trimIndent() + "\n",
    ),
    // Playground: client/src/frameworks/native/files/src/lib.rs
    TemplateFile(
        "src/lib.rs",
        """
        use borsh::{BorshDeserialize, BorshSerialize};
        use solana_program::{
            account_info::{next_account_info, AccountInfo},
            entrypoint,
            entrypoint::ProgramResult,
            msg,
            program_error::ProgramError,
            pubkey::Pubkey,
        };

        /// Define the type of state stored in accounts
        #[derive(BorshSerialize, BorshDeserialize, Debug)]
        pub struct GreetingAccount {
            /// number of greetings
            pub counter: u32,
        }

        // Declare and export the program's entrypoint
        entrypoint!(process_instruction);

        // Program entrypoint's implementation
        pub fn process_instruction(
            program_id: &Pubkey, // Public key of the account the hello world program was loaded into
            accounts: &[AccountInfo], // The account to say hello to
            _instruction_data: &[u8], // Ignored, all helloworld instructions are hellos
        ) -> ProgramResult {
            msg!("Hello World Rust program entrypoint");

            // Iterating accounts is safer than indexing
            let accounts_iter = &mut accounts.iter();

            // Get the account to say hello to
            let account = next_account_info(accounts_iter)?;

            // The account must be owned by the program in order to modify its data
            if account.owner != program_id {
                msg!("Greeted account does not have the correct program id");
                return Err(ProgramError::IncorrectProgramId);
            }

            // Increment and store the number of times the account has been greeted
            let mut greeting_account = GreetingAccount::try_from_slice(&account.data.borrow())?;
            greeting_account.counter += 1;
            greeting_account.serialize(&mut *account.data.borrow_mut())?;

            msg!("Greeted {} time(s)!", greeting_account.counter);

            Ok(())
        }
        """.trimIndent() + "\n",
    ),
    TemplateFile(
        ".gitignore",
        """
        .DS_Store
        target
        **/*.rs.bk
        node_modules
        test-ledger
        """.trimIndent() + "\n",
    ),
)

// --- Seahorse ----------------------------------------------------------------

/**
 * The program every Seahorse project here is born with: Playground's
 * `fizzbuzz`, whatever the project is called.
 *
 * Playground's Seahorse starter is `src/fizzbuzz.py`, and in Seahorse the
 * file's stem *is* the program: the compiler names the generated crate
 * directory after it and passes that name to `anchor build -p`, and
 * Playground's export (`seahorse/export.ts`) takes the program name from the
 * file name the same way. Renaming the file after the project would make the
 * account still called `FizzBuzz` and the seeds still `'fizzbuzz'` in a file
 * called something else — the program name is part of the program. The
 * project's own name is the directory it lives in and the README's title.
 */
private fun seahorseProgram(program: SolanaProgram): SolanaProgram =
    SolanaProgram.of("fizzbuzz", program.programId)

/**
 * `seahorse init`, with Playground's program in it.
 *
 * The layout is the compiler's, not ours. Seahorse reads `programs_py/<name>.py`
 * and *generates* `programs/<name>/src/` from it — only `src/`: the crate
 * manifest beside it is ours to write, exactly as `seahorse init` leaves the
 * one `anchor init` wrote. Two consequences shape this template:
 *
 *  - The program directory and the crate are named after the **module**
 *    (`fizzbuzz`), because Seahorse names the directory after the Python
 *    file's stem and passes that same name to `anchor build -p`.
 *  - A placeholder `src/lib.rs` is scaffolded, and it is replaced wholesale on
 *    the first build. Without it the workspace has a member with no target,
 *    which breaks `cargo metadata` — and with it `anchor keys sync` and
 *    rust-analyzer — until the first `seahorse build` has run.
 *
 * The generated code always imports `anchor_spl`, so the manifest depends on
 * it at Anchor's version whether or not the program touches a token.
 *
 * `seahorse build` then hands off to `anchor build`, so the `Anchor.toml` and
 * workspace `Cargo.toml` below are the same ones the Anchor template ships.
 */
private fun seahorseFiles(project: SolanaProgram, cluster: String): List<TemplateFile> {
    val program = seahorseProgram(project)
    return listOf(
        TemplateFile("Anchor.toml", anchorToml(program, cluster)),
        TemplateFile("Cargo.toml", ANCHOR_WORKSPACE_CARGO_TOML),
        TemplateFile(
            "programs/${program.moduleName}/Cargo.toml",
            """
            # The crate `seahorse build` fills in: it regenerates src/ from
            # programs_py/${program.moduleName}.py on every build and leaves this file alone.
            [package]
            name = "${program.moduleName}"
            version = "0.1.0"
            description = "Created with Thragg"
            edition = "2021"

            [lib]
            crate-type = ["cdylib", "lib"]
            name = "${program.moduleName}"

            [features]
            default = []
            cpi = ["no-entrypoint"]
            no-entrypoint = []
            no-idl = []
            no-log-ix-name = []
            idl-build = ["anchor-lang/idl-build", "anchor-spl/idl-build"]

            [dependencies]
            anchor-lang = "0.31.1"
            anchor-spl = "0.31.1"
            """.trimIndent() + "\n",
        ),
        TemplateFile(
            "programs/${program.moduleName}/src/lib.rs",
            """
            // Replaced by `seahorse build`, which generates this whole directory from
            // programs_py/${program.moduleName}.py. Until then it only gives cargo a
            // target, so the workspace loads and `anchor keys sync` can run.
            use anchor_lang::prelude::*;

            declare_id!("${program.programId}");
            """.trimIndent() + "\n",
        ),
        // Playground: client/src/frameworks/seahorse/files/src/fizzbuzz.py,
        // byte for byte but for the id. Seahorse reads `declare_id` from the
        // Python, so this is the copy the id sync rewrites (chain/ProgramIds.kt).
        TemplateFile(
            "programs_py/${program.moduleName}.py",
            """
            # fizzbuzz
            # Built with Seahorse v0.2.0
            #
            # On-chain, persistent FizzBuzz!

            from seahorse.prelude import *

            # This is your program's public key and it will update
            # automatically when you build the project.
            declare_id('${program.programId}')

            class FizzBuzz(Account):
              fizz: bool
              buzz: bool
              n: u64

            @instruction
            def init(owner: Signer, fizzbuzz: Empty[FizzBuzz]):
              fizzbuzz.init(payer = owner, seeds = ['fizzbuzz', owner])

            @instruction
            def do_fizzbuzz(fizzbuzz: FizzBuzz, n: u64):
              fizzbuzz.fizz = n % 3 == 0
              fizzbuzz.buzz = n % 5 == 0
              if not fizzbuzz.fizz and not fizzbuzz.buzz:
                fizzbuzz.n = n
              else:
                fizzbuzz.n = 0
            """.trimIndent() + "\n",
        ),
        TemplateFile("programs_py/seahorse/prelude.py", SEAHORSE_PRELUDE),
        // Playground: client/src/frameworks/seahorse/files/tests/seahorse.test.ts.
        // Anchor.toml's `[scripts] test` runs tests/**/*.ts, and mocha with
        // nothing to match is a failing run, so the suite ships with the
        // Python. The PDA is derived synchronously: Playground's `async
        // describe` registers its `it`s after mocha has stopped listening.
        TemplateFile(
            "tests/seahorse.test.ts",
            """
            // Mirrors Solana Playground's default Seahorse test (tests/seahorse.test.ts).
            // Playground has web3, anchor, BN, assert and pg as globals; outside the
            // website they are imports, pg.program is anchor.workspace, and
            // pg.wallet / pg.connection are the provider Anchor.toml configures.
            import * as anchor from "@coral-xyz/anchor";
            import { BN, web3 } from "@coral-xyz/anchor";
            import { assert } from "chai";
            import type { ${program.typeName} } from "../target/types/${program.moduleName}";

            describe("FizzBuzz", () => {
              // Configure the client to use the cluster in Anchor.toml
              const provider = anchor.AnchorProvider.env();
              anchor.setProvider(provider);

              const program = anchor.workspace.${program.typeName} as anchor.Program<${program.typeName}>;

              // Generate the fizzbuzz account public key from its seeds
              const [fizzBuzzAccountPk] = web3.PublicKey.findProgramAddressSync(
                [Buffer.from("fizzbuzz"), provider.publicKey.toBuffer()],
                program.programId
              );

              it("init", async () => {
                // Send transaction
                // accountsPartial, not accounts: Anchor 0.30+'s typed accounts()
                // refuses accounts the client resolves itself (the PDA, the
                // system program), and Playground's test names them.
                const txHash = await program.methods
                  .init()
                  .accountsPartial({
                    fizzbuzz: fizzBuzzAccountPk,
                    owner: provider.publicKey,
                    systemProgram: web3.SystemProgram.programId,
                  })
                  .rpc();
                console.log(`Use 'solana confirm -v ${'$'}{txHash}' to see the logs`);

                // Confirm transaction
                await provider.connection.confirmTransaction(txHash);

                // Fetch the created account
                const fizzBuzzAccount = await program.account.fizzBuzz.fetch(
                  fizzBuzzAccountPk
                );

                console.log("Fizz:", fizzBuzzAccount.fizz);
                console.log("Buzz:", fizzBuzzAccount.buzz);
                console.log("N:", fizzBuzzAccount.n.toString());
              });

              it("doFizzbuzz", async () => {
                // Send transaction
                const txHash = await program.methods
                  .doFizzbuzz(new BN(6000))
                  .accountsPartial({
                    fizzbuzz: fizzBuzzAccountPk,
                  })
                  .rpc();

                // Confirm transaction
                await provider.connection.confirmTransaction(txHash);

                // Fetch the fizzbuzz account
                const fizzBuzzAccount = await program.account.fizzBuzz.fetch(
                  fizzBuzzAccountPk
                );

                console.log("Fizz:", fizzBuzzAccount.fizz);
                assert(fizzBuzzAccount.fizz);

                console.log("Buzz:", fizzBuzzAccount.buzz);
                assert(fizzBuzzAccount.buzz);

                console.log("N:", fizzBuzzAccount.n.toString());
                assert.equal(fizzBuzzAccount.n, 0);
              });
            });
            """.trimIndent() + "\n",
        ),
        TemplateFile("client/client.ts", anchorClient()),
        TemplateFile("package.json", anchorPackageJson(program)),
        TemplateFile("tsconfig.json", ANCHOR_TSCONFIG),
        TemplateFile(".gitignore", ANCHOR_GITIGNORE.trimEnd() + "\n__pycache__\n"),
        TemplateFile(
            "README.md",
            """
            # ${project.displayName}

            A Seahorse program — Solana Playground's `fizzbuzz` starter. The source
            is `programs_py/fizzbuzz.py`; `programs/fizzbuzz/src` is generated from
            it on every build and is not the place to edit. The program is named
            after the file, as Seahorse requires, so it stays `fizzbuzz` whatever
            the project is called.

            ```
            seahorse build     # Python -> Rust -> .so, via anchor build
            anchor test
            anchor run client
            ```

            `programs_py/seahorse/prelude.py` is only there so the editor can
            resolve the names — the compiler reads your program's syntax tree and
            never imports it.
            """.trimIndent() + "\n",
        ),
    )
}

/**
 * Type stubs for the names a Seahorse program uses.
 *
 * Deliberately a stub and not a runtime: the Seahorse compiler parses the
 * program's AST and never executes it, so this file's only readers are the
 * editor's language server and anyone who runs `python -m py_compile` on the
 * program to check its syntax. Seahorse's own `init` writes a longer version
 * of the same thing; this is the subset the scaffolded program needs, and
 * installing Seahorse replaces it.
 */
private val SEAHORSE_PRELUDE =
    """
    # Seahorse type stubs — see the note in README.md.
    #
    # Seahorse's integer types are Rust's: fixed width, and they overflow. That
    # cannot be expressed here, so they are aliases for int and the real check
    # happens in the generated program.

    from typing import Generic, TypeVar

    T = TypeVar('T')

    u8 = int
    u16 = int
    u32 = int
    u64 = int
    u128 = int
    i8 = int
    i16 = int
    i32 = int
    i64 = int
    i128 = int
    f32 = float
    f64 = float


    class Array(Generic[T]):
        # Written `Array[u8, 32]` in a program: fixed length, unlike a list.
        pass


    class Pubkey:
        pass


    class Account:
        def key(self) -> Pubkey: ...
        def transfer_lamports(self, to: 'Account', amount: u64) -> None: ...


    class Signer(Account):
        pass


    class Empty(Generic[T]):
        # An account the instruction is about to create.
        def init(self, payer: Signer, seeds: list) -> T: ...


    class Program(Account):
        pass


    class Clock:
        def unix_timestamp(self) -> i64: ...


    class TokenAccount(Account):
        pass


    class TokenMint(Account):
        pass


    def declare_id(program_id: str) -> None: ...


    def instruction(fn):
        return fn
    """.trimIndent() + "\n"

// --- Writing one out ----------------------------------------------------------

/**
 * A template, written into a real directory.
 *
 * Separated from the templates themselves because the strings above are pure
 * — a host test can build every file of every framework and read it without a
 * filesystem — and this is the half that touches disk. **Blocking**: call it
 * off the main thread, as every write in this app is.
 *
 * Nothing here updates any tree. The engine's worktree is watching the same
 * directory, so a scaffold arrives back as a snapshot version bump exactly as
 * a `git clone` into the project would (the rule
 * [to.eyed.thragg.ui.workspace.ProjectFiles] states and follows).
 */
object SolanaScaffold {

    /** What a scaffold did, or why it did nothing. */
    sealed interface Result {
        /**
         * [entryPath] is the *absolute* path of the file to open — the
         * program's source, per [SolanaFramework.entryPath].
         */
        data class Written(val root: File, val entryPath: String, val files: Int) : Result

        data class Failed(val reason: String) : Result
    }

    /**
     * Write [framework]'s files for [program] into [root], which must already
     * exist (`ProjectsRoot.create` made it).
     *
     * A partial scaffold is not cleaned up on failure, and that is deliberate:
     * unlike an import ([to.eyed.thragg.core.SafTransfer], which deletes
     * a half-copied project because it would look complete and quietly be
     * missing files), a half-written scaffold is a directory the user asked
     * for with a named file missing from it. Deleting their new project
     * because the seventh of eight writes failed would be the worse answer;
     * the failure says which file, and the rest is on disk to look at.
     */
    fun write(
        root: File,
        framework: SolanaFramework,
        program: SolanaProgram,
        cluster: String = SolanaProgram.DEFAULT_CLUSTER,
    ): Result {
        val files = framework.files(program, cluster)
        for (file in files) {
            val target = resolve(root, file.path)
                ?: return Result.Failed("${file.path} is not a path inside the project")
            val parent = target.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                return Result.Failed("Could not create ${file.path.substringBeforeLast('/')}")
            }
            val wrote = runCatching { target.writeText(file.contents) }
            if (wrote.isFailure) {
                return Result.Failed(
                    wrote.exceptionOrNull()?.message ?: "Could not write ${file.path}"
                )
            }
        }
        val entry = resolve(root, framework.entryPath(program))
            ?: return Result.Failed("The template has no entry file")
        return Result.Written(root, entry.absolutePath, files.size)
    }

    /**
     * The file [rel] names inside [root], or null if it does not name one.
     *
     * The engine's rule for a project-relative path, applied to strings this
     * package builds itself: no empty components, no `.` and no `..`. The
     * templates are all literals today, but they interpolate a *user-supplied
     * name* into their paths ("programs/${'$'}{crateName}/src/lib.rs"), and the
     * cost of checking is one split. Deliberately a local copy of
     * `ProjectFiles.resolve` rather than a call into it: that file is in
     * ui/workspace and is deleted in P10 (docs/UI.md).
     */
    private fun resolve(root: File, rel: String): File? {
        if (rel.isEmpty()) return null
        if (rel.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        return File(root, rel)
    }
}
