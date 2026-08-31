# Vendored Android modules

Third-party Gradle modules copied into this repository rather than pulled
from a Maven coordinate. The Rust side has its own equivalent record in
`core/vendor/VENDOR.md`.

Vendoring rather than depending on JitPack (`com.termux:terminal-view`) is
deliberate: the artifacts are published from a `targetSdk 28` build with
Java 8 and four ABIs, the upstream release cadence is not ours, and the
terminal is a component we expect to patch (theming, input routing). Keeping
the source in-tree also keeps its ~3k lines of escape-sequence tests running
in our CI instead of trusting a binary.

## terminal-emulator, terminal-view

| | |
|---|---|
| Upstream | https://github.com/termux/termux-app |
| Commit | `3df69d1da197dd9bd71a3bafd902dffd720576b4` (v0.118.0, 2026-07-15) |
| License | GPL-3.0-only, with an Apache-2.0 heritage note (below) |
| Contents | `terminal-emulator/` and `terminal-view/` modules, complete |

Both modules were verified independent of the rest of the Termux app: they
import nothing from `com.termux.shared` or `com.termux.app`, and their
manifests are empty. `termux-shared` is deliberately **not** vendored — see
`agent-docs/archive/research/termux-analysis.md`.

### License

Termux is GPL-3.0-only, which is compatible with this project's
GPL-3.0-or-later. The terminal emulator and view derive from
[Android Terminal Emulator](https://github.com/jackpal/Android-Terminal-Emulator)
(Apache-2.0); upstream records that as an exception in its top-level
`LICENSE.md`, reproduced in each module here. Attribution also appears in
the repository `README.md`.

### Local patches

Everything under `src/` is verbatim upstream **except** for the one hunk set
below. Any future change must be marked with a `// SEEKER PATCH:` comment,
listed here, and reflected in the module's own `LICENSE.md` — GPLv3 s5(a)
asks that a modified file say it was modified, and a `LICENSE.md` claiming
the source is untouched when it is not is the kind of thing an OEM
compliance review finds.

- `terminal-view/src/main/java/com/termux/view/TerminalView.java` — a
  passive highlight (`setHighlight`, `clearHighlight`, the `mHighlight`
  field) drawn with the renderer's selection colour whenever no text
  selection is active. The terminal search bar uses it to mark the current
  match; the selection controller keeps its selectors private and brings
  handles and a copy toolbar a search match does not want.

Replaced, not patched:

- `build.gradle` (Groovy, JitPack publishing, `targetSdk` 28, Java 8, four
  ABIs) → `build.gradle.kts` against our version catalog: `compileSdk` 37,
  `minSdk` 31, Java 11, the NDK pinned in `gradle.properties`, and the two
  ABIs the Rust engine ships for (`arm64-v8a`, `x86_64`).
- `proguard-rules.pro` — both were the unmodified Android Studio template
  with every rule commented out. The app's `src/main/keepRules` already
  keeps every class with `native` members, which covers
  `com.termux.terminal.JNI`.

The Java package names (`com.termux.terminal`, `com.termux.view`) are kept
as-is on purpose: JNI symbol names encode the package
(`Java_com_termux_terminal_JNI_createSubprocess` in
`terminal-emulator/src/main/jni/termux.c`), so renaming the package means
patching the C, for no benefit.

### Syncing with upstream

```
diff -r vendor/terminal-emulator/src /path/to/termux-app/terminal-emulator/src
diff -r vendor/terminal-view/src     /path/to/termux-app/terminal-view/src
```

A sync is a re-copy of `src/`, re-applying the `terminal-view` patch above,
plus a test run (`./gradlew :terminal-emulator:testDebugUnitTest`).
`terminal-emulator` has no patches and is a straight re-copy.

### Known risks to watch on device

Recorded here because both are invisible on the host and neither is
exercised by the unit tests:

1. `TerminalSession.java` wraps the raw PTY fd into a `FileDescriptor` by
   reflecting on its private `descriptor`/`fd` field. If a future Android
   release blocks that, the replacement is
   `ParcelFileDescriptor.adoptFd(fd)`, which is public API.
2. `terminal-view/.../support/PopupWindowCompatGingerbread.java` reflects on
   `PopupWindow.setWindowLayoutType`. It already falls back silently, and it
   only affects the text-selection handles.
