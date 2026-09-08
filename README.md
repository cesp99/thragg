# Thragg

**A Solana-first, native, open-source IDE for the Solana Seeker — write,
build, test and deploy on-chain programs entirely from the phone.**

## What this is

Solana Playground, but the compiler is in your pocket rather than in a
datacentre. Thragg carries a real Debian and the SBF toolchain on the
device, so `anchor build`, `cargo build-sbf`, `seahorse build` and the test
run all happen locally — and a deploy is signed by the phone's own wallet.

- **The toolchain is on the device.** Debian through `proot`, rustup, the
  Solana platform-tools (LLVM + a Rust that targets SBF), `cargo-build-sbf`,
  Anchor, the Seahorse compiler and rust-analyzer. One tap to install, then
  it builds offline. No cloud build server, no account, no upload of your
  program. The two drivers nobody publishes for arm64 come prebuilt from
  [our own public workflow](https://github.com/cesp99/solana-tools-arm64);
  Seahorse compiles on the phone in about two minutes.
- **Solana-first, not Solana-flavoured.** New program means Anchor, Native
  or Seahorse. Build, test, deploy and the cluster are first-class
  screens, not tasks you wire up by hand, and a compile error is a row you
  tap, a squiggle in the editor, and a "Fix with agent" button.
- **The wallet is already here.** The Seeker has Seed Vault, so deploying
  is signed on-device through Mobile Wallet Adapter instead of a keypair
  file sitting in the project. Devnet funding is a tap.
- **Zed's engine, not a lookalike.** The core reuses Zed's actual Rust
  crates (rope/CRDT text engine, tree-sitter, grammars, an LSP client)
  compiled for Android with the NDK.
- **Spettro, in full.** The agent screen speaks
  [ACP](https://agentclientprotocol.com), and Spettro's ACP is a superset of
  it — workflows with live phases, Ultra mode, the Mode/Model/Permission/
  Thinking selectors, question forms, a live context gauge, steering. The
  phone renders all of it natively rather than reducing Spettro to the
  common denominator. Any other ACP agent still works as a settings entry.
- **It remembers where you were.** Android kills a backgrounded process
  holding a 1.4 GB toolchain within a minute; the open files, the caret,
  the scroll and the screen you were on come back.
- **No telemetry. No analytics. Ever.** In the tradition of
  [VSCodium](https://vscodium.com): the user's code and behavior are
  nobody's business.

See [docs/SOLANA.md](docs/SOLANA.md) for the design of the Solana layer,
[docs/CHAIN.md](docs/CHAIN.md) for cluster, wallet and deploy, and
[docs/UI.md](docs/UI.md) for the shell.

## One device, one orientation

Thragg is built for the Seeker: a 400 × 890 dp portrait screen, one hand,
touch, a soft keyboard. Three destinations on a bottom bar — **Code**,
**Agent**, **Build** — with sheets over them for files, search, projects,
deploy and the wallet, and a fixed action row above the keyboard for the
keys a phone keyboard does not have.

That is a deliberate goodbye to the shell this fork inherited. There are no
split panes, docks, tab strip, status bar, command palette, keymap, vim
mode, minimap, multibuffers, commit graph, stash, blame or markdown
preview, and the app does not rotate. DeX, foldables, tablets, external
monitors and Bluetooth-keyboard power users are not the audience; a paired
keyboard still types, and nine ordinary bindings (Enter, Tab, arrows,
Ctrl+S/Z/Y/C/V/X/F, Escape) remain as a courtesy, undocumented as a
feature. Everything the app can do has a touch target.

## Architecture in one paragraph

A Rust engine (`core/`) owns everything that isn't pixels: buffers
(Zed's rope/CRDT), syntax (tree-sitter), language intelligence (LSP),
project state, git, sessions and ACP agent connections. A Kotlin/Jetpack
Compose app (`app/`) owns everything visual and platform-specific:
rendering, input, the soft keyboard, storage access, the Solana toolchain
installer, the build runner, the chain client and Seed Vault. The two meet
at one deliberately narrow, coarse-grained JNI boundary. See
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the long version and
[docs/BUILDING.md](docs/BUILDING.md) to build it yourself.

## Status

| Area | State |
|---|---|
| Setup: Debian, rustup, platform-tools, `cargo-build-sbf`, Anchor, Node, Seahorse, rust-analyzer, Spettro | ✅ one manifest, two install lanes, resumable downloads; the gate opens in under seven minutes on a Seeker, the rest finishes in the background |
| Build | ✅ Anchor, Native and Seahorse; diagnostics parsed into the editor and the Problems screen; program-id sync before the build; ~1 min 15 s first build of a fresh crate, 4–5 s rebuilds |
| Test | ✅ `cargo test` for Native; `anchor test --skip-local-validator --skip-deploy` for Anchor and Seahorse against the program already deployed, with Node and yarn as an optional Setup row, a one-time `yarn install`, and the deploy key written as the test wallet — or `cargo test` as the offered alternative when Node is not installed (the Build tab says so) |
| Deploy, cluster, wallet | ✅ devnet / testnet / mainnet-beta, Seed Vault signing through Mobile Wallet Adapter, Kotlin-side chunked program deploy, buffer recovery, close; devnet funding from the proof-of-work faucet |
| Editor | ✅ Zed's engine under a touch surface: tree-sitter highlighting for 31 languages, LSP diagnostics, completions, hover, go to definition, rename, code actions, formatting; soft wrap on by default; find and replace |
| Agent | ✅ any ACP agent; Spettro rendered in full, bundled by Setup |
| Git | ✅ status, diff, stage and commit, branches, push, clone; credentials asked for on the phone |
| Projects | ✅ create (Anchor / Native / Seahorse), open, clone, import and export through SAF, delete |
| Session | ✅ open files, caret, scroll, destination and Shell mode restored after the process is killed |
| Settings | ✅ fourteen rows and **Edit settings.json**, which still honours Zed's per-language, formatter and `lsp.<server>` keys |
| Translations | ✅ the shell's strings are in `res/values/strings.xml`; adding a language is one file |

## Where it comes from

One build comes out of this repository, and it includes the Debian
userland, so `apt` works. Android only permits that at an older target SDK,
which Google Play does not accept — so Thragg is published on the
[Solana dApp Store](https://docs.solanamobile.com/dapp-publishing/intro),
the Seeker's own store, and as a signed APK on
[GitHub Releases](https://github.com/cesp99/thragg/releases), and not on
Play. A Play-compatible edition without the userland used to exist beside
this one; it could not clone, could not install a language server, could
not build a Solana program and could not run an agent, so it is gone.

See [docs/BUILDING.md](docs/BUILDING.md) for the details and
[docs/USERLAND.md](docs/USERLAND.md) for what the userland can do.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Testing on a real Seeker is
especially valuable — that is the device this is designed around.

## Licence & credits

**Copyright (C) 2026 Eyed** (Carlo Esposito, carlo@aploi.de).

Thragg is free software. Eyed's own code — the Kotlin app and the Rust
engine — is licensed **GPL-3.0-or-later**; see [LICENSE](LICENSE) for the
text and [NOTICE](NOTICE) for the copyright statement.

The application **as distributed is GPL-3.0-only**. It links Termux's
`terminal-emulator` and `terminal-view`, which are GPL-3.0-*only*, so no
recipient of the APK may take the "or later" option over that code. Take
Eyed's code on its own and the "or later" is yours again. If you
redistribute a build of this repository, say GPL-3.0.
[docs/LICENSING.md](docs/LICENSING.md) explains why, with the full
compatibility matrix.

This program comes with ABSOLUTELY NO WARRANTY. It is free software, and you
are welcome to redistribute it under the terms of the GNU General Public
License.

### Lineage

Thragg is a fork of **Conquest Code** (GPL-3.0-or-later), which supplies
the editor, the Debian userland, the terminal, the LSP client, git and the
ACP agent panel. Conquest Code in turn reuses Zed's engine crates and
vendors Termux's terminal libraries. Thragg adds the Solana layer and
rebuilds the shell for the Seeker's screen.

Those notices are kept deliberately. A fork that quietly drops the notices
of the work it stands on is the most common GPL violation there is.

It stands on the shoulders of:

- **[Zed](https://github.com/zed-industries/zed)** (GPL-3.0-or-later and
  Apache-2.0, © Zed Industries, Inc.) — the engine crates this project
  reuses, and the design north star.
- **[Termux](https://github.com/termux/termux-app)** (GPL-3.0-only, ©
  Fredrik Fornwall and the Termux contributors, with an Apache-2.0 heritage
  from [Android Terminal Emulator](https://github.com/jackpal/Android-Terminal-Emulator),
  © Jack Palevich) — its `terminal-emulator` and `terminal-view` libraries
  are vendored here under `vendor/`, and its work is the reference for
  running a real userland on Android.
- **[proot](https://github.com/termux/proot)** (GPL-2.0-or-later) — the
  userspace chroot that lets a Linux distribution run without root — and
  **[talloc](https://download.samba.org/pub/talloc/)** (LGPL-3.0-or-later),
  linked into it.
- **[Debian](https://www.debian.org)** — the userland itself, and the
  package archive behind it.
- **[IBM Plex Sans](https://github.com/IBM/plex)** and
  **[Lilex](https://github.com/mishamyrt/Lilex)** (SIL OFL 1.1) — the two
  faces the app draws in.
- **[Lucide](https://lucide.dev)** (ISC) and **Feather** (MIT, © Cole
  Bemis) — the heritage of Zed's interface icons.
- **[VSCodium](https://github.com/VSCodium/vscodium)** (MIT) — proof that a
  community can keep an IDE honest.

Full provenance is in [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md); the
obligations, the compatibility matrix and the shipping checklist are in
[docs/LICENSING.md](docs/LICENSING.md).

### Source offer

For a period of three years from the date you received this software, and
for as long as Eyed offers spare parts or customer support for the product
model it came on, Eyed will give any third party who possesses the object
code access to copy the complete corresponding source for every GPL- and
LGPL-licensed component in it, from a network server, at no charge — the
second of the two forms GPLv3 §6(b) permits. Write to carlo@aploi.de if you
need help obtaining it.

You do not need the offer to get the source: it is in this repository, and
a complete `corresponding-source` archive is attached to every release. The
offer exists because someone who received the app preinstalled on a phone
never visited this repository, and GPLv3 s6(d) does not reach them.

### Trademarks

The GPL grants no trademark rights. Product names and logos shown beside
file types in the interface are the trademarks of their respective owners
and are used only to identify a file's type. A GPL fork may take this code;
it may not take the Thragg name or Eyed's marks. See
[docs/TRADEMARKS.md](docs/TRADEMARKS.md).

This project is not affiliated with or endorsed by Zed Industries, Termux,
Debian, GitHub, Google, Solana Labs, Solana Mobile, or VSCodium.

### Security

Found a vulnerability? Do not open a public issue —
[SECURITY.md](SECURITY.md) has the private routes and the timelines.
