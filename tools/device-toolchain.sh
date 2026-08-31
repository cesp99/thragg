#!/usr/bin/env bash
#
# The Solana toolchain setup, as a shell script, run against a device over adb.
#
# This is the reference for what the in-app installer does. Every step here was
# run on a real Solana Seeker (Android 16, arm64-v8a, 4 KB pages) and the
# comments record what actually went wrong, because most of it is not guessable:
#
#   * proot's --link2symlink is required by dpkg (apt) and BREAKS cargo install
#   * cargo-build-sbf shells out to rustup, so rustup must exist even though we
#     never want a rustc from it
#   * unpacking 1.4 GB through proot is far slower than unpacking beside it
#
# It installs into /data/local/tmp/seekerlab rather than app storage, so it can
# be run and re-run without the app. See docs/SOLANA.md for the design.
#
# Usage:  tools/device-toolchain.sh [step...]
#         tools/device-toolchain.sh            # everything, in order
#         tools/device-toolchain.sh rootfs apt # just those steps
set -euo pipefail

LAB=/data/local/tmp/seekerlab
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${SEEKER_WORK:-$HERE/../.lab}"
PLATFORM_TOOLS_VERSION="${PLATFORM_TOOLS_VERSION:-v1.57}"
# What cargo-build-sbf 4.2.0 itself pins and downloads when its cache is cold
# and no --tools-version is passed — proven by the 2026-08 device rehearsal,
# where the pinned download ran 27 min and died. See step_cargo_build_sbf.
CARGO_BUILD_SBF_PINNED_TOOLS="${CARGO_BUILD_SBF_PINNED_TOOLS:-v1.56}"

adbsh() { adb shell "$@"; }
say() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }

# proot, two ways.
#
# `guest` is the everyday one. `guest_apt` adds --link2symlink, which rewrites
# hard_link() into a symlink so dpkg can unpack. Using it for anything else is a
# trap: cargo install builds into a scratch directory, hard-links the binary
# out, then deletes the scratch — under --link2symlink that leaves a symlink to
# a directory that no longer exists. cargo reports success; the binary is gone.
push_launchers() {
  local common='-b /dev -b /proc -b /sys -b $LAB/tmp:/tmp -b $LAB:/lab -b $LAB/projects:/projects -w /root'
  for variant in guest guest_apt; do
    local extra=""
    [ "$variant" = guest_apt ] && extra="--link2symlink"
    cat > "/tmp/$variant.sh" <<EOF
#!/system/bin/sh
LAB=$LAB
export PROOT_TMP_DIR=\$LAB/tmp
mkdir -p \$LAB/tmp \$LAB/projects \$LAB/rootfs/lab \$LAB/rootfs/projects
exec \$LAB/proot -0 $extra -r \$LAB/rootfs \\
  -b /dev -b /proc -b /sys \\
  -b \$LAB/tmp:/tmp -b \$LAB:/lab -b \$LAB/projects:/projects \\
  -w /root \\
  /usr/bin/env -i HOME=/root TERM=xterm-256color \\
  PATH=/root/.cargo/bin:/opt/solana/cli/bin:/opt/solana/platform-tools/llvm/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \\
  CARGO_HOME=/root/.cargo RUSTUP_HOME=/root/.rustup \\
  LANG=C.UTF-8 "\$@"
EOF
    adb push "/tmp/$variant.sh" "$LAB/$variant.sh" >/dev/null
    adbsh "chmod 755 $LAB/$variant.sh"
  done
}

step_rootfs() {
  say "Debian rootfs + proot"
  adbsh "mkdir -p $LAB/rootfs $LAB/tmp $LAB/projects"
  # proot comes from the APK's own jniLibs; it is the same binary the app uses.
  adb push "$HERE/../app/src/main/jniLibs/arm64-v8a/libproot_exec.so" "$LAB/proot" >/dev/null
  adbsh "chmod 755 $LAB/proot"

  [ -f "$WORK/debian-arm64-rootfs.tar.gz" ] || "$HERE/fetch-rootfs.sh" stable-slim arm64 "$WORK/debian-arm64-rootfs.tar.gz"
  adb push "$WORK/debian-arm64-rootfs.tar.gz" "$LAB/rootfs.tar.gz" >/dev/null

  # Unpacked with the device's own tar, beside proot rather than through it.
  # Debian's image contains one hard link (perl5.40.1 -> perl) which fails that
  # way; it is not worth a proot round trip for a file nothing here uses.
  adbsh "cd $LAB/rootfs && tar xzf ../rootfs.tar.gz 2>&1 | grep -v 'perl' || true"
  push_launchers
  adbsh "printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n' > $LAB/rootfs/etc/resolv.conf"
  adbsh "$LAB/guest.sh /bin/bash -c 'cat /etc/os-release | head -1; id -u'"
}

step_apt() {
  say "apt essentials (build-essential, git, curl) — the slow step"
  # --link2symlink here and ONLY here.
  adbsh "$LAB/guest_apt.sh /bin/bash -c '
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    apt-get install -y --no-install-recommends \
      curl ca-certificates build-essential pkg-config libssl-dev git python3 xz-utils bzip2
    gcc --version | head -1; git --version'"
}

step_platform_tools() {
  say "Solana platform-tools $PLATFORM_TOOLS_VERSION (505 MB -> ~1.4 GB)"
  local tarball="$WORK/platform-tools-linux-aarch64.tar.bz2"
  [ -f "$tarball" ] || curl -fL --progress-bar -o "$tarball" \
    "https://github.com/anza-xyz/platform-tools/releases/download/$PLATFORM_TOOLS_VERSION/platform-tools-linux-aarch64.tar.bz2"
  adb push "$tarball" "$LAB/platform-tools.tar.bz2" >/dev/null
  # Again: unpacked outside proot. Through it this takes many times longer.
  adbsh "mkdir -p $LAB/rootfs/opt/solana/platform-tools && cd $LAB/rootfs/opt/solana/platform-tools && tar xjf $LAB/platform-tools.tar.bz2"
  adbsh "$LAB/guest.sh /bin/bash -c '
    /opt/solana/platform-tools/rust/bin/rustc --version
    /opt/solana/platform-tools/llvm/bin/ld.lld --version | head -1'"
}

step_rustup() {
  say "rustup (manager only) + link platform-tools as the 'solana' toolchain"
  # cargo-build-sbf execs rustup and dies with "Failed to execute rustup"
  # otherwise. We want the manager, never a compiler: --default-toolchain none
  # downloads no rustc at all. Then the toolchain we already have is linked in
  # and made default, so rustup's cargo shim resolves to platform-tools' cargo.
  adbsh "$LAB/guest.sh /bin/bash -c '
    curl -sSf https://sh.rustup.rs -o /tmp/rustup-init.sh
    sh /tmp/rustup-init.sh -y --default-toolchain none --no-modify-path >/dev/null
    rustup toolchain link solana /opt/solana/platform-tools/rust
    rustup default solana
    rustup toolchain list'"
}

step_cargo_build_sbf() {
  say "cargo-build-sbf, built on the device (~4 min)"
  # NOT guest_apt: see the note on push_launchers.
  adbsh "$LAB/guest.sh /bin/bash -c '
    cargo install cargo-build-sbf --root /opt/solana/cli --target-dir /opt/solana/build 2>&1 | tail -3
    /opt/solana/cli/bin/cargo-build-sbf --version'"
  # Seed cargo-build-sbf's own tools cache with symlinks to the platform-tools
  # we already installed. Without this, a build that reaches its tools-install
  # step (no cache entry for the version it wants) DOWNLOADS its own
  # platform-tools (~450 MB) into /root/.cache/solana/<v>/ — on the 2026-08
  # rehearsal that download ran 27 min 39 s and died. Seeded for:
  #   * $PLATFORM_TOOLS_VERSION — what --tools-version asks for,
  #   * whatever the just-built driver reports as its own pin (--version does
  #     NOT trigger the download; the rehearsal proved it), because that pin
  #     is what an `anchor build` with no --tools-version asks for,
  #   * $CARGO_BUILD_SBF_PINNED_TOOLS as the rehearsal-proven fallback should
  #     the --version output ever stop parsing.
  # ln -sfn: idempotent, including over a hand-made repair symlink.
  adbsh "$LAB/guest.sh /bin/bash -c '
    pinned=\$(/opt/solana/cli/bin/cargo-build-sbf --version 2>/dev/null | grep -oE \"v[0-9]+\\.[0-9]+\" | head -n1)
    for v in $PLATFORM_TOOLS_VERSION \$pinned $CARGO_BUILD_SBF_PINNED_TOOLS; do
      mkdir -p /root/.cache/solana/\$v && ln -sfn /opt/solana/platform-tools /root/.cache/solana/\$v/platform-tools
    done
    ls -l /root/.cache/solana/'"
}

step_rust_analyzer() {
  say "rust-analyzer"
  adbsh "$LAB/guest.sh /bin/bash -c '
    mkdir -p /opt/ra && cd /opt/ra
    RV=\$(curl -sL https://api.github.com/repos/rust-lang/rust-analyzer/releases/latest | grep -oE \"\\\"tag_name\\\": \\\"[^\\\"]*\\\"\" | cut -d\\\" -f4)
    curl -sL -o ra.gz \"https://github.com/rust-lang/rust-analyzer/releases/download/\$RV/rust-analyzer-aarch64-unknown-linux-gnu.gz\"
    gunzip -f ra.gz && mv ra rust-analyzer && chmod +x rust-analyzer
    ./rust-analyzer --version'"
}

step_spettro() {
  say "Spettro (the bundled ACP agent)"
  adbsh "$LAB/guest.sh /bin/bash -c '
    mkdir -p /opt/agents && cd /opt/agents
    SV=\$(curl -sL https://api.github.com/repos/aploide/spettro/releases/latest | grep -oE \"\\\"tag_name\\\": \\\"[^\\\"]*\\\"\" | cut -d\\\" -f4)
    curl -sL -o spettro.tar.gz \"https://github.com/aploide/spettro/releases/download/\$SV/spettro_\${SV}_linux_arm64.tar.gz\"
    tar xzf spettro.tar.gz && chmod +x spettro && rm spettro.tar.gz
    # ACP is a single dash: Go flag package, not POSIX long options.
    ./spettro --help 2>&1 | grep -A1 -- \"-acp\" | head -2'"
}

step_verify() {
  say "end to end: build a program and check the artifact is really SBF"
  adbsh "mkdir -p $LAB/projects/hello_solana/src"
  adb push "$WORK/hello_solana/Cargo.toml" "$LAB/projects/hello_solana/Cargo.toml" >/dev/null
  adb push "$WORK/hello_solana/src/lib.rs" "$LAB/projects/hello_solana/src/lib.rs" >/dev/null
  # The relink guard first, mirroring BuildTasks.toolchainGuard: a previous
  # cargo-build-sbf run that reached its tools-install step UNINSTALLS the
  # rustup \"solana\" toolchain and links its own — after which every cargo
  # through the rustup shim dies with "override toolchain 'solana' is not
  # installed" (2026-08 rehearsal, repaired by hand there). `rustup which`
  # fails both when the link is gone and when it dangles; relinking is
  # idempotent, so running it before every build costs nothing.
  adbsh "$LAB/guest.sh /bin/bash -c '
    rustup which --toolchain solana rustc >/dev/null 2>&1 || {
      rustup toolchain link solana /opt/solana/platform-tools/rust && rustup default solana
    }
    cd /projects/hello_solana
    time cargo-build-sbf --tools-version $PLATFORM_TOOLS_VERSION 2>&1 | tail -5
    ls -la target/deploy/
    /opt/solana/platform-tools/llvm/bin/llvm-readelf -h target/deploy/*.so | grep -E \"Machine|Class\"'"
}

STEPS=(rootfs apt platform_tools rustup cargo_build_sbf rust_analyzer spettro verify)
mkdir -p "$WORK"
if [ $# -gt 0 ]; then STEPS=("$@"); fi
for s in "${STEPS[@]}"; do "step_${s}"; done
say "done"
