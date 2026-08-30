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

## Editions (build flavours)

Seeker IDE builds in two editions from one source tree. They differ in
**one setting**, `targetSdk`, and everything else follows from it:

| | `full` | `play` |
|---|---|---|
| `targetSdk` | 28 | 37 |
| Linux userland (Debian, `apt`) | ✅ | ❌ |
| `INTERNET` permission | ✅ (to fetch the rootfs) | ❌ |
| Terminal | Debian `bash`, or Android's `sh` before the rootfs is installed | Android's `sh` (mksh) + toybox |
| Foreground service | `FOREGROUND_SERVICE` only (API 28 is exempt from service types) | also `foregroundServiceType="specialUse"` + `FOREGROUND_SERVICE_SPECIAL_USE` (API 34+ rule) |
| `ACCESS_NETWORK_STATE` | ✅ (guest DNS follows the device) | ❌ |
| Where it can ship | F-Droid, direct APK download | Google Play, and anywhere else |

Android only executes programs that arrived through the package installer.
At a modern `targetSdk` that means a downloaded program can never run — so
`apt`, and any package manager like it, is impossible. `targetSdk 28` keeps
the app in an older SELinux domain where that restriction does not apply,
which is what makes the Debian userland possible, and is also why Google
Play cannot accept such a build: Play requires a target SDK within about a
year of the current release.

The split is in `app/build.gradle.kts` (`productFlavors`), and the code that
differs lives in exactly two files, one per flavour source set:

```
app/src/full/java/…/terminal/DebianUserland.kt   downloads and runs Debian
app/src/play/java/…/terminal/PlayUserland.kt     says "no userland here"
app/src/main/java/…/terminal/Userland.kt         the interface both satisfy
app/src/full/AndroidManifest.xml                 adds INTERNET
app/src/full/jniLibs/<abi>/libproot_exec.so      proot, built by tools/build-proot.sh
```

Everything above that interface — the terminal, the session layer, the
editor, the engine — is identical in both editions.

## Build

```sh
./gradlew assembleFullDebug     # the edition with the Debian userland
./gradlew assemblePlayDebug     # the Play-compatible edition
./gradlew assembleDebug         # both
```

Release builds follow the same pattern (`assembleFullRelease`,
`assemblePlayRelease`), and `installFullDebug` / `installPlayDebug` push to
a connected device.

**Installing the `full` edition on a modern device.** Android 14 and later
refuse a sideload whose `targetSdk` is as old as this edition needs, and Play
Protect shows *"Unsafe app blocked — built for an older version of Android"*.
The install is not actually unsafe, it is the same trade the userland
requires; `adb` needs to be told to allow it:

```sh
adb install -t -r --bypass-low-target-sdk-block app-full-arm64-v8a-debug.apk
```

Verified on a Solana Seeker running Android 16 (SDK 36). A user installing
the APK by hand taps through the same warning. Instrumented tests need the flavour too:
`connectedFullDebugAndroidTest`.

### proot, for the `full` edition

The Debian userland needs `proot`, which is not in this repository as
source: `tools/build-proot.sh` fetches it (and talloc) with checksums,
cross-compiles both for `arm64-v8a` and `x86_64`, and writes the result to
`app/src/full/jniLibs/<abi>/libproot_exec.so`. The binaries are committed,
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
app/build/outputs/apk/full/debug/app-full-arm64-v8a-debug.apk    ← real devices
app/build/outputs/apk/full/debug/app-full-x86_64-debug.apk       ← emulators
app/build/outputs/apk/full/debug/app-full-universal-debug.apk    ← both
```

A release therefore ships six APKs — the two editions above, each in
`arm64-v8a`, `x86_64` and `universal`. When publishing a release, tag it
and the `.github/workflows/release.yml` workflow opens a **draft** GitHub
release whose body explains which edition and architecture a user needs,
and `app/build/outputs/apk/{full,play}/release/` is where the six signed
APKs are attached to the draft before publishing. The body always carries
the "Which APK" section (even with zero merged pull requests), so nobody
installs the wrong file.

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
extra: both editions pick it up automatically.

Re-record it when the startup path changes in a big way (a new splash
sequence, a different first screen — not routine edits):

```sh
./gradlew :app:generateBaselineProfile
```

That one command builds a non-minified release of each edition, boots a
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
  app/build/outputs/apk/full/debug/app-full-x86_64-debug.apk
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
