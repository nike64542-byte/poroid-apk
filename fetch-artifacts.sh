#!/bin/bash
# Fetch latest VM artifacts from poroid-kernel / poroid-rootfs / poroid-qemu
# GitHub Releases into the locations the Android build expects.
#
# Usage: ./fetch-artifacts.sh
# Requires `gh` CLI authenticated (GITHUB_TOKEN or gh auth login).
set -euo pipefail

KERNEL_REPO="nike64542-byte/poroid-kernel"
ROOTFS_REPO="nike64542-byte/poroid-rootfs"
QEMU_REPO="nike64542-byte/poroid-qemu"

fetch() {
    local repo="$1"; shift
    local dir="$1"; shift
    mkdir -p "${dir}"
    gh release download latest -R "${repo}" "$@" --dir "${dir}" --clobber || \
        echo "警告：无法从 ${repo} 下载，跳过（需要手动放置产物）"
}

fetch "${KERNEL_REPO}" "app/src/main/assets" -p "vmlinuz-virt"
fetch "${ROOTFS_REPO}" "app/src/main/assets" -p "initrd.img" -p "kali-rootfs.squashfs"
fetch "${QEMU_REPO}" "app/src/main/jniLibs/arm64-v8a" \
    -p "libqemu-system-aarch64.so" -p "libslirp.so" \
    -p "libpodroid-bridge.so" -p "libpodroid-launcher.so"

tmpdir="$(mktemp -d)"
fetch "${QEMU_REPO}" "${tmpdir}" -p "qemu-assets.tar.gz"
if [ -f "${tmpdir}/qemu-assets.tar.gz" ]; then
    mkdir -p app/src/main/assets/qemu/
    tar xzf "${tmpdir}/qemu-assets.tar.gz" -C app/src/main/assets/qemu/
fi
rm -rf "${tmpdir}"

echo "完成。检查："
ls -la app/src/main/assets/ 2>/dev/null || true
ls -la app/src/main/jniLibs/arm64-v8a/ 2>/dev/null || true
