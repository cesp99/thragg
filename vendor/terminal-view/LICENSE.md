This module is vendored from the [`termux/termux-app`](https://github.com/termux/termux-app)
repository at commit `3df69d1d` (v0.118.0), which is released under the
[GPLv3 only](https://www.gnu.org/licenses/gpl-3.0.html) license.

### Exceptions

- [Terminal Emulator for Android](https://github.com/jackpal/Android-Terminal-Emulator)
  code is used, which is released under the
  [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0) license. Upstream
  records this exception for the `terminal-emulator` and `terminal-view`
  libraries.

Everything under `src/` is upstream code except for the changes marked
`// THRAGG PATCH:` in `TerminalView.java`, which add the passive
search-match highlight. They are listed in `../VENDOR.md` under "Local
patches"; `terminal-emulator` has none.
