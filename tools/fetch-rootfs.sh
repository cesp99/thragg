#!/usr/bin/env bash
# Pull Debian's official arm64 container base image straight from the registry
# (no docker daemon needed) and leave the rootfs layer as a .tar.gz.
set -euo pipefail
IMAGE=library/debian
REF=${1:-stable-slim}
ARCH=${2:-arm64}
OUT=${3:-debian-${ARCH}-rootfs.tar.gz}

tok=$(curl -s "https://auth.docker.io/token?service=registry.docker.io&scope=repository:${IMAGE}:pull" | sed -E 's/.*"token":"([^"]*)".*/\1/')
A=(-H "Authorization: Bearer $tok"
   -H "Accept: application/vnd.oci.image.index.v1+json"
   -H "Accept: application/vnd.docker.distribution.manifest.list.v2+json"
   -H "Accept: application/vnd.oci.image.manifest.v1+json"
   -H "Accept: application/vnd.docker.distribution.manifest.v2+json")

index=$(curl -s "${A[@]}" "https://registry-1.docker.io/v2/${IMAGE}/manifests/${REF}")
digest=$(echo "$index" | python3 -c "
import json,sys
m=json.load(sys.stdin)
for x in m['manifests']:
    p=x.get('platform',{})
    if p.get('architecture')=='${ARCH}' and p.get('os')=='linux':
        print(x['digest']); break
")
echo "arm64 manifest: $digest"
manifest=$(curl -s "${A[@]}" "https://registry-1.docker.io/v2/${IMAGE}/manifests/${digest}")
layer=$(echo "$manifest" | python3 -c "import json,sys; print(json.load(sys.stdin)['layers'][0]['digest'])")
echo "layer: $layer"
curl -sL "${A[@]}" -o "$OUT" "https://registry-1.docker.io/v2/${IMAGE}/blobs/${layer}"
ls -lh "$OUT"
