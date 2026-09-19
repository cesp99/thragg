# Cluster, wallet, deploy, close

The chain layer lives in `app/src/main/java/to/eyed/thragg/solana/chain/`
and is the answer to one measured fact: **there is no `solana` CLI on the
phone.** The toolchain manifest installs `cargo-build-sbf` and `anchor`, both
compiled on the device; Agave has no arm64 Linux build, so `solana program
deploy`, `solana-keygen` and therefore `anchor deploy` (which shells out to the
CLI) do not exist here. Everything that touches the chain is done in Kotlin
against the BPF Upgradeable Loader directly, over JSON-RPC.

## The three things Settings shows

| Row | Truth it reads | Where it changes |
|---|---|---|
| **Cluster** | `[provider] cluster` in Anchor.toml for Anchor and Seahorse projects; a per-project preference otherwise | Cluster sheet: devnet, testnet, mainnet-beta. Changing it rewrites Anchor.toml and fills `[programs.<cluster>]`, so `anchor build/test` and the UI never disagree |
| **Wallet** | Seed Vault, through Mobile Wallet Adapter | Wallet sheet: connect, disconnect, balance, the deploy key, open buffers, programs deployed from this phone |
| **Program** | The cluster's RPC, asked live for the project's program id | Program sheet: id, upgrade authority, reclaimable rent, explorer link, **Close program and reclaim rent** |

The Program row never shows a cached answer. It reads the 36-byte program
account and the 45-byte header of its programdata account and reports one of:
not deployed, deployed (with who holds the upgrade authority), closed (the id
is burned), or not a program.

## Two keys, one prompt

A deploy is dozens to hundreds of buffer-write transactions, and every one
needs the buffer authority's signature. Asking Seed Vault that many times is
not a product, so the app keeps a **deploy key** — an Ed25519 keypair in
`<filesDir>/chain/deploy-key.json`, generated on first use — that pays for and
signs the mechanical part. Seed Vault is asked at most once per deploy, and
only on mainnet-beta:

- **devnet:** the deploy key mines what it is short from the proof-of-work
  faucet program (`PoWSNH2hEZogtCg1Zgm51FnkmJperzYDgPK4fvs8taL`, Ellipsis
  Labs), which pays 0.02 SOL per transaction co-signed by a key whose Base58
  starts with `AAA`. `KeyGrinder.kt` finds such keys by walking the curve —
  one point addition per candidate, one field inversion per 256 of them —
  and `PowFaucet.kt` packs six claims to a transaction and keeps a dozen in
  flight through the pacer; the approach is devnet-larper's
  (github.com/cesp99/devnet-larper). "Mine 5 SOL" in the Wallet sheet
  — from the Build overflow, the Deploy sheet's balance row, Projects & tools
  or Settings — is the same miner, five SOL a tap, with a Stop. The one thing it cannot do is
  start from nothing: the fee is taken before a claim runs, so a key under
  0.002 SOL is offered `requestAirdrop` once, then the wallet for 0.05 SOL,
  then told the address to send a little devnet SOL to by hand. After that
  it mines its own. A wallet that was only ever connected still lends its
  address as the upgrade authority.
- **testnet:** `requestAirdrop` when it gives; when it is rate-limited — most
  of the time, on a shared IP — Seed Vault is asked to sign one transfer for
  the shortfall instead.
- **mainnet-beta:** Seed Vault signs one transfer that funds the deploy key
  with the estimated rent and fees. Whatever is left is swept back afterwards.

The upgrade authority ends as the Seed Vault address whenever a wallet is
connected (`SetAuthority` needs only the current authority's signature, which
is the deploy key's). Upgrading a program whose authority is Seed Vault asks
the wallet to sign exactly one `Upgrade` transaction; the buffer is still
written by the deploy key and handed over with `SetBufferAuthority` first.

### Transaction V1, and how many writes a deploy is

Every transaction only local keys sign — the buffer's CreateAccount, the
writes, a deploy or upgrade by the deploy key, SetAuthority, Close, the
faucet's claims, "Return SOL to wallet" — is **Transaction V1** (SIMD-0385;
`enable_tx_v1` is active on devnet, testnet and mainnet-beta, verified
2026-09-19): a `0x81` message with a 4,096-byte ceiling instead of 1,232,
fixed-width counts, no address lookup tables, and the compute budget in a
header bitmask rather than in ComputeBudget instructions, which a V1
transaction ignores. `TxFormatPolicy.local()` is where that is decided.

The 4,096 bytes are not spent on a bigger Write. The upgradeable loader reads
each instruction's data through bincode with a 1,232-byte limit
(`limited_deserialize(.., PACKET_DATA_SIZE)`, agave master 2026-09-19), so a
Write's chunk is at most 1,216 bytes whatever the packet; a V1 write
transaction therefore carries **three** 1,216-byte Writes (3,924 bytes with
the signature) where a legacy one carried a single 1,012-byte Write. A 200 kB
artifact is 165 chunks in **55 transactions** instead of 198, and the fees
follow the transaction count: `Loader.writeChunkSize(format)`,
`Loader.writesPerTransaction(format)` and `Loader.estimateDeploy(.., format)`
are all derived from the serializer, and the Deploy sheet and the deployer ask
them with the same format.

Measured on a Seeker against the public devnet endpoint, 2026-09-19: a
109,048-byte Anchor scaffold (SBPFv3) went up as 90 chunks in 30 write
transactions, plus the buffer, the deploy and the authority hand-over — 34
transactions, every one `version: 1` on the explorer, none failed — in
**70 s** from the first transaction to the last; the whole Deploy press,
including a Seed Vault prompt for the funding, was 2 min 25 s. The same
artifact as legacy is 108 write transactions, which at the rate measured on
2026-09-02 (186 chunks in 8 min 38 s, pacer-bound) is about five minutes.

Two facts about V1 that are not in the SIMD's summary and that the code
depends on: a V1 header with the compute-unit bit unset runs on **zero**
units, and one with the loaded-accounts-data-size bit unset loads **zero**
bytes (agave `from_v1_config`, `unwrap_or(0)`), so every V1 message this app
sends carries the legacy defaults spelled out — 3,000 units per builtin
instruction, 64 MiB of account data (`TxFormatPolicy.config`); and the
faucet's claims put their measured budget in the header, eleven keys to a
transaction (the format's twelve signatures bind before its 64 addresses or
its bytes) instead of six.

**Fallback.** A public endpoint behind a load balancer may still hand a V1
payload to a node that cannot read it. The first send or simulate answered
with a decoder, version or sanitize error (`RpcException.isFormatNotUnderstood`)
demotes the whole process to legacy — once, for its lifetime, with a line in
the deploy log — and the caller recompiles the same instructions as legacy. A
deploy demoted mid-upload keeps every chunk already on the buffer and re-cuts
only the gaps to the legacy size (`Loader.recut`), since a Write is by offset.

**The wallet.** The Seed Vault Wallet on the Seeker (1.16.2, 2026-09-19)
lists transaction version 1 in its `get_capabilities` answer and signed a
V1 transfer on devnet — verified on the explorer, `version: 1`, signer
9qVM…jNC5 — although seed-vault-sdk's own V1 pull request (#780) was still
open that day. It was unknown until it was tried, other wallets may differ,
and the answer is one RPC away, so it is asked, not assumed:
a connect calls MWA `get_capabilities` in the same association (a sign does
when nothing is cached for that cluster and account yet), and a
wallet-signed transaction is V1 only when its `supported_transaction_versions`
lists version 1 (`TxFormatPolicy.wallet`, `SeedVaultWallet.formatFor`) —
legacy otherwise, and legacy for the first request after a process restart,
whose association fills the cache. `WalletAnswers` holds a V1 answer to the
same rules as a legacy one, with the header's config as the wallet's to set,
the way its compute-budget instructions were; an answer in the other format
is refused as a shape this app cannot read.

Closing a program asks whoever holds the authority: Seed Vault signs the
`Close` transaction, or the deploy key does. The rent goes to the wallet when
one is connected. The Settings sheet refuses to offer the action for any other
authority, and the confirm says what the loader guarantees: the id can never be
deployed again.

## Files

- `Wire.kt`, `Base58.kt` — the legacy and Transaction V1 formats: compact-u16,
  account ordering (shared), both message layouts, the V1 config mask,
  signature slots; pinned against solana-message 5.0.0's vectors.
- `TxFormatPolicy.kt` — which format a transaction gets (local keys: V1;
  the wallet: what its capabilities say), the config a V1 header must carry,
  and the one-way demotion to legacy.
- `Keys.kt` — Ed25519 (`net.i2p.crypto:eddsa`), the 64-int keypair JSON the
  CLI writes, and `find_program_address` for the programdata PDA.
- `Loader.kt` — every loader instruction and account layout, rent maths, the
  write chunk size and the Writes per transaction derived from the serializer
  rather than assumed, per format.
- `Rpc.kt` — JSON-RPC over `HttpURLConnection`, paced for the public
  endpoints' limits, confirmation by block height.
- `SeedVaultWallet.kt` — the MWA client; `ActivityResultSender` is a field of
  `MainActivity` because the library registers for a result at construction.
  Probes `get_capabilities` on connect (and on a sign with nothing cached)
  for the transaction versions the wallet signs.
- `ProgramDeploy.kt`, `ProgramClose.kt`, `ProgramStatus.kt` — the flows.
- `ChainRecords.kt` — the deploy key, programs deployed from this phone, and
  buffers left open by an interrupted deploy (reclaimable from the Wallet sheet).

## Measured on two Seekers (2026-09-02)

- The Seed Vault Wallet authorizes `solana:devnet` and signs on it, but only
  when its own Settings › Network is set to Devnet. On mainnet it shows a
  "Network mismatch" sheet and closes the association; the app's message
  says which setting to change.
- The wallet **alters transactions when it signs**: a fresh blockhash and its
  own compute-budget instructions in front. `WalletAnswers` accepts exactly
  that and nothing else, and local signers sign the wallet's message. (Those
  measurements were of legacy transactions. Given a V1 request on
  2026-09-19 the same wallet returned a V1 transaction that `WalletAnswers`
  accepted; whether it rewrote the header's config was not inspected.)
- The public devnet endpoint allowed this IP about ten requests per ten
  seconds before answering 429 with a ten-second `Retry-After`, far under the
  documented limit. The pacer honours `Retry-After` and holds everyone; a
  200 kB program took eight to nine minutes of legacy chunk writes on it
  (198 transactions). With V1 a 109 kB program's 30 write transactions,
  buffer, deploy and hand-over took 70 s on the same endpoint (2026-09-19),
  one resend round included.
- The devnet faucet hangs rather than refuses when it is dry; the one
  airdrop still asked of it — a dry key's first few thousandths — is
  short-fused and falls through to the wallet, then to a message naming the
  address to fund. Everything past that is mined.
- A claim on the proof-of-work faucet, as accepted on devnet: 20,000,000
  lamports in, 810,624 out for the receipt PDA's rent, 44,907 compute units.
  Six to a legacy transaction is 1,172 bytes of the 1,232 allowed; eleven to
  a V1 transaction is about 1.9 kB of 4,096, bound by its twelve signatures
  (byte arithmetic, pinned in PowFaucetTest; not yet sent from the device).

## Staying alive in the background

A deploy is minutes of small transactions, and Android has three ways to end
it early: caching the process, sleeping the CPU, and Doze cutting the network.
`BackgroundWork.hold` wraps every deploy and close in the terminal's
foreground service plus a partial wake lock with a ceiling. Doze is the one
thing an app cannot lift for itself, so the Deploy sheet asks once, with the
system's own dialog, for the battery-optimisation exemption, and stops asking
once it is granted.

## After it lands

Build shows a card with the program id, a copy button, the explorer link and
the door to the Program sheet — the same sheet Settings opens, with the close
action on it. The card reads the record the deployer wrote and goes when the
program is closed.
