# Ten-Distro APK Support Design

Status: proposed design for implementation after review

## Goal

Make `poroid-apk` support all ten published `poroid-rootfs` images as first-class
selections. A user must be able to choose a distro, download its fixed-name
release asset on an arm64 Android device, boot the VM, reach the existing
`Ready!` contract, and use the terminal without the app silently falling back to
Kali or executing an incompatible package-manager command.

The APK remains an APK-only repository. Kernel, initramfs, rootfs, and QEMU
artifacts continue to come from sibling GitHub Releases.

## Constraints

- Preserve existing `Distro` enum names because they are persisted DataStore
  values.
- Preserve the existing rootfs asset names and download URL contract.
- Do not add a new runtime dependency for distro metadata or package commands.
- Keep QEMU and AVF behavior aligned through the existing `VmEngine` boundary.
- A failed optional package-index refresh must not prevent the VM from reaching
  `Running`.
- QEMU must be validated on a real arm64 Android device. AVF validation is
  required only when the connected device reports
  `android.software.virtualization_framework` and the required grants succeed.
- The current connected-device check is empty. Device validation remains a
  separate gated phase after code, tests, and APK build complete.

## Current gaps

`Distro`, labels, and the first-run selector already contain ten values, but
support is incomplete:

- `AvfEngine` hard-codes `kali-rootfs.squashfs`.
- `PodroidApplication` only extracts a bundled Kali rootfs.
- `fetch-artifacts.sh` only knows Kali, Debian, and Ubuntu and renames every
  selected image to Kali.
- QEMU runs `apt update` after the first boot for every distro, and its write
  length is too short for the command.
- CI installs an SDK that does not match the app's compile SDK and does not run
  JVM tests.
- User-facing documentation still describes a Kali-only guest.
- Existing tests cover labels and basic URL strings, not selected-file
  resolution, backend parity, or package-manager behavior.

## Chosen approach

Use one backend-neutral distro descriptor as the source of truth. Extend the
existing `Distro` enum without renaming persisted values. Each value owns its
fixed asset, preferred versioned asset, and safe first-boot package-index
command. `SystemImageRepository` remains the owner of the selected distro and
its local rootfs file. Both engines consume that repository instead of
constructing a rootfs filename independently.

This avoids a second configuration system and keeps the existing download and
persistence model intact.

## Distro descriptor

The descriptor will contain, at minimum:

- `asset`: the stable release filename used by the app, such as
  `fedora-rootfs.squashfs`.
- `versionedAsset`: the preferred release filename for local artifact fetching,
  such as `fedora-rootfs-42.squashfs`.
- `packageUpdateCommand`: a bounded, non-interactive command appropriate for
  the guest package manager.

The initial command mapping is:

| Distro | Command |
|---|---|
| Kali, Debian, Ubuntu | `apt-get update` |
| Fedora, Rocky, Alma | `dnf makecache --refresh` |
| openSUSE | `zypper --non-interactive refresh` |
| Arch, Manjaro | `pacman -Sy --noconfirm` |
| Gentoo | `emerge --sync --quiet` |

The command is best-effort after `Ready!`; it is not part of boot readiness.
The implementation must send the complete byte string and must not use a fixed
length copied from a different command.

## Runtime data flow

1. The setup wizard persists the existing enum name and the selected preset
   URL.
2. `SystemImageRepository.downloadAll()` downloads the selected fixed-name
   rootfs into `filesDir/<asset>`.
3. `QemuEngine.buildCommand()` obtains that file through
   `SystemImageRepository.rootfsFile()`.
4. `AvfEngine.buildConfig()` obtains the same file through the same repository.
5. Both engines use the same kernel, initramfs, storage overlay, terminal,
   networking, and boot-stage contract.
6. The first-boot update path reads the selected descriptor, runs its package
   command once per selected distro, and records completion per distro. The
   legacy `first_apt_update_done` value is read only as a compatibility
   fallback for Kali; it is not used to skip updates for another distro.

Switching distros remains destructive/reset-based as documented. Selecting a
new distro clears download completion and must not reuse another distro's
rootfs or update marker.

## Local artifact and bundled-asset handling

`fetch-artifacts.sh` will:

- accept all ten canonical distro keys;
- try the versioned asset first and fall back to the fixed asset;
- preserve the selected asset name in `app/src/main/assets/`;
- download common kernel, initramfs, QEMU binaries, and QEMU assets;
- use the GitHub release URL directly, with optional `GITHUB_TOKEN`, so a local
  build does not require the `gh` CLI;
- fail clearly for an unknown distro instead of silently choosing Kali.

`PodroidApplication` will consider all ten known rootfs asset names when
extracting optional bundled fallback files. A complete file already downloaded
by the user always wins over a bundled file. A missing optional asset remains
non-fatal until VM launch, where the existing engine-level error is shown.

`.gitignore` will cover all ten local rootfs asset names.

## Tests and CI

Add JVM tests for:

- exhaustive enum-to-label and enum-to-asset mappings;
- fixed and versioned release URLs for all ten distros;
- selected rootfs path resolution;
- package-update command selection;
- legacy first-update-state compatibility and per-distro isolation;
- the complete known bundled-rootfs asset set.

Add a small artifact verification step that performs bounded HTTP range checks
for all ten fixed rootfs release assets. It validates availability without
downloading the large images into CI.

CI will:

- install the Android platform and build tools required by the app;
- stop hiding SDK setup failures;
- run `:app:testDebugUnitTest` before assembling the APK;
- build the existing Debug or signed Release variant;
- upload the APK and its existing signatures/checksums.

No new Gradle dependency is required.

## Device validation

After a connected arm64 device is available:

1. Install the Debug APK with `adb`.
2. For each of the ten distros, select it, download the fixed release asset,
   start QEMU, wait for `Ready!`, and confirm terminal access.
3. Reset between distros so each image is tested from a clean download path.
4. Capture logcat and `console.log` for failures.
5. If AVF is supported, grant the two VM permissions, restart the app, and
   repeat the boot/download flow for the supported backend. If AVF is not
   reported by the device, record it as unavailable rather than treating it as
   an application failure.

The implementation is not complete until the build and test gates pass. The
hardware phase is explicitly blocked until a device appears in `adb devices`.

## Non-goals

- Adding more guest distributions.
- Replacing the QEMU or AVF engines.
- Changing the rootfs build scripts in the sibling `poroid-rootfs` repository.
- Removing the destructive distro-switch/reset behavior.
- Claiming AVF support on hardware that does not expose the framework.
