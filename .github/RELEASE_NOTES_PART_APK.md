## 📥 Which APK should I install?

Every release ships **three APKs**: one build, three architectures. There is
one question to answer.

| APK contains | Install it on |
|---|---|
| `arm64-v8a` | virtually every real device — phones, tablets, foldables |
| `x86_64` | emulators (Android Studio AVD on an Intel/AMD machine) |
| `universal` | both at once — when in doubt, or `adb install` on an unknown device |

`universal` always works but makes you download both engines (~12 MB more);
the single-architecture APK is the lean choice when you know yours.

### Cheat sheet

| Your situation | Install |
|---|---|
| Real device | `app-arm64-v8a-release.apk` |
| Emulator | `app-x86_64-release.apk` |
| Architecture unknown | `app-universal-release.apk` |

> ℹ️ Android may flag this app as "built for an older version of Android".
> That is intentional, not a defect: executing programs installed by `apt` is
> only possible at the older target SDK, which is exactly what makes the
> Debian userland — and therefore the language servers, the Solana toolchain
> and the agent — work. The APK is signed and safe to install.
>
> It is also why Seeker IDE is not on Google Play: Play requires a target SDK
> within about a year of the current release. There was once a second,
> Play-compatible edition without the userland; it could not clone, install a
> language server, build a program or run an agent, so it no longer ships.
