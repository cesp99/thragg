# The Solana layer

Seeker IDE is Conquest Code's editor with a Solana toolchain underneath it: a
real Debian on the phone, the SBF compiler, Anchor, and a build/deploy/test
loop that never leaves the device. This page is the design of that layer —
what is fetched, from where, and what runs when you press **Build**.

Everything here is new on top of the forked engine. The editor, terminal,
userland, LSP client, git and ACP panel are inherited unchanged; see
[ARCHITECTURE.md](ARCHITECTURE.md).

## What we verified on the device first

The whole design rests on facts measured on a Solana Seeker
(MediaTek MT6878, Android 16 / SDK 36, arm64-v8a only, 7.6 GB RAM, 93 GB
free), not on assumptions:

| Question | Answer |
|---|---|
| Page size | **4 KB** — the 16 KB page migration would have broken proot and every prebuilt guest binary; this device is not affected |
| Does proot run under Android 16? | ✅ the vendored `libproot_exec.so` starts and reports its version |
| Does Debian run under it? | ✅ Debian 13 (trixie) arm64, `id -u` = 0 |
| Does `apt` work? | ✅ `apt-get update` fetched 10.1 MB at 4 MB/s from Debian's own mirrors |
| Is there an SBF toolchain for arm64 Linux? | ✅ `platform-tools-linux-aarch64.tar.bz2`, v1.57 — **this is the load-bearing fact**; without it nothing else matters |
| Is there an Agave/`solana` build for arm64 Linux? | ❌ x86_64 and macOS only — see [What we have to build ourselves](#what-we-have-to-build-ourselves) |
| Is there an Anchor build for arm64 Linux? | ❌ Anchor ships no release binaries at all; `avm` builds from source |
| Does a Solana program actually compile on the phone? | ✅ **yes** — a native `solana-program` crate built to `hello_solana.so` in **1 min 15 s** (4 min 18 s CPU; the build parallelises across cores) |
| Is the artifact real? | ✅ `llvm-readelf` reports `Machine: Solana Bytecode Format`, format `elf64-sbf`, with an exported `entrypoint` symbol — a deployable program, not a host build |
| Does the real `cargo-build-sbf` pipeline work? | ✅ the toolchain built **itself** on the phone (`cargo install cargo-build-sbf`, 3 min 45 s) and then produced the canonical `target/deploy/` layout — `hello_solana.so` (17 KB, `Machine: Solana Bytecode Format`) plus the program keypair — in **34 s**. This is the same path `anchor build` takes |

`platform-tools` is 505 MB compressed and carries its own `cargo`, `rustc`
and an LLVM with the SBF backend (`./rust/bin/`, `./llvm/bin/`). That size
is why the toolchain cannot live in the APK.

What that unpacks to, running on the device: `rustc 1.95.0-dev`, `cargo 1.95.0`,
`clang 22.1.2`, `LLD 22.1.2`, and the targets `sbpf-solana-solana` and
`sbpfv0`–`sbpfv3-solana-solana`.

## Where the toolchain comes from

The APK stays small. On first run a **Set up Solana toolchain** step fetches
the components below, verifies each against a pinned SHA-256, and unpacks
them into the userland. It is one tap, resumable, and once done the device
builds offline.

Nothing here is hosted by us. Every component comes from its own upstream, or
is built on the device from crates.io — fewer things to trust, no mirror to go
stale, and no binaries of ours for anyone to have to take on faith:

| Component | Source | Size |
|---|---|---|
| Debian rootfs | Debian's official container image, from the registry | 30 MB |
| `rustup` (manager only, no toolchain) | `sh.rustup.rs`, `--default-toolchain none` | ~15 MB |
| SBF platform-tools | `anza-xyz/platform-tools` releases | 505 MB |
| rust-analyzer | `rust-lang/rust-analyzer` releases, `aarch64-unknown-linux-gnu` | 40 MB |
| Spettro | `aploide/spettro` releases, `linux_arm64` | 15 MB |
| `cargo-build-sbf` | crates.io — **built on the phone**, see below | ~4 min of CPU |
| Anchor | crates.io — **built on the phone**, see below | one-time build |

No full Rust download is needed: `platform-tools` already carries a host
`aarch64-unknown-linux-gnu` toolchain alongside the SBF one, and that is the
cargo that built `cargo-build-sbf` on the device.

`rustup` itself *is* needed, but only the toolchain manager — `cargo-build-sbf`
shells out to it and dies with `Failed to execute rustup` otherwise. So the
installer runs `rustup-init -y --default-toolchain none`, which downloads no
compiler at all, and then points it at the toolchain we already have:

```sh
rustup toolchain link solana /opt/solana/platform-tools/rust
```

There is no Node — Spettro is a single static binary, which is part of why it
is the bundled agent.

### The two with no arm64 build: built on the phone, once

`cargo-build-sbf` and `anchor` have no arm64 Linux binary anywhere upstream —
but both are on crates.io, and the phone can simply build them. Measured:

```
cargo install cargo-build-sbf   →  3 min 45 s   (21 min CPU across the cores)
```

That is a one-time cost inside the toolchain setup, and it removes the need to
cross-compile anything on a workstation or to host binaries of our own. The
installer runs it as the last setup step, in the background, with the rest of
the toolchain already usable.

Verified afterwards on the device: `cargo-build-sbf 4.2.0`, driving
`platform-tools`, producing `target/deploy/`.

## Living with proot

Two things about the sandbox cost real time to find, and the installer has to
respect both.

**`--link2symlink` must not be used for `cargo install`.** proot's
`--link2symlink` rewrites `hard_link()` into a symlink, which is what makes
`dpkg` — and therefore `apt` — work at all. But `cargo install` builds into a
scratch directory and then hard-links the finished binary into place before
deleting the scratch. Under `--link2symlink` the result is a symlink to a
directory that no longer exists: `cargo install` reports success and leaves a
dangling link, and the second binary fails outright with
`Operation not permitted (os error 1)`.

The fix is not a workaround so much as a rule: **`--link2symlink` belongs to
`apt` and nothing else.** The same proot, invoked without it, installs both
binaries as real files. Ordinary hard links work fine without the flag — it is
specifically dpkg's unpacking that needs it.

**Unpacking is much faster outside the guest than inside it.** proot traces
every syscall, so extracting a 1.4 GB archive of many small files through it is
painful. The rootfs layout is known, so large archives are unpacked with the
host's own tar directly into the rootfs directory and only then used from
inside — with the caveat that a hard link in the archive fails that way
(Debian's own rootfs contains one, for `perl`), so the archive's links must be
handled or the unpack must go through proot for that one case.

## The manifest

Components are data, not code. `solana/toolchain/manifest.json` lists each
one with its URL, SHA-256, unpacked size and install path, so a toolchain
bump is a manifest edit rather than a release. The installer is a plain
fetch → verify → unpack loop with progress per component, and it resumes a
partial download rather than starting over on a dropped connection — the
first run pulls the better part of a gigabyte over a phone's Wi-Fi.

## What runs when you press Build

Programs are compiled by `cargo-build-sbf`, which drives the platform-tools
`cargo` at the `sbpf-solana-solana` target. Anchor projects go through
`anchor build`, which calls the same thing.

```
Build   →  anchor build            (Anchor)
           cargo build-sbf         (Native)
Test    →  anchor test / cargo test
Deploy  →  solana program deploy target/deploy/<name>.so
```

Each runs inside the userland through the existing session layer, so a build
survives backgrounding the way a terminal does. Output is streamed to a
**Build** panel; cargo's JSON diagnostics are parsed and fed into the same
diagnostics store the language server writes to, so a compile error is a
squiggle in the editor and a row in the Problems tab, not just text.

## Projects

The new-project dialog mirrors Solana Playground's: a name and a framework.

| Framework | What it scaffolds |
|---|---|
| **Anchor (Rust)** | `Anchor.toml`, `programs/<name>/src/lib.rs`, `tests/` |
| **Native (Rust)** | `Cargo.toml` against `solana-program`, `src/lib.rs` |
| **Seahorse (Python)** | a Seahorse program that compiles down to Anchor |

## Wallet and cluster

This is the part a laptop cannot do. The Seeker has **Seed Vault**, and the
device wallet is already there — so a deploy is signed on the phone through
Mobile Wallet Adapter rather than by a keypair file lying in the project.

A filesystem keypair stays available for devnet throwaway work, because that
is what most tutorials assume, and airdrops are one button. The cluster —
localnet, devnet, testnet, mainnet-beta — is a status-bar item next to the
toolchain, and the balance of the selected wallet sits beside it.

## Agents

The inherited panel speaks [ACP](https://agentclientprotocol.com) and is
agent-agnostic: every agent is an `agent_servers` entry in settings.json.
Conquest Code deliberately named none of them and installed none.

Seeker IDE ships **one** agent, and ships it properly: **Spettro**.

The reason is not favouritism, it is that the other candidates cannot yet be
themselves over ACP on a phone. Measured on the device:

| Agent | State | Verdict |
|---|---|---|
| **Spettro** | `spettro_linux_arm64` runs natively; ACP is built in, via `-acp` (one dash — Go's flag package) | **bundled** |
| Claude Code | needs Node plus the third-party `@zed-industries/claude-code-acp` adapter | not bundled |
| Codex | `codex-cli 0.151.0` runs, but has **no** `acp` subcommand — only an experimental `app-server` — so it too needs `@zed-industries/codex-acp` | not bundled |

Both of the others work if you want them; they are just not a button. Install
them in the terminal and add an `agent_servers` entry, which is exactly the
path any other ACP agent takes.

### Spettro is not a generic ACP agent

Spettro's ACP is a superset of the standard, and the panel is built for that
superset rather than reducing Spettro to the common denominator. Its extension
surface is advertised at `initialize` under `_meta["spettro.app/extensions"]`,
and the client only receives the richer transports if it **mirrors back** the
methods it implements — so the handshake is the gate for everything below.

What the phone renders natively:

- **Toolbar selectors** — Mode (`plan` / `coding` / `ask`), Model grouped by
  provider, Permission (`ask-first` / `restricted` / `yolo`), Thinking level,
  and the **Ultra** toggle, driven by `session/set_config_option` and kept in
  sync by `config_option_update`. Ultra requires Restricted or YOLO; under
  Ask-first it is refused, and dropping back to Ask-first suspends it.
- **Workflows** — a message containing `ultracode` opens a single
  `workflow <name>` tool call that is rewritten as the run proceeds: phases
  appear pending, fill in as agents land under them, and carry per-phase
  done/failed counts and the script's `log()` lines. Each sub-agent gets its
  own tool call so "follow the agent" still works.
- **Live context gauge** — `usage_update` arrives after every LLM request
  within a turn, not merely at the end, so the gauge moves while the agent
  works.
- **Plan** — the session task graph mirrored in dependency order, with
  dependency-blocked tasks marked.
- **Question forms** — `ask-user` puts up to four related questions with
  option descriptions, previews, a recommended marker, multi-select and free
  text. On a phone this is a bottom sheet, which is the transport's best
  home; it needs the `_spettro/question/ask` extension to arrive whole.
- **Steering** — a prompt sent while a turn is running is *not* a cancel: it
  is injected as a user message at the agent's next step boundary.
- **Sessions** — `session/list` / `load` / `resume` are all supported, so
  conversations started in Spettro's own TUI show up on the phone and vice
  versa.

See `docs/acp.md` in the Spettro repository for the authoritative protocol.
