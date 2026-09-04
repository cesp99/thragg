#!/usr/bin/env bash
#
# Builds proot for the Debian userland.
#
# proot fakes a filesystem root for its children using ptrace, which is what
# lets a Linux distribution's own binaries run on Android without root. The
# output is a single self-contained executable per ABI, with talloc linked in
# statically, dropped into app/src/main/jniLibs/<abi>/libproot_exec.so.
#
# Sources, both GPL-2.0-or-later, both fetched with their checksum verified:
#
#   proot   github.com/termux/proot — Termux's fork, not proot-me/proot.
#           Upstream proot builds fine for Android but its guests are killed
#           with SIGSYS on Android 17; the fork carries the fixes. Verified on
#           a Galaxy Z Fold: see agent-docs/archive/research/proot-spike.md.
#   talloc  samba.org — proot's only library dependency. Built from a
#           hand-written config.h instead of its waf build system, which does
#           not cross-compile pleasantly; bionic satisfies everything talloc
#           actually needs.
#
# Usage: tools/build-proot.sh [--clean]
set -euo pipefail

PROOT_VERSION="5.1.107.91"
PROOT_SHA256="a7bc2fab34bf9a39073e8291f08a662e848c61a67494e59f5f84f5ca10690128"
TALLOC_VERSION="2.4.2"
TALLOC_SHA256="85ecf9e465e20f98f9950a52e9a411e14320bc555fa257d87697b7e7a9b1d8a6"

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
work="$repo_root/build/proot"
out_root="$repo_root/app/src/main/jniLibs"

# The NDK pin is shared with cargo-ndk and the terminal modules.
ndk_version="$(sed -n 's/^thragg\.ndkVersion=//p' "$repo_root/gradle.properties")"
sdk_dir="${ANDROID_HOME:-$HOME/Android/Sdk}"
if [ -f "$repo_root/local.properties" ]; then
    sdk_dir="$(sed -n 's/^sdk\.dir=//p' "$repo_root/local.properties" || true)"
    sdk_dir="${sdk_dir:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
fi
toolchain="$sdk_dir/ndk/$ndk_version/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -d "$toolchain" ] || { echo "NDK $ndk_version not found at $sdk_dir/ndk" >&2; exit 1; }

# minSdk, so the binaries run everywhere the app does.
API=31

if [ "${1:-}" = "--clean" ]; then rm -rf "$work"; fi
mkdir -p "$work"
cd "$work"

fetch() { # url sha256 output
    if [ ! -f "$3" ]; then curl -fsSL -o "$3.part" "$1" && mv "$3.part" "$3"; fi
    echo "$2  $3" | sha256sum -c - >/dev/null || { echo "checksum mismatch: $3" >&2; exit 1; }
}

fetch "https://download.samba.org/pub/talloc/talloc-$TALLOC_VERSION.tar.gz" \
      "$TALLOC_SHA256" "talloc.tar.gz"
fetch "https://github.com/termux/proot/archive/v$PROOT_VERSION.zip" \
      "$PROOT_SHA256" "proot.zip"

[ -d "talloc-$TALLOC_VERSION" ] || tar xzf talloc.tar.gz
[ -d "proot-$PROOT_VERSION" ] || unzip -q proot.zip

# talloc's generated config.h, by hand. Every one of these is true on bionic.
mkdir -p tallocinc
cat > tallocinc/config.h <<'EOF'
#define HAVE_STDINT_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_UNISTD_H 1
#define HAVE_STRING_H 1
#define HAVE_STRINGS_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STDDEF_H 1
#define HAVE_LIMITS_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_SYS_PARAM_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_SYS_TIME_H 1
#define HAVE_DLFCN_H 1
#define HAVE_STDBOOL_H 1
#define HAVE_MEMSET 1
#define HAVE_MEMMOVE 1
#define HAVE_MEMCPY 1
#define HAVE_STRDUP 1
#define HAVE_STRNDUP 1
#define HAVE_STRNLEN 1
#define HAVE_STRLCPY 1
#define HAVE_STRLCAT 1
#define HAVE_SNPRINTF 1
#define HAVE_VSNPRINTF 1
#define HAVE_C99_VSNPRINTF 1
#define HAVE_ASPRINTF 1
#define HAVE_VASPRINTF 1
#define HAVE_VA_COPY 1
#define HAVE_BZERO 1
#define HAVE_GETPAGESIZE 1
#define HAVE_SECURE_GETENV 1
#define HAVE_USECONDS_T 1
#define HAVE_USLEEP 1
#define HAVE_INTPTR_T 1
#define HAVE_UINTPTR_T 1
#define HAVE_PTRDIFF_T 1
#define HAVE_BOOL 1
#define HAVE_VOLATILE 1
#define HAVE_COMPILER_WILL_OPTIMIZE_OUT_FNS 1
#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define HAVE_DESTRUCTOR_ATTRIBUTE 1
#define HAVE_ATTRIBUTE_PRINTF_FORMAT 1
#define HAVE_GCC_ATOMIC_BUILTINS 1
EOF

# THRAGG PATCH: clang 18+ makes implicit function declarations an error, and
# this file uses strcmp/memset without including <string.h>. Idempotent.
ashmem="proot-$PROOT_VERSION/src/extension/ashmem_memfd/ashmem_memfd.c"
if ! grep -q "THRAGG PATCH" "$ashmem"; then
    sed -i '0,/#include <stdlib.h>/s//#include <stdlib.h>\n#include <string.h>     \/* THRAGG PATCH: strcmp\/memset *\//' "$ashmem"
fi

# THRAGG PATCH: execve(2) from a non-leader thread. The kernel gives the
# exec'ing thread the leader's tid before PTRACE_EVENT_EXEC, so proot's
# per-tid execve state (load info, saved registers, the PTRACE_SYSCALL
# restart the exit stage needs) is stranded on a Tracee whose tid no longer
# exists, the loader runs with no load script, and the new program dies with
# SIGSEGV. Go's toolchain switch (go.mod requiring a newer Go than the
# installed one) execs from a worker thread and hit this every time; verified
# on a Galaxy Z Fold. The patch adopts the former thread's state at
# PTRACE_EVENT_EXEC using PTRACE_GETEVENTMSG. Idempotent.
event_c="proot-$PROOT_VERSION/src/tracee/event.c"
if ! grep -q "adopt_thread_exec_state" "$event_c"; then
    patch -p1 -d "proot-$PROOT_VERSION" < "$repo_root/tools/proot-thread-execve.patch"
fi

build_abi() { # abi triple
    local abi="$1" triple="$2"
    local cc="$toolchain/${triple}${API}-clang"
    local objdir="$work/obj-$abi"
    echo "==> $abi"
    rm -rf "$objdir"; mkdir -p "$objdir"

    "$cc" -c "talloc-$TALLOC_VERSION/talloc.c" -o "$objdir/talloc.o" \
        -DHAVE_CONFIG_H -D__STDC_WANT_LIB_EXT1__=1 \
        -DTALLOC_BUILD_VERSION_MAJOR=2 -DTALLOC_BUILD_VERSION_MINOR=4 \
        -DTALLOC_BUILD_VERSION_RELEASE=2 \
        -I "$work/tallocinc" -I "talloc-$TALLOC_VERSION" \
        -I "talloc-$TALLOC_VERSION/lib/replace" -O2 -fPIC
    "$toolchain/llvm-ar" rcs "$objdir/libtalloc.a" "$objdir/talloc.o"

    make -C "proot-$PROOT_VERSION/src" clean >/dev/null 2>&1 || true
    make -C "proot-$PROOT_VERSION/src" -j"$(nproc)" proot \
        CC="$cc" LD="$cc" \
        OBJCOPY="$toolchain/llvm-objcopy" OBJDUMP="$toolchain/llvm-objdump" \
        STRIP="$toolchain/llvm-strip" \
        HAS_SWIG= HAS_PYTHON_CONFIG= \
        CPPFLAGS="-DARG_MAX=131072 -DVERSION=\\\"$PROOT_VERSION\\\" -D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -I$work/talloc-$TALLOC_VERSION -I$work/tallocinc" \
        CFLAGS="-O2" \
        LDFLAGS="-L$objdir -ltalloc -Wl,-z,noexecstack"

    mkdir -p "$out_root/$abi"
    "$toolchain/llvm-strip" "proot-$PROOT_VERSION/src/proot" \
        -o "$out_root/$abi/libproot_exec.so"
    make -C "proot-$PROOT_VERSION/src" clean >/dev/null 2>&1 || true
    ls -l "$out_root/$abi/libproot_exec.so"
}

build_abi arm64-v8a aarch64-linux-android
build_abi x86_64 x86_64-linux-android

echo
echo "proot $PROOT_VERSION built for both ABIs into app/src/main/jniLibs/."
echo "Both are GPL-2.0-or-later; see docs/THIRD_PARTY.md for the source offer."
