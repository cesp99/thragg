# Cluster, wallet, deploy, close

The chain layer lives in `app/src/main/java/to/eyed/seeker/code/solana/chain/`
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

A deploy is hundreds of buffer-write transactions, and every one needs the
buffer authority's signature. Asking Seed Vault two hundred times is not a
product, so the app keeps a **deploy key** — an Ed25519 keypair in
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
  (github.com/cesp99/devnet-larper). The Airdrop button in Settings is the
  same miner, five SOL a tap, with a Stop. The one thing it cannot do is
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

Closing a program asks whoever holds the authority: Seed Vault signs the
`Close` transaction, or the deploy key does. The rent goes to the wallet when
one is connected. The Settings sheet refuses to offer the action for any other
authority, and the confirm says what the loader guarantees: the id can never be
deployed again.

## Files

- `Wire.kt`, `Base58.kt` — legacy transaction format: compact-u16, account
  ordering, message serialization, signature slots.
- `Keys.kt` — Ed25519 (`net.i2p.crypto:eddsa`), the 64-int keypair JSON the
  CLI writes, and `find_program_address` for the programdata PDA.
- `Loader.kt` — every loader instruction and account layout, rent maths, the
  write chunk size derived from the serializer rather than assumed.
- `Rpc.kt` — JSON-RPC over `HttpURLConnection`, paced for the public
  endpoints' limits, confirmation by block height.
- `SeedVaultWallet.kt` — the MWA client; `ActivityResultSender` is a field of
  `MainActivity` because the library registers for a result at construction.
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
  that and nothing else, and local signers sign the wallet's message.
- The public devnet endpoint allowed this IP about ten requests per ten
  seconds before answering 429 with a ten-second `Retry-After`, far under the
  documented limit. The pacer honours `Retry-After` and holds everyone; a
  200 kB program takes eight to nine minutes of chunk writes on it.
- The devnet faucet hangs rather than refuses when it is dry; the one
  airdrop still asked of it — a dry key's first few thousandths — is
  short-fused and falls through to the wallet, then to a message naming the
  address to fund. Everything past that is mined.
- A claim on the proof-of-work faucet, as accepted on devnet: 20,000,000
  lamports in, 810,624 out for the receipt PDA's rent, 44,907 compute units.
  Six to a transaction is 1,172 bytes of the 1,232 allowed.

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
