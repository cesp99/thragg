#![allow(clippy::disallowed_methods, reason = "build scripts are exempt")]

fn main() {
    println!("cargo::rustc-check-cfg=cfg(gles)");

    let target_os = std::env::var("CARGO_CFG_TARGET_OS").unwrap_or_default();

    // SEEKER PATCH: the Windows manifest step is gone with the
    // `windows-manifest` feature; we never build for Windows.
    let _ = target_os;
}
