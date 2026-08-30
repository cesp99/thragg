## Summary

<!-- What does this change do, and why? Link issues: "Closes #123". -->

## Testing

<!-- What you ran, and on which device or emulator.
     Merge gates: ./gradlew assembleFullDebug assemblePlayDebug,
     ./gradlew :app:testFullDebugUnitTest, cd core && cargo test && cargo clippy -->

## Checklist

<!-- See CONTRIBUTING.md for the reasoning behind these. -->

- [ ] Editor logic stays in Rust (`core/`), UI in Kotlin (`app/`); if the JNI boundary moved, both sides changed together
- [ ] Interactive features work by touch **and** keyboard **and** mouse; `docs/SHORTCUTS.md` updated if shortcuts changed
- [ ] Nothing blocking on the main thread, no unrequested network or telemetry, no private info committed
- [ ] Both editions considered (`full` with the Debian userland, `play` without)

## Screenshots

<!-- Before/after for UI changes. Foldable layouts especially. -->
