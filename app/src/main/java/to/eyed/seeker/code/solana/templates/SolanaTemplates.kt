package to.eyed.seeker.code.solana.templates

import androidx.annotation.StringRes
import to.eyed.seeker.code.R

/**
 * One file a template writes: a project-relative, '/'-separated path and its
 * whole contents.
 *
 * The same path vocabulary [to.eyed.seeker.code.ui.workspace.ProjectFiles]
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
 * Every template here is a project that *builds* — real manifests at real
 * versions, a program with working accounts, an Anchor test that runs. A
 * scaffold with `// TODO` in it teaches nothing and, on a phone, costs the
 * several minutes of SBF build time it takes to find out it was never going to
 * work. The versions are pinned loosely (`"0.31.1"`, `"2.2"`) so cargo and npm
 * pick up patch releases without the template being wrong the week after it
 * was written.
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

    /** Every file this template writes, in creation order. */
    fun files(program: SolanaProgram): List<TemplateFile> = when (this) {
        Anchor -> anchorFiles(program)
        Native -> nativeFiles(program)
        Seahorse -> seahorseFiles(program)
    }

    /**
     * The file to open once the project is created: the source, every time.
     * A new project that opens on `Cargo.toml` is a new project you have to
     * navigate out of before you can start.
     */
    fun entryPath(program: SolanaProgram): String = when (this) {
        Anchor -> "programs/${program.crateName}/src/lib.rs"
        Native -> "src/lib.rs"
        Seahorse -> "programs_py/${program.moduleName}.py"
    }
}

// --- Anchor ------------------------------------------------------------------

/**
 * `anchor init` at 0.31, with the pieces that assume a workstation left out:
 * no `migrations/deploy.ts` (there is nothing to migrate on a fresh program)
 * and no `.prettierignore`.
 *
 * The program is a counter rather than the usual empty `initialize` because an
 * empty instruction exercises none of the three things an Anchor beginner has
 * to meet on the first day — a PDA, an account with state, and a constraint
 * that rejects the wrong signer — and the test is the only place those are
 * ever shown working.
 */
private fun anchorFiles(program: SolanaProgram): List<TemplateFile> = listOf(
    TemplateFile(
        "Anchor.toml",
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
        cluster = "Localnet"
        wallet = "~/.config/solana/id.json"

        [scripts]
        test = "yarn run ts-mocha -p ./tsconfig.json -t 1000000 tests/**/*.ts"
        """.trimIndent() + "\n",
    ),
    TemplateFile(
        "Cargo.toml",
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
        """.trimIndent() + "\n",
    ),
    TemplateFile(
        "programs/${program.crateName}/Cargo.toml",
        """
        [package]
        name = "${program.crateName}"
        version = "0.1.0"
        description = "Created with Seeker IDE"
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
    TemplateFile(
        "programs/${program.crateName}/src/lib.rs",
        """
        use anchor_lang::prelude::*;

        // Replaced by `anchor keys sync` with the address of the keypair in
        // target/deploy the first time this program is built.
        declare_id!("${program.programId}");

        #[program]
        pub mod ${program.moduleName} {
            use super::*;

            /// Create this signer's counter and set its starting value.
            pub fn initialize(ctx: Context<Initialize>, start: u64) -> Result<()> {
                let counter = &mut ctx.accounts.counter;
                counter.authority = ctx.accounts.payer.key();
                counter.count = start;
                msg!("counter created at {}", counter.count);
                Ok(())
            }

            /// Add one, refusing to wrap.
            pub fn increment(ctx: Context<Increment>) -> Result<()> {
                let counter = &mut ctx.accounts.counter;
                counter.count = counter
                    .count
                    .checked_add(1)
                    .ok_or(CounterError::Overflow)?;
                msg!("counter is now {}", counter.count);
                Ok(())
            }
        }

        #[derive(Accounts)]
        pub struct Initialize<'info> {
            // A PDA seeded by the payer, so every wallet gets its own counter
            // and the client can find it again without storing an address.
            #[account(
                init,
                payer = payer,
                space = 8 + Counter::INIT_SPACE,
                seeds = [b"counter", payer.key().as_ref()],
                bump,
            )]
            pub counter: Account<'info, Counter>,
            #[account(mut)]
            pub payer: Signer<'info>,
            pub system_program: Program<'info, System>,
        }

        #[derive(Accounts)]
        pub struct Increment<'info> {
            // `has_one` is the constraint that makes this safe: without it,
            // anyone could pass someone else's counter and increment it.
            #[account(
                mut,
                seeds = [b"counter", authority.key().as_ref()],
                bump,
                has_one = authority,
            )]
            pub counter: Account<'info, Counter>,
            pub authority: Signer<'info>,
        }

        // `InitSpace` derives the byte count the `space` above adds to the
        // 8-byte discriminator, so growing this struct cannot silently
        // under-allocate the account.
        #[account]
        #[derive(InitSpace)]
        pub struct Counter {
            pub authority: Pubkey,
            pub count: u64,
        }

        #[error_code]
        pub enum CounterError {
            #[msg("The counter cannot go any higher")]
            Overflow,
        }
        """.trimIndent() + "\n",
    ),
    TemplateFile(
        "tests/${program.crateName}.ts",
        """
        import * as anchor from "@coral-xyz/anchor";
        import { Program } from "@coral-xyz/anchor";
        import { assert } from "chai";
        import { ${program.typeName} } from "../target/types/${program.moduleName}";

        describe("${program.crateName}", () => {
          const provider = anchor.AnchorProvider.env();
          anchor.setProvider(provider);

          const program = anchor.workspace.${program.typeName} as Program<${program.typeName}>;

          // The same PDA the program derives; deriving it here rather than
          // storing it is the point of seeding it by the wallet.
          const [counter] = anchor.web3.PublicKey.findProgramAddressSync(
            [Buffer.from("counter"), provider.publicKey.toBuffer()],
            program.programId
          );

          it("creates a counter", async () => {
            await program.methods
              .initialize(new anchor.BN(0))
              .accountsPartial({
                counter,
                payer: provider.publicKey,
                systemProgram: anchor.web3.SystemProgram.programId,
              })
              .rpc();

            const account = await program.account.counter.fetch(counter);
            assert.equal(account.count.toNumber(), 0);
            assert.isTrue(account.authority.equals(provider.publicKey));
          });

          it("increments it", async () => {
            await program.methods
              .increment()
              .accountsPartial({ counter, authority: provider.publicKey })
              .rpc();

            const account = await program.account.counter.fetch(counter);
            assert.equal(account.count.toNumber(), 1);
          });
        });
        """.trimIndent() + "\n",
    ),
    TemplateFile("package.json", anchorPackageJson(program)),
    TemplateFile("tsconfig.json", ANCHOR_TSCONFIG),
    TemplateFile(".gitignore", ANCHOR_GITIGNORE),
)

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
 * Anchor's own ignores plus `test-ledger`, the several-hundred-megabyte
 * directory `anchor test` leaves behind — on a phone that is not a detail.
 */
private val ANCHOR_GITIGNORE =
    """
    .anchor
    target
    node_modules
    test-ledger
    **/*.rs.bk
    """.trimIndent() + "\n"

// --- Native ------------------------------------------------------------------

/**
 * The smallest thing that deploys: one crate, one entrypoint, no framework.
 *
 * This is the template that already built on the device in 1 min 11 s
 * (docs/SOLANA.md, "What we verified on the device first"), so it is also the
 * fastest way to find out whether a toolchain install worked.
 */
private fun nativeFiles(program: SolanaProgram): List<TemplateFile> = listOf(
    TemplateFile(
        "Cargo.toml",
        """
        [package]
        name = "${program.crateName}"
        version = "0.1.0"
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
        solana-program = "2.2"

        [profile.release]
        overflow-checks = true
        lto = "fat"
        codegen-units = 1
        """.trimIndent() + "\n",
    ),
    TemplateFile(
        "src/lib.rs",
        """
        use solana_program::{
            account_info::{next_account_info, AccountInfo},
            declare_id,
            entrypoint,
            entrypoint::ProgramResult,
            msg,
            pubkey::Pubkey,
        };

        // Replaced by the address of target/deploy/${program.moduleName}-keypair.json
        // once `cargo build-sbf` has generated it.
        declare_id!("${program.programId}");

        #[cfg(not(feature = "no-entrypoint"))]
        entrypoint!(process_instruction);

        /// Every native program is this one function: the runtime hands it the
        /// program's own address, the accounts the transaction named, and the
        /// instruction's bytes, and anything it wants to say goes to the
        /// transaction log through `msg!`.
        pub fn process_instruction(
            program_id: &Pubkey,
            accounts: &[AccountInfo],
            instruction_data: &[u8],
        ) -> ProgramResult {
            msg!("${program.crateName}: {} byte(s) of instruction data", instruction_data.len());
            msg!("program {} called with {} account(s)", program_id, accounts.len());

            // `next_account_info` is how a program walks the slice: it returns
            // NotEnoughAccountKeys rather than panicking when the client sent
            // fewer accounts than the instruction needs.
            let accounts_iter = &mut accounts.iter();
            if let Ok(first) = next_account_info(accounts_iter) {
                msg!(
                    "first account {} — signer: {}, writable: {}, {} lamports",
                    first.key,
                    first.is_signer,
                    first.is_writable,
                    first.lamports()
                );
            }

            Ok(())
        }
        """.trimIndent() + "\n",
    ),
    TemplateFile(
        ".gitignore",
        """
        target
        **/*.rs.bk
        test-ledger
        """.trimIndent() + "\n",
    ),
)

// --- Seahorse ----------------------------------------------------------------

/**
 * `seahorse init`: an Anchor project whose program is written in Python.
 *
 * The layout is the compiler's, not ours — Seahorse reads the `.py` files
 * under `programs_py/` and *generates* the Rust under `programs/`, which is
 * why nothing is scaffolded there. `seahorse build` writes it, then hands off to
 * `anchor build`, so the `Anchor.toml` and workspace `Cargo.toml` below are
 * the same ones the Anchor template ships.
 */
private fun seahorseFiles(program: SolanaProgram): List<TemplateFile> = listOf(
    TemplateFile(
        "Anchor.toml",
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
        cluster = "Localnet"
        wallet = "~/.config/solana/id.json"

        [scripts]
        test = "yarn run ts-mocha -p ./tsconfig.json -t 1000000 tests/**/*.ts"
        """.trimIndent() + "\n",
    ),
    TemplateFile(
        "Cargo.toml",
        """
        # The members glob is empty until `seahorse build` generates
        # programs/${program.crateName} from programs_py/${program.moduleName}.py.
        [workspace]
        members = ["programs/*"]
        resolver = "2"

        [profile.release]
        overflow-checks = true
        lto = "fat"
        codegen-units = 1

        [profile.release.build-override]
        opt-level = 3
        incremental = false
        codegen-units = 1
        """.trimIndent() + "\n",
    ),
    TemplateFile(
        "programs_py/${program.moduleName}.py",
        """
        # ${program.displayName}
        #
        # A Seahorse program: Python that the Seahorse compiler turns into the
        # Anchor program under programs/. Run `seahorse build` to generate it.

        from seahorse.prelude import *

        declare_id('${program.programId}')


        class Counter(Account):
            owner: Pubkey
            count: u64


        @instruction
        def initialize(owner: Signer, counter: Empty[Counter]):
            # `Empty[...]` is an account that does not exist yet; `init` creates
            # it as a PDA at these seeds, paid for by the signer.
            counter = counter.init(payer = owner, seeds = ['counter', owner])
            counter.owner = owner.key()
            counter.count = 0


        @instruction
        def increment(owner: Signer, counter: Counter):
            # An assert is Seahorse's constraint: it becomes a require! in the
            # generated Rust, and the transaction fails with this message.
            assert counter.owner == owner.key(), 'This counter is not yours'
            counter.count += 1
        """.trimIndent() + "\n",
    ),
    TemplateFile("programs_py/seahorse/prelude.py", SEAHORSE_PRELUDE),
    TemplateFile("package.json", anchorPackageJson(program)),
    TemplateFile("tsconfig.json", ANCHOR_TSCONFIG),
    TemplateFile(
        ".gitignore",
        """
        .anchor
        target
        node_modules
        test-ledger
        **/*.rs.bk
        __pycache__
        """.trimIndent() + "\n",
    ),
    TemplateFile(
        "README.md",
        """
        # ${program.displayName}

        A Seahorse program. The source is `programs_py/${program.moduleName}.py`;
        everything under `programs/` is generated from it.

        ```
        seahorse build     # Python -> Rust -> .so, via anchor build
        anchor test
        ```

        `programs_py/seahorse/prelude.py` is only there so the editor can
        resolve the names — the compiler reads your program's syntax tree and
        never imports it.
        """.trimIndent() + "\n",
    ),
)

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
