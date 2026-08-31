# Building Seeker IDE

Seeker IDE is a hybrid project: a Rust engine (`core/`) compiled for
Android with the NDK, and a Kotlin/Jetpack Compose app (`app/`) that
embeds it. One Gradle command builds both.

## Prerequisites

- **JDK 17+**
- **Android SDK** with **NDK 28.x** (install via Android Studio's SDK
  Manager or `sdkmanager "ndk;28.2.13676358"`)
- **Rust** (via [rustup](https://rustup.rs)) with the Android targets:

  ```sh
  rustup target add aarch64-linux-android x86_64-linux-android
  ```

- **cargo-ndk**:

  ```sh
  cargo install cargo-ndk
  ```

## One edition, and `targetSdk 28`

Seeker IDE has one build variant — no product flavours — and the single most
consequential line in `app/build.gradle.kts` is `targetSdk = 28`. (It still
splits into one APK per ABI; that is architecture, not edition. See below.)

Android only executes programs that arrived through the package installer.
At a modern `targetSdk` that means a downloaded program can never run — so
`apt`, and any package manager like it, is impossible. `targetSdk 28` keeps
the app in an older SELinux domain where that restriction does not apply,
which is what makes the Debian userland possible, and is also why Google
Play cannot accept such a build: Play requires a target SDK within about a
year of the current release.

**There used to be two editions.** A `play` product flavour targeted a
modern SDK and shipped without a userland; a `full` flavour carried Debian.
That split is gone. A build with no Debian cannot install `platform-tools`,
cannot run `cargo-build-sbf`, cannot run `rust-analyzer` and cannot run any
ACP agent — it cannot do the one thing this product exists to do, so it was
not an edition of this app so much as a different, smaller one. Removing it
forecloses Google Play distribution, deliberately; F-Droid and direct APK
download are where this ships.

The code the userland needs:

```
app/src/main/java/…/terminal/Userland.kt         the seam: what a session runs in
app/src/main/java/…/terminal/DebianUserland.kt   downloads and runs Debian
app/src/main/jniLibs/<abi>/libproot_exec.so      proot, built by tools/build-proot.sh
```

`Userland.kt` is still an interface with one implementation. That is not a
leftover: everything above it — the terminal, the session layer, the editor,
the engine — asks for a command to run and gets one, and the not-installed
state is genuinely reachable, because a rootfs is a download.

## Build

```sh
./gradlew assembleDebug
```

Release builds are `assembleRelease`, and `installDebug` pushes to a
connected device.

**Installing on a modern device.** Android 14 and later refuse a sideload
whose `targetSdk` is as old as this app needs, and Play Protect shows
*"Unsafe app blocked — built for an older version of Android"*. The install
is not actually unsafe, it is the same trade the userland requires; `adb`
needs to be told to allow it:

```sh
adb install -t -r --bypass-low-target-sdk-block app-arm64-v8a-debug.apk
```

Verified on a Solana Seeker running Android 16 (SDK 36). A user installing
the APK by hand taps through the same warning. Instrumented tests are
`connectedDebugAndroidTest`.

### proot

The Debian userland needs `proot`, which is not in this repository as
source: `tools/build-proot.sh` fetches it (and talloc) with checksums,
cross-compiles both for `arm64-v8a` and `x86_64`, and writes the result to
`app/src/main/jniLibs/<abi>/libproot_exec.so`. The binaries are committed
(the `.gitignore` rule for the cargo-generated `jniLibs` has an exception
for exactly these two files),
so an ordinary build needs no extra steps; run the script only to update or
rebuild them:

```sh
tools/build-proot.sh            # or --clean to start from scratch
```

Both proot and talloc are GPL-2.0-or-later. See docs/THIRD_PARTY.md for
provenance and the source offer that obligation implies.

The `cargoNdkBuild` Gradle task cross-compiles `core/` to
`libseekercore.so` for each supported ABI and drops it into
`app/src/main/jniLibs/` (generated, gitignored) before the APK is
packaged. The NDK path is derived from `sdk.dir` in `local.properties`
(falling back to `$ANDROID_HOME`); the NDK version pin lives in
`gradle.properties` (`seeker.ndkVersion`) and is shared with the
vendored terminal modules.

Two more Gradle modules live under `vendor/`: `terminal-emulator` and
`terminal-view`, Termux's terminal libraries vendored at a pinned commit
(`vendor/VENDOR.md`). The emulator builds a second small native library,
`libtermux.so`, via `ndk-build` — no extra setup beyond the NDK above.
Their unit tests run on the host:

```sh
./gradlew :terminal-emulator:testDebugUnitTest
```

The build emits one APK per ABI plus a universal one, since the Rust
engine is by far the largest thing in the package and no device can use
more than one architecture's copy:

```
app/build/outputs/apk/debug/app-arm64-v8a-debug.apk    ← real devices
app/build/outputs/apk/debug/app-x86_64-debug.apk       ← emulators
app/build/outputs/apk/debug/app-universal-debug.apk    ← both
```

A release therefore ships three APKs — `arm64-v8a`, `x86_64` and
`universal`. When publishing a release, tag it and the
`.github/workflows/release.yml` workflow opens a **draft** GitHub release
whose body explains which architecture a user needs, and
`app/build/outputs/apk/release/` is where the three signed APKs are
attached to the draft before publishing. The body always carries the "Which
APK" section (even with zero merged pull requests), so nobody installs the
wrong file.

Release builds additionally run R8 (code shrinking + obfuscation) and
resource shrinking. **The JNI boundary must survive that**: a native
symbol name encodes the Java class and method it binds to, so
`CoreBridge` is kept verbatim by `app/src/main/keepRules/rules.keep`.
Adding a class that Android instantiates reflectively — an Activity,
a Service, a Parcelable — may need a keep rule of its own.

## Baseline profile

Release builds compile a [baseline
profile](https://developer.android.com/topic/performance/baselineprofiles/overview)
ahead of time — a recorded list of the methods a cold start actually runs —
so first launch executes compiled code instead of waiting on the JIT. The
profile lives at `app/src/main/generated/baselineProfiles/baseline-prof.txt`
and is committed, so ordinary release builds need no emulator and nothing
extra.

Re-record it when the startup path changes in a big way (a new splash
sequence, a different first screen — not routine edits):

```sh
./gradlew :app:generateBaselineProfile
```

That one command builds a non-minified release, boots a
Gradle-managed headless emulator (API 36 `google_apis` `x86_64`, the same
system image `tools/fold-emulator.sh` uses), cold-starts the app a few times
while recording, and writes the merged profile back into `src/main`. It
needs that system image installed and KVM available, and takes a few
minutes. The journey it records is deliberately just "cold start into the
workspace" — see `baselineprofile/src/main/java/…/BaselineProfileGenerator.kt`
for why it stays shallow.

## Rust-only iteration

```sh
cd core
cargo test          # host-side unit tests, no device needed
cargo clippy
```

To cross-compile just the native library:

```sh
cd core
ANDROID_NDK_HOME=$ANDROID_HOME/ndk/28.2.13676358 \
  cargo ndk -t arm64-v8a -o /tmp/jniLibs build --release -p jni-bridge
```

## Install on a device

```sh
./gradlew installDebug
```

Supported ABIs: `arm64-v8a` (all real devices) and `x86_64` (emulator).

## A foldable emulator

This is a foldable-first IDE, and most of the bugs worth finding have only
ever appeared on a device: a handler on the wrong thread, a display id, a
fold that recreated the activity. A phone-shaped emulator finds none of
them, so there is a script for one that has a hinge:

```sh
tools/fold-emulator.sh          # create it if needed, then boot
adb -s emulator-5554 install -r \
  app/build/outputs/apk/debug/app-x86_64-debug.apk
```

It is `x86_64`, so install the `x86_64` APK rather than the arm64 one. The
whole stack runs there, Debian under proot included. Folding and unfolding
are commands rather than gestures:

```sh
adb -s emulator-5554 shell cmd device_state state 0   # closed
adb -s emulator-5554 shell cmd device_state state 3   # opened
adb -s emulator-5554 exec-out screencap -p > shot.png
```

To try a release build locally, sign it with the debug key — an unsigned
APK cannot be installed:

```sh
apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
  --ks-key-alias androiddebugkey --key-pass pass:android \
  app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk
```
