# Security policy

Thragg runs a Debian userland under `proot`, executes toolchains it
downloads, and on a Seeker it sits on the same device as Seed Vault. That is
a larger attack surface than a text editor, and it is the reason this file
exists rather than being a formality.

## Reporting a vulnerability

**Do not open a public issue for a security problem.** Two private routes,
either is fine:

- **GitHub private vulnerability reporting** — the "Report a vulnerability"
  button in this repository's Security tab. Preferred, because it keeps the
  report, the fix and the advisory in one place.
- **Email carlo@aploi.de** with `SECURITY` in the subject. If you want to
  encrypt it, ask for a key in a first message that contains no details.

Please include: what you found, the version (`versionName` and
`versionCode`, both on the About screen), the device and Android version,
and the smallest set of steps that reproduces it. A proof of concept helps
and is never required — a clear description of the flaw is worth more than
an exploit we have to ask you to explain.

## What happens next

| Stage | Target |
|---|---|
| We acknowledge your report | **3 working days** |
| We tell you whether it is confirmed, and our initial severity | **10 working days** |
| We ship a fix, or explain why it will take longer | **90 days** from confirmation |
| Public advisory and credit | With the fix, or at 90 days, whichever is first |

If we go quiet past those windows, chase us — a missed reply is a mistake,
not a decision. If we disagree with your severity we will say so and why,
rather than letting the clock run out.

We ask for coordinated disclosure: give us the 90 days before publishing.
If the flaw is being exploited in the wild, tell us that and we will drop
everything and agree a much shorter window with you.

You will be credited in the advisory and in the release notes under whatever
name or handle you ask for, or not at all if you prefer. There is no bug
bounty; this is an open-source project without a budget for one, and we
would rather say so plainly than imply otherwise.

## Supported versions

Only the latest release gets security fixes. This is a young project on a
single device and backporting to an old `versionCode` would be a promise we
could not keep.

| Version | Supported |
|---|---|
| Latest release | ✅ |
| Anything older | ❌ — update |

When Thragg ships preinstalled, the supported version is whatever the
current OEM image and the current published release are; a fix goes to both.

## Scope

**In scope** — anything that lets code or data cross a boundary the app is
supposed to hold:

- Escaping the app's private storage: reading or writing another app's data,
  or the user's files outside a project, without the user choosing them
  through the SAF picker.
- Anything that reaches the wallet. Thragg never touches a private key;
  signing goes through Seed Vault and Mobile Wallet Adapter. A path that
  gets a transaction signed without the user seeing and approving it, or
  that misrepresents what is being signed, is the most serious class of bug
  this app can have.
- Opening a project, a file, or an archive that causes code to execute
  without the user asking for it — including through a language server, a
  task definition, a `.zed/settings.json`, or a tree-sitter injection.
- Anything an **ACP agent** can do beyond what the user permitted:
  escaping the permission gate on edits, reading outside the project,
  running a command that was not approved.
- Integrity of the toolchain installer: a way to make Setup install
  something other than the pinned, SHA-256-verified artifact in
  `app/src/main/assets/solana/toolchain/manifest.json`.
- The `proot` guest reaching host state it should not — the app's own
  private files, other apps, or the system — beyond what the userland is
  designed to expose.
- Anything that sends data off the device. The app is built to send nothing;
  a network call the user did not ask for is a bug of this kind, not a
  feature request.
- Memory safety in the Rust engine or across the JNI boundary that is
  reachable from file content, project content or agent input.

**Out of scope**, and not because we do not care:

- Vulnerabilities in Debian packages the user installs with `apt`, in the
  Solana platform-tools, in rustup, in rust-analyzer or in an ACP agent
  binary. Report those to their own projects; tell us as well if we should
  bump a pin.
- The userland being able to do what a userland does. A shell that can run
  `rm -rf ~` inside the guest is a shell, not a vulnerability.
- Anything requiring a physically unlocked device in the attacker's hands,
  or an already-rooted device.
- Reports produced only by an automated scanner, with no demonstrated
  reachable impact in this app.
- Missing hardening flags with no exploit path. Interesting; send them as a
  normal issue.

## What the app already does not do

Stated so a reporter does not spend time confirming it, and so a reviewer
can check it:

- **No telemetry, no analytics, no crash reporting.** Nothing is sent
  anywhere the user did not ask for.
- **Two permissions**, both normal/runtime: `FOREGROUND_SERVICE` so terminal
  sessions survive backgrounding, and `POST_NOTIFICATIONS` so the service
  can say why it is running.
- **No signature checks, no attestation, no integrity gating.** A build you
  compiled and signed yourself behaves exactly like ours. That is
  deliberate — see `docs/INSTALLATION_INFORMATION.md` — and any change to it
  needs to be argued in a PR, not slipped in.
- **Every downloaded component is pinned by SHA-256** in the toolchain
  manifest, and the Debian image by the registry's own digest.

## Third-party components

The full register is `docs/THIRD_PARTY.md`, and the licence and obligation
analysis is `docs/LICENSING.md`. Both are the right place to look when
triaging a CVE against something this project vendors — Zed's crates,
Termux's terminal, proot, talloc, or one of the crates.io dependencies
linked into `libthraggcore.so`.
