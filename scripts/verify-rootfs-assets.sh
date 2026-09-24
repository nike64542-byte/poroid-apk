#!/bin/bash
set -euo pipefail

ROOTFS_URL="https://github.com/nike64542-byte/poroid-rootfs/releases/download/latest"
ASSETS=(
    kali-rootfs.squashfs
    debian-rootfs.squashfs
    ubuntu-rootfs.squashfs
    fedora-rootfs.squashfs
    rocky-rootfs.squashfs
    alma-rootfs.squashfs
    opensuse-rootfs.squashfs
    arch-rootfs.squashfs
    manjaro-rootfs.squashfs
    gentoo-rootfs.squashfs
)

curl_args=(-fsSIL --retry 3 --retry-delay 2)
if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    curl_args+=(-H "Authorization: Bearer ${GITHUB_TOKEN}")
fi

for asset in "${ASSETS[@]}"; do
    curl "${curl_args[@]}" -o /dev/null "${ROOTFS_URL}/${asset}"
    printf 'verified %s\n' "${asset}"
done
