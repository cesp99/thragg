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
rustup toolchain link seeker /opt/solana/platform-tools/rust
rustup default seeker
```

The name is load-bearing. `cargo-build-sbf` 4.2.0 (`src/toolchain.rs`,
`link_solana_toolchain`) takes the first rustup toolchain whose *name
contains "solana"* and, unless it is the `<rustc>-sbpf-solana-<tag>` entry
it wants for the `--tools-version` it was given, uninstalls it before
linking its own. Until 2026-09-02 the default was linked as `solana`, so
every build deleted it, and `anchor build` — whose IDL step runs `cargo
test` on the *default* toolchain after `cargo build-sbf` returns — failed
with "override toolchain 'solana' is not installed" on every phone. Named
`seeker` it is invisible to the driver, which links and relinks its own
`1.95.0-sbpf-solana-v1.52`-style entries beside it into the seeded cache
symlinks (see the manifest's `toolsCacheSeedsNote` for why each tag is
seeded). `BuildTasks.toolchainGuard` re-runs the link+default pair before
every build, so a phone set up under the old name heals on its next build.

There is no Node — Spettro is a single static binary, which is part of why it
is the bundled agent.

### The two with no arm64 build: built by our own workflow

`cargo-build-sbf` and `anchor` have no arm64 Linux binary anywhere upstream.
Both are on crates.io, and the phone *can* build them — measured 3 min 34 s
and 10 min 26 s cold, 2 min 38 s and 6 min 23 s at opt-level 0 — but that
was nine of the thirteen minutes of setup. So they are the one thing we
build ourselves: [cesp99/solana-tools-arm64](https://github.com/cesp99/solana-tools-arm64)
is a public repository whose only job is a GitHub Actions workflow on
GitHub's `ubuntu-24.04-arm` runner that runs `cargo install <crate>
--version <v>` inside `debian:stable-slim` — the same image the phone
unpacks, so glibc and OpenSSL match — strips the result, and publishes it
as a release tagged `<tool>-v<version>` with a `.sha256`, a `build-info.txt`
(image, rustc, exact command, run URL) and a signed provenance attestation.
The manifest pins the URL and the hash; the installer unpacks the tarball
with the host's tar over `/opt/solana/cli`, which is exactly where the
on-device build used to put them.

Bumping either tool is: edit `versions.json` there, push, wait for the
release, copy the hash into this manifest. Compiling on the phone is still
supported — the installer's `cargo-install` method was kept — and is a
manifest edit away if the workflow is ever unavailable.

Verified afterwards on the device: `cargo-build-sbf 4.2.0`, driving
`platform-tools`, producing `target/deploy/`.

## How long it takes

Measured on a Solana Seeker (MediaTek, 4×A76 + 4×A55, 7.4 GB) over a home
Wi-Fi on 2026-09-02, from a wiped rootfs, with the timings the installer logs
(`adb logcat -s seeker-toolchain`; they are also written to
`files/solana-toolchain.json` and are what the onboarding quotes once a phone
has a full set of its own).

| Component | Serial installer | Where the time goes |
|---|---|---|
| Debian userland | 1 min 20 s | registry pull + unpack through proot |
| rustup | 3 s | |
| SBF platform-tools | 2 min 48 s | 42 s download at ~12 MB/s, **2 min 06 s** bzip2 unpack |
| rust-analyzer | 2 s | |
| Spettro | 2 s | |
| Build tools (apt) | 2 min 57 s | dpkg, single-threaded |
| cargo-build-sbf | 3 min 34 s | crates fetch + compile |
| Anchor | 10 min 26 s | the long pole; ~5 min 20 s of it at opt-level 0 (below) |
| **Total** | **21 min 12 s** | wall, Start to Done |

Three things were changed on the strength of that table, in this order.

**The installer runs two lanes.** The serial loop spent about three minutes
downloading and unpacking while the guest sat idle, and then three minutes in
apt while the network sat idle. Now a *fetch lane* pulls every download
smallest-first and unpacks it with the host's tar, and a *guest lane* runs
the userland, apt, the postInstalls and the compiles; a component starts the
moment every id in its manifest `needs` is installed and its own bytes are
staged. platform-tools downloads and unpacks while apt runs, so the gigabyte
is off the critical path. The compiles stay in series with each other: two
`cargo install`s at once fight for eight cores and the phone's RAM, and the
second one reuses the first one's dependencies from the shared scratch.
Measured, same phone, same day, from a wiped rootfs, with the compile flags
below already in:

| Two-lane run | Time |
|---|---|
| Debian userland (guest lane) | 1 min 08 s |
| Build tools, apt (guest lane) | 2 min 14 s — platform-tools downloaded (35 s) and unpacked (2 min 09 s) underneath it |
| rustup, rust-analyzer, Spettro, platform-tools link | under 1 s each; their bytes were already staged |
| cargo-build-sbf | 2 min 38 s |
| **gate opens** (every required row in) | **6 min 40 s** |
| Anchor | 6 min 23 s, in the background after Continue |
| **Total** | **12 min 59 s** — was 21 min 12 s |

The fetch lane finished everything it had at 3 min 43 s, well inside apt;
the guest lane never waited on a byte.

**The cheap tier.** Four changes measured together on 2026-09-02, after the
drivers went prebuilt: the platform-tools unpack no longer waits for the
userland — every archive unpacks into `files/toolchain-stage/<id>` and is
moved into the rootfs with a rename once Debian has landed — so the 2 min
bzip2 runs from t≈0; dpkg runs with `--force-unsafe-io`, since a retried
apt protects nothing an fsync would; apt installs `gcc libc6-dev make`
instead of `build-essential`, dropping g++ and dpkg-dev; and Debian's own
image is unpacked with the host's tar beside proot, as the reference script
always did, instead of through a JVM gzip pipe into a ptraced tar.
Measured from a wiped rootfs, same phone, same Wi-Fi:

| Cheap tier | Time |
|---|---|
| Debian userland | 22 s — was 1 min 21 s |
| Build tools (apt) | 2 min 00 s — was 2 min 24 s |
| platform-tools download + unpack, from t≈0 | 1 min 00 s + 2 min 09 s, now the critical path |
| **Total** | **3 min 20 s** — was 4 min 44 s |

Debian's 81 s was mostly the ptraced tar. apt's saving is smaller than the
package list suggests because `apt-get update` and the fetch are on the
network. The install is now bounded by platform-tools alone: a 60 s
download followed by 129 s of bzip2 that the guest lane finishes waiting
for. 

**Streaming the tarball into tar.** The last serial pair on the critical
path was platform-tools' own download and unpack. The fetch lane now pipes
every chunk of a fresh tarball download into the host's tar as it arrives,
while still writing the `.part` file and the digest, so the pair takes
about as long as the slower of the two; a drop mid-stream kills tar, throws
the staging tree away and leaves the `.part` for the plain resume path.
Measured from a wiped rootfs on a slower, metered Wi-Fi (about 3.4 MB/s,
against the router's 8–12 MB/s earlier in the day):

| Streaming | Time |
|---|---|
| Debian userland | 8 s |
| Build tools (apt) | 2 min 06 s |
| platform-tools, download and unpack together | 2 min 35 s, download-bound; bzip2 finished 73 ms after the last byte |
| **Total** | **2 min 48 s** — was 3 min 20 s on the faster network |

On the router's bandwidth the same pair is bounded by the 129 s of bzip2,
and the guest lane (8 s + 126 s) finishes first: the install is then about
2 min 20 s, and the next lever is upstream's choice of bzip2 (the "repack"
tier), or apt.

**The compiles left the phone.** Nine of those thirteen minutes were the two
`cargo install`s. They now come prebuilt from cesp99/solana-tools-arm64 (see
"The two with no arm64 build" above), which leaves the userland, apt and
~700 MB of downloads. Measured, same phone, from a wiped rootfs:

| Prebuilt-driver run | Time |
|---|---|
| Debian userland | 1 min 21 s |
| Build tools (apt) | 2 min 24 s |
| platform-tools download + unpack, on the fetch lane | 1 min 02 s + 2 min 09 s, finishing 59 s after apt |
| cargo-build-sbf, Anchor | 2–3 s each to download, under 1 s to verify |
| **Total** | **4 min 44 s** — was 21 min 12 s at the start of the day |

The critical path is now the userland, apt, and the tail of the
platform-tools unpack; the only compile left anywhere is upstream's bzip2.

Proven afterwards on the same phone with the prebuilt drivers: `anchor
build` on the escrow project produced `target/deploy/escrow.so` (Solana
Bytecode Format) *and* `target/idl/escrow.json` in 4 min 30 s — the IDL
half being the step that had failed on every phone until the toolchain
link was renamed (see "Where the toolchain comes from", rustup).

**The compiles are tuned for a phone under ptrace.** `cargo install` builds
with the release profile, whose opt-level 3 and single codegen unit per crate
buy nothing for two build *drivers* whose runtime is process-spawn noise. The
installer used to export `CARGO_PROFILE_RELEASE_OPT_LEVEL=1`, `LTO=off`,
`CODEGEN_UNITS=256`. Measured on the phone with the crate cache warm, one
variant at a time (`cargo install cargo-build-sbf` unless noted):

```
opt 1, cgu 256, lto off                    2 min 49 s   16 min CPU
  + -C link-arg=-fuse-ld=lld               2 min 45 s
  + -Zthreads=4 (RUSTC_BOOTSTRAP=1)        3 min 02 s   slower: 8 rustcs × 4 threads on 8 cores
opt 0 + lld + -Zthreads=4                  2 min 05 s    9 min CPU
opt 0 + lld            (anchor-cli 1.1.2)  5 min 23 s   23 min CPU   — was 10 min 26 s cold at opt 1
```

So the installer now exports **opt-level 0** and lld, and not the parallel
frontend. Opt 0 is safe for these two crates because they are build
*drivers*: their slow path is waiting on the compiler they spawn, and
nothing in them is hot. lld is kept because it is free — platform-tools
ships it on the guest `PATH` — and a 500-rlib link is where bfd shows on an
A76. What was *not* found: proot's
tracer is not the cost it was thought to be — sampled during the Anchor
compile, `libproot_exec.so` ran at 35–70 % of one core against five to six
`rustc`s at 95 % each, roughly a tenth of the compile's CPU. The compile is
CPU-bound on rustc, and the only lever left that moves it by more than a
minute is not compiling on the phone at all: an arm64 build of both crates
published from a GitHub Actions `ubuntu-24.04-arm` runner would turn
~7–8 min of compile into a 30 s download. That is a policy change —
the manifest's note says "we host nothing and mirror nothing" — and it is
the one this document recommends if the number above is still too long.

Two smaller findings, recorded so nobody re-measures them: the Setup screen
itself costs about half a core while it is on screen (the main thread at
~30 % and the render thread at ~20 %, keeping the spinner and the
indeterminate bar moving on a 120 Hz panel) — locking the phone gives that
core to rustc; and the platform-tools unpack is bzip2-bound at 2 min for
1.4 GB, which is upstream's choice of archive and not ours to change.

## Updating the toolchain

A toolchain bump is a manifest edit and no app release, and since 2026-09-02
that is literally true: the same `manifest.json` is published at
`https://raw.githubusercontent.com/cesp99/solana-tools-arm64/main/manifest.json`,
and the toolchain page (Settings → Toolchain) has **Check for updates**. The
check fetches that file, adopts it if its `released` date is later than the
manifest in use (the APK's asset, or a remote one adopted earlier — a newer
asset from an app update wins back), and compares every component's
recorded revision with the manifest's. Rows that differ show "installed ·
update available", the primary button becomes **Update (N MB)**, and
pressing it is a Start over exactly those rows: an outdated tarball's own
directory is cleared and unpacked afresh (a shared one, `/opt/solana/cli`,
is overwritten in place), an outdated apt list is re-run, and everything at
the current revision is untouched. Build keeps working throughout: the gate
and `toolchainReady` ask the lenient question — present at *any* recorded
revision — and only the rows and the Update button ask the strict one.

The remote manifest is an index, not the trust: every archive it names still
has to match the sha256 pinned beside it, and the manifest itself comes over
HTTPS from a repository whose every release carries a provenance
attestation. Bumping is: edit the asset here, bump `released`, copy the file
to the build repository, one commit each. `ToolchainManifestTest` refuses an
undated manifest.

## Living with proot

Three things about the sandbox cost real time to find, and the installer has
to respect all of them.

**The guest's DNS is TCP-only, so every resolver in its `resolv.conf` must
actually speak TCP.** `options use-vc` is load-bearing (engine-spawned
processes hit an intermittent UDP-wide EPERM on the Seeker; see
`GuestResolvers` in DebianUserland.kt for the A/B/A evidence) — but under it,
glibc's TCP leg is a plain blocking `connect(2)` with no timeout of its own.
A nameserver that drops the handshake on port 53, which a home router
routinely does, therefore stalls **every** lookup in the guest for the
kernel's full SYN retry cycle, about two minutes, before the next
`nameserver` line is tried. Measured live: `cargo install cargo-build-sbf`
sat eight minutes at zero CPU in SYN_SENT to the router before it had
resolved a single name. The writer now probes each candidate with a short
TCP connect (`ResolverReach`) and a resolver that fails the probe is left
out of the file; when nothing passes — the phone is offline — the whole
list is written anyway, so the failure stays a readable resolver error.

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
one with its URL, SHA-256, unpacked size, install path, the ids it `needs`
before it may start, and the seconds it took on the reference phone, so a
toolchain bump is a manifest edit rather than a release. The installer is a
two-lane fetch → verify → unpack / guest-side install loop over that graph
(see "How long it takes"), with progress per component, and it resumes a
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
