# poroid-apk

Podroid Android APK — the Podroid client app (rootless Podman in an aarch64
QEMU or AVF micro-VM, accessed via the built-in terminal).

This repo contains **only the Android app**: `app/`, the vendored
`terminal-emulator` + `terminal-view` modules, and the Gradle build. The VM
artifacts it bundles are **not committed** — they are downloaded at build time
from the sibling repos' GitHub Releases:

| Artifact | Source repo | Destination |
|---|---|---|
| `vmlinuz-virt` | [poroid-kernel](https://github.com/nike64542-byte/poroid-kernel) | `app/src/main/assets/` |
| `initrd.img` | [poroid-rootfs](https://github.com/nike64542-byte/poroid-rootfs) | `app/src/main/assets/` |
| `kali-rootfs.squashfs` | [poroid-rootfs](https://github.com/nike64542-byte/poroid-rootfs) | `app/src/main/assets/` |
| `debian-rootfs.squashfs` | [poroid-rootfs](https://github.com/nike64542-byte/poroid-rootfs) | `app/src/main/assets/` |
| `ubuntu-rootfs.squashfs` | [poroid-rootfs](https://github.com/nike64542-byte/poroid-rootfs) | `app/src/main/assets/` |
| `fedora-rootfs.squashfs` | [poroid-rootfs](https://github.com/nike64542-byte/poroid-rootfs) | `app/src/main/assets/` |
| `rocky-rootfs.squashfs` | [poroid-rootfs](https://github.com/nike64542-byte/poroid-rootfs) | `app/src/main/assets/` |
| `alma-rootfs.squashfs` | [poroid-rootfs](https://github.com/nike64542-byte/poroid-rootfs) | `app/src/main/assets/` |
| `opensuse-rootfs.squashfs` | [poroid-rootfs](https://github.com/nike64542-byte/poroid-rootfs) | `app/src/main/assets/` |
| `arch-rootfs.squashfs` | [poroid-rootfs](https://github.com/nike64542-byte/poroid-rootfs) | `app/src/main/assets/` |
| `manjaro-rootfs.squashfs` | [poroid-rootfs](https://github.com/nike64542-byte/poroid-rootfs) | `app/src/main/assets/` |
| `gentoo-rootfs.squashfs` | [poroid-rootfs](https://github.com/nike64542-byte/poroid-rootfs) | `app/src/main/assets/` |
| `libqemu-system-aarch64.so` + friends | [poroid-qemu](https://github.com/nike64542-byte/poroid-qemu) | `app/src/main/jniLibs/arm64-v8a/` |
| `efi-virtio.rom` + `keymaps/` (`qemu-assets.tar.gz`) | [poroid-qemu](https://github.com/nike64542-byte/poroid-qemu) | `app/src/main/assets/qemu/` |

## Build

```bash
./fetch-artifacts.sh fedora   # download one selected rootfs plus common VM assets
./gradlew :app:assembleDebug
```

The first-run wizard can select Kali, Debian, Ubuntu, Fedora, Rocky, Alma,
openSUSE, Arch, Manjaro, or Gentoo. The app downloads the selected fixed-name
rootfs on the device; the local fetch command is only a build-time fallback.

Requires JDK 17 + Android SDK. The release APK needs the signing keystore
secrets (`PODROID_KEYSTORE_B64` etc.) which are configured as GitHub Actions
secrets on this repo — never commit the `.jks`.

## CI

- `push` / `workflow_dispatch` downloads the latest artifacts from the three
  sibling repos' `latest` Releases, builds the APK, signs it if the keystore
  secret is present, and uploads the APK as a build artifact.
