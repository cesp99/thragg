# If you are using a Seeker, download the arm64-v8a version

## 📥 Which APK should I install?

Every release ships **3 APKs**: three architectures.
Answer two questions and you know yours.

### 1 · Pick an architecture

| APK contains | Install it on |
|---|---|
| `arm64-v8a` | virtually every real device — phones, tablets, foldables |
| `x86_64` | emulators (Android Studio AVD on an Intel/AMD machine) |
| `universal` | both at once — when in doubt, or `adb install` on an unknown device |

`universal` always works but makes you download both engines;
the single-architecture APK is the lean choice when you know yours.

> [!NOTE]
> Android may flag the app as "built for an older version of
> Android". That is intentional, not a defect: executing programs installed
> by `apt` is only possible at the older target SDK, which is exactly what
> makes the Debian userland work. The APK is signed and safe to install.
