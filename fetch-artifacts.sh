#!/bin/bash
# Fetch latest VM artifacts from poroid-kernel / poroid-rootfs / poroid-qemu
# GitHub Releases into the locations the Android build expects.
#
# Rootfs images are published under BOTH a versioned name
# (kali-rootfs-rolling.squashfs / debian-rootfs-12.squashfs /
#  ubuntu-rootfs-24.04.squashfs) and a stable fixed name
# (kali-rootfs.squashfs). This script prefers the versioned name and falls
# back to the fixed name so it keeps working even if only one is uploaded.
#
# Usage: ./fetch-artifacts.sh [rootfs-name]
#   rootfs-name: kali-rootfs | debian-rootfs | ubuntu-rootfs (default kali-rootfs)
# Requires `gh` CLI authenticated (GITHUB_TOKEN or gh auth login).
set -euo pipefail

KERNEL_REPO="nike64542-byte/poroid-kernel"
ROOTFS_REPO="nike64542-byte/poroid-rootfs"
QEMU_REPO="nike64542-byte/poroid-qemu"

# Versioned filename per distro (matches what poroid-rootfs Release uploads).
case "${1:-kali-rootfs}" in
    kali-rootfs)   ROOTFS_VERSIONED="kali-rootfs-rolling.squashfs"   ;;
    debian-rootfs) ROOTFS_VERSIONED="debian-rootfs-12.squashfs"      ;;
    ubuntu-rootfs) ROOTFS_VERSIONED="ubuntu-rootfs-24.04.squashfs"   ;;
    *) echo "未知 rootfs: $1"; exit 1 ;;
esac

fetch() {
    local repo="$1"; shift
    local dir="$1"; shift
    mkdir -p "${dir}"
    gh release download latest -R "${repo}" "$@" --dir "${dir}" --clobber || \
        echo "警告：无法从 ${repo} 下载，跳过（需要手动放置产物）"
}

fetch "${KERNEL_REPO}" "app/src/main/assets" -p "vmlinuz-virt"
fetch "${ROOTFS_REPO}" "app/src/main/assets" -p "initrd.img"

# Rootfs: versioned name first, then fixed-name fallback; normalize to the
# fixed name the app/engines expect (kali-rootfs.squashfs).
rootfs_tmp="$(mktemp -d)"
mkdir -p app/src/main/assets/
if gh release download latest -R "${ROOTFS_REPO}" -p "${ROOTFS_VERSIONED}" \
    --dir "${rootfs_tmp}" --clobber >/dev/null 2>&1 && \
    [ -f "${rootfs_tmp}/${ROOTFS_VERSIONED}" ]; then
    cp "${rootfs_tmp}/${ROOTFS_VERSIONED}" "app/src/main/assets/kali-rootfs.squashfs"
    echo "使用版本化 rootfs: ${ROOTFS_VERSIONED}"
elif gh release download latest -R "${ROOTFS_REPO}" -p "kali-rootfs.squashfs" \
    --dir app/src/main/assets/ --clobber >/dev/null 2>&1; then
    echo "使用固定名 rootfs: kali-rootfs.squashfs"
else
    echo "警告：无法下载 rootfs，跳过（需要手动放置产物）"
fi
rm -rf "${rootfs_tmp}"

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
