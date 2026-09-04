# Installation Information

GPL version 3, section 6, requires that when object code is conveyed **in or
with a User Product** — as part of a transaction transferring possession of
it — the recipient also gets the **Installation Information**: the methods,
procedures and authorization keys needed to install and run a modified
version built from modified corresponding source, on that device.

A Solana Seeker is a User Product. Thragg ships on it. So this document
is a licence deliverable, not documentation for its own sake.

## What section 6 does and does not ask for

Worth stating plainly, because this is where the topic usually goes wrong.

**It requires:** enough information that someone who modifies the source can
build the result and install it on the device they bought, and have it run.

**It does not require:** unlocking the bootloader, publishing Eyed's signing
key, modifying anything outside this application, supporting or warranting
modified builds, or making the rest of the phone modifiable. Section 6 also
says explicitly that a warranty or support may be refused for a modified
version, and that network protocols may still require a modified version not
to break the network.

Where the requirement actually bites is the **shape of the preinstall**. If
the app were placed in `/system/priv-app`, platform-signed, or granted
signature-level permissions, a user could not install a modified build that
worked the same way — and that is the case section 6 is aimed at.

## The configuration Thragg ships in

The compliant answer is available here, and the work is to keep it that way:

| | |
|---|---|
| Permissions declared | `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS` — both normal/runtime |
| Signature-level permissions | none |
| Platform signing required | no |
| Signature / integrity checks in the app | none — no `GET_SIGNATURES` comparison, no Play Integrity, no SafetyNet, no "unofficial build" refusal |
| Privileged install location required | no |
| Package name | `to.eyed.thragg` |

Because the app checks nothing about who signed it, a build you compiled and
signed with your own key behaves exactly like ours. Everything below follows
from that.

**This must be written into the OEM agreement**, or it stops being true:
preinstalled as an ordinary user-updatable application (`/system/app` or
`/product/app` is fine), not platform-signed, no signature-level
permissions, and a user-signed APK of the same package can be installed.

`CONTRIBUTING.md` carries the matching rule for the codebase: no
self-integrity checks. That is what keeps this a one-page document.

## Installing your own build

### 1. Get the corresponding source

Either clone the repository at the release tag, or take the per-release
`thragg-<version>-corresponding-source.tar.xz` archive, which is the
complete Corresponding Source including the proot and talloc upstream
tarballs, both patches, the build script and a `cargo vendor` of the Rust
closure. See `docs/LICENSING.md` §3, and `NOTICE` for the written offer.

### 2. Build it

`docs/BUILDING.md` is the full guide — the NDK version is pinned in
`gradle.properties` and the Rust toolchain is pinned in the workspace.

```
./gradlew assembleRelease
```

Which produces an unsigned `app-<abi>-release-unsigned.apk` under
`app/build/outputs/apk/release/`.

### 3. Sign it with your own key

```
keytool -genkey -v -keystore my-release.jks -keyalg RSA -keysize 4096 \
        -validity 10000 -alias thragg

$ANDROID_HOME/build-tools/<version>/apksigner sign \
        --ks my-release.jks --ks-key-alias thragg \
        --out thragg-signed.apk \
        app-arm64-v8a-release-unsigned.apk
```

Any key works. The app does not care which one.

### 4. Install it

Because the preinstalled copy is signed by Eyed and yours is signed by you,
Android will not treat yours as an update. Remove the existing one for your
user first, then install:

```
adb shell pm uninstall --user 0 to.eyed.thragg
adb install thragg-signed.apk
```

`pm uninstall --user 0` removes the app for your user without touching the
system image; a factory reset restores the preinstalled copy. If you would
rather keep both, change `applicationId` in `app/build.gradle.kts` and your
build installs alongside — note that GPLv3 s7(e) means you may not keep the
Thragg name and marks on a modified build without permission, which is
the usual arrangement and is described in `docs/TRADEMARKS.md`.

Your data lives in the app's private storage under the package name, so a
build under a different `applicationId` starts empty. Export a project
through the SAF exporter first if you want to carry it over.

### 5. If the app is ever placed in a privileged slot

It should not be, per the agreement above. If a future image puts it in
`/system/priv-app` or platform-signs it, then this section becomes the
substance of the section 6 obligation and must be filled in with the actual
procedure for getting a user-built APK into that slot on that device. Do not
let that change land without updating this file — it is the difference
between a paragraph and a real problem.

## What this document does not cover

Bootloader unlocking, the recovery image, the OEM's OTA process, and every
other component of the phone. Those are the device manufacturer's to
document, under whatever licences their own software carries. This file is
the Installation Information for **Thragg**, which is what Eyed conveys
and what Eyed owes.
