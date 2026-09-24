#!/usr/bin/env bash
set -euo pipefail

KERNEL_REPO="nike64542-byte/poroid-kernel"
ROOTFS_REPO="nike64542-byte/poroid-rootfs"
QEMU_REPO="nike64542-byte/poroid-qemu"
ASSET_DIR="app/src/main/assets"
JNI_DIR="app/src/main/jniLibs/arm64-v8a"

case "${1:-kali}" in
    kali|kali-rootfs)
        ROOTFS_VERSIONED="kali-rootfs-rolling.squashfs"
        ROOTFS_FIXED="kali-rootfs.squashfs"
        ;;
    debian|debian-rootfs)
        ROOTFS_VERSIONED="debian-rootfs-12.squashfs"
        ROOTFS_FIXED="debian-rootfs.squashfs"
        ;;
    ubuntu|ubuntu-rootfs)
        ROOTFS_VERSIONED="ubuntu-rootfs-24.04.squashfs"
        ROOTFS_FIXED="ubuntu-rootfs.squashfs"
        ;;
    fedora|fedora-rootfs)
        ROOTFS_VERSIONED="fedora-rootfs-42.squashfs"
        ROOTFS_FIXED="fedora-rootfs.squashfs"
        ;;
    rocky|rocky-rootfs)
        ROOTFS_VERSIONED="rocky-rootfs-9.squashfs"
        ROOTFS_FIXED="rocky-rootfs.squashfs"
        ;;
    alma|alma-rootfs)
        ROOTFS_VERSIONED="alma-rootfs-9.squashfs"
        ROOTFS_FIXED="alma-rootfs.squashfs"
        ;;
    opensuse|opensuse-rootfs)
        ROOTFS_VERSIONED="opensuse-rootfs-15.6.squashfs"
        ROOTFS_FIXED="opensuse-rootfs.squashfs"
        ;;
    arch|arch-rootfs)
        ROOTFS_VERSIONED="arch-rootfs-rolling.squashfs"
        ROOTFS_FIXED="arch-rootfs.squashfs"
        ;;
    manjaro|manjaro-rootfs)
        ROOTFS_VERSIONED="manjaro-rootfs-rolling.squashfs"
        ROOTFS_FIXED="manjaro-rootfs.squashfs"
        ;;
    gentoo|gentoo-rootfs)
        ROOTFS_VERSIONED="gentoo-rootfs-rolling.squashfs"
        ROOTFS_FIXED="gentoo-rootfs.squashfs"
        ;;
    *)
        printf '未知 rootfs: %s\n' "$1" >&2
        exit 1
        ;;
esac

curl_args=(-fL --retry 3 --retry-delay 2)
if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    curl_args+=(-H "Authorization: Bearer ${GITHUB_TOKEN}")
fi

download_asset() {
    local repo="$1"
    local asset="$2"
    local destination="$3"
    curl "${curl_args[@]}" \
        "https://github.com/${repo}/releases/download/latest/${asset}" \
        -o "${destination}"
}

mkdir -p "${ASSET_DIR}" "${JNI_DIR}"
download_asset "${KERNEL_REPO}" "vmlinuz-virt" "${ASSET_DIR}/vmlinuz-virt"
download_asset "${ROOTFS_REPO}" "initrd.img" "${ASSET_DIR}/initrd.img"

tmpdir="$(mktemp -d)"
trap 'rm -rf "${tmpdir}"' EXIT
if ! download_asset "${ROOTFS_REPO}" "${ROOTFS_VERSIONED}" "${tmpdir}/${ROOTFS_VERSIONED}"; then
    download_asset "${ROOTFS_REPO}" "${ROOTFS_FIXED}" "${tmpdir}/${ROOTFS_FIXED}"
    cp "${tmpdir}/${ROOTFS_FIXED}" "${ASSET_DIR}/${ROOTFS_FIXED}"
    printf '使用固定名 rootfs: %s\n' "${ROOTFS_FIXED}"
else
    cp "${tmpdir}/${ROOTFS_VERSIONED}" "${ASSET_DIR}/${ROOTFS_FIXED}"
    printf '使用版本化 rootfs: %s\n' "${ROOTFS_VERSIONED}"
fi

for binary in \
    libqemu-system-aarch64.so \
    libslirp.so \
    libpodroid-bridge.so \
    libpodroid-launcher.so; do
    download_asset "${QEMU_REPO}" "${binary}" "${JNI_DIR}/${binary}"
done

if download_asset "${QEMU_REPO}" "qemu-assets.tar.gz" "${tmpdir}/qemu-assets.tar.gz"; then
    mkdir -p "${ASSET_DIR}/qemu"
    tar xzf "${tmpdir}/qemu-assets.tar.gz" -C "${ASSET_DIR}/qemu/"
fi

printf '完成。rootfs: %s\n' "${ASSET_DIR}/${ROOTFS_FIXED}"
