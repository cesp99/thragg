## Summary

<!-- What does this change do, and why? Link issues: "Closes #123". -->

## Testing

<!-- What you ran, and on which device or emulator.
     Merge gates: ./gradlew assembleDebug -Pthragg.abis=arm64-v8a,
     ./gradlew :app:testDebugUnitTest, cd core && cargo test && cargo clippy -->

## Checklist

<!-- See CONTRIBUTING.md for the reasoning behind these. -->

- [ ] Editor logic stays in Rust (`core/`), UI in Kotlin (`app/`); if the JNI boundary moved, both sides changed together
- [ ] Every interactive feature has a touch target that a thumb reaches on a 400 × 890 dp portrait screen; nothing is keyboard-only
- [ ] Nothing blocking on the main thread, no unrequested network or telemetry, no private info committed

## Screenshots

<!-- Before/after for UI changes, from a Seeker or a 400 × 890 dp portrait emulator. -->
