# The five-minute demo

How to show Seeker IDE to someone from the Solana Foundation without anything
downloading, compiling cold, or failing in front of them. Every number here was
measured on the real Seeker (docs/SOLANA.md, "What was verified on hardware");
nothing below is aspirational.

The claim being demonstrated, in one sentence: **a Solana program is written,
compiled to real SBF bytecode, and inspected entirely on the phone** — no
laptop, no cloud builder, no remote toolchain.

## Preparation, the day before

All of this happens once, on Wi-Fi, on the charger. Budget 30–45 minutes,
mostly unattended.

1. **Run Setup to completion.** Build tab → the setup screen. ~677 MB down,
   ~2.1 GB on disk; the slow steps are the platform-tools download (505 MB),
   apt (2–4 min) and `cargo install cargo-build-sbf`, which compiles on the
   phone in ~3 min 45 s. Every row must be green — the fallback path when
   `cargo-build-sbf` is missing builds into the wrong layout and would poison
   the demo. Keep ≥3 GB free; the installer refuses to start with less.
   Skip nothing that is marked required. `anchor` is optional and slow
   (an unmeasured 10–20 min on-device compile); install it only if there is
   time to *verify a full `anchor build` of the scaffold afterwards* —
   an unverified Anchor card is not a demo, it is a gamble.
2. **Sign in to Spettro** (Settings → the account row, or the Agent tab's
   setup card) so the agent step doesn't hit an auth screen live.
3. **Warm the crates cache.** Create a throwaway **Native** project and build
   it once. The first build downloads `solana-program` and friends into the
   shared `CARGO_HOME`; every later Native project builds from that cache,
   offline, at the verified speed. Delete the throwaway afterwards.
4. **Cold-start check.** Force-stop the app, reopen it, confirm the Build tab
   shows the run button and not the setup push — `toolchainReady` is recomputed
   from disk at startup, so this proves the state survives.

## The five minutes

1. **Create** — Code tab → new program → name it something two-word so the
   name derivation shows (`Vault Counter` → crate `vault-counter`, module
   `vault_counter`), framework **Native**, Create. The editor opens
   `src/lib.rs` on a complete, commented entrypoint.
2. **Agent** — Agent tab, ask Spettro for a small, visible change: *"parse the
   first instruction byte and log increment/decrement/reset accordingly"*.
   Show the plan, the diff pill, the edit landing in the editor.
3. **Build** — Build tab, run. Say out loud what the log shows: buffers
   auto-saved, cargo streaming, and the wall time — **~34 s warm** (first-ever
   build of a fresh crate against the warm cache: ~1 min 11 s). If the agent
   left a deliberate error in, this is where the red squiggle, the Problems
   row, and "Fix with agent" earn their keep — one round trip, rebuild, green.
4. **The money shot** — the artifact row shows `target/deploy/vault_counter.so`
   with a fresh dot. Open the terminal and prove it is real Solana bytecode:

   ```
   /opt/solana/platform-tools/llvm/bin/llvm-readelf -h target/deploy/vault_counter.so
   ```

   `Machine: Solana Bytecode Format` — compiled by the phone, on the phone.

## What not to touch live

- **Seahorse** — the card exists, the compiler is not shipped; `seahorse
  build` dies command-not-found.
- **Deploy** — P6 is unbuilt; there is no wallet or cluster wiring yet. The
  overflow item errors honestly, but a demo is not the place to prove that.
- **Anchor Test** — needs Node, which the manifest doesn't ship; the fallback
  dialog offers `cargo test`, an awkward beat in front of a guest.
- The talk track for all three is the same and true: the SBF toolchain and the
  agent loop are the hard part and they are done; deploy/sign is Seed Vault
  integration, designed and next.
