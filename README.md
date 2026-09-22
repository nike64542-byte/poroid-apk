# poroid-apk

Podroid Android APK — the Podroid client app (rootless Podman in an aarch64
QEMU micro-VM, accessed via built-in serial terminal).

This repo contains **only the Android app**: `app/`, the vendored
`terminal-emulator` + `terminal-view` modules, and the Gradle build. The VM
artifacts it bundles are **not committed** — they are downloaded at build time
from the sibling repos' GitHub Releases:

| Artifact | Source repo | Destination |
|---|---|---|
| `vmlinuz-virt` | [poroid-kernel](https://github.com/nike64542-byte/poroid-kernel) | `app/src/main/assets/` |
| `initrd.img` | [poroid-rootfs](https://github.com/nike64542-byte/poroid-rootfs) | `app/src/main/assets/` |
| `kali-rootfs.squashfs` | [poroid-rootfs](https://github.com/nike64542-byte/poroid-rootfs) | `app/src/main/assets/` |
| `libqemu-system-aarch64.so` + friends | [poroid-qemu](https://github.com/nike64542-byte/poroid-qemu) | `app/src/main/jniLibs/arm64-v8a/` |
| `efi-virtio.rom` + `keymaps/` (`qemu-assets.tar.gz`) | [poroid-qemu](https://github.com/nike64542-byte/poroid-qemu) | `app/src/main/assets/qemu/` |

## Build

```bash
./fetch-artifacts.sh   # download latest Release assets from the 3 repos
./gradlew :app:assembleDebug
```

Requires JDK 17 + Android SDK. The release APK needs the signing keystore
secrets (`PODROID_KEYSTORE_B64` etc.) which are configured as GitHub Actions
secrets on this repo — never commit the `.jks`.

## CI

- `push` / `workflow_dispatch` downloads the latest artifacts from the three
  sibling repos' `latest` Releases, builds the APK, signs it if the keystore
  secret is present, and uploads the APK as a build artifact.
